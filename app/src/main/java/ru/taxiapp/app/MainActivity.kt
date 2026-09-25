package ru.taxiapp.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging

// Адрес сервера зашит в приложении — пользователь его не меняет и не видит.
private const val SERVER_URL = "https://umkatax.ru"
private const val LOCATION_PERMISSION_REQUEST = 1001
private const val NOTIFICATION_PERMISSION_REQUEST = 1002
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pageLoaded = false

    companion object {
        // Ссылка на текущую Activity — нужна FcmService, чтобы передать новый
        // токен устройства прямо в открытую страницу (см. onNewToken).
        // Обнуляется в onDestroy, чтобы не удерживать Activity в памяти дольше
        // необходимого (простая защита от утечки).
        var instance: MainActivity? = null
        // Если токен обновился, пока страница ещё не дозагрузилась — сохраняем
        // здесь и отправляем в WebView, как только onPageFinished сработает.
        var pendingFcmToken: String? = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Тема запуска (Theme.TaxiApp.Splash, с логотипом UMKA) уже показана системой
        // на самом старте; здесь сразу переключаемся на обычную тему приложения.
        setTheme(R.style.Theme_TaxiApp)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        instance = this

        // Сразу при запуске приложения спрашиваем разрешение на GPS — не дожидаясь,
        // пока сама страница попробует определить местоположение. Так пользователь
        // видит системный диалог Android сразу при первом открытии приложения.
        requestLocationPermissionUpfront()
        requestNotificationPermissionIfNeeded()

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.setGeolocationEnabled(true)
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                hideSplashOverlay()
                pageLoaded = true
                pendingFcmToken?.let { deliverFcmTokenToWebView(it) }
            }
        }
        // На всякий случай прячем заставку и по таймауту — если страница долго
        // не присылает событие завершения загрузки.
        Handler(Looper.getMainLooper()).postDelayed({ hideSplashOverlay() }, 5000)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
                    ) {
                if (hasLocationPermission()) {
                    callback?.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                            ),
                        LOCATION_PERMISSION_REQUEST
                        )
                }
            }
        }

        webView.loadUrl(SERVER_URL)

        fetchFcmToken()
    }

    private fun hideSplashOverlay() {
        findViewById<View>(R.id.splashOverlay)?.visibility = View.GONE
    }

    private fun hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
    PackageManager.PERMISSION_GRANTED
    private fun requestLocationPermissionUpfront() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                LOCATION_PERMISSION_REQUEST
                )
        }
    }

    // На Android 13+ показ уведомлений требует отдельного runtime-разрешения
    // (POST_NOTIFICATIONS) — до 13-й версии уведомления разрешены по умолчанию.
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST
                )
            }
        }
    }

    // Текущий токен устройства — может быть уже известен из прошлого запуска
    // (Firebase кэширует его сам), а может понадобиться сгенерировать заново.
    private fun fetchFcmToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                pendingFcmToken = token
                if (pageLoaded) deliverFcmTokenToWebView(token)
            }
        }
    }

    // Вызывает JS-функцию registerFcmToken (см. public/js/common.js в
    // taxi-app) — она сама решает, отправлять ли токен на сервер (если
    // пользователь ещё не вошёл в приложение — просто ничего не сделает).
    fun deliverFcmTokenToWebView(token: String) {
        val escaped = token.replace("\\", "\\\\").replace("'", "\\'")
        webView.post {
            webView.evaluateJavascript(
                "if (window.registerFcmToken) { window.registerFcmToken('$escaped'); }", null
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
        ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
            pendingGeoOrigin = null
            pendingGeoCallback = null
        }
        // NOTIFICATION_PERMISSION_REQUEST — намеренно без обработки результата:
        // если пользователь откажет, приложение просто продолжит работать без
        // push (как и раньше), запрашивать повторно молча не будем.
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }
}
