package com.r1media

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.ServiceCompat
import java.util.ArrayDeque
import java.util.UUID

/**
 * Self-healing link to the EVEN R1 ring on its "glasses" channel. Runs in its
 * own :ble process and as a foreground service so it keeps running (and keeps
 * the connection live) when the UI task is swiped away. Reconnects by active
 * scanning, which recovers from drops and cold restarts.
 */
class RingService : Service() {

    companion object {
        private const val TAG = "r1media"

        const val ACTION_RESTART = "com.r1media.RESTART"
        const val ACTION_STOP = "com.r1media.STOP"
        const val ACTION_SYNC = "com.r1media.SYNC"

        const val ACTION_STATE = "com.r1media.STATE"
        const val EX_STATUS = "status"
        const val EX_BATTERY = "battery"
        const val EX_CHARGING = "charging"
        const val EX_WEAR = "wear"
        const val EX_GESTURE = "gesture"
        const val EX_ACTION = "action"
        const val EX_ARMED = "armed"
        const val EX_CONNECTED = "connected"

        private const val NAME_PREFIX = "EVEN R1"

        private val SERVICE_UUID = UUID.fromString("bae80001-4f05-4503-8e65-3af1f7329d1f")
        private val WRITE_UUID = UUID.fromString("bae80010-4f05-4503-8e65-3af1f7329d1f")
        private val NOTIFY_UUID = UUID.fromString("bae80011-4f05-4503-8e65-3af1f7329d1f")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private val PAIR_AUTH = byteArrayOf(0x00, 0x35, 0x88.toByte(), 0x00)
        private val GLS_STATUS = byteArrayOf(0x00, 0x1a, 0x89.toByte(), 0x01, 0x00, 0x00, 0x00, 0x00)
        private val ALGO_TIME = byteArrayOf(0x00, 0x1a, 0x8a.toByte(), 0x01, 0x64, 0x00)
        private val TOUCH_ON = byteArrayOf(0x00, 0x1a, 0x85.toByte(), 0x01, 0x00)
        private val HEARTBEAT = byteArrayOf(0x00, 0x1a, 0x94.toByte(), 0x01)

        private const val HEARTBEAT_MS = 2000L
        private const val STALE_MS = 8000L
        private const val RESCAN_MS = 12000L       // refresh the scan while searching
        private const val RECONNECT_DELAY_MS = 1500L

        private const val CHANNEL_ID = "r1media"
        private const val NOTIF_ID = 1
    }

    private val main = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var device: BluetoothDevice? = null

    @Volatile private var active = false
    @Volatile private var connected = false
    @Volatile private var lastRx = 0L
    private var started = false
    private var scanning = false

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writing = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var hbCount = 0

    private var emitted = false
    private var armedUntil = 0L

    // UI snapshot (broadcast to the activity).
    private var sStatus = "Starting…"
    private var sBattery = -1
    private var sCharging = false
    private var sWear = false
    private var sGesture = "—"
    private var sAction = "—"
    private var sArmed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        adapter = getSystemService(BluetoothManager::class.java)?.adapter
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "stop requested")
            stopEverything()
            return START_NOT_STICKY
        }
        if (!startForegroundNotif(sStatus)) {
            Log.w(TAG, "could not enter foreground; bailing")
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        active = true
        if (!started) {
            started = true
            Log.i(TAG, "service started (pid ${android.os.Process.myPid()}), beginning connect")
            ensureConnection()
        }
        when (intent?.action) {
            ACTION_RESTART -> forceReconnect()
            ACTION_SYNC -> broadcastState()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (active) {
            val restart = Intent(applicationContext, RingService::class.java)
            val pi = PendingIntent.getForegroundService(
                this, 1, restart,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            getSystemService(AlarmManager::class.java)
                ?.setAndAllowWhileIdle(AlarmManager.RTC, System.currentTimeMillis() + 1500, pi)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        main.removeCallbacks(heartbeatTick)
        main.removeCallbacks(rescan)
        main.removeCallbacks(disarmTick)
        main.removeCallbacks(writeTimeout)
        stopScan()
        closeGatt()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun stopEverything() {
        active = false
        connected = false
        main.removeCallbacksAndMessages(null)
        stopScan()
        closeGatt()
        releaseWakeLock()
        setStatus("Stopped")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- wake lock ---------------------------------------------------------

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "r1media:link")
                ?.apply { setReferenceCounted(false) }
        }
        try {
            if (wakeLock?.isHeld != true) wakeLock?.acquire()
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
    }

    // ---- connect / reconnect (active-scan based) --------------------------

    private fun ensureConnection() {
        if (!active || connected) return
        scan()
    }

    private fun forceReconnect() {
        Log.i(TAG, "force reconnect")
        connected = false
        closeGatt()
        setStatus("Reconnecting…")
        main.postDelayed({ ensureConnection() }, 300)
    }

    @SuppressLint("MissingPermission")
    private fun scan() {
        if (scanning || connected || !active) return
        val a = adapter
        if (a == null || !a.isEnabled) {
            setStatus("Enable Bluetooth")
            main.postDelayed({ ensureConnection() }, 4000)
            return
        }
        val scanner = a.bluetoothLeScanner ?: run {
            setStatus("No BLE scanner"); return
        }
        setStatus("Searching for ring…")
        scanning = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (_: SecurityException) {
            scanning = false
            setStatus("Bluetooth permission missing")
            return
        }
        // Keep the scan fresh until we connect.
        main.removeCallbacks(rescan)
        main.postDelayed(rescan, RESCAN_MS)
    }

    private val rescan = Runnable {
        if (active && !connected) {
            stopScan()
            scan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        main.removeCallbacks(rescan)
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: try {
                result.device.name
            } catch (_: SecurityException) {
                null
            }
            if (name?.startsWith(NAME_PREFIX) == true) {
                Log.i(TAG, "found $name, connecting")
                stopScan()
                setStatus("Connecting to $name…")
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            scanning = false
            if (active && !connected) main.postDelayed({ ensureConnection() }, 3000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(dev: BluetoothDevice) {
        device = dev
        closeGatt()
        try {
            gatt = dev.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            setStatus("Bluetooth permission missing")
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) {
        }
        gatt = null
        writeChar = null
        synchronized(writeQueue) { writeQueue.clear(); writing = false }
    }

    // ---- GATT --------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    Log.i(TAG, "gatt connected (status $status), discovering")
                    lastRx = SystemClock.uptimeMillis()
                    setStatus("Discovering services…")
                    try {
                        g.discoverServices()
                    } catch (_: SecurityException) {
                    }
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    Log.i(TAG, "gatt disconnected (status $status)")
                    connected = false
                    setConnectedFlag(false)
                    main.removeCallbacks(heartbeatTick)
                    writeChar = null
                    synchronized(writeQueue) { writeQueue.clear(); writing = false }
                    try {
                        g.close()
                    } catch (_: SecurityException) {
                    }
                    if (gatt === g) gatt = null
                    if (active) {
                        setStatus("Reconnecting…")
                        main.postDelayed({ ensureConnection() }, RECONNECT_DELAY_MS)
                    } else {
                        setStatus("Disconnected")
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE_UUID) ?: run {
                Log.w(TAG, "ring service not found"); setStatus("Ring service not found"); return
            }
            writeChar = svc.getCharacteristic(WRITE_UUID)
            val notify = svc.getCharacteristic(NOTIFY_UUID)
            if (writeChar == null || notify == null) {
                setStatus("Ring channel not found"); return
            }
            try {
                g.setCharacteristicNotification(notify, true)
                val cccd = notify.getDescriptor(CCCD_UUID)
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            } catch (_: SecurityException) {
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            Log.i(TAG, "handshake: notifications on, sending frames")
            connected = true
            setConnectedFlag(true)
            lastRx = SystemClock.uptimeMillis()
            hbCount = 0
            setStatus("Connected")
            enqueue(PAIR_AUTH)
            enqueue(GLS_STATUS)
            enqueue(ALGO_TIME)
            enqueue(TOUCH_ON)
            main.removeCallbacks(heartbeatTick)
            main.postDelayed(heartbeatTick, HEARTBEAT_MS)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
        ) {
            main.removeCallbacks(writeTimeout)
            synchronized(writeQueue) { writing = false }
            drain()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (c.uuid == NOTIFY_UUID) handleNotify(value)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == NOTIFY_UUID) c.value?.let { handleNotify(it) }
        }
    }

    // ---- write queue + heartbeat ------------------------------------------

    private fun enqueue(bytes: ByteArray) {
        synchronized(writeQueue) { writeQueue.add(bytes) }
        drain()
    }

    @SuppressLint("MissingPermission")
    private fun drain() {
        val g = gatt ?: return
        val ch = writeChar ?: return
        val next: ByteArray
        synchronized(writeQueue) {
            if (writing) return
            next = writeQueue.pollFirst() ?: return
            writing = true
        }
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val ok = try {
            @Suppress("DEPRECATION")
            run {
                ch.value = next
                g.writeCharacteristic(ch)
            }
        } catch (_: SecurityException) {
            false
        }
        if (!ok) {
            synchronized(writeQueue) {
                writeQueue.addFirst(next)
                writing = false
            }
            main.postDelayed({ drain() }, 60)
            return
        }
        main.removeCallbacks(writeTimeout)
        main.postDelayed(writeTimeout, 1200)
    }

    private val writeTimeout = Runnable {
        synchronized(writeQueue) { writing = false }
        drain()
    }

    private val heartbeatTick = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            val g = gatt ?: return
            val now = SystemClock.uptimeMillis()
            if (connected && lastRx > 0 && now - lastRx > STALE_MS) {
                Log.i(TAG, "link stale, forcing reconnect")
                setStatus("Link stalled — reconnecting…")
                try {
                    g.disconnect()
                } catch (_: SecurityException) {
                }
                return
            }
            hbCount++
            if (hbCount % 10 == 0) {
                enqueue(GLS_STATUS)
                enqueue(ALGO_TIME)
                enqueue(TOUCH_ON)
            }
            enqueue(HEARTBEAT)
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    // ---- gesture decode ----------------------------------------------------

    private fun handleNotify(v: ByteArray) {
        lastRx = SystemClock.uptimeMillis()
        if (v.size < 3 || v[0].toInt() != 0x00) return
        when (v[2].toInt() and 0xFF) {
            0x61 -> if (v.size >= 5) classifyTouch(v[4].toInt() and 0xFF)
            0x8b -> if (v.size >= 6) {
                sBattery = v[4].toInt() and 0xFF
                sCharging = v[5].toInt() != 0
                broadcastState()
            }
            0x8c -> if (v.size >= 5) {
                sWear = v[4].toInt() != 0
                broadcastState()
            }
        }
    }

    private fun classifyTouch(type: Int) {
        when (type) {
            0x0a -> emitted = false
            0x04 -> emit(Gesture.SWIPE_UP)
            0x05 -> emit(Gesture.SWIPE_DOWN)
            0x01 -> emit(Gesture.TAP)
            0x02 -> emit(Gesture.DOUBLE_TAP)
            0x00 -> emit(Gesture.HOLD)
            0x08 -> {}
        }
    }

    private enum class Gesture { TAP, DOUBLE_TAP, HOLD, SWIPE_UP, SWIPE_DOWN }

    private fun emit(g: Gesture) {
        if (emitted) return
        emitted = true
        onGesture(g)
    }

    private fun onGesture(g: Gesture) {
        val now = SystemClock.uptimeMillis()
        sGesture = g.name.lowercase().replace('_', ' ')

        if (g == Gesture.HOLD) {
            arm(now)
            sArmed = true
            broadcastState()
            return
        }

        if (now < armedUntil) {
            when (g) {
                Gesture.SWIPE_UP -> { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); sAction = "next track"; arm(now); sArmed = true }
                Gesture.SWIPE_DOWN -> { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); sAction = "previous track"; arm(now); sArmed = true }
                Gesture.TAP -> { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); sAction = "play / pause"; arm(now); sArmed = true }
                else -> {}
            }
        } else {
            sArmed = false
        }
        broadcastState()
    }

    private fun arm(now: Long) {
        val w = Prefs.windowSeconds(this) * 1000L
        armedUntil = now + w
        main.removeCallbacks(disarmTick)
        main.postDelayed(disarmTick, w + 50)
    }

    private val disarmTick = Runnable {
        if (SystemClock.uptimeMillis() >= armedUntil) {
            sArmed = false
            broadcastState()
        }
    }

    private fun mediaKey(code: Int) {
        val t = SystemClock.uptimeMillis()
        audio?.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        audio?.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    // ---- state broadcast + notification -----------------------------------

    private fun setStatus(s: String) {
        sStatus = s
        broadcastState()
        updateNotif(s)
    }

    private fun setConnectedFlag(c: Boolean) { /* reflected via broadcast */ }

    private fun broadcastState() {
        val i = Intent(ACTION_STATE).setPackage(packageName)
        i.putExtra(EX_STATUS, sStatus)
        i.putExtra(EX_BATTERY, sBattery)
        i.putExtra(EX_CHARGING, sCharging)
        i.putExtra(EX_WEAR, sWear)
        i.putExtra(EX_GESTURE, sGesture)
        i.putExtra(EX_ACTION, sAction)
        i.putExtra(EX_ARMED, sArmed)
        i.putExtra(EX_CONNECTED, connected)
        sendBroadcast(i)
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ring link", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotif(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("R1 Media")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundNotif(text: String): Boolean {
        val notif = buildNotif(text)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotif(text))
    }
}
