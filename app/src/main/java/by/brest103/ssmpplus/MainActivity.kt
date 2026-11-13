package by.brest103.ssmpplus

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class MkbCodeItem(val code: String, val name: String, val letter: String)

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var dbHelper: DatabaseHelper
    private var backPressedTime: Long = 0
    private var fullMkbList: List<MkbCodeItem> = emptyList()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dbHelper = DatabaseHelper(this)
        webView = findViewById(R.id.webView)

        loadFullMkbAsync()

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

    private fun loadFullMkbAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val jsonStr = assets.open("mkb10.json").bufferedReader().use { it.readText() }
                val arr = JSONArray(jsonStr)
                val list = ArrayList<MkbCodeItem>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val c = obj.optString("c").trim().uppercase()
                    val n = obj.optString("n").trim()
                    
                    // Строгая фильтрация: первая буква A-Z + наличие цифры
                    if (c.length >= 3 && c[0] in 'A'..'Z' && c.any { it.isDigit() }) {
                        val letter = c[0].toString()
                        list.add(MkbCodeItem(c, n, letter))
                    }
                }
                fullMkbList = list
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun injectScript() {
        try {
            val jsCode = assets.open("injector.js").bufferedReader().use { it.readText() }
            webView.evaluateJavascript(jsCode, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun openMkbSheet() {
        val bottomSheet = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(35, 20, 35, 25)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(90, 8).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 14
            }
            background = GradientDrawable().apply { setColor(Color.parseColor("#B0BEC5")); cornerRadius = 4f }
        })

        root.addView(TextView(this).apply {
            text = "📖 Справочник МКБ-10 (${fullMkbList.size} диагнозов)"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00695C"))
            setPadding(0, 0, 0, 10)
        })

        val categories = listOf(
            Pair("Все", "ALL"),
            Pair("❤️ I Кардио", "I"),
            Pair("🫁 J Дыхание", "J"),
            Pair("🚨 R Симптомы", "R"),
            Pair("🩹 S Травмы", "S"),
            Pair("🧪 T Токсико", "T"),
            Pair("🧠 G Невро", "G"),
            Pair("🤢 K ЖКТ", "K"),
            Pair("🩸 E Эндокрин", "E"),
            Pair("🏥 Z Прочее", "Z")
        )

        var selectedCategory = "ALL"
        val chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, 12)
        }
        val chipsLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipsScroll.addView(chipsLayout)
        root.addView(chipsScroll)

        val searchInput = EditText(this).apply {
            hint = "Поиск по коду или болезни (I48, инфаркт)..."
            setPadding(35, 26, 35, 26)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E0F2F1"))
                cornerRadius = 20f
            }
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
        }
        root.addView(searchInput)

        val listView = ListView(this).apply {
            divider = null
            dividerHeight = 12
            setPadding(0, 14, 0, 0)
        }
        root.addView(listView)

        bottomSheet.setContentView(root)
        bottomSheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheet.behavior.peekHeight = 1400

        fun filterMkb() {
            val q = searchInput.text.toString().trim().lowercase()
            val filtered = fullMkbList.asSequence().filter { item ->
                val matchCat = if (selectedCategory == "ALL") true else item.letter == selectedCategory
                val matchText = q.isEmpty() || item.code.lowercase().contains(q) || item.name.lowercase().contains(q)
                matchCat && matchText
            }.take(80).toList()

            val titles = filtered.map { "[${it.code}] ${it.name}" }
            listView.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, titles) {
                override fun getView(pos: Int, conv: View?, parent: ViewGroup): View {
                    val tv = super.getView(pos, conv, parent) as TextView
                    tv.setPadding(26, 20, 26, 20)
                    tv.setTextColor(Color.parseColor("#263238"))
                    tv.textSize = 14f
                    tv.background = GradientDrawable().apply {
                        setColor(Color.parseColor("#F7FAFA"))
                        setStroke(2, Color.parseColor("#E0EAEA"))
                        cornerRadius = 14f
                    }
                    return tv
                }
            }

            listView.setOnItemClickListener { _, _, pos, _ ->
                val item = filtered[pos]
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("MKB-10", "[${item.code}] ${item.name}")
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "📋 Скопировано: [${item.code}] ${item.name}", Toast.LENGTH_SHORT).show()
                bottomSheet.dismiss()
            }
        }

        fun updateCategoryChips() {
            chipsLayout.removeAllViews()
            categories.forEach { (label, key) ->
                val isSel = key == selectedCategory
                val chip = TextView(this).apply {
                    text = label
                    textSize = 12f
                    typeface = if (isSel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(if (isSel) Color.WHITE else Color.parseColor("#004D40"))
                    setPadding(22, 10, 22, 10)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(if (isSel) "#00796B" else "#E0F2F1"))
                        cornerRadius = 16f
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = 10 }
                    setOnClickListener {
                        selectedCategory = key
                        updateCategoryChips()
                        filterMkb()
                    }
                }
                chipsLayout.addView(chip)
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filterMkb() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })

        updateCategoryChips()
        filterMkb()
        bottomSheet.show()
    }

    private fun openSearchSheet(isBestOnly: Boolean) {
        val bottomSheet = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 30)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(90, 8).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 20
            }
            background = GradientDrawable().apply { setColor(Color.parseColor("#B0BEC5")); cornerRadius = 4f }
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
                    setPadding(35, 16, 35, 20)
                    setBackgroundColor(Color.WHITE)
                }

                root.addView(View(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(90, 8).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = 14
                    }
                    background = GradientDrawable().apply { setColor(Color.parseColor("#B0BEC5")); cornerRadius = 4f }
                })

                val header = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, 8)
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
                    background = GradientDrawable().apply { setColor(Color.parseColor("#FFEBEE")); cornerRadius = 20f }
                    setPadding(14, 14, 14, 14)
                }
                header.addView(deleteBtn)
                root.addView(header)

                val callChipsScroll = HorizontalScrollView(this@MainActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    setPadding(0, 4, 0, 10)
                }
                val callChipsLayout = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                callChipsScroll.addView(callChipsLayout)
                root.addView(callChipsScroll)

                val badgesScroll = HorizontalScrollView(this@MainActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    setPadding(0, 0, 0, 12)
                }
                val badgesLayout = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                badgesScroll.addView(badgesLayout)
                root.addView(badgesScroll)

                root.addView(View(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
                    setBackgroundColor(Color.parseColor("#E0EAEA"))
                })

                val scrollView = NestedScrollView(this@MainActivity).apply {
                    isFillViewport = true
                    isNestedScrollingEnabled = true
                    setPadding(0, 12, 0, 10)
                }
                val detailsText = TextView(this@MainActivity).apply {
                    textSize = 15f
                    setTextIsSelectable(true)
                    setTextColor(Color.parseColor("#263238"))
                    setLineSpacing(6f, 1.15f)
                    setPadding(4, 8, 4, 25)
                }
                scrollView.addView(detailsText)
                root.addView(scrollView)

                var selectedIndex = 0

                fun renderActiveCard() {
                    val itm = history[selectedIndex]
                    val j = try { JSONObject(itm.jsonData) } catch (e: Exception) { JSONObject() }

                    callChipsLayout.removeAllViews()
                    history.forEachIndexed { idx, card ->
                        val date = sdf.format(Date(card.timestamp))
                        val isSelected = idx == selectedIndex
                        val chip = TextView(this@MainActivity).apply {
                            text = "Вызов #${history.size - idx} ($date)"
                            textSize = 12.5f
                            typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                            setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#004D40"))
                            setPadding(24, 12, 24, 12)
                            background = GradientDrawable().apply {
                                setColor(Color.parseColor(if (isSelected) "#00796B" else "#E0F2F1"))
                                cornerRadius = 18f
                            }
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { rightMargin = 12 }
                            setOnClickListener {
                                selectedIndex = idx
                                renderActiveCard()
                            }
                        }
                        callChipsLayout.addView(chip)
                    }

                    badgesLayout.removeAllViews()
                    fun addB(t: String, c: String) {
                        badgesLayout.addView(TextView(this@MainActivity).apply {
                            text = t
                            textSize = 12f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.WHITE)
                            setPadding(20, 8, 20, 8)
                            background = GradientDrawable().apply { setColor(Color.parseColor(c)); cornerRadius = 16f }
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 10 }
                        })
                    }

                    val bp = j.optString("bp").ifEmpty { j.optString("bpAdapted") }
                    if (bp.isNotEmpty() && !bp.contains("ул.") && !bp.contains(".")) addB("❤️ $bp", "#E53935")
                    if (j.optString("pulse").isNotEmpty()) addB("💓 ${j.optString("pulse")}", "#D81B60")
                    if (j.optString("saturation").isNotEmpty()) addB("🫁 ${j.optString("saturation")}%", "#00897B")
                    if (j.optString("glucose").isNotEmpty()) addB("🩸 ${j.optString("glucose")}", "#8E24AA")
                    if (j.optString("glasgowScore").isNotEmpty()) addB("🧠 ${j.optString("glasgowScore")} б", "#3949AB")

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
                                            history.removeAt(selectedIndex)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@MainActivity, "Удалено", Toast.LENGTH_SHORT).show()
                                                if (history.isEmpty()) {
                                                    bottomSheet.dismiss()
                                                } else {
                                                    selectedIndex = 0
                                                    renderActiveCard()
                                                }
                                            }
                                        }
                                    }
                                }
                                .setNegativeButton("Отмена", null)
                                .show()
                        }
                    }

                    detailsText.text = formatCardText(itm.jsonData)
                    scrollView.post { scrollView.scrollTo(0, 0) }
                }

                renderActiveCard()

                bottomSheet.setContentView(root)
                bottomSheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
                bottomSheet.behavior.skipCollapsed = true
                bottomSheet.behavior.peekHeight = 1400
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
            if (j.optString("apo").isNotEmpty()) b.append("🏥 АПО: ${j.optString("apo")}\n")

            b.append("\n📋 ДИАГНОЗ:\n• ${j.optString("diagnosis")}\n")
            if (j.optString("mkb").isNotEmpty()) b.append("• Код МКБ: ${j.optString("mkb")}\n")

            val ecgB = j.optString("ecgBefore")
            val ecgA = j.optString("ecgAfter")
            if (ecgB.isNotEmpty() || ecgA.isNotEmpty()) {
                b.append("\n📈 ЭКГ:\n")
                if (ecgB.isNotEmpty()) b.append("• До помощи: $ecgB\n")
                if (ecgA.isNotEmpty()) b.append("• После помощи: $ecgA\n")
            }

            b.append("\n🩺 ОБЪЕКТИВНО:\n")
            if (j.optString("generalState").isNotEmpty()) b.append("• Состояние: ${j.optString("generalState")}\n")
            if (j.optString("consciousness").isNotEmpty()) b.append("• Сознание: ${j.optString("consciousness")}\n")
            if (j.optString("position").isNotEmpty()) b.append("• Положение: ${j.optString("position")}\n")
            if (j.optString("behavior").isNotEmpty()) b.append("• Поведение: ${j.optString("behavior")}\n")
            if (j.optString("glasgowScore").isNotEmpty()) b.append("• Глазго: ${j.optString("glasgowScore")} б\n")

            val bpAd = j.optString("bpAdapted")
            if (bpAd.isNotEmpty() && !bpAd.contains(".")) b.append("• Привычное АД: $bpAd\n")
            val bp = j.optString("bp")
            if (bp.isNotEmpty() && !bp.contains(".")) b.append("• АД на вызове: $bp\n")

            if (j.optString("pulse").isNotEmpty()) b.append("• Пульс: ${j.optString("pulse")} уд/мин\n")
            if (j.optString("rr").isNotEmpty()) b.append("• ЧДД: ${j.optString("rr")} в мин\n")
            if (j.optString("saturation").isNotEmpty()) b.append("• SpO2: ${j.optString("saturation")}%\n")
            if (j.optString("glucose").isNotEmpty()) b.append("• Сахар крови: ${j.optString("glucose")} ммоль/л\n")
            if (j.optString("temp").isNotEmpty()) b.append("• Температура: ${j.optString("temp")}°C\n")

            if (j.optString("heartSounds").isNotEmpty() && !j.optString("heartSounds").contains("[object")) b.append("• Тоны сердца: ${j.optString("heartSounds")}\n")
            if (j.optString("lungs").isNotEmpty() && !j.optString("lungs").contains("[object")) b.append("• Дыхание: ${j.optString("lungs")}\n")
            if (j.optString("percussion").isNotEmpty() && !j.optString("percussion").contains("[object")) b.append("• Перкуссия: ${j.optString("percussion")}\n")
            if (j.optString("abdomen").isNotEmpty() && !j.optString("abdomen").contains("[object")) b.append("• Живот: ${j.optString("abdomen")}\n")

            if (j.optString("pupils").isNotEmpty()) b.append("• Зрачки: ${j.optString("pupils")}\n")
            if (j.optString("lightReaction").isNotEmpty()) b.append("• Реакция на свет: ${j.optString("lightReaction")}\n")
            if (j.optString("speech").isNotEmpty()) b.append("• Речь: ${j.optString("speech")}\n")
            if (j.optString("face").isNotEmpty()) b.append("• Лицо: ${j.optString("face")}\n")
            if (j.optString("gait").isNotEmpty()) b.append("• Походка: ${j.optString("gait")}\n")
            if (j.optString("nystagmus").isNotEmpty()) b.append("• Нистагм: ${j.optString("nystagmus")}\n")

            if (j.optString("skin").isNotEmpty()) b.append("• Кожа: ${j.optString("skin")}\n")
            if (j.optString("tongue").isNotEmpty()) b.append("• Язык: ${j.optString("tongue")}\n")
            if (j.optString("mucous").isNotEmpty()) b.append("• Слизистые: ${j.optString("mucous")}\n")

            if (j.optString("peristalsis").isNotEmpty()) b.append("• Перистальтика: ${j.optString("peristalsis")}\n")
            if (j.optString("liver").isNotEmpty()) b.append("• Печень: ${j.optString("liver")}\n")
            if (j.optString("urination").isNotEmpty()) b.append("• Мочеиспускание: ${j.optString("urination")}\n")
            if (j.optString("localStatus").isNotEmpty()) b.append("• Локально: ${j.optString("localStatus")}\n")

            if (j.optString("complaints").isNotEmpty()) b.append("\n🗣️ Жалобы:\n${j.optString("complaints")}\n")
            if (j.optString("anamnesis").isNotEmpty()) b.append("\n📖 Анамнез:\n${j.optString("anamnesis")}\n")
            if (j.optString("pastDiseases").isNotEmpty()) b.append("\n🩹 Перенесенные: ${j.optString("pastDiseases")}\n")
            if (j.optString("allergy").isNotEmpty()) b.append("⚠️ Аллергия: ${j.optString("allergy")}\n")

            val meds = j.optString("medications")
            val attack = j.optString("attack")
            val effect = j.optString("therapyEffect")
            val callResult = j.optString("callResult")

            if (meds.isNotEmpty() || attack.isNotEmpty() || effect.isNotEmpty() || callResult.isNotEmpty()) {
                b.append("\n💉 ОКАЗАННАЯ ПОМОЩЬ:\n")
                if (meds.isNotEmpty()) b.append("• Препараты:\n• $meds\n")
                if (attack.isNotEmpty()) b.append("• Приступ: $attack\n")
                if (effect.isNotEmpty()) b.append("• Эффект: $effect\n")
                if (callResult.isNotEmpty()) b.append("• Итог: $callResult\n")
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
        fun openMkbDialog() = runOnUiThread { openMkbSheet() }

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