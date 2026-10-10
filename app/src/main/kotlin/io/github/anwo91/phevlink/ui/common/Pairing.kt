package io.github.anwo91.phevlink.ui.common

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.companion.WifiDeviceFilter
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.wifi.ScanResult
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.Executor
import java.util.regex.Pattern

/**
 * Companion-device pairing with the car's Wi-Fi access point. The user picks
 * the car from a system list once; afterwards Android lets the app join that
 * exact access point silently, even from the background. No location
 * permission needed.
 */
class CarPairing(
    private val context: Context,
    private val launch: (IntentSender) -> Unit,
) {
    fun start(ssid: String, onError: (String) -> Unit) {
        val cdm = context.getSystemService(CompanionDeviceManager::class.java)
        if (cdm == null) {
            onError("This phone does not support companion pairing"); return
        }
        val pattern = if (ssid.isNotBlank()) Pattern.compile(Pattern.quote(ssid)) else Pattern.compile("REMOTE.*")
        val filter = WifiDeviceFilter.Builder().setNamePattern(pattern).build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val exec: Executor = context.mainExecutor
            cdm.associate(request, exec, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = launch(intentSender)
                override fun onAssociationCreated(associationInfo: AssociationInfo) {}
                override fun onFailure(error: CharSequence?) = onError(error?.toString() ?: "Pairing failed")
            })
        } else {
            @Suppress("DEPRECATION")
            cdm.associate(request, object : CompanionDeviceManager.Callback() {
                @Deprecated("Deprecated in Java")
                override fun onDeviceFound(chooserLauncher: IntentSender) = launch(chooserLauncher)
                override fun onFailure(error: CharSequence?) = onError(error?.toString() ?: "Pairing failed")
            }, null)
        }
    }

    companion object {
        /** The car's BSSID from the pairing result, if any. */
        fun bssidFrom(data: Intent?): String? {
            if (data == null) return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
                    ?.deviceMacAddress?.toString()?.let { return it }
                data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, ScanResult::class.java)?.BSSID?.let { return it }
                return null
            }
            @Suppress("DEPRECATION")
            val sr = data.getParcelableExtra<ScanResult>(CompanionDeviceManager.EXTRA_DEVICE)
            return sr?.BSSID
        }
    }
}

@Composable
fun rememberCarPairing(onPaired: (String?) -> Unit): CarPairing {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        onPaired(if (result.resultCode == android.app.Activity.RESULT_OK) CarPairing.bssidFrom(result.data) else null)
    }
    return remember(ctx) {
        CarPairing(ctx) { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
    }
}
