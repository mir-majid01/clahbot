package com.clahbot.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.clahbot.R
import com.clahbot.data.SharedVisionBuffer
import com.clahbot.service.AutomationForegroundService

class FloatingDashboard(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    fun show() {
        if (overlayView != null) return

        val inflater = LayoutInflater.from(context)
        overlayView = inflater.inflate(R.layout.overlay_dashboard, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 100
        }

        windowManager.addView(overlayView, params)
        bindControls()
    }

    fun hide() {
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }

    fun updateStatus(status: String) {
        overlayView?.findViewById<TextView>(R.id.tvPlaybackStatusDetail)?.text = status
    }

    fun updateProgress(currentMs: Long, totalMs: Long, macroName: String) {
        val view = overlayView ?: return
        val cSec = (currentMs / 1000) % 60
        val cMin = (currentMs / 60000)
        val tSec = (totalMs / 1000) % 60
        val tMin = (totalMs / 60000)
        view.findViewById<TextView>(R.id.tvPlaybackProgress).text =
            String.format("%02d:%02d / %02d:%02d", cMin, cSec, tMin, tSec)
        view.findViewById<TextView>(R.id.tvPlaybackName).text = macroName
    }

    private fun bindControls() {
        val view = overlayView ?: return
        view.findViewById<Button>(R.id.btnPlaybackPause).setOnClickListener {
            context.startService(
                android.content.Intent(context, AutomationForegroundService::class.java)
                    .setAction(AutomationForegroundService.ACTION_PAUSE)
            )
        }
        view.findViewById<Button>(R.id.btnPlaybackStop).setOnClickListener {
            AutomationForegroundService.stop(context)
        }
    }
}
