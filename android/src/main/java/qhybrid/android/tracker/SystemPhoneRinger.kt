package qhybrid.android.tracker

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * WP-TRACKER — the production [PhoneRinger] over the live device, modelled on Gadgetbridge's
 * `FindPhoneActivity` (see WP-TRACKER-GPS-WIRING-PLAN.md §4): a looping ringtone on the **alarm
 * stream** at **max volume** plus a waveform vibration, both with alarm `AudioAttributes` so the
 * sound bypasses the ringer-silent / Do-Not-Disturb modes where the OS allows alarms through.
 * Auto-stops after [RingPolicy.AUTO_STOP_MS] so a pocketed phone can't ring forever, and restores
 * the user's prior alarm volume on [stop].
 *
 * Zero Google Play Services — uses only AOSP `MediaPlayer` / `AudioManager` / `Vibrator`. The audio
 * itself is on-device-verified; the loud/long decision is the pure [RingPolicy] (unit-tested).
 *
 * **Threading.** [start]/[stop] are synchronized; the auto-stop fires on a small scheduler. Safe to
 * call [start] while already ringing (idempotent) and [stop] when not ringing.
 */
class SystemPhoneRinger(context: Context) : PhoneRinger {

    private val appContext = context.applicationContext
    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val lock = Any()
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var priorAlarmVolume: Int? = null
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "phone-ringer").apply { isDaemon = true }
    }
    private var autoStop: java.util.concurrent.ScheduledFuture<*>? = null

    override fun start() {
        synchronized(lock) {
            startLocked()
        }
    }

    private fun startLocked() {
        if (player != null) {
            Log.i(TAG, "ring: already ringing")
            return
        }
        Log.i(TAG, "ring: start (loud alarm-stream ringtone + vibrate)")
        runCatching { boostAlarmVolume() }.onFailure { Log.w(TAG, "boost volume failed", it) }
        runCatching { startRingtone() }.onFailure { Log.w(TAG, "start ringtone failed", it) }
        runCatching { startVibration() }.onFailure { Log.w(TAG, "start vibration failed", it) }
        // Auto-stop so a pocketed phone can't ring forever.
        autoStop = runCatching {
            scheduler.schedule({ stop() }, RingPolicy.AUTO_STOP_MS, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    override fun stop() {
        synchronized(lock) {
            stopLocked()
        }
    }

    private fun stopLocked() {
        autoStop?.cancel(false)
        autoStop = null
        runCatching {
            player?.let { if (it.isPlaying) it.stop(); it.reset(); it.release() }
        }.onFailure { Log.w(TAG, "stop ringtone failed", it) }
        player = null
        runCatching { vibrator?.cancel() }.onFailure { Log.w(TAG, "cancel vibration failed", it) }
        vibrator = null
        // Restore the user's prior alarm volume.
        priorAlarmVolume?.let { vol ->
            runCatching {
                audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, vol, 0)
            }.onFailure { Log.w(TAG, "restore volume failed", it) }
        }
        priorAlarmVolume = null
        Log.i(TAG, "ring: stopped")
    }


    private fun boostAlarmVolume() {
        val am = audioManager ?: return
        priorAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(AudioManager.STREAM_ALARM, RingPolicy.ringVolume(max), AudioManager.FLAG_PLAY_SOUND)
    }

    private fun startRingtone() {
        // Prefer the user's ringtone; fall back to alarm, then notification (some ROMs return null
        // for a given type — try a few so a fix can always be heard).
        val uri: Uri = firstNonNull(
            { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) },
            { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) },
            { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) },
        ) ?: run {
            Log.w(TAG, "ring: no ringtone URI available")
            return
        }
        val mp = MediaPlayer()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        mp.setAudioAttributes(attrs)
        mp.setDataSource(appContext, uri)
        mp.isLooping = true
        mp.prepare()
        mp.start()
        player = mp
    }

    private fun startVibration() {
        val vib = resolveVibrator() ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(
                RingPolicy.VIBRATION_PATTERN, RingPolicy.VIBRATION_REPEAT,
            )
            vib.vibrate(effect, attrs)
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(RingPolicy.VIBRATION_PATTERN, RingPolicy.VIBRATION_REPEAT)
        }
        vibrator = vib
    }

    private fun resolveVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun firstNonNull(vararg suppliers: () -> Uri?): Uri? {
        for (s in suppliers) runCatching { s() }.getOrNull()?.let { return it }
        return null
    }

    private companion object {
        private const val TAG = "FossilQ-Tracker"
    }
}
