package ru.taxiapp.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.provider.ContactsContract
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import android.view.KeyEvent
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import ru.rustore.sdk.pushclient.RuStorePushClient

// Адрес сервера зашит в приложении — пользователь его не меняет и не видит.
private const val SERVER_URL = "https://umkatax.ru"
// Запрос разрешений от самой веб-страницы (когда она просит геолокацию).
private const val LOCATION_PERMISSION_REQUEST = 1001
// Общий запрос всех нужных разрешений при старте приложения.
private const val STARTUP_PERMISSION_REQUEST = 1003
// Запрос разрешения на звонки (при первом нажатии «Позвонить»).
private const val CALL_PERMISSION_REQUEST = 1004
// Своя страница «сервис недоступен» (лежит в assets) и таймаут загрузки основной страницы.
private const val OFFLINE_PAGE = "file:///android_asset/offline.html"
private const val LOAD_TIMEOUT_MS = 30_000L
private const val ERROR_UNSUPPORTED_SCHEME_CODE = -10
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pageLoaded = false

    // ---- Экран «сервис недоступен» ----
    // Если сервер не отвечает, WebView показывает белую страницу или системную ошибку.
    // Вместо этого открываем свою страницу из assets/offline.html с кнопкой «Повторить».
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadTimeoutRunnable = Runnable { onLoadTimeout() }
    private var lastMainUrl: String? = null

    // ---- Выбор файла/фото для <input type="file"> на страницах ----
    // Без onShowFileChooser WebView молча игнорирует нажатие на «Прикрепить фото».
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = filePathCallback
            filePathCallback = null
            if (cb == null) return@registerForActivityResult
            var uris: Array<Uri>? = null
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                val picked = FileChooserParams.parseResult(result.resultCode, data)
                uris = when {
                    picked != null && picked.isNotEmpty() -> picked
                    // Снимок с камеры: система кладёт его в наш uri и ничего не возвращает в data.
                    cameraPhotoUri != null -> arrayOf(cameraPhotoUri!!)
                    else -> null
                }
            }
            cb.onReceiveValue(uris)
            cameraPhotoUri = null
        }

    // Intent камеры, который пишет снимок в наш временный файл (FileProvider).
    private fun buildCameraIntent(front: Boolean): Intent? {
        return try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val file = File.createTempFile("photo_", ".jpg", dir)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            cameraPhotoUri = uri
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (front) {
                intent.putExtra("android.intent.extras.CAMERA_FACING", 1)
                intent.putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
            }
            if (intent.resolveActivity(packageManager) != null) intent else null
        } catch (e: Exception) {
            null
        }
    }

    // ---- Мост для сайта: звонок, «Поделиться», выбор контакта, SMS (window.UmkaApp в JS) ----
    private var pendingCallNumber: String? = null

    // Выбор контакта из телефонной книги. Через системный выбор (ACTION_PICK) разрешение на
    // чтение всех контактов не нужно: приложение получает только тот номер, который выбрал пользователь.
    private val contactPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            var name = ""
            var phone = ""
            val uri = result.data?.data
            if (result.resultCode == RESULT_OK && uri != null) {
                try {
                    contentResolver.query(
                        uri,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                        ),
                        null, null, null
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            phone = c.getString(0) ?: ""
                            name = c.getString(1) ?: ""
                        }
                    }
                } catch (e: Exception) { /* оставим пустым — страница покажет подсказку */ }
            }
            deliverContactToWebView(name, phone)
        }

    private fun jsEscape(v: String): String =
        v.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ")

    private fun deliverContactToWebView(name: String, phone: String) {
        webView.post {
            webView.evaluateJavascript(
                "if (window.onContactPicked) { window.onContactPicked('${jsEscape(name)}', '${jsEscape(phone)}'); }", null
            )
        }
    }

    // Только наш сайт может пользоваться мостом (а не, например, страница оплаты банка).
    private fun isTrustedPage(): Boolean {
        val host = Uri.parse(webView.url ?: return false).host ?: return false
        return host == Uri.parse(SERVER_URL).host
    }

    private fun normalizePhone(raw: String): String? {
        val n = raw.replace(Regex("[^0-9+]"), "")
        return if (n.length in 3..16) n else null
    }

    // Звонок: если разрешение на звонки уже выдано — набираем сразу; нет — просим разрешение,
    // а при отказе открываем обычный набор номера (звонок всё равно будет в один тап).
    private fun placeCall(raw: String) {
        val number = normalizePhone(raw) ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            startCall(number, true)
        } else {
            pendingCallNumber = number
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), CALL_PERMISSION_REQUEST)
        }
    }

    private fun startCall(number: String, direct: Boolean) {
        val action = if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL
        try {
            startActivity(Intent(action, Uri.parse("tel:$number")))
        } catch (e: SecurityException) {
            startCall(number, false)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "На устройстве нет приложения для звонков", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Работа в фоне: пока водитель на линии, держим службу переднего плана (DriverService) ----
    private var lineActive = false

    // Один раз просим «не ограничивать» приложение по батарее — без этого часть прошивок
    // (Xiaomi, Huawei, Honor, Realme и др.) всё равно усыпляет приложение в фоне.
    private fun askIgnoreBatteryOptimizationsOnce() {
        val prefs = getSharedPreferences("umka", Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_asked", false)) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        prefs.edit().putBoolean("battery_asked", true).apply()
        openBatterySettings()
    }

    @SuppressLint("BatteryLife")
    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { /* нет такого экрана */ }
        }
    }

    inner class UmkaBridge {
        // Страница водителя сообщает: вышел на линию (true) / ушёл с линии (false).
        @JavascriptInterface
        fun setOnLine(on: Boolean) {
            runOnUiThread {
                if (!isTrustedPage()) return@runOnUiThread
                lineActive = on
                if (on) {
                    DriverService.start(this@MainActivity)
                    askIgnoreBatteryOptimizationsOnce()
                } else {
                    DriverService.stop(this@MainActivity)
                }
            }
        }

        // Ключ входа водителя — для отправки GPS из службы, когда приложение свёрнуто.
        @JavascriptInterface
        fun setAuth(token: String) {
            if (!isTrustedPage()) return
            DriverService.baseUrl = SERVER_URL
            DriverService.authToken = token
        }

        // Кнопка «Выйти из приложения» (Назад на главном экране → «Выйти»): полностью закрываем приложение.
        @JavascriptInterface
        fun exitApp() {
            runOnUiThread {
                if (!isTrustedPage()) return@runOnUiThread
                lineActive = false
                DriverService.stop(this@MainActivity)
                finishAffinity()
            }
        }

        // Экран настройки батареи — для подсказки водителю в настройках приложения.
        @JavascriptInterface
        fun openBatterySettings() {
            runOnUiThread { if (isTrustedPage()) this@MainActivity.openBatterySettings() }
        }

        @JavascriptInterface
        fun call(phone: String) {
            runOnUiThread { if (isTrustedPage()) placeCall(phone) }
        }

        @JavascriptInterface
        fun share(text: String, title: String) {
            runOnUiThread {
                if (!isTrustedPage()) return@runOnUiThread
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
                }
                try {
                    startActivity(Intent.createChooser(send, "Отправить приглашение"))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Не удалось открыть меню отправки", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun pickContact() {
            runOnUiThread {
                if (!isTrustedPage()) return@runOnUiThread
                try {
                    contactPickerLauncher.launch(
                        Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                    )
                } catch (e: Exception) {
                    deliverContactToWebView("", "")
                }
            }
        }

        // SMS открывается как черновик в приложении сообщений — отправляет сам пользователь.
        @JavascriptInterface
        fun sendSms(phone: String, text: String) {
            runOnUiThread {
                if (!isTrustedPage()) return@runOnUiThread
                val number = normalizePhone(phone) ?: return@runOnUiThread
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                    putExtra("sms_body", text)
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Не удалось открыть сообщения", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        // Ссылка на текущую Activity — нужна FcmService, чтобы передать новый
        // токен устройства прямо в открытую страницу (см. onNewToken).
        // Обнуляется в onDestroy, чтобы не удерживать Activity в памяти дольше
        // необходимого (простая защита от утечки).
        var instance: MainActivity? = null
        // Если токен обновился, пока страница ещё не дозагрузилась — сохраняем
        // здесь и отправляем в WebView, как только onPageFinished сработает.
        var pendingFcmToken: String? = null
        // То же для RuStore Push.
        var pendingRustoreToken: String? = null
        // Приложение сейчас на экране (между onStart и onStop) — нужно службе GPS, чтобы не дублировать страницу.
        @Volatile var appVisible = false
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
        ensureNotificationChannel()

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.setGeolocationEnabled(true)
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.addJavascriptInterface(UmkaBridge(), "UmkaApp")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url != null && !isOfflineUrl(url)) {
                    lastMainUrl = url
                    // Если страница не загрузилась за 30 секунд — сервер, скорее всего, недоступен.
                    mainHandler.removeCallbacks(loadTimeoutRunnable)
                    mainHandler.postDelayed(loadTimeoutRunnable, LOAD_TIMEOUT_MS)
                }
            }

            // Ошибка загрузки ОСНОВНОЙ страницы (нет сети, сервер не отвечает, обрыв TLS и т.п.).
            // Ошибки отдельных картинок и скриптов игнорируем — иначе экран ошибки вылезал бы зря.
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame != true) return
                // ERROR_UNSUPPORTED_SCHEME (-10): ссылки банков и т.п. обрабатываются отдельно.
                if (error?.errorCode == ERROR_UNSUPPORTED_SCHEME_CODE) return
                showOfflinePage(request?.url?.toString())
            }

            // Сервер ответил, но с ошибкой 5xx (502/503/504 — приложение упало или перегружено).
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame != true) return
                val code = errorResponse?.statusCode ?: return
                if (code in 500..599) showOfflinePage(request?.url?.toString())
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                mainHandler.removeCallbacks(loadTimeoutRunnable)
                hideSplashOverlay()
                pageLoaded = true
                pendingFcmToken?.let { deliverFcmTokenToWebView(it) }
                pendingRustoreToken?.let { deliverRustoreTokenToWebView(it) }
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
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                // Если предыдущий выбор не завершён — отменяем, иначе WebView зависнет.
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                cameraPhotoUri = null
                val wantsCamera = params?.isCaptureEnabled == true
                val intent: Intent
                if (wantsCamera) {
                    // Селфи: сразу открываем фронтальную камеру.
                    val cam = buildCameraIntent(true)
                    intent = cam ?: params!!.createIntent()
                } else {
                    val gallery = try { params?.createIntent() } catch (e: Exception) { null }
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }
                    val chooser = Intent(Intent.ACTION_CHOOSER)
                    chooser.putExtra(Intent.EXTRA_INTENT, gallery)
                    chooser.putExtra(Intent.EXTRA_TITLE, "Выберите фото")
                    val cam = buildCameraIntent(false)
                    if (cam != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cam))
                    intent = chooser
                }
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    callback?.onReceiveValue(null)
                    Toast.makeText(this@MainActivity, "Не удалось открыть выбор фото", Toast.LENGTH_SHORT).show()
                    true
                }
            }

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
        fetchRustoreToken()
        checkForUpdate()
    }

    // Канал уведомлений создаём заранее: когда приложение свёрнуто, push показывает сама система Firebase
    // и использует канал из манифеста (default_notification_channel_id). Если канала нет, он создаётся
    // «тихим», и заказ приходит без звука и всплывающей плашки.
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel("umka_default") != null) return
        val ch = NotificationChannel("umka_default", "Уведомления UMKA Такси", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Новые заказы, статусы поездок, сообщения"
        ch.enableVibration(true)
        ch.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
    }

    // ---- Проверка новой версии приложения ----
    // Сервер отдаёт номер последней версии (GET /api/app/android-version). Если он больше номера
    // установленной — раз в сутки предлагаем обновиться. Ошибки сети молча игнорируем.
    private fun installedVersionCode(): Long = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: Exception) { Long.MAX_VALUE }

    private fun checkForUpdate() {
        val prefs = getSharedPreferences("umka", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("update_asked_at", 0L) < 24L * 60 * 60 * 1000) return
        Thread {
            try {
                val c = URL("$SERVER_URL/api/app/android-version").openConnection() as HttpURLConnection
                c.connectTimeout = 6000
                c.readTimeout = 6000
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val json = JSONObject(body)
                val latest = json.optLong("latestVersionCode", 0L)
                val url = json.optString("url", "")
                if (latest > installedVersionCode() && url.startsWith("https://")) {
                    runOnUiThread { if (!isFinishing && !isDestroyed) showUpdateDialog(url) }
                }
            } catch (e: Exception) { /* нет сети или сервер недоступен — не страшно */ }
        }.start()
    }

    private fun showUpdateDialog(url: String) {
        getSharedPreferences("umka", Context.MODE_PRIVATE).edit()
            .putLong("update_asked_at", System.currentTimeMillis()).apply()
        AlertDialog.Builder(this)
            .setTitle("Доступно обновление")
            .setMessage("Вышла новая версия приложения UMKA. Обновите её, чтобы всё работало правильно.")
            .setPositiveButton("Обновить") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { /* нет браузера */ }
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    // Возвращает true, если ссылку обработали снаружи (WebView её грузить не
    // должен), и false для обычных http/https-страниц, которые открываются
    // внутри приложения как раньше.
    private fun openExternalAppIfNeeded(url: String): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase() ?: return false
        // Ссылки на MAX (чат-бот UMKA с экрана «сервис недоступен») открываем в самом мессенджере MAX,
        // а если его нет — в браузере; внутри приложения они не нужны.
        if (scheme == "http" || scheme == "https") {
            val host = Uri.parse(url).host?.lowercase() ?: ""
            if (host == "max.ru" || host.endsWith(".max.ru")) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Toast.makeText(this, "Не удалось открыть MAX", Toast.LENGTH_SHORT).show()
                }
                return true
            }
            // Обычные страницы — как и раньше, внутри приложения.
            return false
        }
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

    private fun isOfflineUrl(url: String?): Boolean =
        url != null && url.startsWith(OFFLINE_PAGE)

    private fun onLoadTimeout() {
        // Таймаут срабатывает только если страница так и не загрузилась.
        if (isOfflineUrl(webView.url)) return
        if (webView.progress >= 100) return
        showOfflinePage(lastMainUrl ?: webView.url)
    }

    private fun showOfflinePage(failedUrl: String?) {
        mainHandler.removeCallbacks(loadTimeoutRunnable)
        if (isOfflineUrl(webView.url)) return
        val target = failedUrl?.takeIf { it.startsWith(SERVER_URL) } ?: SERVER_URL
        webView.stopLoading()
        webView.loadUrl(OFFLINE_PAGE + "?u=" + Uri.encode(target))
        hideSplashOverlay()
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

    // Токен RuStore Push. Если на телефоне нет приложения RuStore или оно не может выдать токен —
    // тихо пропускаем, останется Google-push (FCM).
    private fun fetchRustoreToken() {
        try {
            RuStorePushClient.getToken()
                .addOnSuccessListener { token ->
                    pendingRustoreToken = token
                    if (pageLoaded) deliverRustoreTokenToWebView(token)
                }
                .addOnFailureListener { /* RuStore недоступен — не страшно */ }
        } catch (e: Exception) { /* SDK не инициализирован — пропускаем */ }
    }

    // Вызывает JS-функцию registerRustoreToken (см. public/js/common.js).
    fun deliverRustoreTokenToWebView(token: String) {
        val escaped = token.replace("\\", "\\\\").replace("'", "\\'")
        webView.post {
            webView.evaluateJavascript(
                "if (window.registerRustoreToken) { window.registerRustoreToken('$escaped'); }", null
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
        ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CALL_PERMISSION_REQUEST) {
            val number = pendingCallNumber
            pendingCallNumber = null
            if (number != null) {
                val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                startCall(number, granted)
            }
        }
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
        // С экрана «сервис недоступен» кнопка «назад» закрывает приложение, а не возвращает
        // на ту же недоступную страницу.
        if (keyCode == KeyEvent.KEYCODE_BACK && isOfflineUrl(webView.url)) {
            return super.onKeyDown(keyCode, event)
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // Приложение свернули: пока водитель на линии, не даём странице «заснуть» —
    // иначе замирают таймеры и сокет, и заказы перестают приходить.
    override fun onStart() {
        super.onStart()
        appVisible = true
    }

    override fun onStop() {
        super.onStop()
        appVisible = false
        if (lineActive) {
            webView.onResume()
            webView.resumeTimers()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(loadTimeoutRunnable)
        // Приложение закрыто совсем (а не пересоздано системой) — служба «на линии» не нужна.
        if (isFinishing) DriverService.stop(this)
        super.onDestroy()
        if (instance === this) instance = null
    }
}
