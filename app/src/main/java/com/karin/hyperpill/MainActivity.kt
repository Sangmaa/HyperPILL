package com.karin.hyperpill

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.karin.hyperpill.ui.MainScreen
import com.karin.hyperpill.ui.theme.HyperPILLTheme

class MainActivity : ComponentActivity() {

    private val viewModel: PillViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.refreshDevices()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        android.util.Log.i("HyperPILL", "POST_NOTIFICATIONS granted=$granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        // The module usually connects before this screen is ever opened, so the UI starts
        // mirroring that session instead of waiting for a manual tap.
        viewModel.startMirroringSystemSession()
        setContent {
            HyperPILLTheme {
                val state by viewModel.uiState.collectAsState()
                val context = LocalContext.current
                val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                LaunchedEffect(permissionGranted) {
                    if (permissionGranted) viewModel.refreshDevices()
                }

                MainScreen(
                    state = state,
                    permissionGranted = permissionGranted,
                    onRequestPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        } else {
                            viewModel.refreshDevices()
                        }
                    },
                    onSelectDevice = { viewModel.connect(it) },
                    onDisconnect = { viewModel.disconnect() },
                    onRefreshDevices = { viewModel.refreshDevices() },
                    onRefreshBattery = { viewModel.refreshBattery() },
                    onSetGain = { viewModel.setGain(it) },
                    onSelectEq = { viewModel.selectEqSet(it) },
                    onSetOneBringTwo = { viewModel.setOneBringTwoState(it) },
                    onSetOneBringTwoTimeout = { viewModel.setOneBringTwoTimeout(it) },
                    onSetVoiceEnabled = { viewModel.setVoiceEnabled(it) },
                    onSetVoiceVolume = { viewModel.setVoiceVolume(it) },
                    onSpoofDevice = { viewModel.spoofDevice(it) }
                )
            }
        }
    }

    /**
     * HyperOS 4 keeps "允许通知" switched off (and reverts manual enabling to "不允许")
     * when the app never asks for POST_NOTIFICATIONS. Ask once on launch so the user can
     * grant it; the notification itself is posted from the Bluetooth host process.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
