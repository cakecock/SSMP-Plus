package by.brest103.ssmpplus

import android.annotation.SuppressLint
import android.content.*
import android.content.ClipboardManager
import android.graphics.*
import android.graphics.drawable.*
import android.net.http.SslError
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.*
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.webkit.*
import com.google.android.material.bottomsheet.*
import kotlinx.coroutines.*
import org.json.*
import java.text.SimpleDateFormat
import java.util.*

data class MkbCodeItem(val code: String, val name: String, val letter: String)

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var dbHelper: DatabaseHelper
    private var backPressedTime: Long = 0
    private var fullMkbList: List<MkbCodeItem> = emptyList()

    private val exportDbLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val data = dbHelper.exportDatabaseToJson()
                    contentResolver.openOutputStream(it)?.use { out -> out.write(data.toByteArray(Charsets.UTF_8)) }
                    withContext(Dispatchers.Main) { toast("📤 База экспортирована!") }
                } catch (e: Exception) { withContext(Dispatchers.Main) { toast("Ошибка экспорта: ${e.message}") } }
            }
        }
    }

    private val importDbLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val json = contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() } ?: ""
                    val count = dbHelper.importDatabaseFromJson(json)
                    withContext(Dispatchers.Main) { toast("📥 Импортировано записей: $count") }
                } catch (e: Exception) { withContext(Dispatchers.Main) { toast("Ошибка импорта файла") } }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun roundBg(hex: String, r: Float, strokeHex: String? = null) = GradientDrawable().apply {
        setColor(Color.parseColor(hex)); cornerRadius = r; strokeHex?.let { setStroke(2, Color.parseColor(it)) }
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
                if (webView.canGoBack()) webView.goBack()
                else if (System.currentTimeMillis() - backPressedTime < 2000) finish()
                else { backPressedTime = System.currentTimeMillis(); toast("Нажмите назад ещё раз для выхода") }
            }
        })

        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(webView, true) }
        webView.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            allowFileAccess = true; allowContentAccess = true; loadWithOverviewMode = true
            useWideViewPort = true; cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        }

        val polyfill = """
            if (typeof window.Notification === 'undefined') {
                function MockNotification(t, o) { this.title = t; this.options = o; this.close = function(){}; }
                MockNotification.permission = 'granted';
                MockNotification.requestPermission = function(cb) { if (typeof cb === 'function') cb('granted'); return Promise.resolve('granted'); };
                window.Notification = MockNotification;
            }
        """.trimIndent()

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, polyfill, setOf("*"))
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(v: WebView?, u: String?, m: String?, r: JsResult?): Boolean {
                Toast.makeText(this@MainActivity, m ?: "", Toast.LENGTH_LONG).show(); r?.confirm(); return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: SslError?) { h?.proceed() }
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean = false
            override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) { super.onPageStarted(v, u, f); v?.evaluateJavascript(polyfill, null) }
            override fun onPageFinished(v: WebView?, u: String?) { super.onPageFinished(v, u); injectScript() }
        }
        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")
        webView.loadUrl("https://soft.103brest.by/ssmp/")
    }

    private fun loadFullMkbAsync() = lifecycleScope.launch(Dispatchers.IO) {
        try {
            val arr = JSONArray(assets.open("mkb10.json").bufferedReader().use { it.readText() })
            val list = ArrayList<MkbCodeItem>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val c = obj.optString("c").trim().uppercase()
                val n = obj.optString("n").trim()
                if (c.length >= 3 && c[0] in 'A'..'Z' && c.any { it.isDigit() }) list.add(MkbCodeItem(c, n, c[0].toString()))
            }
            fullMkbList = list
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun injectScript() {
        try { webView.evaluateJavascript(assets.open("injector.js").bufferedReader().use { it.readText() }, null) } catch (_: Exception) {}
    }

    private fun createSheet(title: String, titleColor: String): Pair<BottomSheetDialog, LinearLayout> {
        val bs = BottomSheetDialog(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 18, 36, 24); setBackgroundColor(Color.WHITE) }
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(90, 8).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = 14 }
            background = roundBg("#B0BEC5", 4f)
        })
        root.addView(TextView(this).apply {
            text = title; textSize = 18.5f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(titleColor)); setPadding(0, 0, 0, 12)
        })
        bs.setContentView(root)
        bs.behavior.apply { state = BottomSheetBehavior.STATE_EXPANDED; skipCollapsed = true; peekHeight = 1400 }
        return bs to root
    }

    private fun makeListView(root: LinearLayout): ListView = ListView(this).apply {
        layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        isNestedScrollingEnabled = true; divider = null; dividerHeight = 12; setPadding(0, 14, 0, 0)
        root.addView(this)
    }

    private fun setupAdapter(lv: ListView, items: List<String>, bg: String, stroke: String, onClick: (Int) -> Unit) {
        lv.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            override fun getView(pos: Int, conv: View?, parent: ViewGroup): View = (super.getView(pos, conv, parent) as TextView).apply {
                setPadding(26, 20, 26, 20); setTextColor(Color.parseColor("#263238")); textSize = 14f
                background = roundBg(bg, 16f, stroke)
            }
        }
        lv.setOnItemClickListener { _, _, pos, _ -> onClick(pos) }
    }

    private fun showMenuDialog(title: String, builders: (android.app.Dialog, LinearLayout) -> Unit) {
        val dialog = android.app.Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE); window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(44, 36, 44, 40); background = roundBg("#FFFFFF", 18f); layoutParams = ViewGroup.LayoutParams(900, -2) }
        val hdr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 24) }
        hdr.addView(TextView(this).apply { text = title; textSize = 19.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#212121")); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        hdr.addView(TextView(this).apply { text = "✕"; textSize = 20f; setTextColor(Color.GRAY); setPadding(16, 4, 8, 4); setOnClickListener { dialog.dismiss() } })
        card.addView(hdr)
        builders(dialog, card)
        dialog.setContentView(card)
        dialog.show()
    }

    private fun addDialogBtn(dialog: android.app.Dialog, parent: LinearLayout, title: String, color: String = "#008577", onClick: () -> Unit) {
        parent.addView(TextView(this).apply {
            text = title; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(24, 28, 24, 28); background = roundBg(color, 10f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16 }
            setOnClickListener { dialog.dismiss(); onClick() }
        })
    }

    private fun showMainModalMenu() = showMenuDialog("Меню SSMP Plus") { d, card ->
        addDialogBtn(d, card, "⭐ ОБРАЗЦЫ КАРТ") { openSearchSheet(true) }
        addDialogBtn(d, card, "📂 ОБЩИЙ АРХИВ ВЫЗОВОВ") { openSearchSheet(false) }
        addDialogBtn(d, card, "🧰 ПОМОЩНИК СМП", "#0277BD") { showHelperSubMenu() }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
        fun half(t: String, mr: Int, act: () -> Unit) = TextView(this).apply {
            text = t; textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 1
            setPadding(12, 28, 12, 28); background = roundBg("#00695C", 10f)
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = mr }
            setOnClickListener { d.dismiss(); act() }
        }
        row.addView(half("📥 ИМПОРТ", 14) { importDbLauncher.launch(arrayOf("*/*")) })
        row.addView(half("📤 ЭКСПОРТ", 0) { exportDbLauncher.launch("ssmp_archive_${SimpleDateFormat("dd_MM_yyyy", Locale.getDefault()).format(Date())}.json") })
        card.addView(row)
    }

    private fun showHelperSubMenu() = showMenuDialog("Помощник СМП") { d, card ->
        addDialogBtn(d, card, "📖 СПРАВОЧНИК МКБ-10", "#008577") { openMkbSheet() }
        addDialogBtn(d, card, "👶 ПЕДИАТРИЯ: НОРМЫ И ДОЗЫ", "#0277BD") { MedicalTools.showPediatricsSheet(this) }
        addDialogBtn(d, card, "🧠 ШКАЛА ГЛАЗГО И ШОК-ИНДЕКС", "#5E35B1") { MedicalTools.showCalculatorsSheet(this) }
    }

    private fun showAutofillPickerSheet(currentFio: String = "") {
        val (bs, root) = createSheet("⚡ Выберите шаблон или карту:", "#0288D1")
        val cleanFio = currentFio.trim()
        if (cleanFio.length >= 3) {
            lifecycleScope.launch(Dispatchers.IO) {
                dbHelper.getBestCardForPatient(cleanFio)?.let { card ->
                    withContext(Dispatchers.Main) {
                        root.addView(TextView(this@MainActivity).apply {
                            text = "👤 ЗАПОЛНИТЬ ИЗ АРХИВА: ${cleanFio.uppercase()}"
                            textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                            setPadding(20, 20, 20, 20); background = roundBg("#2E7D32", 16f)
                            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 }
                            setOnClickListener { bs.dismiss(); webView.evaluateJavascript("setAndApplyAutofill(${JSONObject.quote(card)});", null); toast("⚡ Заполнено!") }
                        }, 2)
                    }
                }
            }
        }

        val total = dbHelper.searchBuiltInTemplates("").size
        var showT = true
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 12) }
        val t1 = TextView(this).apply { text = "⭐ Образцы ($total)"; gravity = Gravity.CENTER; setPadding(16, 14, 16, 14); layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 8 } }
        val t2 = TextView(this).apply { text = "📂 Мой архив"; gravity = Gravity.CENTER; setPadding(16, 14, 16, 14); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        tabs.addView(t1); tabs.addView(t2); root.addView(tabs)

        val qInp = EditText(this).apply { hint = "Поиск (СЛР, диагноз, фамилия)..."; setPadding(30, 22, 30, 22); background = roundBg("#F0F8FF", 18f, "#BBDEFB"); setTextColor(Color.BLACK) }
        root.addView(qInp)
        val lv = makeListView(root)

        fun update(q: String) {
            t1.typeface = if (showT) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; t1.setTextColor(if (showT) Color.WHITE else Color.parseColor("#004D40")); t1.background = roundBg(if (showT) "#00796B" else "#E0F2F1", 14f)
            t2.typeface = if (!showT) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; t2.setTextColor(if (!showT) Color.WHITE else Color.parseColor("#004D40")); t2.background = roundBg(if (!showT) "#00796B" else "#E0F2F1", 14f)
            lifecycleScope.launch(Dispatchers.IO) {
                if (showT) {
                    val list = dbHelper.searchBuiltInTemplates(q)
                    withContext(Dispatchers.Main) {
                        setupAdapter(lv, list.map { "${if (it.isCustom) "📌" else "⭐"} ${it.title}\n📋 ${it.mkb}" }, "#FFFDE7", "#FFE082") { pos ->
                            bs.dismiss(); webView.evaluateJavascript("setAndApplyAutofill(${JSONObject.quote(list[pos].jsonData)});", null); toast("⚡ Заполнен: ${list[pos].title}")
                        }
                    }
                } else {
                    val list = dbHelper.searchPatientSummaries(q)
                    withContext(Dispatchers.Main) {
                        setupAdapter(lv, list.map { "👤 ${it.fio.uppercase()}\n📋 ${it.lastReason}" }, "#F0F8FF", "#BBDEFB") { pos ->
                            bs.dismiss()
                            lifecycleScope.launch(Dispatchers.IO) {
                                dbHelper.getBestCardForPatient(list[pos].fio)?.let { c ->
                                    withContext(Dispatchers.Main) { webView.evaluateJavascript("setAndApplyAutofill(${JSONObject.quote(c)});", null); toast("⚡ Заполнено!") }
                                }
                            }
                        }
                    }
                }
            }
        }
        t1.setOnClickListener { showT = true; update(qInp.text.toString()) }
        t2.setOnClickListener { showT = false; update(qInp.text.toString()) }
        qInp.addTextChangedListener(object : TextWatcher { override fun afterTextChanged(s: Editable?) = update(s.toString()); override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}; override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {} })
        bs.show(); update("")
    }

    private fun openMkbSheet() {
        val (bs, root) = createSheet("📖 Справочник МКБ-10 (${fullMkbList.size} кодов)", "#00695C")
        val cats = listOf("🌟 Все" to "ALL", "❤️ I Кардио" to "I", "🫁 J Дыхание" to "J", "🧠 G Невро" to "G", "🩹 S Травмы" to "S", "🧪 T Токсико" to "T", "🚨 R Симптомы" to "R", "🤢 K ЖКТ" to "K", "🩸 E Эндокрин" to "E", "🤰 O Акушерство" to "O", "🏥 Z Прочее" to "Z", "🦠 A/B Инфекции" to "AB", "🤪 F Психиатрия" to "F", "🚽 N Урология" to "N", "🦴 M Кости" to "M", "🎗️ C/D Онко" to "CD", "👂 H ЛОР" to "H", "👶 P/Q Дети" to "PQ", "⚠️ V-Y Причины" to "VY")
        var selCat = "ALL"
        val chipsLay = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setPadding(0, 0, 0, 12); addView(chipsLay) })
        val qInp = EditText(this).apply { hint = "Поиск (I48, инфаркт)..."; setPadding(30, 22, 30, 22); background = roundBg("#E0F2F1", 18f); setTextColor(Color.BLACK) }
        root.addView(qInp)
        val lv = makeListView(root)

        fun filter() {
            val q = qInp.text.toString().trim().lowercase()
            val res = fullMkbList.asSequence().filter { item ->
                val mc = when (selCat) {
                    "ALL" -> true; "AB" -> item.letter in listOf("A", "B"); "CD" -> item.letter in listOf("C", "D")
                    "PQ" -> item.letter in listOf("P", "Q"); "VY" -> item.letter in listOf("V", "W", "X", "Y"); else -> item.letter == selCat
                }
                mc && (q.isEmpty() || item.code.lowercase().contains(q) || item.name.lowercase().contains(q))
            }.take(80).toList()
            setupAdapter(lv, res.map { "[${it.code}] ${it.name}" }, "#F7FAFA", "#E0EAEA") { pos ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("MKB", "[${res[pos].code}] ${res[pos].name}"))
                toast("📋 Скопировано: [${res[pos].code}]"); bs.dismiss()
            }
        }

        fun renderChips() {
            chipsLay.removeAllViews()
            cats.forEach { (lbl, key) ->
                val sel = key == selCat
                chipsLay.addView(TextView(this).apply {
                    text = lbl; textSize = 12f; typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(if (sel) Color.WHITE else Color.parseColor("#004D40")); setPadding(20, 10, 20, 10)
                    background = roundBg(if (sel) "#00796B" else "#E0F2F1", 16f)
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8 }
                    setOnClickListener { selCat = key; renderChips(); filter() }
                })
            }
        }
        qInp.addTextChangedListener(object : TextWatcher { override fun afterTextChanged(s: Editable?) = filter(); override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}; override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {} })
        renderChips(); filter(); bs.show()
    }

    private fun openSearchSheet(isBest: Boolean) {
        val total = dbHelper.searchBuiltInTemplates("").size
        val (bs, root) = createSheet(if (isBest) "⭐ Образцы карт ($total шаблонов)" else "📂 Общий архив вызовов", if (isBest) "#E65100" else "#004D40")
        val qInp = EditText(this).apply {
            hint = if (isBest) "Поиск по диагнозу или СЛР..." else "Поиск по фамилии..."
            setPadding(30, 22, 30, 22); background = roundBg(if (isBest) "#FFF8E1" else "#F0F4F4", 18f, if (isBest) "#FFE082" else null); setTextColor(Color.BLACK)
        }
        root.addView(qInp)
        val lv = makeListView(root)
        val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

        fun run(q: String) = lifecycleScope.launch(Dispatchers.IO) {
            if (isBest) {
                val list = dbHelper.searchBuiltInTemplates(q)
                withContext(Dispatchers.Main) {
                    setupAdapter(lv, list.map { "${if (it.isCustom) "📌" else "⭐"} ${it.title}\n📋 ${it.mkb}" }, "#FFFDE7", "#FFE082") { pos -> bs.dismiss(); showTemplatePreview(list[pos]) }
                }
            } else {
                val list = dbHelper.searchPatientSummaries(q)
                withContext(Dispatchers.Main) {
                    setupAdapter(lv, list.map { "👤 ${it.fio.uppercase()}${if (it.callCount > 1) " [${it.callCount}]" else ""}\nСохранено: ${sdf.format(Date(it.lastTimestamp))}\n• ${it.lastReason}" }, "#F7FAFA", "#E0EAEA") { pos -> bs.dismiss(); showPatientHistory(list[pos].fio) }
                }
            }
        }
        qInp.addTextChangedListener(object : TextWatcher { override fun afterTextChanged(s: Editable?) { run(s.toString()) }; override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}; override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {} })
        bs.show(); run("")
    }

    private fun showTemplatePreview(s: BestCardSample) {
        val (bs, root) = createSheet("${if (s.isCustom) "📌" else "⭐"} ${s.title}", "#E65100")
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 12) }
        bar.addView(TextView(this).apply {
            text = "⚡ Вставить в карту"; textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(24, 16, 24, 16); background = roundBg("#0288D1", 16f); layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = if (s.isCustom) 10 else 0 }
            setOnClickListener { bs.dismiss(); webView.evaluateJavascript("setAndApplyAutofill(${JSONObject.quote(s.jsonData)});", null); toast("⚡ Шаблон вставлен!") }
        })
        if (s.isCustom) {
            bar.addView(ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_delete); background = roundBg("#FFEBEE", 16f); setPadding(18, 16, 18, 16)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle("Удалить шаблон?").setPositiveButton("Удалить") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) { if (dbHelper.deleteCustomTemplate(s.id)) withContext(Dispatchers.Main) { toast("Удалено"); bs.dismiss() } }
                    }.setNegativeButton("Отмена", null).show()
                }
            })
        }
        root.addView(bar)
        val sv = NestedScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); isFillViewport = true; isNestedScrollingEnabled = true }
        sv.addView(TextView(this).apply { textSize = 15f; setTextIsSelectable(true); setTextColor(Color.parseColor("#263238")); setLineSpacing(6f, 1.15f); text = formatCardText(s.jsonData) })
        root.addView(sv); bs.show()
    }

    private fun showPatientHistory(fio: String) = lifecycleScope.launch(Dispatchers.IO) {
        val history = dbHelper.getPatientHistory(fio).toMutableList()
        if (history.isEmpty()) return@launch
        withContext(Dispatchers.Main) {
            val (bs, root) = createSheet(fio.uppercase(), "#004D40")
            val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

            val actions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 12) }
            val toTpl = TextView(this@MainActivity).apply { text = "⭐ В шаблон"; textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#E65100")); gravity = Gravity.CENTER; setPadding(16, 14, 16, 14); background = roundBg("#FFF8E1", 14f, "#FFE082"); layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 8 } }
            val fillBtn = TextView(this@MainActivity).apply { text = "⚡ Вставить"; textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(16, 14, 16, 14); background = roundBg("#0288D1", 14f); layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 8 } }
            val delBtn = ImageButton(this@MainActivity).apply { setImageResource(android.R.drawable.ic_menu_delete); background = roundBg("#FFEBEE", 14f); setPadding(16, 12, 16, 12) }
            actions.addView(toTpl); actions.addView(fillBtn); actions.addView(delBtn); root.addView(actions)

            val chipsLay = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            root.addView(HorizontalScrollView(this@MainActivity).apply { isHorizontalScrollBarEnabled = false; setPadding(0, 0, 0, 8); addView(chipsLay) })
            val badgesLay = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            root.addView(HorizontalScrollView(this@MainActivity).apply { isHorizontalScrollBarEnabled = false; setPadding(0, 0, 0, 10); addView(badgesLay) })

            val sv = NestedScrollView(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); isFillViewport = true; isNestedScrollingEnabled = true }
            val details = TextView(this@MainActivity).apply { textSize = 15f; setTextIsSelectable(true); setTextColor(Color.parseColor("#263238")); setLineSpacing(6f, 1.15f); setPadding(4, 6, 4, 20) }
            sv.addView(details); root.addView(sv)

            var selIdx = 0
            fun render() {
                val itm = history[selIdx]
                val j = try { JSONObject(itm.jsonData) } catch (_: Exception) { JSONObject() }
                toTpl.setOnClickListener {
                    val inp = EditText(this@MainActivity).apply { setText(j.optString("diagnosis").ifEmpty { j.optString("callReason") }.take(45)); setPadding(30, 20, 30, 20) }
                    AlertDialog.Builder(this@MainActivity).setTitle("Сохранить в свои шаблоны").setMessage("ФИО и адрес будут удалены.").setView(inp).setPositiveButton("Сохранить") { _, _ ->
                        val t = inp.text.toString().trim()
                        if (t.isNotEmpty()) lifecycleScope.launch(Dispatchers.IO) { dbHelper.saveCustomTemplate(t, itm.jsonData); withContext(Dispatchers.Main) { toast("📌 Шаблон «$t» сохранен!") } }
                    }.setNegativeButton("Отмена", null).show()
                }
                fillBtn.setOnClickListener { bs.dismiss(); webView.evaluateJavascript("setAndApplyAutofill(${JSONObject.quote(itm.jsonData)});", null); toast("⚡ Вставлено!") }
                delBtn.setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle("Удалить вызов?").setPositiveButton("Удалить") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            if (dbHelper.deleteCard(itm.id)) {
                                history.removeAt(selIdx)
                                withContext(Dispatchers.Main) { toast("Удалено"); if (history.isEmpty()) bs.dismiss() else { selIdx = 0; render() } }
                            }
                        }
                    }.setNegativeButton("Отмена", null).show()
                }

                chipsLay.removeAllViews()
                history.forEachIndexed { idx, c ->
                    val on = idx == selIdx
                    chipsLay.addView(TextView(this@MainActivity).apply {
                        text = "Вызов #${history.size - idx} (${sdf.format(Date(c.timestamp))})"; textSize = 12f
                        typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        setTextColor(if (on) Color.WHITE else Color.parseColor("#004D40")); setPadding(20, 10, 20, 10)
                        background = roundBg(if (on) "#00796B" else "#E0F2F1", 16f)
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8 }
                        setOnClickListener { selIdx = idx; render() }
                    })
                }
                badgesLay.removeAllViews()
                fun addB(t: String, c: String) = badgesLay.addView(TextView(this@MainActivity).apply {
                    text = t; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(18, 6, 18, 6); background = roundBg(c, 14f)
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8 }
                })
                val bp = cleanBpString(j.optString("bp")).ifEmpty { cleanBpString(j.optString("bpAdapted")) }
                if (bp.isNotEmpty()) addB("❤️ $bp", "#E53935")
                if (j.optString("pulse").isNotEmpty()) addB("💓 ${j.optString("pulse")}", "#D81B60")
                if (j.optString("saturation").isNotEmpty()) addB("🫁 ${j.optString("saturation")}%", "#00897B")
                if (j.optString("glucose").isNotEmpty()) addB("🩸 ${j.optString("glucose")}", "#8E24AA")
                if (j.optString("glasgowScore").isNotEmpty()) addB("🧠 ${j.optString("glasgowScore")} б", "#3949AB")

                details.text = formatCardText(itm.jsonData)
                sv.post { sv.scrollTo(0, 0) }
            }
            render(); bs.show()
        }
    }

    private fun cleanBpString(raw: String): String {
        val s = raw.trim().replace("null", "")
        if (s.isEmpty()) return ""
        if (s.startsWith("{")) return try {
            val o = JSONObject(s)
            val sys = o.optString("sys").ifEmpty { o.optString("systolic") }.replace("null", "").trim()
            val dis = o.optString("dis").ifEmpty { o.optString("dia") }.ifEmpty { o.optString("diastolic") }.replace("null", "").trim()
            if (sys.isNotEmpty() || dis.isNotEmpty()) "$sys/$dis" else ""
        } catch (_: Exception) { "" }
        return if (s.contains("ул.") || s.contains(".202")) "" else s
    }

    private fun cleanMedicationsString(raw: String): String {
        var s = raw.trim().replace("null", "")
        if (s.isEmpty()) return ""
        if (s.contains("{")) {
            val rx = Regex(""""(?:type|name|title)"\s*:\s*"([^"]+)"""")
            s = s.replace(Regex("""\{[^{}]*\}""")) { rx.find(it.value)?.groupValues?.get(1) ?: "" }
        }
        return s.replace(Regex("""(?i)\bid\s*[:=]?\s*\d+\s*[,;]?"""), "").trim()
    }

    private fun formatCardText(jsonStr: String): String = try {
        val j = JSONObject(jsonStr)
        StringBuilder().apply {
            fun add(label: String, key: String, suf: String = "") { val v = j.optString(key); if (v.isNotEmpty()) append("$label: $v$suf\n") }
            add("🎂 Родился", "birthDate"); add("⏳ Возраст", "age", " ${j.optString("ageUnit")}".trimEnd())
            add("⚧ Пол", "gender"); add("📍 Адрес", "address"); add("🚨 Повод", "callReason"); add("🏥 АПО", "apo")

            append("\n📋 ДИАГНОЗ:\n• ${j.optString("diagnosis")}\n"); add("• Код МКБ", "mkb")
            if (j.optString("recommendedTherapy").isNotEmpty()) append("\n💊 ПРОТОКОЛЬНАЯ ТЕРАПИЯ (подсказка):\n${j.optString("recommendedTherapy")}\n")

            if (j.optString("ecgBefore").isNotEmpty() || j.optString("ecgAfter").isNotEmpty()) {
                append("\n📈 ЭКГ:\n"); add("• До помощи", "ecgBefore"); add("• После помощи", "ecgAfter")
            }

            append("\n🩺 ОБЪЕКТИВНО:\n")
            add("• Состояние", "generalState"); add("• Сознание", "consciousness"); add("• Положение", "position")
            add("• Поведение", "behavior"); add("• Глазго", "glasgowScore", " б")
            val bpAd = cleanBpString(j.optString("bpAdapted")); if (bpAd.isNotEmpty()) append("• Привычное АД: $bpAd\n")
            val bp = cleanBpString(j.optString("bp")); if (bp.isNotEmpty()) append("• АД на вызове: $bp\n")
            add("• Пульс", "pulse", " уд/мин"); add("• ЧДД", "rr", " в мин"); add("• SpO2", "saturation", "%")
            add("• Сахар", "glucose", " ммоль/л"); add("• Температура", "temp", "°C")
            add("• Тоны сердца", "heartSounds"); add("• Дыхание", "lungs"); add("• Перкуссия", "percussion")
            add("• Живот", "abdomen"); add("• Кожа", "skin"); add("• Высыпания", "rash")
            add("• Отеки", "edema"); add("• Зрачки", "pupils"); add("• Реакция на свет", "lightReaction")
            add("• Речь", "speech"); add("• Лицо", "face"); add("• Походка", "gait"); add("• Нистагм", "nystagmus")
            add("• Язык", "tongue"); add("• Слизистые", "mucous"); add("• Перистальтика", "peristalsis")
            add("• Печень", "liver"); add("• Брюшина", "peritoneal"); add("• Мочеиспускание", "urination"); add("• Стул", "stool")
            add("• Запах алкоголя", "alcohol"); add("• Запах ацетона", "acetone"); add("• Локально", "localStatus")

            if (j.optString("complaints").isNotEmpty()) append("\n🗣️ Жалобы:\n${j.optString("complaints")}\n")
            if (j.optString("anamnesis").isNotEmpty()) append("\n📖 Анамнез:\n${j.optString("anamnesis")}\n")
            if (j.optString("pastDiseases").isNotEmpty()) append("\n🩹 Перенесенные: ${j.optString("pastDiseases")}\n")
            add("⚠️ Аллергия", "allergy")

            val meds = cleanMedicationsString(j.optString("medications"))
            if (meds.isNotEmpty() || j.optString("attack").isNotEmpty() || j.optString("therapyEffect").isNotEmpty() || j.optString("callResult").isNotEmpty()) {
                append("\n💉 ОКАЗАННАЯ ПОМОЩЬ:\n"); add("• Помощь", "medHelp")
                if (meds.isNotEmpty()) append("• Препараты:\n• $meds\n")
                add("• Приступ", "attack"); add("• Эффект", "therapyEffect"); add("• Итог", "callResult")
            }
        }.toString()
    } catch (_: Exception) { "Ошибка чтения данных" }

    private fun extractName(obj: JSONObject, vararg keys: String): String {
        for (k in keys) {
            obj.optJSONObject(k)?.let { n ->
                val t = n.optString("type").ifEmpty { n.optString("name") }.ifEmpty { n.optString("title") }.replace("null", "").trim()
                if (t.isNotEmpty() && !t.startsWith("{")) return t
                val deep = n.optJSONObject("name") ?: n.optJSONObject("type") ?: n.optJSONObject("medicine")
                deep?.let { d -> val dt = d.optString("type").ifEmpty { d.optString("name") }.replace("null", "").trim(); if (dt.isNotEmpty()) return dt }
            } ?: run {
                val s = obj.optString(k).replace("null", "").trim()
                if (s.isNotEmpty() && !s.startsWith("{") && !s.all { it.isDigit() }) return s
            }
        }
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next(); val kl = k.lowercase()
            if (kl.contains("unit") || kl.contains("way") || kl.contains("route") || kl.contains("id") || kl in listOf("count", "amount", "quantity")) continue
            obj.optJSONObject(k)?.let { sub ->
                val st = sub.optString("type").ifEmpty { sub.optString("name") }.ifEmpty { sub.optString("title") }.replace("null", "").trim()
                if (st.isNotEmpty() && !st.startsWith("{")) return st
            }
        }
        return ""
    }

    private fun parseServerCardJson(jsonStr: String): Triple<String, String, Pair<Int, JSONObject>>? = try {
        val raw = JSONObject(jsonStr)
        val p = raw.optJSONObject("patient") ?: JSONObject()
        val fio = p.optString("fullName").ifEmpty { raw.optString("initialFullName") }.trim()
        if (fio.isEmpty()) null else {
            val regDateOnly = raw.optString("dateOfRegistration").substringBefore("T")
            val rawBDate = p.optString("birthDate").replace("null", "")
            val ageUnit = raw.optJSONObject("ageType")?.optString("type") ?: ""
            var bDate = ""
            if (rawBDate.contains("-")) {
                val bDateOnly = rawBDate.substringBefore("T")
                if (bDateOnly != regDateOnly || ageUnit in listOf("Дней", "Часов")) {
                    val sp = bDateOnly.split("-"); if (sp.size == 3) bDate = "${sp[2]}.${sp[1]}.${sp[0]} / 00:00"
                }
            }
            fun jArr(k: String) = raw.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { extractName(it, "type", "name", "title") } ?: a.optString(i) }.filter { it.isNotEmpty() && it != "null" && !it.startsWith("{") }.joinToString(", ") } ?: ""
            fun parseMeds(): String {
                val dom = raw.optString("_domMedications").replace("null", "").trim()
                if (dom.isNotEmpty()) return dom
                val arr = raw.optJSONArray("medications") ?: return ""
                return (0 until arr.length()).mapNotNull { i ->
                    val m = arr.optJSONObject(i) ?: return@mapNotNull null
                    val name = extractName(m, "medication", "medicine", "drug", "nomenclature", "preparation", "med", "name", "title")
                    val cnt = listOf("count", "amount", "quantity", "dose", "value").firstNotNullOfOrNull { k -> m.optString(k).replace("null", "").trim().takeIf { it.isNotEmpty() && !it.startsWith("{") } } ?: ""
                    val unit = (m.optJSONObject("unit") ?: m.optJSONObject("measurement"))?.let { it.optString("type").ifEmpty { it.optString("name") }.replace("null", "").trim() } ?: ""
                    val way = (m.optJSONObject("wayOfAdministration") ?: m.optJSONObject("way") ?: m.optJSONObject("route"))?.let { it.optString("type").ifEmpty { it.optString("name") }.replace("null", "").trim() } ?: ""
                    if (name.isEmpty()) null else "$name${listOf(cnt, unit).filter { it.isNotEmpty() }.joinToString(" ").let { if (it.isNotEmpty()) " — $it" else "" }}${if (way.isNotEmpty() && !way.contains("не определен")) " [$way]" else ""}"
                }.joinToString(";\n• ")
            }

            val complaints = raw.optString("complaints").replace("null", "")
            val anamnesis = raw.optString("anamnesis").replace("null", "")
            val diag = raw.optString("diagnosis").replace("null", "")
            val meds = parseMeds()
            val bp = cleanBpString(raw.optString("arterialPressure"))
            val bpAd = cleanBpString(raw.optString("arterialPressureAdaptive"))
            var score = 0; if (anamnesis.length > 10) score += 35; if (complaints.isNotEmpty()) score += 25; if (diag.isNotEmpty()) score += 25; if (meds.isNotEmpty() || bp.isNotEmpty()) score += 15
            val icd = raw.optJSONObject("icd")

            val card = JSONObject().apply {
                put("fio", fio); put("birthDate", bDate); put("age", raw.optString("age").replace("null", "")); put("ageUnit", ageUnit)
                put("address", p.optString("placeOfRegistration").ifEmpty { raw.optString("address") }.replace("null", ""))
                put("apo", p.optJSONObject("territorialMedicalFacility")?.optString("type") ?: "")
                put("callReason", raw.optJSONObject("reasonToCall")?.optString("type") ?: "")
                put("gender", if (p.optBoolean("gender", false)) "МУЖЧИНА" else "ЖЕНЩИНА")
                put("complaints", complaints); put("anamnesis", anamnesis); put("pastDiseases", raw.optString("pastIllnesses").replace("null", ""))
                put("allergy", if (raw.optBoolean("allergy", false)) "ДА" else "НЕТ")
                put("generalState", raw.optJSONObject("generalState")?.optString("type") ?: "")
                put("consciousness", raw.optJSONObject("consciousness")?.optString("type") ?: "")
                put("position", raw.optJSONObject("position")?.optString("type")?.uppercase() ?: "")
                put("behavior", raw.optJSONObject("behavior")?.optString("type")?.uppercase() ?: "")
                put("glasgowScore", raw.optString("comaScale").replace("null", ""))
                put("bp", bp); put("bpAdapted", bpAd)
                put("pulse", raw.optString("pulse").replace("null", "")); put("rr", raw.optString("breathCount").replace("null", ""))
                put("temp", raw.optString("skinTemp").replace("null", "")); put("saturation", raw.optString("saturation1").replace("null", ""))
                put("glucose", raw.optString("glycemia1").replace("null", ""))
                put("heartSounds", jArr("heartSounds")); put("lungs", jArr("breaths")); put("percussion", jArr("percussionOfLungs"))
                put("abdomen", jArr("stomachs")); put("skin", jArr("skins")); put("urination", jArr("urinations").uppercase())
                put("stool", raw.optJSONObject("defecatio")?.optString("type")?.uppercase() ?: "")
                put("edema", if (raw.optBoolean("peripheralEdema", false)) "ДА" else "НЕТ")
                put("rash", if (raw.optBoolean("skinRashes", false)) "ДА" else "НЕТ")
                put("ascites", if (raw.optBoolean("ascites", false)) "ДА" else "НЕТ")
                put("medHelp", jArr("medicalAssistanceProvided")); put("medications", meds)
                put("pupils", raw.optJSONObject("pupils")?.optString("type")?.uppercase() ?: "")
                put("lightReaction", if (raw.optBoolean("pupilsLight", true)) "ДА" else "НЕТ")
                put("speech", raw.optJSONObject("speech")?.optString("type")?.uppercase() ?: "")
                put("gait", raw.optJSONObject("gait")?.optString("type")?.uppercase() ?: "")
                put("face", raw.optJSONObject("face")?.optString("type")?.uppercase() ?: "")
                put("nystagmus", raw.optJSONObject("nystagmus")?.optString("type")?.uppercase() ?: "")
                put("tongue", raw.optString("tongue").replace("null", "")); put("mucous", raw.optString("mucosa").replace("null", ""))
                put("liver", raw.optJSONObject("liver")?.optString("type")?.uppercase() ?: "")
                put("peristalsis", if (raw.optBoolean("peristalsis", true)) "ДА" else "НЕТ")
                put("localStatus", raw.optString("localStatus").replace("null", ""))
                put("ecgBefore", raw.optString("electrocardiogramBefore").replace("null", ""))
                put("ecgAfter", raw.optString("electrocardiogramAfter").replace("null", ""))
                put("diagnosis", diag); put("mkb", if (icd != null) "[${icd.optString("code")}] ${icd.optString("type").ifEmpty { icd.optString("name") }}".trim() else "")
                put("attack", raw.optJSONObject("postAttack")?.optString("type")?.uppercase() ?: "")
                put("therapyEffect", raw.optJSONObject("result")?.optString("type")?.uppercase() ?: "")
                put("callResult", raw.optJSONObject("resultDeparture")?.optString("type")?.uppercase() ?: "")
            }
            Triple(fio, bDate, Pair(score, card))
        }
    } catch (_: Exception) { null }

    inner class WebAppInterface {
        @JavascriptInterface fun openMainMenu() = runOnUiThread { showMainModalMenu() }
        @JavascriptInterface fun openSearchDialog() = runOnUiThread { openSearchSheet(false) }
        @JavascriptInterface fun openBestCardsDialog() = runOnUiThread { openSearchSheet(true) }
        @JavascriptInterface fun openMkbDialog() = runOnUiThread { openMkbSheet() }
        @JavascriptInterface fun openAutofillPicker() = runOnUiThread { showAutofillPickerSheet("") }
        @JavascriptInterface fun notifyUser(msg: String) = runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show() }
        @JavascriptInterface fun requestAutofill(fio: String) = runOnUiThread { showAutofillPickerSheet(fio) }

        @JavascriptInterface
        fun saveApiCardForced(jsonStr: String) = lifecycleScope.launch(Dispatchers.IO) {
            parseServerCardJson(jsonStr)?.let { (fio, bDate, sc) ->
                dbHelper.saveOrUpdateCard(fio, bDate, sc.first, sc.second.toString())
                withContext(Dispatchers.Main) { toast("💾 Карта $fio сохранена в архив!") }
            }
        }
    }
}
