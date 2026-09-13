package ru.taxiapp.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

private const val PREFS_NAME = "taxi_prefs"
private const val KEY_SERVER_URL = "server_url"
private const val DEFAULT_URL = "http://[2a03:6f00:a::3:21d1]:3000"
private const val LOCATION_PERMISSION_REQUEST = 1001

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.setGeolocationEnabled(true)
        webView.settings.mediaPlaybackRequiresUserGesture = false

        webView.webViewClient = object : WebViewClient() {}
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

        val savedUrl = getServerUrl()
        if (savedUrl.isNullOrBlank()) {
            // Сразу открываем ваш развёрнутый сервер — адрес можно сменить
            // позже через меню (например, когда подключите домен).
            saveServerUrl(DEFAULT_URL)
            webView.loadUrl(DEFAULT_URL)
        } else {
            webView.loadUrl(savedUrl)
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menu?.add(0, 1, 0, "Изменить адрес сервера")
        menu?.add(0, 2, 0, "Обновить")
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            1 -> promptForServerUrl(firstRun = false)
            2 -> webView.reload()
        }
        return super.onOptionsItemSelected(item)
    }

    private fun promptForServerUrl(firstRun: Boolean) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_TEXT_VARIATION_URI
        input.hint = "https://ваш-домен.ru"
        input.setText(getServerUrl() ?: "")

        val dialog = AlertDialog.Builder(this)
            .setTitle("Адрес сервера такси-сервиса")
            .setMessage("Укажите адрес вашего сервера (например, https://mytaxi.ru или http://IP-адрес:3000)")
            .setView(input)
            .setCancelable(!firstRun)
            .setPositiveButton("Сохранить") { _, _ ->
                var url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://$url"
                    }
                    saveServerUrl(url)
                    webView.loadUrl(url)
                }
            }

        if (!firstRun) {
            dialog.setNegativeButton("Отмена", null)
        }
        dialog.show()
    }

    private fun getServerUrl(): String? =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_SERVER_URL, null)

    private fun saveServerUrl(url: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_SERVER_URL, url).apply()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

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
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
