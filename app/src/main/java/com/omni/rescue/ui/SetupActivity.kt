package com.omni.rescue.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.omni.rescue.R
import com.omni.rescue.data.AppPreferences

class SetupActivity : AppCompatActivity() {
    private lateinit var prefs: AppPreferences
    private val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        prefs = AppPreferences(this)

        findViewById<Button>(R.id.btnGrantPermissions).setOnClickListener {
            requestPermissions()
        }

        // If already granted, go to dashboard
        if (allPermissionsGranted()) {
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
        }
    }

    private fun requestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing, 100)
        } else {
            onPermissionsGranted()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && allPermissionsGranted()) {
            onPermissionsGranted()
        } else {
            Toast.makeText(this, "All permissions are required", Toast.LENGTH_SHORT).show()
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun onPermissionsGranted() {
        prefs.isServiceEnabled = true
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }
}
