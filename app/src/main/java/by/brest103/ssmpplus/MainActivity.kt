package by.brest103.ssmpplus

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.http.SslError
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
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

    private val exportDbLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val jsonBackup = dbHelper.exportDatabaseToJson()
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(jsonBackup.toByteArray(Charsets.UTF_8))
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "📤 База успешно экспортирована!", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private val importDbLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val jsonStr = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    val count = dbHelper.importDatabaseFromJson(jsonStr)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "📥 Импортировано карт: $count", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Ошибка импорта файла базы", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

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

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                Toast.makeText(this@MainActivity, message ?: "", Toast.LENGTH_LONG).show()
                result?.confirm()
                return true
            }
        }

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
                    if (c.length >= 3 && c[0] in 'A'..'Z' && c.any { it.isDigit() }) {
                        list.add(MkbCodeItem(c, n, c[0].toString()))
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

    // МОДАЛЬНОЕ МЕНЮ ПО ЦЕНТРУ ЭКРАНА
    private fun showMainModalMenu() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 50)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 16f
            }
            layoutParams = ViewGroup.LayoutParams(900, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 35)
        }
        header.addView(TextView(this).apply {
            text = "Меню"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#212121"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.parseColor("#757575"))
            setPadding(20, 5, 10, 5)
            setOnClickListener { dialog.dismiss() }
        })
        card.addView(header)

        fun addMenuBtn(title: String, bgColor: String = "#008577", onClick: () -> Unit) {
            val btn = TextView(this).apply {
                text = title
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(30, 32, 30, 32)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(bgColor))
                    cornerRadius = 10f
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 24 }
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            }
            card.addView(btn)
        }

        addMenuBtn("⭐ ОБРАЗЦЫ КАРТ") { openSearchSheet(true) }
        addMenuBtn("📂 ОБЩИЙ АРХИВ ВЫЗОВОВ") { openSearchSheet(false) }
        addMenuBtn("📖 СПРАВОЧНИК МКБ-10") { openMkbSheet() }
        addMenuBtn("📥 ИМПОРТ БАЗЫ", "#00695C") { importDbLauncher.launch(arrayOf("*/*")) }
        addMenuBtn("📤 ЭКСПОРТ БАЗЫ", "#00695C") {
            val sdf = SimpleDateFormat("dd_MM_yyyy", Locale.getDefault())
            exportDbLauncher.launch("ssmp_archive_${sdf.format(Date())}.json")
        }

        dialog.setContentView(card)
        dialog.show()
    }

    // Окно выбора при нажатии на молнию ⚡
    private fun showAutofillPickerSheet(currentFio: String = "") {
        val bottomSheet = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 30)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(90, 8).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 16
            }
            background = GradientDrawable().apply { setColor(Color.parseColor("#B0BEC5")); cornerRadius = 4f }
        })

        root.addView(TextView(this).apply {
            text = "⚡ Выберите шаблон или карту из архива:"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0288D1"))
            setPadding(0, 0, 0, 12)
        })

        // Если текущий пациент уже есть в личном архиве — показываем быструю кнопку сверху
        val cleanFio = currentFio.trim()
        if (cleanFio.length >= 3) {
            lifecycleScope.launch(Dispatchers.IO) {
                val matchedCard = dbHelper.getBestCardForPatient(cleanFio)
                if (matchedCard != null) {
                    withContext(Dispatchers.Main) {
                        val quickBtn = TextView(this@MainActivity).apply {
                            text = "👤 ЗАПОЛНИТЬ ИЗ АРХИВА: ${cleanFio.uppercase()}"
                            textSize = 13.5f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.WHITE)
                            gravity = Gravity.CENTER
                            setPadding(24, 22, 24, 22)
                            background = GradientDrawable().apply {
                                setColor(Color.parseColor("#2E7D32"))
                                cornerRadius = 16f
                            }
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = 14 }
                            setOnClickListener {
                                bottomSheet.dismiss()
                                val safe = JSONObject.quote(matchedCard)
                                webView.evaluateJavascript("setAndApplyAutofill($safe);", null)
                                Toast.makeText(applicationContext, "⚡ Заполнено из архива: ${cleanFio.uppercase()}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        root.addView(quickBtn, 2)
                    }
                }
            }
        }

        val totalTemplates = dbHelper.searchBuiltInTemplates("").size
        var showTemplates = true
        val tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 14)
        }
        val tabTemplates = TextView(this).apply {
            text = "⭐ Образцы СМП ($totalTemplates)"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 10 }
        }
        val tabArchive = TextView(this).apply {
            text = "📂 Мой архив"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tabsRow.addView(tabTemplates)
        tabsRow.addView(tabArchive)
        root.addView(tabsRow)

        val searchInput = EditText(this).apply {
            hint = "Поиск по диагнозу, МКБ или фамилии..."
            setPadding(35, 26, 35, 26)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0F8FF"))
                setStroke(2, Color.parseColor("#BBDEFB"))
                cornerRadius = 20f
            }
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
        }
        root.addView(searchInput)

        val listView = ListView(this).apply {
            divider = null
            dividerHeight = 12
            isNestedScrollingEnabled = true
            setPadding(0, 14, 0, 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(listView)

        bottomSheet.setContentView(root)
        bottomSheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheet.behavior.skipCollapsed = true
        bottomSheet.behavior.peekHeight = 1400

        fun styleTabs() {
            tabTemplates.typeface = if (showTemplates) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tabTemplates.setTextColor(if (showTemplates) Color.WHITE else Color.parseColor("#004D40"))
            tabTemplates.background = GradientDrawable().apply {
                setColor(Color.parseColor(if (showTemplates) "#00796B" else "#E0F2F1"))
                cornerRadius = 16f
            }
            tabArchive.typeface = if (!showTemplates) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tabArchive.setTextColor(if (!showTemplates) Color.WHITE else Color.parseColor("#004D40"))
            tabArchive.background = GradientDrawable().apply {
                setColor(Color.parseColor(if (!showTemplates) "#00796B" else "#E0F2F1"))
                cornerRadius = 16f
            }
        }

        fun updateList(query: String) {
            styleTabs()
            lifecycleScope.launch(Dispatchers.IO) {
                if (showTemplates) {
                    val samples = dbHelper.searchBuiltInTemplates(query)
                    withContext(Dispatchers.Main) {
                        val titles = samples.map { "⭐ ${it.title}\n📋 ${it.mkb}" }
                        setupAdapter(listView, titles, "#FFFDE7", "#FFE082") { pos ->
                            val chosen = samples[pos]
                            bottomSheet.dismiss()
                            val safe = JSONObject.quote(chosen.jsonData)
                            webView.evaluateJavascript("setAndApplyAutofill($safe);", null)
                            Toast.makeText(applicationContext, "⚡ Заполнен образец: ${chosen.title}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    val summaries = dbHelper.searchPatientSummaries(query)
                    withContext(Dispatchers.Main) {
                        val titles = summaries.map { "👤 ${it.fio.uppercase()}\n📋 ${it.lastReason}" }
                        setupAdapter(listView, titles, "#F0F8FF", "#BBDEFB") { pos ->
                            val chosen = summaries[pos]
                            bottomSheet.dismiss()
                            lifecycleScope.launch(Dispatchers.IO) {
                                val cardJson = dbHelper.getBestCardForPatient(chosen.fio)
                                withContext(Dispatchers.Main) {
                                    if (cardJson != null) {
                                        val safe = JSONObject.quote(cardJson)
                                        webView.evaluateJavascript("setAndApplyAutofill($safe);", null)
                                        Toast.makeText(applicationContext, "⚡ Заполнено из карты: ${chosen.fio.uppercase()}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        tabTemplates.setOnClickListener { showTemplates = true; updateList(searchInput.text.toString()) }
        tabArchive.setOnClickListener { showTemplates = false; updateList(searchInput.text.toString()) }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updateList(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })

        bottomSheet.show()
        updateList("")
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
            text = "📖 Справочник МКБ-10 (${fullMkbList.size} кодов)"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00695C"))
            setPadding(0, 0, 0, 10)
        })

        val categories = listOf(
            Pair("🌟 Все", "ALL"),
            Pair("❤️ I Кардио", "I"),
            Pair("🫁 J Дыхание", "J"),
            Pair("🧠 G Невро", "G"),
            Pair("🩹 S Травмы", "S"),
            Pair("🧪 T Токсико", "T"),
            Pair("🚨 R Симптомы", "R"),
            Pair("🤢 K ЖКТ", "K"),
            Pair("🩸 E Эндокрин", "E"),
            Pair("🤰 O Акушерство", "O"),
            Pair("🏥 Z Прочее", "Z"),
            Pair("🦠 A/B Инфекции", "AB"),
            Pair("🤪 F Психиатрия", "F"),
            Pair("🚽 N Урология", "N"),
            Pair("🦴 M Костно-мышечн.", "M"),
            Pair("🎗️ C/D Онкология", "CD"),
            Pair("👂 H ЛОР", "H"),
            Pair("👶 P/Q Перинатал.", "PQ"),
            Pair("⚠️ V-Y Внешн. причины", "VY")
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
            hint = "Поиск (код или болезнь: I48, инфаркт)..."
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
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isNestedScrollingEnabled = true
            divider = null
            dividerHeight = 12
            setPadding(0, 14, 0, 0)
        }
        root.addView(listView)

        bottomSheet.setContentView(root)
        bottomSheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheet.behavior.skipCollapsed = true
        bottomSheet.behavior.peekHeight = 1400

        fun filterMkb() {
            val q = searchInput.text.toString().trim().lowercase()
            val filtered = fullMkbList.asSequence().filter { item ->
                val matchCat = when (selectedCategory) {
                    "ALL" -> true
                    "AB" -> item.letter == "A" || item.letter == "B"
                    "CD" -> item.letter == "C" || item.letter == "D"
                    "PQ" -> item.letter == "P" || item.letter == "Q"
                    "VY" -> item.letter in listOf("V", "W", "X", "Y")
                    else -> item.letter == selectedCategory
                }
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

        val totalTemplates = dbHelper.searchBuiltInTemplates("").size
        root.addView(TextView(this).apply {
            text = if (isBestOnly) "⭐ Образцы карт СМП ($totalTemplates шаблонов)" else "📂 Общий архив вызовов"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(if (isBestOnly) "#E65100" else "#004D40"))
            setPadding(0, 0, 0, 14)
        })

        val searchInput = EditText(this).apply {
            hint = if (isBestOnly) "Поиск по диагнозу или МКБ (ОКС, I48, астма)..." else "Поиск сохраненных карт по фамилии..."
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
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isNestedScrollingEnabled = true
            divider = null
            dividerHeight = 14
            setPadding(0, 18, 0, 0)
        }
        root.addView(listView)

        bottomSheet.setContentView(root)
        bottomSheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheet.behavior.skipCollapsed = true
        bottomSheet.behavior.peekHeight = 1350

        val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

        fun runSearch(query: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                if (isBestOnly) {
                    val list = dbHelper.searchBuiltInTemplates(query)
                    withContext(Dispatchers.Main) {
                        val items = list.map { "⭐ ${it.title}\n📋 ${it.mkb}" }
                        setupAdapter(listView, items, "#FFFDE7", "#FFE082") { pos ->
                            bottomSheet.dismiss()
                            showTemplatePreviewDialog(list[pos])
                        }
                    }
                } else {
                    val list = dbHelper.searchPatientSummaries(query)
                    withContext(Dispatchers.Main) {
                        val items = list.map {
                            val count = if (it.callCount > 1) " [${it.callCount} выз.]" else ""
                            val reason = if (it.lastReason.isNotEmpty()) "\n• ${it.lastReason}" else ""
                            "👤 ${it.fio.uppercase()}$count\nСохранено: ${sdf.format(Date(it.lastTimestamp))}$reason"
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

    private fun showTemplatePreviewDialog(sample: BestCardSample) {
        val bottomSheet = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(35, 16, 35, 20)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(90, 8).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 14
            }
            background = GradientDrawable().apply { setColor(Color.parseColor("#B0BEC5")); cornerRadius = 4f }
        })

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }
        header.addView(TextView(this).apply {
            text = "⭐ ${sample.title}"
            textSize = 16.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E65100"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        val fillBtn = TextView(this).apply {
            text = "⚡ Вставить"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(28, 16, 28, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0288D1"))
                cornerRadius = 20f
            }
            setOnClickListener {
                bottomSheet.dismiss()
                val safe = JSONObject.quote(sample.jsonData)
                webView.evaluateJavascript("setAndApplyAutofill($safe);", null)
                Toast.makeText(this@MainActivity, "⚡ Образец вставлен в карту!", Toast.LENGTH_SHORT).show()
            }
        }
        header.addView(fillBtn)
        root.addView(header)

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
            setBackgroundColor(Color.parseColor("#FFE082"))
        })

        val scrollView = NestedScrollView(this).apply {
            isFillViewport = true
            isNestedScrollingEnabled = true
            setPadding(0, 12, 0, 10)
        }
        val detailsText = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
            setTextColor(Color.parseColor("#263238"))
            setLineSpacing(6f, 1.15f)
            setPadding(4, 8, 4, 25)
            text = formatCardText(sample.jsonData)
        }
        scrollView.addView(detailsText)
        root.addView(scrollView)

        bottomSheet.setContentView(root)
        bottomSheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheet.behavior.skipCollapsed = true
        bottomSheet.behavior.peekHeight = 1400
        bottomSheet.show()
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
                    textSize = 16.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#004D40"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                val fillFromThisBtn = TextView(this@MainActivity).apply {
                    text = "⚡ Вставить"
                    textSize = 12.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setPadding(24, 14, 24, 14)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#0288D1"))
                        cornerRadius = 20f
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = 12 }
                }
                header.addView(fillFromThisBtn)

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

                    fillFromThisBtn.setOnClickListener {
                        bottomSheet.dismiss()
                        val safe = JSONObject.quote(itm.jsonData)
                        webView.evaluateJavascript("setAndApplyAutofill($safe);", null)
                        Toast.makeText(this@MainActivity, "⚡ Данные вставлены в карту!", Toast.LENGTH_SHORT).show()
                    }

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
            if (j.optString("age").isNotEmpty()) b.append("⏳ Возраст: ${j.optString("age")} ${j.optString("ageUnit")}\n")
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

            if (j.optString("heartSounds").isNotEmpty()) b.append("• Тоны сердца: ${j.optString("heartSounds")}\n")
            if (j.optString("lungs").isNotEmpty()) b.append("• Дыхание: ${j.optString("lungs")}\n")
            if (j.optString("percussion").isNotEmpty()) b.append("• Перкуссия: ${j.optString("percussion")}\n")
            if (j.optString("abdomen").isNotEmpty()) b.append("• Живот: ${j.optString("abdomen")}\n")

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

            val medHelp = j.optString("medHelp")
            val meds = j.optString("medications")
            val attack = j.optString("attack")
            val effect = j.optString("therapyEffect")
            val callResult = j.optString("callResult")

            if (medHelp.isNotEmpty() || meds.isNotEmpty() || attack.isNotEmpty() || effect.isNotEmpty() || callResult.isNotEmpty()) {
                b.append("\n💉 ОКАЗАННАЯ ПОМОЩЬ:\n")
                if (medHelp.isNotEmpty()) b.append("• Помощь: $medHelp\n")
                if (meds.isNotEmpty()) b.append("• Препараты:\n• $meds\n")
                if (attack.isNotEmpty()) b.append("• Приступ: $attack\n")
                if (effect.isNotEmpty()) b.append("• Эффект: $effect\n")
                if (callResult.isNotEmpty()) b.append("• Итог: $callResult\n")
            }
            b.toString()
        } catch (e: Exception) { "Ошибка" }
    }

    private fun parseServerCardJson(jsonStr: String): Triple<String, String, Pair<Int, JSONObject>>? {
        return try {
            val raw = JSONObject(jsonStr)
            val patient = raw.optJSONObject("patient") ?: JSONObject()
            val fio = patient.optString("fullName").ifEmpty { raw.optString("initialFullName") }.trim()
            if (fio.isEmpty()) return null

            var bDate = patient.optString("birthDate").replace("null", "")
            if (bDate.contains("T") && bDate.contains("-")) {
                val p = bDate.substringBefore("T").split("-")
                if (p.size == 3) bDate = "${p[2]}.${p[1]}.${p[0]} / 00:00"
            }

            fun joinArr(key: String): String {
                val arr = raw.optJSONArray(key) ?: return ""
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i)
                    val t = item?.optString("type") ?: item?.optString("name") ?: arr.optString(i)
                    if (t.isNotEmpty() && t != "null") list.add(t)
                }
                return list.joinToString(", ")
            }

            fun parseMedicationsArray(): String {
                val arr = raw.optJSONArray("medications") ?: return ""
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val medObj = m.optJSONObject("medication") ?: m.optJSONObject("medicine") ?: m
                    val name = medObj.optString("type").ifEmpty { medObj.optString("name") }.ifEmpty { m.optString("name") }
                    val count = m.optString("count").ifEmpty { m.optString("amount") }.ifEmpty { m.optString("quantity") }.replace("null", "")
                    val unit = m.optJSONObject("unit")?.optString("type") ?: medObj.optJSONObject("unit")?.optString("type") ?: ""
                    val way = m.optJSONObject("wayOfAdministration")?.optString("type")
                        ?: m.optJSONObject("way")?.optString("type")
                        ?: m.optString("wayOfAdministration").replace("null", "")

                    if (name.isNotEmpty() && name != "null") {
                        val qtyStr = if (count.isNotEmpty()) " — $count $unit".trimEnd() else ""
                        val wayStr = if (way.isNotEmpty() && !way.contains("не определен")) " [$way]" else ""
                        list.add("$name$qtyStr$wayStr")
                    }
                }
                return list.joinToString(";\n• ")
            }

            val complaints = raw.optString("complaints").replace("null", "")
            val anamnesis = raw.optString("anamnesis").replace("null", "")
            val diag = raw.optString("diagnosis").replace("null", "")
            val medsStr = parseMedicationsArray()

            var score = 0
            if (anamnesis.length > 10) score += 35
            if (complaints.isNotEmpty()) score += 25
            if (diag.isNotEmpty()) score += 25
            if (medsStr.isNotEmpty() || raw.optString("arterialPressure").replace("null", "").isNotEmpty()) score += 15

            val icdObj = raw.optJSONObject("icd")
            val mkbStr = if (icdObj != null) "[${icdObj.optString("code")}] ${icdObj.optString("type").ifEmpty { icdObj.optString("name") }}".trim() else ""

            val card = JSONObject().apply {
                put("fio", fio)
                put("birthDate", bDate)
                put("age", raw.optString("age").replace("null", ""))
                put("ageUnit", raw.optJSONObject("ageType")?.optString("type") ?: "")
                put("address", patient.optString("placeOfRegistration").ifEmpty { raw.optString("address") }.replace("null", ""))
                put("apo", patient.optJSONObject("territorialMedicalFacility")?.optString("type") ?: "")
                put("callReason", raw.optJSONObject("reasonToCall")?.optString("type") ?: "")
                put("gender", if (patient.optBoolean("gender", false)) "МУЖЧИНА" else "ЖЕНЩИНА")
                put("complaints", complaints)
                put("anamnesis", anamnesis)
                put("pastDiseases", raw.optString("pastIllnesses").replace("null", ""))
                put("allergy", if (raw.optBoolean("allergy", false)) "ДА" else "НЕТ")
                put("generalState", raw.optJSONObject("generalState")?.optString("type") ?: "")
                put("consciousness", raw.optJSONObject("consciousness")?.optString("type") ?: "")
                put("position", raw.optJSONObject("position")?.optString("type")?.uppercase() ?: "")
                put("behavior", raw.optJSONObject("behavior")?.optString("type")?.uppercase() ?: "")
                put("glasgowScore", raw.optString("comaScale").replace("null", ""))
                put("bp", raw.optString("arterialPressure").replace("null", ""))
                put("bpAdapted", raw.optString("arterialPressureAdaptive").replace("null", ""))
                put("pulse", raw.optString("pulse").replace("null", ""))
                put("rr", raw.optString("breathCount").replace("null", ""))
                put("temp", raw.optString("skinTemp").replace("null", ""))
                put("saturation", raw.optString("saturation1").replace("null", ""))
                put("glucose", raw.optString("glycemia1").replace("null", ""))
                put("heartSounds", joinArr("heartSounds"))
                put("lungs", joinArr("breaths"))
                put("percussion", joinArr("percussionOfLungs"))
                put("abdomen", joinArr("stomachs"))
                put("skin", joinArr("skins"))
                put("urination", joinArr("urinations").uppercase())
                put("medHelp", joinArr("medicalAssistanceProvided"))
                put("medications", medsStr)
                put("pupils", raw.optJSONObject("pupils")?.optString("type")?.uppercase() ?: "")
                put("lightReaction", if (raw.optBoolean("pupilsLight", true)) "ДА" else "НЕТ")
                put("speech", raw.optJSONObject("speech")?.optString("type")?.uppercase() ?: "")
                put("gait", raw.optJSONObject("gait")?.optString("type")?.uppercase() ?: "")
                put("face", raw.optJSONObject("face")?.optString("type")?.uppercase() ?: "")
                put("nystagmus", raw.optJSONObject("nystagmus")?.optString("type")?.uppercase() ?: "")
                put("tongue", raw.optString("tongue").replace("null", ""))
                put("mucous", raw.optString("mucosa").replace("null", ""))
                put("liver", raw.optJSONObject("liver")?.optString("type")?.uppercase() ?: "")
                put("peristalsis", if (raw.optBoolean("peristalsis", true)) "ДА" else "НЕТ")
                put("localStatus", raw.optString("localStatus").replace("null", ""))
                put("ecgBefore", raw.optString("electrocardiogramBefore").replace("null", ""))
                put("ecgAfter", raw.optString("electrocardiogramAfter").replace("null", ""))
                put("diagnosis", diag)
                put("mkb", mkbStr)
                put("attack", raw.optJSONObject("postAttack")?.optString("type")?.uppercase() ?: "")
                put("therapyEffect", raw.optJSONObject("result")?.optString("type")?.uppercase() ?: "")
                put("callResult", raw.optJSONObject("resultDeparture")?.optString("type")?.uppercase() ?: "")
            }
            Triple(fio, bDate, Pair(score, card))
        } catch (e: Exception) {
            null
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun openMainMenu() = runOnUiThread { showMainModalMenu() }

        @JavascriptInterface
        fun openSearchDialog() = runOnUiThread { openSearchSheet(false) }

        @JavascriptInterface
        fun openBestCardsDialog() = runOnUiThread { openSearchSheet(true) }

        @JavascriptInterface
        fun openMkbDialog() = runOnUiThread { openMkbSheet() }

        @JavascriptInterface
        fun openAutofillPicker() = runOnUiThread { showAutofillPickerSheet("") }

        @JavascriptInterface
        fun notifyUser(msg: String) = runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show() }

        @JavascriptInterface
        fun saveApiCardForced(jsonStr: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                val parsed = parseServerCardJson(jsonStr)
                if (parsed != null) {
                    val (fio, bDate, scoreAndCard) = parsed
                    dbHelper.saveOrUpdateCard(fio, bDate, scoreAndCard.first, scoreAndCard.second.toString())
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "💾 Карта $fio сохранена в ваш архив!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    try {
                        val j = JSONObject(jsonStr)
                        val fio = j.optString("fio").trim()
                        if (fio.isNotEmpty()) {
                            dbHelper.saveOrUpdateCard(fio, j.optString("birthDate"), 80, jsonStr)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(applicationContext, "💾 Карта $fio сохранена в ваш архив!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        }

        @JavascriptInterface
        fun requestAutofill(fio: String) {
            runOnUiThread {
                showAutofillPickerSheet(fio)
            }
        }
    }
}