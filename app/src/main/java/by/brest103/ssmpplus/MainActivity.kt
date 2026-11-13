package by.brest103.ssmpplus

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    if (System.currentTimeMillis() - backPressedTime < 2000) {
                        finish()
                    } else {
                        backPressedTime = System.currentTimeMillis()
                        Toast.makeText(this@MainActivity, "Нажмите назад ещё раз для выхода", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

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

    private fun showSearchDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val searchInput = EditText(this).apply {
            hint = "Введите фамилию пациента..."
        }
        val listView = ListView(this)

        dialogView.addView(searchInput)
        dialogView.addView(listView)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Архив пациентов")
            .setView(dialogView)
            .setPositiveButton("Закрыть", null)
            .create()

        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

        fun performSearch(query: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                val results = dbHelper.searchPatients(query)
                withContext(Dispatchers.Main) {
                    val titles = results.map {
                        val date = sdf.format(Date(it.timestamp))
                        "${it.fio.uppercase()} ($date)"
                    }
                    val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, titles)
                    listView.adapter = adapter
                    listView.setOnItemClickListener { _, _, position, _ ->
                        val card = results[position]
                        showPatientDetails(card.jsonData)
                    }
                }
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                performSearch(s.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })

        dialog.show()
        performSearch("")
    }

    private fun showPatientDetails(jsonData: String) {
        try {
            val json = JSONObject(jsonData)
            val info = StringBuilder().apply {
                append("👤 ФИО: ${json.optString("fio")}\n")
                if (json.optString("birthDate").isNotEmpty()) append("🎂 Дата рожд.: ${json.optString("birthDate")}\n")
                if (json.optString("address").isNotEmpty()) append("📍 Адрес: ${json.optString("address")}\n")
                if (json.optString("callReason").isNotEmpty()) append("🚨 Повод: ${json.optString("callReason")}\n")
                
                if (json.optString("bpAdapted").isNotEmpty()) append("❤️ Адаптированное АД: ${json.optString("bpAdapted")}\n")
                if (json.optString("bp").isNotEmpty()) append("🩺 АД на вызове: ${json.optString("bp")}\n")
                if (json.optString("pulse").isNotEmpty()) append("💓 Пульс: ${json.optString("pulse")}\n")
                if (json.optString("saturation").isNotEmpty()) append("🫁 SpO2: ${json.optString("saturation")}%\n")
                if (json.optString("glucose").isNotEmpty()) append("🩸 Гликемия: ${json.optString("glucose")} ммоль/л\n")
                
                if (json.optString("diagnosis").isNotEmpty()) append("\n📋 Диагноз: ${json.optString("diagnosis")}\n")
                if (json.optString("mkb").isNotEmpty()) append("🔢 МКБ: ${json.optString("mkb")}\n")

                if (json.optString("complaints").isNotEmpty()) append("\n🗣️ Жалобы:\n${json.optString("complaints")}\n")
                if (json.optString("anamnesis").isNotEmpty()) append("\n📖 Анамнез:\n${json.optString("anamnesis")}\n")
                if (json.optString("pastDiseases").isNotEmpty()) append("\n🩹 Перенесенные болезни:\n${json.optString("pastDiseases")}\n")
            }

            AlertDialog.Builder(this)
                .setTitle("Полные данные из архива")
                .setMessage(info.toString())
                .setPositiveButton("Понятно", null)
                .show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun openSearchDialog() {
            runOnUiThread {
                showSearchDialog()
            }
        }

        @JavascriptInterface
        fun saveCardData(jsonStr: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val json = JSONObject(jsonStr)
                    val fio = json.optString("fio").trim()
                    val birthDate = json.optString("birthDate")
                    if (fio.isEmpty()) return@launch

                    var score = 0
                    if (json.optString("anamnesis").length > 10) score += 30
                    if (json.optString("complaints").isNotEmpty()) score += 20
                    if (json.optString("bpAdapted").isNotEmpty()) score += 20
                    if (json.optString("diagnosis").isNotEmpty()) score += 20
                    if (json.optString("pastDiseases").isNotEmpty()) score += 10

                    dbHelper.saveOrUpdateCard(fio, birthDate, score, jsonStr)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "Карта обновлена в архиве", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        @JavascriptInterface
        fun autoSaveCardData(jsonStr: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val json = JSONObject(jsonStr)
                    val fio = json.optString("fio").trim()
                    if (fio.length < 5) return@launch

                    val birthDate = json.optString("birthDate")
                    var score = 0
                    if (json.optString("anamnesis").length > 10) score += 30
                    if (json.optString("complaints").isNotEmpty()) score += 20
                    if (json.optString("bpAdapted").isNotEmpty()) score += 20
                    if (json.optString("diagnosis").isNotEmpty()) score += 20

                    dbHelper.saveOrUpdateCard(fio, birthDate, score, jsonStr)
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
                        Toast.makeText(applicationContext, "Данные автозаполнены из архива", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(applicationContext, "Пациент не найден в базе", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
