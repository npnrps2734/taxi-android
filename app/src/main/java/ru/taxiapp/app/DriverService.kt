package ru.taxiapp.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

// Служба «Вы на линии». Пока водитель на линии, она работает как служба переднего плана с
// постоянным уведомлением: Android не усыпляет приложение и не отключает сокет с заказами и GPS,
// даже если приложение свёрнуто или экран выключен. Запускается и останавливается страницей
// водителя через мост UmkaApp.setOnLine(true/false) (см. MainActivity.UmkaBridge).
class DriverService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    // ---- GPS из службы ----
    // Пока приложение на экране, координаты шлёт сама страница. Когда приложение свёрнуто, WebView
    // может присылать GPS редко или совсем перестать — тогда координаты читаем здесь и отправляем
    // на сервер обычным запросом (POST /api/driver/location), чтобы машина не пропадала с карты.
    private var locThread: HandlerThread? = null
    private var locationManager: LocationManager? = null
    private var lastSentAt = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (MainActivity.appVisible) return // страница на экране — она сама шлёт координаты
            val now = System.currentTimeMillis()
            if (now - lastSentAt < MIN_SEND_INTERVAL_MS) return
            lastSentAt = now
            postLocation(location.latitude, location.longitude)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    private fun startNativeLocation() {
        if (locationManager != null) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) return
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val thread = HandlerThread("umka-gps").also { it.start() }
            locThread = thread
            locationManager = lm
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, GPS_INTERVAL_MS, 0f, locationListener, thread.looper)
                }
            }
        } catch (e: Exception) { /* нет доступа к геолокации — работаем без нативного GPS */ }
    }

    private fun stopNativeLocation() {
        try { locationManager?.removeUpdates(locationListener) } catch (e: Exception) { /* ничего */ }
        locationManager = null
        locThread?.quitSafely()
        locThread = null
    }

    // Вызывается из потока GPS (не из главного), поэтому сеть здесь допустима.
    private fun postLocation(lat: Double, lng: Double) {
        val token = authToken ?: return
        if (token.isEmpty()) return
        try {
            val c = URL("$baseUrl/api/driver/location").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer $token")
            c.outputStream.use { it.write("{\"lat\":$lat,\"lng\":$lng}".toByteArray()) }
            c.responseCode // дождаться ответа
            c.disconnect()
        } catch (e: Exception) { /* нет сети — следующая точка уйдёт позже */ }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            // Нет права на геолокацию или система не разрешила запуск — просто не работаем в фоне.
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "umka:line").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MAX_MS) // страховка: не держим процессор дольше 14 часов
            }
        }
        startNativeLocation()
        // Если систему вынудили убить процесс — сами не воскресаем: без открытой страницы
        // (WebView) толку от службы нет, водитель откроет приложение заново.
        return START_NOT_STICKY
    }

    // Смахнули приложение из списка недавних — страница закрыта, служба больше не нужна.
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopNativeLocation()
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) { /* ничего */ }
        wakeLock = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "Вы на линии", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Работа приложения водителя в фоне"
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("UMKA: вы на линии")
            .setContentText("Заказы приходят, даже если приложение свёрнуто")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "driver_line"
        private const val NOTIF_ID = 4201
        private const val WAKE_LOCK_MAX_MS = 14L * 60 * 60 * 1000
        private const val GPS_INTERVAL_MS = 5000L
        private const val MIN_SEND_INTERVAL_MS = 5000L

        // Ключ входа водителя и адрес сервера — страница присылает их через мост UmkaApp.setAuth.
        // Хранятся только в памяти, на диск не пишутся; при выходе с линии стираются.
        @Volatile var authToken: String? = null
        @Volatile var baseUrl: String = "https://umkatax.ru"

        fun start(ctx: Context) {
            // На Android 14 тип «геолокация» требует выданного разрешения на местоположение.
            val hasLocation = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (!hasLocation) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, DriverService::class.java))
            } catch (e: Exception) { /* запуск из фона запрещён системой — пропускаем */ }
        }

        fun stop(ctx: Context) {
            authToken = null
            try { ctx.stopService(Intent(ctx, DriverService::class.java)) } catch (e: Exception) { /* ничего */ }
        }
    }
}
