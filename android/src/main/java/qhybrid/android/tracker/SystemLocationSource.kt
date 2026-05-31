package qhybrid.android.tracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WP-TRACKER — the production [LocationSource] over the **platform `LocationManager`** (AOSP, zero
 * Google Play Services / no `play-services-location`), so it works on de-Googled phones
 * (GrapheneOS / e/OS / non-GMS ROMs). See WP-TRACKER-GPS-WIRING-PLAN.md.
 *
 * Strategy — "last-known + a single high-accuracy update with a timeout", all dependency-free:
 *   1. Resolve the [LocationManager].
 *   2. Permission guard — if `ACCESS_FINE_LOCATION` is NOT granted, log + return null (never throw,
 *      never request from here; the request is a UI concern in [qhybrid.android.MainActivity]).
 *   3. Fast path — a recent enough `getLastKnownLocation(provider)` is returned immediately.
 *   4. Otherwise request a single fresh fix and block the worker up to [FIX_TIMEOUT_MS]
 *      (API 30+ `getCurrentLocation`; API < 30 deprecated `requestSingleUpdate`).
 *   5. Map → [LocationSource.Fix], or null on no provider / no fix / timeout.
 *
 * **Threading.** `currentFix()` is called from `recordWaypointAsync` on the injected IO scope
 * ([ServiceTrackerDispatch.io]); blocking-with-timeout is acceptable there. Do NOT call it on the
 * main / ble-gatt thread.
 *
 * The pure Location→Fix mapping is factored to [toFix] so it can be unit-tested off-device; the
 * live `LocationManager` call itself is on-device-verified only.
 */
class SystemLocationSource(
    context: Context,
    private val timeoutMs: Long = FIX_TIMEOUT_MS,
    private val staleFixMs: Long = STALE_FIX_MS,
) : LocationSource {

    private val appContext = context.applicationContext

    override fun currentFix(): LocationSource.Fix? {
        if (!hasFinePermission()) {
            Log.w(TAG, "currentFix: ACCESS_FINE_LOCATION not granted — returning null")
            return null
        }
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            Log.w(TAG, "currentFix: no LocationManager")
            return null
        }
        val providers = candidateProviders(lm)
        if (providers.isEmpty()) {
            Log.w(TAG, "currentFix: no enabled location provider")
            return null
        }

        // 1) Fast path: a recent enough last-known fix from any candidate provider.
        bestRecentLastKnown(lm, providers)?.let { fix ->
            Log.i(TAG, "currentFix: using recent last-known fix")
            return fix
        }

        // 2) Slow path: a single fresh update on the best provider, blocking up to the timeout.
        val provider = providers.first()
        return requestSingleFix(lm, provider)
    }

    /** True iff `ACCESS_FINE_LOCATION` is currently granted (self-contained; never requests). */
    private fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Enabled providers, best → fallback:
     *   - API 31+: prefer `FUSED_PROVIDER` (Android's OWN fused provider, NOT Google's).
     *   - GPS, then NETWORK (coarse fallback when GPS has no fix yet).
     */
    private fun candidateProviders(lm: LocationManager): List<String> {
        val ordered = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        return ordered.distinct().filter {
            runCatching { lm.isProviderEnabled(it) }.getOrDefault(false)
        }
    }

    /** The freshest last-known fix across [providers] that is younger than [staleFixMs], or null. */
    private fun bestRecentLastKnown(
        lm: LocationManager,
        providers: List<String>,
    ): LocationSource.Fix? {
        val now = System.currentTimeMillis()
        return providers
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .filter { now - it.time <= staleFixMs }
            .maxByOrNull { it.time }
            ?.let { toFix(it) }
    }

    /**
     * Request a single fresh fix on [provider], blocking the worker up to [timeoutMs]. API 30+ uses
     * the modern `getCurrentLocation` one-shot; older uses the deprecated `requestSingleUpdate`.
     */
    @Suppress("MissingPermission") // guarded by hasFinePermission() above.
    private fun requestSingleFix(lm: LocationManager, provider: String): LocationSource.Fix? {
        val result = AtomicReference<Location?>(null)
        val latch = CountDownLatch(1)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            val executor = Executors.newSingleThreadExecutor()
            try {
                lm.getCurrentLocation(provider, signal, executor) { loc ->
                    result.set(loc)
                    latch.countDown()
                }
                awaitFix(latch, result) { signal.cancel() }
            } catch (e: Exception) {
                Log.w(TAG, "getCurrentLocation failed", e)
                null
            } finally {
                executor.shutdownNow()
            }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    result.set(location)
                    latch.countDown()
                }

                override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }
            try {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                awaitFix(latch, result) { lm.removeUpdates(listener) }
            } catch (e: Exception) {
                Log.w(TAG, "requestSingleUpdate failed", e)
                null
            }
        }
    }

    /** Block up to [timeoutMs] for the fix; on timeout run [onTimeout] (cancel/cleanup) → null. */
    private fun awaitFix(
        latch: CountDownLatch,
        result: AtomicReference<Location?>,
        onTimeout: () -> Unit,
    ): LocationSource.Fix? {
        val got = runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!got) {
            Log.w(TAG, "currentFix: timed out after ${timeoutMs}ms")
            runCatching { onTimeout() }
            return null
        }
        return result.get()?.let { toFix(it) }
    }

    companion object {
        private const val TAG = "FossilQ-Tracker"

        /** A last-known fix younger than this is fresh enough to use without a new request. */
        const val STALE_FIX_MS = 60_000L

        /** Max time to block the IO worker waiting for a single fresh fix. */
        const val FIX_TIMEOUT_MS = 10_000L

        /**
         * Pure [android.location.Location] → [LocationSource.Fix] mapping (unit-testable off-device).
         * `accuracyM` is null when the location reports no accuracy.
         */
        fun toFix(location: Location): LocationSource.Fix =
            LocationSource.Fix(
                lat = location.latitude,
                lon = location.longitude,
                accuracyM = if (location.hasAccuracy()) location.accuracy else null,
                timestamp = location.time,
            )
    }
}
