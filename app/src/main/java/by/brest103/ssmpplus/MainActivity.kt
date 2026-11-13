package by.brest103.ssmpplus

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.http.SslError
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
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

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

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

    // Универсальный BottomSheet (работает и для "Общего архива", и для "Базы образцов")
    private fun openSearchSheet(isBestOnly: Boolean) {
        val bottomSheet = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 30)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(100, 10).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 20
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B0BEC5"))
                cornerRadius = 5f
            }
        })

        root.addView(TextView(this).apply {
            text = if (isBestOnly) "⭐ Образцы карт (Помощь)" else "📂 Общий архив вызовов"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(if (isBestOnly) "#E65100" else "#004D40"))
            setPadding(0, 0, 0, 14)
        })

        val searchInput = EditText(this).apply {
            hint = if (isBestOnly) "Поиск по диагнозу, МКБ (ОКС, I48)..." else "Поиск по фамилии..."
            setPadding(35, 26, 35, 26)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (isBestOnly) "#FFF8E1" else "#F0F4F4"))
                if (isBestOnly) setStroke(2, Color.parseColor("#FFE082"))
                cornerRadius = 22f
            }
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
        }
        root.addView(searchInput)

        val listView = ListView(this).apply {
            divider = null
            dividerHeight = 14
            setPadding(0, 18, 0, 0)
        }
        root.addView(listView)

        bottomSheet.setContentView(root)
        bottomSheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheet.behavior.peekHeight = 1300

        val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

        fun runSearch(query: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                if (isBestOnly) {
                    val list = dbHelper.searchBestCardSamples(query)
                    withContext(Dispatchers.Main) {
                        val items = list.map {
                            val mkb = if (it.mkb.isNotEmpty()) "[${it.mkb}] " else ""
                            "⭐ ${it.score}% • ${it.fio.uppercase()}\n📋 $mkb${it.diagnosis}"
                        }
                        setupAdapter(listView, items, "#FFFDE7", "#FFE082") { pos ->
                            bottomSheet.dismiss()
                            showPatientHistoryDialog(list[pos].fio)
                        }
                    }
                } else {
                    val list = dbHelper.searchPatientSummaries(query)
                    withContext(Dispatchers.Main) {
                        val items = list.map {
                            val count = if (it.callCount > 1) " [${it.callCount} выз.]" else ""
                            val reason = if (it.lastReason.isNotEmpty()) "\n• ${it.lastReason}" else ""
                            "👤 ${it.fio.uppercase()}$count\nПосл. выезд: ${sdf.format(Date(it.lastTimestamp))}$reason"
                        }
                        setupAdapter(listView, items, "#F7FAFA", "#E0EAEA") { pos ->
                            bottomSheet.dismiss()
                            showPatientHistoryDialog(list[pos].fio)
                        }
                    }
                }
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { runSearch(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })

        bottomSheet.show()
        runSearch("")
    }

    private fun setupAdapter(listView: ListView, items: List<String>, bgColor: String, strokeColor: String, onClick: (Int) -> Unit) {
        listView.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            override fun getView(pos: Int, conv: View?, parent: ViewGroup): View {
                val tv = super.getView(pos, conv, parent) as TextView
                tv.setPadding(28, 22, 28, 22)
                tv.setTextColor(Color.parseColor("#263238"))
                tv.background = GradientDrawable().apply {
                    setColor(Color.parseColor(bgColor))
                    setStroke(2, Color.parseColor(strokeColor))
                    cornerRadius = 16f
                }
                return tv
            }
        }
        listView.setOnItemClickListener { _, _, pos, _ -> onClick(pos) }
    }

    private fun showPatientHistoryDialog(fio: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val history = dbHelper.getPatientHistory(fio).toMutableList()
            if (history.isEmpty()) return@launch

            withContext(Dispatchers.Main) {
                val bottomSheet = BottomSheetDialog(this@MainActivity)
                val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

                val root = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(35, 20, 35, 25)
                    setBackgroundColor(Color.WHITE)
                }

                root.addView(View(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(100, 10).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = 16
                    }
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#B0BEC5"))
                        cornerRadius = 5f
                    }
                })

                val header = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, 12)
                }
                header.addView(TextView(this@MainActivity).apply {
                    text = fio.uppercase()
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#004D40"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                val deleteBtn = ImageButton(this@MainActivity).apply {
                    setImageResource(android.R.drawable.ic_menu_delete)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#FFEBEE"))
                        cornerRadius = 24f
                    }
                    setPadding(16, 16, 16, 16)
                }
                header.addView(deleteBtn)
                root.addView(header)

                val spinner = Spinner(this@MainActivity).apply {
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#E0F2F1"))
                        cornerRadius = 14f
                    }
                    setPadding(20, 14, 20, 14)
                }
                root.addView(spinner)

                val scrollView = ScrollView(this@MainActivity).apply {
                    isFillViewport = true
                    setPadding(0, 14, 0, 10)
                }
                val content = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                scrollView.addView(content)
                root.addView(scrollView)

                fun updateSpinner() {
                    val titles = history.mapIndexed { idx, itm ->
                        val date = sdf.format(Date(itm.timestamp))
                        val j = try { JSONObject(itm.jsonData) } catch (e: Exception) { JSONObject() }
                        val d = j.optString("diagnosis").ifEmpty { j.optString("callReason") }
                        "Вызов #${history.size - idx} ($date) ${if (d.length > 20) d.take(20) + "..." else d}"
                    }
                    spinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, titles)
                }

                fun renderCard(itm: CardHistoryItem) {
                    content.removeAllViews()
                    val j = try { JSONObject(itm.jsonData) } catch (e: Exception) { JSONObject() }

                    if (itm.isBuiltin) {
                        deleteBtn.visibility = View.GONE
                    } else {
                        deleteBtn.visibility = View.VISIBLE
                        deleteBtn.setOnClickListener {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Удаление")
                                .setMessage("Удалить этот вызов из архива?")
                                .setPositiveButton("Удалить") { _, _ ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        if (dbHelper.deleteCard(itm.id)) {
                                            history.remove(itm)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@MainActivity, "Удалено", Toast.LENGTH_SHORT).show()
                                                if (history.isEmpty()) bottomSheet.dismiss() else {
                                                    updateSpinner()
                                                    renderCard(history[0])
                                                }
                                            }
                                        }
                                    }
                                }
                                .setNegativeButton("Отмена", null)
                                .show()
                        }
                    }

                    val badges = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 10, 0, 10)
                    }
                    fun addB(t: String, c: String) {
                        badges.addView(TextView(this@MainActivity).apply {
                            text = t
                            textSize = 12f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.WHITE)
                            setPadding(18, 6, 18, 6)
                            background = GradientDrawable().apply { setColor(Color.parseColor(c)); cornerRadius = 16f }
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 10 }
                        })
                    }

                    val bp = j.optString("bp").ifEmpty { j.optString("bpAdapted") }
                    if (bp.isNotEmpty()) addB("❤️ $bp", "#E53935")
                    if (j.optString("pulse").isNotEmpty()) addB("💓 ${j.optString("pulse")}", "#D81B60")
                    if (j.optString("saturation").isNotEmpty()) addB("🫁 ${j.optString("saturation")}%", "#00897B")
                    if (j.optString("glucose").isNotEmpty()) addB("🩸 ${j.optString("glucose")}", "#8E24AA")
                    if (j.optString("glasgowScore").isNotEmpty()) addB("🧠 ${j.optString("glasgowScore")} б", "#3949AB")

                    content.addView(HorizontalScrollView(this@MainActivity).apply { isHorizontalScrollBarEnabled = false; addView(badges) })

                    content.addView(TextView(this@MainActivity).apply {
                        textSize = 14.5f
                        setTextIsSelectable(true)
                        setTextColor(Color.parseColor("#37474F"))
                        setLineSpacing(5f, 1.15f)
                        text = formatCardText(itm.jsonData)
                        setPadding(8, 8, 8, 25)
                    })
                }

                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = renderCard(history[pos])
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }

                updateSpinner()
                renderCard(history[0])

                bottomSheet.setContentView(root)
                bottomSheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
                bottomSheet.behavior.peekHeight = 1350
                bottomSheet.show()
            }
        }
    }

    private fun formatCardText(jsonStr: String): String {
        return try {
            val j = JSONObject(jsonStr)
            val b = StringBuilder()
            if (j.optString("birthDate").isNotEmpty()) b.append("🎂 Родился: ${j.optString("birthDate")}\n")
            if (j.optString("gender").isNotEmpty()) b.append("⚧ Пол: ${j.optString("gender")}\n")
            if (j.optString("address").isNotEmpty()) b.append("📍 Адрес: ${j.optString("address")}\n")
            if (j.optString("callReason").isNotEmpty()) b.append("🚨 Повод: ${j.optString("callReason")}\n")

            b.append("\n📋 ДИАГНОЗ:\n• ${j.optString("diagnosis")}\n")
            if (j.optString("mkb").isNotEmpty()) b.append("• Код МКБ: ${j.optString("mkb")}\n")

            b.append("\n🩺 ОБЪЕКТИВНО:\n")
            if (j.optString("generalState").isNotEmpty()) b.append("• Состояние: ${j.optString("generalState")}\n")
            if (j.optString("consciousness").isNotEmpty()) b.append("• Сознание: ${j.optString("consciousness")}\n")
            if (j.optString("glasgowScore").isNotEmpty()) b.append("• Глазго: ${j.optString("glasgowScore")} б\n")
            if (j.optString("bpAdapted").isNotEmpty()) b.append("• Привычное АД: ${j.optString("bpAdapted")}\n")
            if (j.optString("bp").isNotEmpty()) b.append("• АД: ${j.optString("bp")}\n")
            if (j.optString("pulse").isNotEmpty()) b.append("• Пульс: ${j.optString("pulse")} уд/мин\n")
            if (j.optString("rr").isNotEmpty()) b.append("• ЧДД: ${j.optString("rr")} в мин\n")
            if (j.optString("saturation").isNotEmpty()) b.append("• SpO2: ${j.optString("saturation")}%\n")
            if (j.optString("glucose").isNotEmpty()) b.append("• Сахар: ${j.optString("glucose")} ммоль/л\n")
            if (j.optString("temp").isNotEmpty()) b.append("• Температура: ${j.optString("temp")}°C\n")

            if (j.optString("heartSounds").isNotEmpty() && !j.optString("heartSounds").contains("[object")) b.append("• Тоны сердца: ${j.optString("heartSounds")}\n")
            if (j.optString("lungs").isNotEmpty() && !j.optString("lungs").contains("[object")) b.append("• Дыхание: ${j.optString("lungs")}\n")
            if (j.optString("percussion").isNotEmpty() && !j.optString("percussion").contains("[object")) b.append("• Перкуссия: ${j.optString("percussion")}\n")
            if (j.optString("abdomen").isNotEmpty() && !j.optString("abdomen").contains("[object")) b.append("• Живот: ${j.optString("abdomen")}\n")
            if (j.optString("localStatus").isNotEmpty()) b.append("• Локально: ${j.optString("localStatus")}\n")

            if (j.optString("complaints").isNotEmpty()) b.append("\n🗣️ Жалобы:\n${j.optString("complaints")}\n")
            if (j.optString("anamnesis").isNotEmpty()) b.append("\n📖 Анамнез:\n${j.optString("anamnesis")}\n")
            if (j.optString("pastDiseases").isNotEmpty()) b.append("\n🩹 Перенесенные: ${j.optString("pastDiseases")}\n")
            if (j.optString("allergy").isNotEmpty()) b.append("⚠️ Аллергия: ${j.optString("allergy")}\n")

            val meds = j.optString("medications")
            val help = j.optString("medHelp")
            if (help.isNotEmpty() || (meds.isNotEmpty() && !meds.contains("О ВЫЗОВЕ"))) {
                b.append("\n💉 ПОМОЩЬ И ТЕРАПИЯ:\n")
                if (help.isNotEmpty()) b.append("• Помощь: $help\n")
                if (meds.isNotEmpty() && !meds.contains("О ВЫЗОВЕ")) b.append("• Препараты: $meds\n")
                if (j.optString("attack").isNotEmpty()) b.append("• Приступ: ${j.optString("attack")}\n")
                if (j.optString("therapyEffect").isNotEmpty()) b.append("• Эффект: ${j.optString("therapyEffect")}\n")
                if (j.optString("callResult").isNotEmpty()) b.append("• Итог: ${j.optString("callResult")}\n")
            }
            b.toString()
        } catch (e: Exception) { "Ошибка" }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun openSearchDialog() = runOnUiThread { openSearchSheet(false) }

        @JavascriptInterface
        fun openBestCardsDialog() = runOnUiThread { openSearchSheet(true) }

        @JavascriptInterface
        fun saveCardData(jsonStr: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val j = JSONObject(jsonStr)
                    val fio = j.optString("fio").trim()
                    if (fio.isEmpty()) return@launch

                    var score = 0
                    if (j.optString("anamnesis").length > 10) score += 30
                    if (j.optString("complaints").isNotEmpty()) score += 20
                    if (j.optString("bpAdapted").isNotEmpty()) score += 20
                    if (j.optString("diagnosis").isNotEmpty()) score += 20
                    if (j.optString("pastDiseases").isNotEmpty()) score += 10

                    dbHelper.saveOrUpdateCard(fio, j.optString("birthDate"), score, jsonStr)
                    withContext(Dispatchers.Main) { Toast.makeText(applicationContext, "Карта сохранена в архив", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }

        @JavascriptInterface
        fun autoSaveCardData(jsonStr: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val j = JSONObject(jsonStr)
                    val fio = j.optString("fio").trim()
                    if (fio.length < 5) return@launch

                    var score = 0
                    if (j.optString("anamnesis").length > 10) score += 30
                    if (j.optString("complaints").isNotEmpty()) score += 20
                    if (j.optString("bpAdapted").isNotEmpty()) score += 20
                    if (j.optString("diagnosis").isNotEmpty()) score += 20

                    dbHelper.saveOrUpdateCard(fio, j.optString("birthDate"), score, jsonStr)
                } catch (e: Exception) { e.printStackTrace() }
            }
        }

        @JavascriptInterface
        fun requestAutofill(fio: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                val jsonCard = dbHelper.getBestCardForPatient(fio)
                withContext(Dispatchers.Main) {
                    if (jsonCard != null) {
                        val safe = jsonCard.replace("'", "\\'")
                        webView.evaluateJavascript("applyAutofill('$safe');", null)
                        Toast.makeText(applicationContext, "Данные автозаполнены", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(applicationContext, "Пациент не найден", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}