package by.brest103.ssmpplus

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.http.SslError
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
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

        // Обработка кнопки и жеста "Назад"
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

        // Поддержка Cookies и сессий
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

        // Полифилл Notification для предотвращения падения React
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

    // Окно поиска со списком уникальных пациентов и количеством вызовов
    private fun showSearchDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 25, 40, 20)
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

        val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

        fun performSearch(query: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                val summaries = dbHelper.searchPatientSummaries(query)
                withContext(Dispatchers.Main) {
                    val titles = summaries.map {
                        val date = sdf.format(Date(it.lastTimestamp))
                        val countText = if (it.callCount > 1) " [${it.callCount} выз.]" else ""
                        val reasonText = if (it.lastReason.isNotEmpty()) "\n• ${it.lastReason}" else ""
                        "👤 ${it.fio.uppercase()}$countText\nПосл. вызов: $date$reasonText"
                    }
                    val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, titles)
                    listView.adapter = adapter
                    listView.setOnItemClickListener { _, _, position, _ ->
                        val summary = summaries[position]
                        showPatientHistoryDialog(summary.fio)
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

    // Окно чтения полной карты со скроллом, переключателем вызовов и копированием
    private fun showPatientHistoryDialog(fio: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val history = dbHelper.getPatientHistory(fio)
            if (history.isEmpty()) return@launch

            withContext(Dispatchers.Main) {
                val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

                val container = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(35, 20, 35, 10)
                }

                // Выпадающий переключатель прошлых вызовов
                val spinnerTitles = history.mapIndexed { idx, item ->
                    val date = sdf.format(Date(item.timestamp))
                    val json = try { JSONObject(item.jsonData) } catch (e: Exception) { JSONObject() }
                    val diag = json.optString("diagnosis").ifEmpty { json.optString("callReason") }
                    val shortDiag = if (diag.length > 25) diag.take(25) + "..." else diag
                    "Вызов #${history.size - idx}: $date $shortDiag"
                }

                val spinner = Spinner(this@MainActivity).apply {
                    adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, spinnerTitles)
                    setBackgroundResource(android.R.drawable.btn_default)
                }
                container.addView(spinner)

                // Область со скроллом для чтения подробной карты
                val scrollView = ScrollView(this@MainActivity).apply {
                    isFillViewport = true
                    setPadding(0, 20, 0, 10)
                }

                val detailsText = TextView(this@MainActivity).apply {
                    textSize = 15f
                    setTextIsSelectable(true) // Позволяет выделять и копировать текст пальцем
                    typeface = Typeface.DEFAULT
                }
                scrollView.addView(detailsText)
                container.addView(scrollView)

                fun renderCard(jsonData: String) {
                    detailsText.text = formatFullCard(jsonData)
                    scrollView.post { scrollView.scrollTo(0, 0) }
                }

                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        renderCard(history[position].jsonData)
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }

                renderCard(history[0].jsonData)

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Хронология: ${fio.uppercase()}")
                    .setView(container)
                    .setPositiveButton("Закрыть", null)
                    .show()
            }
        }
    }

    // Красивое форматирование всей медицинской информации
    private fun formatFullCard(jsonData: String): String {
        return try {
            val json = JSONObject(jsonData)
            StringBuilder().apply {
                append("👤 Пациент: ${json.optString("fio")}\n")
                if (json.optString("birthDate").isNotEmpty()) append("🎂 Родился: ${json.optString("birthDate")}\n")
                if (json.optString("address").isNotEmpty()) append("📍 Адрес: ${json.optString("address")}\n")
                if (json.optString("callReason").isNotEmpty()) append("🚨 Повод: ${json.optString("callReason")}\n")

                append("\n📋 ДИАГНОЗ:\n")
                if (json.optString("diagnosis").isNotEmpty()) append("• ${json.optString("diagnosis")}\n")
                if (json.optString("mkb").isNotEmpty()) append("• Код МКБ: ${json.optString("mkb")}\n")

                append("\n🩺 ОБЪЕКТИВНЫЙ СТАТУС:\n")
                if (json.optString("generalState").isNotEmpty()) append("• Состояние: ${json.optString("generalState")}\n")
                if (json.optString("consciousness").isNotEmpty()) append("• Сознание: ${json.optString("consciousness")}\n")

                // Шкала Глазго
                val gScore = json.optString("glasgowScore")
                val gTest = json.optString("glasgowTest")
                if (gScore.isNotEmpty() || gTest.isNotEmpty()) {
                    append("• Шкала Глазго: ${if (gScore.isNotEmpty()) "$gScore баллов" else ""} ${if (gTest.isNotEmpty()) "($gTest)" else ""}\n")
                }

                if (json.optString("bpAdapted").isNotEmpty()) append("• Привычное АД: ${json.optString("bpAdapted")}\n")
                if (json.optString("bp").isNotEmpty()) append("• АД на вызове: ${json.optString("bp")}\n")
                if (json.optString("pulse").isNotEmpty()) append("• Пульс: ${json.optString("pulse")} уд/мин\n")
                if (json.optString("rr").isNotEmpty()) append("• ЧДД: ${json.optString("rr")} в мин\n")
                if (json.optString("saturation").isNotEmpty()) append("• Сатурация SpO2: ${json.optString("saturation")}%\n")
                if (json.optString("glucose").isNotEmpty()) append("• Сахар крови: ${json.optString("glucose")} ммоль/л\n")
                if (json.optString("temp").isNotEmpty()) append("• Температура: ${json.optString("temp")}°C\n")

                if (json.optString("heartSounds").isNotEmpty()) append("• Тоны сердца: ${json.optString("heartSounds")}\n")
                if (json.optString("lungs").isNotEmpty()) append("• Дыхание: ${json.optString("lungs")}\n")
                if (json.optString("percussion").isNotEmpty()) append("• Перкуссия легких: ${json.optString("percussion")}\n")
                if (json.optString("abdomen").isNotEmpty()) append("• Живот: ${json.optString("abdomen")}\n")
                if (json.optString("localStatus").isNotEmpty()) append("• Локально: ${json.optString("localStatus")}\n")

                if (json.optString("complaints").isNotEmpty()) append("\n🗣️ Жалобы:\n${json.optString("complaints")}\n")
                if (json.optString("anamnesis").isNotEmpty()) append("\n📖 Анамнез заболевания:\n${json.optString("anamnesis")}\n")
                if (json.optString("pastDiseases").isNotEmpty()) append("\n🩹 Перенесенные болезни:\n${json.optString("pastDiseases")}\n")

                if (json.optString("medHelp").isNotEmpty() || json.optString("medications").isNotEmpty() || json.optString("therapyEffect").isNotEmpty()) {
                    append("\n💉 ОКАЗАННАЯ ПОМОЩЬ И ТЕРАПИЯ:\n")
                    if (json.optString("medHelp").isNotEmpty()) append("• Помощь: ${json.optString("medHelp")}\n")
                    if (json.optString("medications").isNotEmpty()) append("• Препараты: ${json.optString("medications")}\n")
                    if (json.optString("attack").isNotEmpty()) append("• Приступ: ${json.optString("attack")}\n")
                    if (json.optString("therapyEffect").isNotEmpty()) append("• Эффект: ${json.optString("therapyEffect")}\n")
                    if (json.optString("callResult").isNotEmpty()) append("• Итог: ${json.optString("callResult")}\n")
                }
            }.toString()
        } catch (e: Exception) {
            "Ошибка чтения данных"
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
                        Toast.makeText(applicationContext, "Карта сохранена в архив", Toast.LENGTH_SHORT).show()
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
