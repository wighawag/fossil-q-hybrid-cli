package qhybrid.android.notifications

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * WP16c — one installed, launchable app the user can attach a notification rule to.
 *
 * @property packageName the app's package id (e.g. `com.google.android.calendar`) — this is
 *   what the watch matches against (its CRC; see WP6 `NotificationCompiler`).
 * @property label the human display name (e.g. "Calendar") for search + display.
 * @property icon the launcher icon, or null if unavailable / not loaded (e.g. in tests).
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable? = null,
) {
    /** True if [query] matches the display name OR the package id (case-insensitive). */
    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return label.contains(q, ignoreCase = true) || packageName.contains(q, ignoreCase = true)
    }
}

/**
 * WP16c — narrow, injectable seam that supplies the list of installed apps the user can pick
 * from, so the picker is unit-testable with a fake (no real device / `PackageManager`).
 *
 * The production impl ([SystemInstalledAppsProvider]) enumerates **launchable** apps via
 * `PackageManager.queryIntentActivities(ACTION_MAIN/CATEGORY_LAUNCHER)`. That query needs **no
 * special permission** and does NOT require the Play-sensitive `QUERY_ALL_PACKAGES` — it only
 * surfaces apps that have a launcher icon (the user-facing apps that actually post
 * notifications), which is exactly what we want here.
 */
interface InstalledAppsProvider {
    /** All launchable apps, sorted by display label (case-insensitive). */
    fun installedApps(): List<InstalledApp>
}

/**
 * Production [InstalledAppsProvider] — queries the launcher apps from [PackageManager].
 * Holds the application context so it never leaks an Activity.
 */
class SystemInstalledAppsProvider(context: Context) : InstalledAppsProvider {
    private val appContext = context.applicationContext

    override fun installedApps(): List<InstalledApp> {
        val pm = appContext.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(launcherIntent, 0)
        return resolved
            .asSequence()
            .mapNotNull { info ->
                val ai = info.activityInfo ?: return@mapNotNull null
                val pkg = ai.packageName ?: return@mapNotNull null
                val label = runCatching { info.loadLabel(pm)?.toString() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: pkg
                val icon = runCatching { info.loadIcon(pm) }.getOrNull()
                InstalledApp(packageName = pkg, label = label, icon = icon)
            }
            // De-dup: an app may export several launcher activities; keep one per package.
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
