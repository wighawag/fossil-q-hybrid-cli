package qhybrid.android.debug

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import qhybrid.android.BuildConfig
import qhybrid.android.CompanionManager
import qhybrid.android.WatchState
import qhybrid.android.db.ButtonMappingEntity
import qhybrid.android.db.NotificationRuleEntity
import qhybrid.android.db.WatchAlarmEntity
import qhybrid.android.db.WatchEntity
import qhybrid.android.db.WatchRepository

/**
 * WP15 — the Debug Menu's action implementations. Each logs via SLF4J (tag
 * {@code FossilQ-Debug}) so output lands in the in-app log console (and logcat).
 *
 * DB work runs off the main thread on a private IO scope (DAOs are `suspend`); BLE actions
 * go through [qhybrid.android.WatchConnectionService] static entry points (fired from the
 * composable). Reads [WatchState] / [CompanionManager] for the link / association dumps.
 */
class DebugTools(
    private val appContext: Context,
    // Injectable so headless tests can drive the DB actions against an in-memory Room repo.
    private val repo: WatchRepository = WatchRepository(appContext),
    // Injectable so headless tests can run DB actions on a deterministic test scope.
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {

    val log = LoggerFactory.getLogger("FossilQ-Debug")

    /** Launches [block] on the (injectable) scope; returns the [Job] so tests can join it. */
    private fun dbOp(name: String, block: suspend () -> Unit): Job =
        scope.launch {
            runCatching { block() }
                .onFailure { log.error("Debug DB op '$name' failed", it) }
        }

    // ---- DB tools (WP4) ------------------------------------------------------

    fun dumpDatabase() = dbOp("dumpDatabase") {
        val watches = repo.getAllWatches()
        log.info("=== DB DUMP: ${watches.size} watch(es) ===")
        for (w in watches) {
            log.info("Watch ${w.macAddress} '${w.name}' model=${w.model} fw=${w.firmwareVersion} batt=${w.batteryLevel}% active=${w.isActive}")
            val alarms = repo.getAlarms(w.macAddress)
            val rules = repo.getRules(w.macAddress)
            val buttons = repo.getButtons(w.macAddress)
            log.debug("  alarms=${alarms.size} rules=${rules.size} buttons=${buttons.size}")
            for (a in alarms) log.debug("  alarm slot=${a.slotId} ${a.hour}:${a.minute} days=0x%02X rep=%b en=%b".format(a.daysMask, a.isRepeating, a.isEnabled))
            for (r in rules) log.debug("  rule pkg=${r.packageName} vibe=${r.vibePattern} h=${r.hourHandDegrees} m=${r.minuteHandDegrees}")
            for (b in buttons) log.debug("  button id=${b.buttonId} type=${b.modeType} actions=${b.actionsJson}")
        }
        log.info("=== DB DUMP end ===")
    }

    fun seedSampleData() = dbOp("seedSampleData") {
        val mac = "AA:BB:CC:DD:EE:01"
        log.info("Seeding sample watch $mac with 2 alarms / 1 rule / 1 button")
        repo.upsertWatch(
            WatchEntity(
                macAddress = mac, name = "Sample Watch",
                model = "HW.0.0", firmwareVersion = "HW0.0.2.9r.v3", batteryLevel = 77,
            )
        )
        repo.upsertAlarm(
            WatchAlarmEntity(
                watchMac = mac, slotId = 0, hour = 7, minute = 30,
                isEnabled = true, daysMask = 0b0111110, isRepeating = true, label = "Weekday",
            )
        )
        repo.upsertAlarm(
            WatchAlarmEntity(
                watchMac = mac, slotId = 1, hour = 9, minute = 0,
                isEnabled = true, daysMask = 0b1000001, isRepeating = true, label = "Weekend",
            )
        )
        repo.upsertRule(
            NotificationRuleEntity(
                watchMac = mac, packageName = "com.whatsapp",
                vibePattern = 4, hourHandDegrees = 90, minuteHandDegrees = 90,
            )
        )
        repo.upsertButton(
            ButtonMappingEntity(
                watchMac = mac, buttonId = 0, modeType = "SINGLE_ACTION",
                actionsJson = """[{"action":"MUSIC_PLAY"}]""",
            )
        )
        log.info("Seed complete — run 'Dump DB' to inspect.")
    }

    fun listWatches() = dbOp("listWatches") {
        val watches = repo.getAllWatches()
        val active = repo.getActiveWatch()
        log.info("Watches (${watches.size}): " +
            watches.joinToString { "${it.macAddress}${if (it.isActive) "*" else ""}" })
        log.info("Active watch: ${active?.macAddress ?: "(none)"}")
    }

    fun transfer(fromMac: String, toMac: String) = dbOp("transfer") {
        if (fromMac.isEmpty() || toMac.isEmpty()) {
            log.warn("Clone/transfer needs both From and To MACs"); return@dbOp
        }
        log.info("Cloning settings $fromMac → $toMac (WatchRepository.transferSettings)")
        // Ensure the target watch row exists so its cloned children have a valid FK parent.
        if (repo.getWatch(toMac) == null) repo.registerWatch(toMac, name = toMac)
        repo.transferSettings(fromMac, toMac)
        val a = repo.getAlarms(toMac).size
        val r = repo.getRules(toMac).size
        val b = repo.getButtons(toMac).size
        log.info("Clone done — $toMac now has alarms=$a rules=$r buttons=$b")
    }

    fun setActive(mac: String) = dbOp("setActive") {
        if (mac.isEmpty()) { log.warn("Activate needs a MAC"); return@dbOp }
        if (repo.getWatch(mac) == null) { log.warn("No such watch $mac — seed/register it first"); return@dbOp }
        repo.setActiveWatch(mac)
        log.info("Active watch set to $mac")
    }

    fun wipe(mac: String) = dbOp("wipe") {
        if (mac.isEmpty()) { log.warn("Wipe needs a MAC"); return@dbOp }
        val before = repo.getAlarms(mac).size + repo.getRules(mac).size + repo.getButtons(mac).size
        log.info("Wiping watch $mac (CASCADE) — had $before child row(s)")
        repo.deleteWatch(mac)
        val after = repo.getAlarms(mac).size + repo.getRules(mac).size + repo.getButtons(mac).size
        log.info("Wipe done — child rows now $after (CASCADE removed them)")
    }

    // ---- BLE / protocol tools (WP3) -----------------------------------------

    fun dumpLinkState() {
        val s = WatchState.status.value
        log.info("Link=${s.link} mac=${s.mac} mtu=${if (s.mtu > 0) s.mtu else "?"} batt=${s.battery} fw=${s.firmware} model=${s.model}")
    }

    // ---- misc ----------------------------------------------------------------

    fun dumpBuildInfo() {
        log.info("App ${BuildConfig.APPLICATION_ID} v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) debug=${BuildConfig.DEBUG} build=${BuildConfig.BUILD_TYPE}")
        log.info("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    fun dumpPermissions() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        log.info("=== Permissions ===")
        for (p in perms) {
            val granted = appContext.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            log.info("  ${p.substringAfterLast('.')}: ${if (granted) "GRANTED" else "DENIED"}")
        }
        log.info("Battery-opt exempt: ${CompanionManager.isIgnoringBatteryOptimizations(appContext)}")
    }

    fun dumpAssociations() {
        val mac = CompanionManager.getAssociatedMac(appContext)
        log.info("Associated MAC (pref): ${mac ?: "(none)"}")
        if (mac != null) log.info("  isAssociated(CDM)=${CompanionManager.isAssociated(appContext, mac)}")
    }
}
