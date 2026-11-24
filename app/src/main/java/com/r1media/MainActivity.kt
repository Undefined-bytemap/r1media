package com.r1media

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

/**
 * Thin UI over the always-on RingService. The service manages the connection;
 * this screen just shows live state and offers Restart / Stop.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var gesture: TextView
    private lateinit var action: TextView
    private lateinit var restart: Button
    private lateinit var stop: Button
    private lateinit var windowLabel: TextView
    private lateinit var windowSlider: SeekBar
    private lateinit var battery: Button
    private lateinit var autostart: SwitchCompat

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == RingService.ACTION_STATE) render(i)
        }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) startService() else status.text = "Bluetooth permission is required"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        details = findViewById(R.id.details)
        gesture = findViewById(R.id.gesture)
        action = findViewById(R.id.action)
        restart = findViewById(R.id.restart)
        stop = findViewById(R.id.stop)
        windowLabel = findViewById(R.id.windowLabel)
        windowSlider = findViewById(R.id.windowSlider)
        battery = findViewById(R.id.battery)
        autostart = findViewById(R.id.autostart)

        restart.setOnClickListener { sendToService(RingService.ACTION_RESTART) }
        stop.setOnClickListener { sendToService(RingService.ACTION_STOP) }

        val startS = Prefs.windowSeconds(this)
        windowSlider.progress = startS
        windowLabel.text = "Command window: $startS s"
        windowSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                val v = value.coerceIn(1, 30)
                windowLabel.text = "Command window: $v s"
                Prefs.setWindowSeconds(this@MainActivity, v)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        autostart.isChecked = Prefs.autostart(this)
        autostart.setOnCheckedChangeListener { _, checked -> Prefs.setAutostart(this, checked) }

        battery.setOnClickListener { requestBatteryExemption() }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, stateReceiver, IntentFilter(RingService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // The app being open means it should be running: make sure it is.
        ensureRunning()
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryButton()
        sendToService(RingService.ACTION_SYNC)   // pull current state into the UI
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
    }

    // ---- service control ---------------------------------------------------

    private fun ensureRunning() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) startService() else permLauncher.launch(needed.toTypedArray())
    }

    private fun startService() {
        ContextCompat.startForegroundService(this, Intent(this, RingService::class.java))
        if (!isExempt()) requestBatteryExemption()
    }

    private fun sendToService(action: String) {
        if (requiredPermissions().any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }) {
            if (action != RingService.ACTION_STOP) { ensureRunning(); return }
        }
        ContextCompat.startForegroundService(
            this, Intent(this, RingService::class.java).setAction(action)
        )
        if (action == RingService.ACTION_STOP) {
            status.text = "Stopped"
            gesture.text = "—"
        }
    }

    private fun requiredPermissions(): List<String> {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p += Manifest.permission.BLUETOOTH_SCAN
            p += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            p += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            p += Manifest.permission.POST_NOTIFICATIONS
        }
        return p
    }

    // ---- battery optimization ---------------------------------------------

    private fun isExempt(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @Suppress("BatteryLife")
    private fun requestBatteryExemption() {
        if (isExempt()) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun refreshBatteryButton() {
        battery.text = if (isExempt()) {
            "Battery optimization: disabled ✓"
        } else {
            "Battery optimization: ON — tap to disable"
        }
    }

    // ---- live UI -----------------------------------------------------------

    private fun render(i: Intent) {
        val st = i.getStringExtra(RingService.EX_STATUS) ?: "—"
        val armed = i.getBooleanExtra(RingService.EX_ARMED, false)
        status.text = if (armed) "$st   •  ARMED" else st

        val b = i.getIntExtra(RingService.EX_BATTERY, -1)
        val charging = i.getBooleanExtra(RingService.EX_CHARGING, false)
        val wear = i.getBooleanExtra(RingService.EX_WEAR, false)
        val batt = if (b in 0..100) "$b%${if (charging) " (charging)" else ""}" else "—"
        details.text = "battery $batt  ·  wear ${if (wear) "on" else "off"}"

        gesture.text = i.getStringExtra(RingService.EX_GESTURE) ?: "—"
        action.text = "last action: ${i.getStringExtra(RingService.EX_ACTION) ?: "—"}"
    }
}
