package qhybrid.android.defaults

import qhybrid.android.buttons.ButtonActionsJson
import qhybrid.android.db.ButtonMappingEntity
import qhybrid.android.db.NotificationRuleEntity
import qhybrid.android.db.WatchAlarmEntity

/**
 * WP-DEFAULTS (sub-part 2) — the **pure mapper** that re-keys a [DefaultsProfile] onto a concrete
 * watch mac, producing the WP4 child rows the provisioning / apply-defaults paths persist + push.
 *
 * - Empty profile section → empty seed list.
 * - The mac is normalized to UPPER-CASE to match the [qhybrid.android.db.WatchEntity] PK
 *   (see [qhybrid.android.db.WatchRepository.registerSeededWatch] / `registerWatch`).
 * - Button `actions` are encoded back into the canonical `actionsJson` via
 *   [ButtonActionsJson.encode] (the same representation the compiler/editor use).
 *
 * Pure: no Android, no Room, no coroutines; never throws.
 */
object DefaultsToSeed {

    /** The re-keyed child rows ready to persist + push for a watch. */
    data class Seed(
        val alarms: List<WatchAlarmEntity>,
        val rules: List<NotificationRuleEntity>,
        val buttons: List<ButtonMappingEntity>,
    )

    /** Map [profile] onto [mac] (normalized to upper-case). Empty sections → empty lists. */
    fun seed(profile: DefaultsProfile, mac: String): Seed {
        val key = mac.uppercase()
        return Seed(
            alarms = profile.alarms.map { it.toEntity(key) },
            rules = profile.rules.map { it.toEntity(key) },
            buttons = profile.buttons.map { it.toEntity(key) },
        )
    }

    private fun DefaultAlarm.toEntity(mac: String): WatchAlarmEntity =
        WatchAlarmEntity(
            watchMac = mac,
            slotId = slotId,
            hour = hour,
            minute = minute,
            isEnabled = isEnabled,
            daysMask = daysMask,
            isRepeating = isRepeating,
            label = label,
        )

    private fun DefaultRule.toEntity(mac: String): NotificationRuleEntity =
        NotificationRuleEntity(
            watchMac = mac,
            packageName = packageName,
            vibePattern = vibePattern,
            hourHandDegrees = hourHandDegrees,
            minuteHandDegrees = minuteHandDegrees,
        )

    private fun DefaultButton.toEntity(mac: String): ButtonMappingEntity =
        ButtonMappingEntity(
            watchMac = mac,
            buttonId = buttonId,
            modeType = modeType,
            actionsJson = ButtonActionsJson.encode(actions),
        )
}
