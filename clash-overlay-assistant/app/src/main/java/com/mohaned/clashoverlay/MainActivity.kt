package com.mohaned.clashoverlay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import com.mohaned.clashoverlay.overlay.OverlayService

class MainActivity : Activity() {
    companion object { private const val CAPTURE_REQUEST = 7001 }

    private var pendingStart = false
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            pendingStart = false
            status.text = "Status: stopped"
        }

        findViewById<Button>(R.id.startButton).setOnClickListener { requestStart() }
    }

    override fun onResume() {
        super.onResume()
        if (pendingStart && Settings.canDrawOverlays(this)) {
            pendingStart = false
            requestScreenCapture()
        }
    }

    private fun requestStart() {
        if (!Settings.canDrawOverlays(this)) {
            pendingStart = true
            status.text = "Status: enable Display over other apps"
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        status.text = "Status: waiting for screen-share approval…"
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE_REQUEST)
    }

    @Deprecated("Use Activity Result API when migrating the UI; kept for API 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CAPTURE_REQUEST) return

        if (resultCode != RESULT_OK || data == null) {
            status.text = "Status: screen-share cancelled"
            return
        }

        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(OverlayService.EXTRA_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent)
        else startService(serviceIntent)
        status.text = "Status: capture starting • observe only"
    }
}
