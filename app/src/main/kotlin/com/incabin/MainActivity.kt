package com.incabin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.app.Activity
import android.util.Log

class MainActivity : Activity() {

    companion object {
        private const val TAG = "InCabin-Activity"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toggleButton = findViewById(R.id.toggleButton)
        statusText = findViewById(R.id.statusText)

        toggleButton.setOnClickListener {
            if (isRunning) {
                stopMonitoring()
            } else {
                checkPermissionsAndStart()
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            startMonitoring()
        } else {
            requestPermissions(needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val cameraGranted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED

            if (cameraGranted) {
                startMonitoring()
            } else {
                statusText.text = "Status: Camera permission denied"
                Log.w(TAG, "Camera permission denied")
            }
        }
    }

    private fun startMonitoring() {
        val intent = Intent(this, InCabinService::class.java).apply {
            action = InCabinService.ACTION_START
        }
        startForegroundService(intent)
        isRunning = true
        toggleButton.text = getString(R.string.stop_service)
        statusText.text = "Status: Monitoring active"
        Log.i(TAG, "Monitoring started")
    }

    private fun stopMonitoring() {
        val intent = Intent(this, InCabinService::class.java).apply {
            action = InCabinService.ACTION_STOP
        }
        startService(intent)
        isRunning = false
        toggleButton.text = getString(R.string.start_service)
        statusText.text = "Status: Idle"
        Log.i(TAG, "Monitoring stopped")
    }
}
