package by.brest103.ssmpplus

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var dbHelper: DatabaseHelper
    private var backPressedTime: Long = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dbHelper = DatabaseHelper(this)
        webView = findViewById(R.id.webView)

        // Обработка жестов "Назад" и физической кнопки
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    // Возвращаемся из карты назад к общему списку
                    webView.goBack()
                } else {
                    // Мы на главном экране: выход по двойному нажатию/свайпу
                    if (System.currentTimeMillis() - backPressedTime < 2000) {
                        finish()
                    } else {
                        backPressedTime = System.currentTimeMillis()
                        Toast.makeText(this@MainActivity, "Нажмите назад ещё раз для выхода", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        // Поддержка Cookies
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        }

        // Полифилл Notification: устраняем падение React
        val polyfillScript = """
            if (typeof window.Notification === 'undefined') {
                function MockNotification(title, options) {
                    this.title = title;
                    this.options = options;
                    this.close = function() {};
                }
                MockNotification.permission = 'granted';
                MockNotification.requestPermission = function(cb) {
                    if (typeof cb === 'function') cb('granted');
                    return Promise.resolve('granted');
                };
                window.Notification = MockNotification;
            }
        """.trimIndent()

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, polyfillScript, setOf("*"))
        }

        WebView.setWebContentsDebuggingEnabled(true)
        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {
            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view?.evaluateJavascript(polyfillScript, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectScript()
            }
        }

        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")
        webView.loadUrl("https://soft.103brest.by/ssmp/")
    }

    private fun injectScript() {
        try {
            val jsCode = assets.open("injector.js").bufferedReader().use { it.readText() }
            webView.evaluateJavascript(jsCode, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun saveCardData(jsonStr: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val json = JSONObject(jsonStr)
                    val fio = json.optString("fio").trim()
                    val birthDate = json.optString("birthDate")
                    if (fio.isEmpty()) return@launch

                    var score = 0
                    if (json.optString("anamnesis").length > 10) score += 40
                    if (json.optString("complaints").isNotEmpty()) score += 30
                    if (json.optString("bpAdapted").isNotEmpty()) score += 30

                    dbHelper.saveCard(fio, birthDate, score, jsonStr)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "Карта сохранена в локальную базу", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        @JavascriptInterface
        fun requestAutofill(fio: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                val jsonCard = dbHelper.getBestCardForPatient(fio)
                withContext(Dispatchers.Main) {
                    if (jsonCard != null) {
                        val safeJson = jsonCard.replace("'", "\\'")
                        webView.evaluateJavascript("applyAutofill('$safeJson');", null)
                        Toast.makeText(applicationContext, "Данные автозаполнены из истории", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(applicationContext, "Пациент не найден в локальной базе", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
