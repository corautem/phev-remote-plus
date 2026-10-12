package io.github.anwo91.phevlink.util

import android.content.Context
import androidx.annotation.StringRes
import io.github.anwo91.phevlink.R
import io.github.anwo91.phevlink.core.model.CarCommand
import io.github.anwo91.phevlink.core.model.ClimateMode
import io.github.anwo91.phevlink.core.model.Doors
import io.github.anwo91.phevlink.core.model.VehicleSettingsText
import io.github.anwo91.phevlink.core.protocol.ModelYear

/**
 * Localized names for things the core library labels in English (the core, the bridge
 * and the diagnostics log keep using those English labels).
 */
object Labels {
    @StringRes
    fun mode(m: ClimateMode): Int = when (m) {
        ClimateMode.COOL -> R.string.mode_cool
        ClimateMode.HEAT -> R.string.mode_heat
        ClimateMode.WINDSCREEN -> R.string.mode_defrost
    }

    /** The mode as used inside a sentence ("Scheduled heat failed"). */
    @StringRes
    fun modeInline(m: ClimateMode): Int = when (m) {
        ClimateMode.COOL -> R.string.mode_cool_inline
        ClimateMode.HEAT -> R.string.mode_heat_inline
        ClimateMode.WINDSCREEN -> R.string.mode_defrost_inline
    }

    /** What a command does, for buttons, messages and notifications. */
    fun command(context: Context, cmd: CarCommand): String {
        val res = AppLocale.localized(context)
        return when (cmd) {
            is CarCommand.ClimateOn -> {
                val mode = res.getString(mode(cmd.mode))
                if (cmd.delayMinutes > 0) res.getString(R.string.cmd_climate_on_delayed, mode, cmd.duration.minutes, cmd.delayMinutes)
                else res.getString(R.string.cmd_climate_on, mode, cmd.duration.minutes)
            }
            CarCommand.ClimateOff -> res.getString(R.string.cmd_climate_off)
            is CarCommand.Headlights -> res.getString(if (cmd.on) R.string.cmd_headlights_on else R.string.cmd_headlights_off)
            is CarCommand.ParkingLights -> res.getString(if (cmd.on) R.string.cmd_parking_on else R.string.cmd_parking_off)
            is CarCommand.Locate -> res.getString(R.string.cmd_locate)
            CarCommand.RequestUpdate -> res.getString(R.string.common_refresh)
            CarCommand.RegisterPhone -> res.getString(R.string.cmd_register)
            CarCommand.UnregisterPhone -> res.getString(R.string.cmd_unregister)
            CarCommand.AckClimateCancelled -> res.getString(R.string.cmd_ack_climate)
            CarCommand.ChargeNow -> res.getString(R.string.cmd_charge_now)
            CarCommand.ResumeChargeTimer -> res.getString(R.string.cmd_resume_charge_timer)
            is CarCommand.SetChargeTimer -> res.getString(R.string.cmd_set_charge_timer)
            is CarCommand.SyncClock -> res.getString(R.string.cmd_sync_clock)
            is CarCommand.ChangeSetting -> {
                val lang = AppLocale.effectiveLanguage(context)
                res.getString(
                    R.string.cmd_change_setting,
                    VehicleSettingsText.name(cmd.id, lang),
                    VehicleSettingsText.option(cmd.id, cmd.value, lang),
                )
            }
            CarCommand.ResetSettings -> res.getString(R.string.cmd_reset_settings)
            is CarCommand.WifiAlwaysOn -> res.getString(if (cmd.on) R.string.cmd_wifi_always_on else R.string.cmd_wifi_always_off)
            // Raw writes are hex.
            else -> cmd.label
        }
    }

    /** Why a command failed, in the app language where the core reports a known reason (English otherwise). */
    fun failureReason(context: Context, reason: String): String {
        val res = AppLocale.localized(context)
        return when (reason) {
            CarCommand.ChangeSetting.UNCONFIRMED -> res.getString(R.string.msg_setting_unconfirmed)
            CarCommand.SetChargeTimer.UNCONFIRMED -> res.getString(R.string.msg_charge_timer_unconfirmed)
            CarCommand.SetChargeTimer.NOT_MY14 -> res.getString(R.string.ct_my18)
            CarCommand.ClimateOn.POWER_ON -> res.getString(R.string.msg_climate_needs_off)
            CarCommand.ClimateOn.QUICK_CHARGING -> res.getString(R.string.msg_climate_quick_charging)
            CarCommand.ClimateOn.NOT_STARTED -> res.getString(R.string.msg_climate_not_started)
            CarCommand.ChargeNow.NOT_PLUGGED -> res.getString(R.string.msg_charge_not_plugged)
            // An older bridge doesn't know a newer command.
            else -> if (reason.startsWith("Unknown action")) res.getString(R.string.msg_bridge_needs_update) else reason
        }
    }

    /** Protocol generation: the year range, or "unknown" in the app language. */
    fun modelYear(context: Context, my: ModelYear): String =
        if (my == ModelYear.UNKNOWN) AppLocale.localized(context).getString(R.string.protocol_unknown) else my.label

    /** The open doors/lids, lower case, for joining into a sentence. */
    fun openDoors(context: Context, doors: Doors): List<String> {
        val res = AppLocale.localized(context)
        return buildList {
            if (doors.driver) add(res.getString(R.string.door_driver))
            if (doors.frontPassenger) add(res.getString(R.string.door_passenger))
            if (doors.rearLeft) add(res.getString(R.string.door_rear_left))
            if (doors.rearRight) add(res.getString(R.string.door_rear_right))
            if (doors.boot) add(res.getString(R.string.door_boot))
            if (doors.bonnet) add(res.getString(R.string.door_bonnet))
        }
    }
}
