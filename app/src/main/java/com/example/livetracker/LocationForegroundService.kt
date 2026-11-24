package com.example.livetracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class LocationForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.example.livetracker.START"
        const val ACTION_STOP = "com.example.livetracker.STOP"
        const val EXTRA_EMAIL = "extra_email"
        const val EXTRA_PASSWORD = "extra_password"
        const val EXTRA_ENDPOINT = "extra_endpoint"
        private const val CHANNEL_ID = "live_location_channel"
        private const val NOTIF_ID = 1001
        private const val INTERVAL_MS = 4000L
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var locationManager: LocationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var email: String = ""
    private var password: String = ""
    private var updateUrl: String = "https://ab.arashpanela.kdns.fr/api/update"
    private var isRunning = false

    private val loopRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            fetchAndSendOnce()
            mainHandler.postDelayed(this, INTERVAL_MS)
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!isRunning) return
            sendLocationPayload(location, "service_watch")
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                isRunning = false
                mainHandler.removeCallbacks(loopRunnable)
                stopLocationUpdates()
                sendStopPayload {
                    releaseWakeLock()
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val em = intent?.getStringExtra(EXTRA_EMAIL)?.trim()
                val pw = intent?.getStringExtra(EXTRA_PASSWORD)?.trim()
                val ep = intent?.getStringExtra(EXTRA_ENDPOINT)?.trim()

                if (!em.isNullOrEmpty()) email = em
                if (!pw.isNullOrEmpty()) password = pw
                if (!ep.isNullOrEmpty()) updateUrl = ep

                if (email.isEmpty() || password.isEmpty()) {
                    return START_NOT_STICKY
                }

                startForeground(NOTIF_ID, buildNotification("ارسال موقعیت زنده فعال است..."))
                acquireWakeLock()
                isRunning = true
                startLocationUpdates()
                mainHandler.removeCallbacks(loopRunnable)
                mainHandler.post(loopRunnable)
                return START_STICKY
            }
        }
        return START_STICKY
    }

    private fun startLocationUpdates() {
        try {
            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2500L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
            if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    3000L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) {}
    }

    private fun stopLocationUpdates() {
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: Exception) {}
    }

    private fun fetchAndSendOnce() {
        try {
            var bestLoc: Location? = null
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            for (p in providers) {
                if (locationManager?.isProviderEnabled(p) == true) {
                    val loc = locationManager?.getLastKnownLocation(p)
                    if (loc != null && (bestLoc == null || loc.time > bestLoc.time)) {
                        bestLoc = loc
                    }
                }
            }
            if (bestLoc != null) {
                sendLocationPayload(bestLoc, "service_tick")
            }
        } catch (_: SecurityException) {}
    }

    private fun sendLocationPayload(loc: Location, source: String) {
        if (email.isEmpty() || password.isEmpty()) return
        val bodyJson = JSONObject().apply {
            put("email", email)
            put("password", password)
            put("lat", loc.latitude)
            put("lng", loc.longitude)
            put("accuracy", loc.accuracy.toDouble())
            put("speed", loc.speed.toDouble())
            put("source", source)
        }.toString()

        executor.execute {
            postJson(updateUrl, bodyJson)
        }
    }

    private fun sendStopPayload(onComplete: () -> Unit) {
        if (email.isEmpty() || password.isEmpty()) {
            mainHandler.post(onComplete)
            return
        }
        val stopUrl = updateUrl.replace("/api/update", "/api/stop")
        val bodyJson = JSONObject().apply {
            put("email", email)
            put("password", password)
        }.toString()

        executor.execute {
            postJson(stopUrl, bodyJson)
            mainHandler.post(onComplete)
        }
    }

    private fun postJson(targetUrl: String, jsonBody: String) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(targetUrl)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonBody)
                writer.flush()
            }
            conn.responseCode
        } catch (_: Exception) {
        } finally {
            conn?.disconnect()
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiveTracker::WakeLock")
                wakeLock?.acquire(6 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (_: Exception) {}
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("target_url", "https://ab.arashpanela.kdns.fr/")
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ردیاب زنده فعال")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "سرویس موقعیت زنده",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacks(loopRunnable)
        stopLocationUpdates()
        releaseWakeLock()
        executor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
