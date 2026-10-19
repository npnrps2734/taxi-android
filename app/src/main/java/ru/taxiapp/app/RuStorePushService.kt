package ru.taxiapp.app

import ru.rustore.sdk.pushclient.messaging.model.RemoteMessage
import ru.rustore.sdk.pushclient.messaging.service.RuStoreMessagingService

// Приём push-уведомлений через RuStore (см. rustore.js на сервере). Если в сообщении есть блок
// notification, уведомление показывает сам SDK (канал umka_default создаётся в MainActivity),
// поэтому здесь нужно только передавать приложению обновлённый токен устройства.
class RuStorePushService : RuStoreMessagingService() {

    override fun onNewToken(token: String) {
        MainActivity.pendingRustoreToken = token
        MainActivity.instance?.deliverRustoreTokenToWebView(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Ничего дополнительно не делаем: уведомление уже показано системой/SDK.
    }
}
