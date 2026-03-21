package com.omni.rescue.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.omni.rescue.R
import com.omni.rescue.data.local.AppPreferences
import com.omni.rescue.service.RescueListenerService

class SetupActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            requestBatteryOptimization()
        } else {
            val denied = permissions.filterValues { !it }.keys.joinToString(", ")
            Toast.makeText(this, "Required permissions denied: $denied", Toast.LENGTH_LONG).show()
            if (permissions.any { !it.value && !shouldShowRequestPermissionRationale(it.key) }) {
                openAppSettings()
            }
        }
    }

    // Launched AFTER the battery dialog so service starts only when user returns
    private val batteryOptLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        startListeningService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        prefs = AppPreferences(applicationContext)

        if (prefs.isServiceRunning) {
            goToDashboard()
            return
        }

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            checkPermissionsAndStart()
        }
    }

    private fun checkPermissionsAndStart() {
        val needed = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.CAMERA
            // VIBRATE is a normal permission — must NOT be requested at runtime
        )

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) requestBatteryOptimization()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun requestBatteryOptimization() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        // Use result launcher so service starts only after user dismisses the dialog
        batteryOptLauncher.launch(intent)
    }

    private fun startListeningService() {
        startForegroundService(Intent(this, RescueListenerService::class.java))
        prefs.isServiceRunning = true
        goToDashboard()
    }

    private fun goToDashboard() {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        })
    }
}