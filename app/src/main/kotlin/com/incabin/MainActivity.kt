package com.incabin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.app.Activity
import android.util.Log

class MainActivity : Activity() {

    companion object {
        private const val TAG = "InCabin-Activity"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val PREVIEW_POLL_MS = 500L
    }

    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private lateinit var previewImage: ImageView
    private lateinit var dashboardPanel: LinearLayout
    private lateinit var riskBanner: TextView
    private lateinit var earText: TextView
    private lateinit var marText: TextView
    private lateinit var yawText: TextView
    private lateinit var pitchText: TextView
    private lateinit var passengerText: TextView
    private lateinit var distractionText: TextView
    private lateinit var detectionsText: TextView
    private var isRunning = false

    private val handler = Handler(Looper.getMainLooper())
    private val previewPoller = object : Runnable {
        override fun run() {
            try {
                val frameData = FrameHolder.getLatest()
                if (frameData != null && !frameData.bitmap.isRecycled) {
                    previewImage.setImageBitmap(frameData.bitmap)
                    updateDashboard(frameData.result)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Preview update failed", e)
            }
            handler.postDelayed(this, PREVIEW_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toggleButton = findViewById(R.id.toggleButton)
        statusText = findViewById(R.id.statusText)
        previewImage = findViewById(R.id.previewImage)
        dashboardPanel = findViewById(R.id.dashboardPanel)
        riskBanner = findViewById(R.id.riskBanner)
        earText = findViewById(R.id.earText)
        marText = findViewById(R.id.marText)
        yawText = findViewById(R.id.yawText)
        pitchText = findViewById(R.id.pitchText)
        passengerText = findViewById(R.id.passengerText)
        distractionText = findViewById(R.id.distractionText)
        detectionsText = findViewById(R.id.detectionsText)

        toggleButton.setOnClickListener {
            if (isRunning) {
                stopMonitoring()
            } else {
                checkPermissionsAndStart()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isRunning) {
            handler.post(previewPoller)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(previewPoller)
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
        dashboardPanel.visibility = LinearLayout.VISIBLE
        handler.post(previewPoller)
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
        dashboardPanel.visibility = LinearLayout.GONE
        handler.removeCallbacks(previewPoller)
        previewImage.setImageBitmap(null)
        Log.i(TAG, "Monitoring stopped")
    }

    private fun updateDashboard(result: OutputResult) {
        // Risk banner
        when (result.riskLevel) {
            "high" -> {
                riskBanner.text = "RISK: HIGH"
                riskBanner.setBackgroundColor(Color.rgb(0xF4, 0x43, 0x36))
                riskBanner.setTextColor(Color.WHITE)
            }
            "medium" -> {
                riskBanner.text = "RISK: MEDIUM"
                riskBanner.setBackgroundColor(Color.rgb(0xFF, 0x98, 0x00))
                riskBanner.setTextColor(Color.BLACK)
            }
            else -> {
                riskBanner.text = "RISK: LOW"
                riskBanner.setBackgroundColor(Color.rgb(0x4C, 0xAF, 0x50))
                riskBanner.setTextColor(Color.BLACK)
            }
        }

        // Metrics
        earText.text = "EAR: ${result.earValue?.let { "%.3f".format(it) } ?: "--"}"
        marText.text = "MAR: ${result.marValue?.let { "%.3f".format(it) } ?: "--"}"
        yawText.text = "Yaw: ${result.headYaw?.let { "%.1f".format(it) } ?: "--"}"
        pitchText.text = "Pitch: ${result.headPitch?.let { "%.1f".format(it) } ?: "--"}"

        // Info row
        passengerText.text = "Passengers: ${result.passengerCount}"
        distractionText.text = "Distraction: ${result.distractionDurationS}s"

        // Active detections
        val active = mutableListOf<String>()
        if (result.driverUsingPhone) active.add("Phone")
        if (result.driverEyesClosed) active.add("Eyes Closed")
        if (result.driverYawning) active.add("Yawning")
        if (result.driverDistracted) active.add("Distracted")
        if (result.driverEatingDrinking) active.add("Eating/Drinking")
        if (result.dangerousPosture) active.add("Bad Posture")
        if (result.childSlouching) active.add("Child Slouching")
        detectionsText.text = active.joinToString(" | ")
    }
}
