package ru.taxiapp.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging

// Адрес сервера зашит в приложении — пользователь его не меняет и не видит.
private const val SERVER_URL = "https://umkatax.ru"
// Запрос разрешений от самой веб-страницы (когда она просит геолокацию).
private const val LOCATION_PERMISSION_REQUEST = 1001
// Общий запрос всех нужных разрешений при старте приложения.
private const val STARTUP_PERMISSION_REQUEST = 1003
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

        // Сразу при запуске приложения спрашиваем разрешения — не дожидаясь,
        // пока сама страница попробует определить местоположение. Так пользователь
        // видит системный диалог Android сразу при первом открытии приложения.
        requestStartupPermissions()

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

            // Оплата через СБП: ЮKassa уводит не на веб-страницу, а на ссылку
            // вида bank100000000004://qr.nspk.ru/... — это команда "открой
            // приложение банка". WebView такие схемы не понимает и показывает
            // ERR_UNKNOWN_URL_SCHEME, поэтому перехватываем их сами и отдаём
            // системе, чтобы она запустила нужное приложение.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return openExternalAppIfNeeded(url)
            }

            // Устаревший вариант метода — некоторые прошивки всё ещё вызывают его.
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return openExternalAppIfNeeded(url ?: return false)
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

    // Возвращает true, если ссылку обработали снаружи (WebView её грузить не
    // должен), и false для обычных http/https-страниц, которые открываются
    // внутри приложения как раньше.
    private fun openExternalAppIfNeeded(url: String): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase() ?: return false
        // Обычные страницы — как и раньше, внутри приложения.
        if (scheme == "http" || scheme == "https") return false
        // Эти схемы наружу не отдаём: file:// открыл бы доступ к файлам
        // устройства, javascript: исполнился бы в контексте страницы.
        if (scheme == "file" || scheme == "javascript" || scheme == "about" || scheme == "data") return true

        val intent = try {
            if (scheme == "intent") {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
        } catch (e: Exception) {
            null
        } ?: run {
            Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
            return true
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            // Приложения банка на устройстве нет. У intent://-ссылок обычно
            // есть запасной веб-адрес — открываем его; иначе объясняем, что
            // произошло, вместо непонятной ошибки WebView.
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null) {
                webView.loadUrl(fallback)
            } else {
                Toast.makeText(
                    this,
                    "Нужное приложение банка не установлено. Попробуйте оплатить картой.",
                    Toast.LENGTH_LONG
                ).show()
            }
            true
        }
    }

    private fun hideSplashOverlay() {
        findViewById<View>(R.id.splashOverlay)?.visibility = View.GONE
    }

    private fun hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
    PackageManager.PERMISSION_GRANTED
    // Все стартовые разрешения запрашиваются ОДНИМ вызовом.
    //
    // Раньше геолокация и уведомления запрашивались двумя вызовами подряд, и
    // это не работало: Android показывает только один диалог разрешений за
    // раз, а второй запрос, отправленный пока первый ещё не закрыт, просто
    // игнорируется — до уведомлений дело не доходило. Если передать все
    // разрешения одним массивом, система сама покажет диалоги по очереди.
    //
    // POST_NOTIFICATIONS появилось только в Android 13; на более старых
    // версиях уведомления разрешены по умолчанию и запрашивать нечего.
    private fun requestStartupPermissions() {
        val needed = mutableListOf<String>()
        if (!hasLocationPermission()) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), STARTUP_PERMISSION_REQUEST)
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
        if (requestCode == LOCATION_PERMISSION_REQUEST || requestCode == STARTUP_PERMISSION_REQUEST) {
            // В стартовом запросе разрешений несколько, поэтому ответ именно
            // по геолокации ищем по имени, а не по первому элементу массива.
            val locationIndex = permissions.indexOf(Manifest.permission.ACCESS_FINE_LOCATION)
            if (locationIndex >= 0 && locationIndex < grantResults.size) {
                val granted = grantResults[locationIndex] == PackageManager.PERMISSION_GRANTED
                pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
                pendingGeoOrigin = null
                pendingGeoCallback = null
            }
        }
        // Результат по уведомлениям намеренно не обрабатываем: если
        // пользователь откажет, приложение просто продолжит работать без push
        // (как и раньше), молча переспрашивать не будем.
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
