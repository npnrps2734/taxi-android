package ru.taxiapp.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

private const val CHANNEL_ID = "umka_default"
private const val NOTIFICATION_ID = 1001

// Получает push-уведомления от сервера (см. fcm.js/pushToUser в проекте
// taxi-app) и показывает их системным уведомлением — работает, даже если
// приложение свёрнуто или экран телефона выключен (в отличие от обновлений
// по WebSocket внутри WebView, которые в таком состоянии Android
// приостанавливает).
class FcmService : FirebaseMessagingService() {

    // Новый токен устройства — передаём в WebView, если приложение открыто
    // (MainActivity сам подхватит его при следующем запуске через
    // FirebaseMessaging.getInstance().token, если сейчас закрыто).
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        MainActivity.pendingFcmToken = token
        MainActivity.instance?.deliverFcmTokenToWebView(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title ?: message.data["title"] ?: "UMKA Такси"
        val body = message.notification?.body ?: message.data["body"] ?: ""
        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Уведомления UMKA Такси", NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }
}
