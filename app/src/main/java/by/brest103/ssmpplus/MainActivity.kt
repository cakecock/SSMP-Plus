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
        val bottomSheet = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 30)
            setBackgroundColor(Color.WHITE)
        }

        val pullBar = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(100, 10).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 24
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B0BEC5"))
                cornerRadius = 5f
            }
        }
        root.addView(pullBar)

        val title = TextView(this).apply {
            text = "🔍 Архив вызовов"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#004D40"))
            setPadding(0, 0, 0, 16)
        }
        root.addView(title)

        val searchInput = EditText(this).apply {
            hint = "Поиск по фамилии..."
            setPadding(35, 28, 35, 28)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0F4F4"))
                cornerRadius = 24f
            }
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
        }
        root.addView(searchInput)

        val listView = ListView(this).apply {
            divider = null
            dividerHeight = 16
            setPadding(0, 20, 0, 0)
        }
        root.addView(listView)

        bottomSheet.setContentView(root)
        bottomSheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheet.behavior.peekHeight = 1200

        val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

        fun performSearch(query: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                val summaries = dbHelper.searchPatientSummaries(query)
                withContext(Dispatchers.Main) {
                    val titles = summaries.map {
                        val date = sdf.format(Date(it.lastTimestamp))
                        val countText = if (it.callCount > 1) " [${it.callCount} выз.]" else ""
                        val reasonText = if (it.lastReason.isNotEmpty()) "\n• ${it.lastReason}" else ""
                        "👤 ${it.fio.uppercase()}$countText\nПосл. выезд: $date$reasonText"
                    }
                    val adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_list_item_1, titles) {
                        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                            val view = super.getView(position, convertView, parent) as TextView
                            view.setPadding(30, 24, 30, 24)
                            view.setTextColor(Color.parseColor("#263238"))
                            view.background = GradientDrawable().apply {
                                setColor(Color.parseColor("#F7FAFA"))
                                setStroke(2, Color.parseColor("#E0EAEA"))
                                cornerRadius = 18f
                            }
                            return view
                        }
                    }
                    listView.adapter = adapter
                    listView.setOnItemClickListener { _, _, position, _ ->
                        val summary = summaries[position]
                        bottomSheet.dismiss()
                        showPatientHistoryDialog(summary.fio)
                    }
                }
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { performSearch(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })

        bottomSheet.show()
        performSearch("")
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

                val headerLayout = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, 12)
                }

                val titleView = TextView(this@MainActivity).apply {
                    text = fio.uppercase()
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#004D40"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                headerLayout.addView(titleView)

                val deleteBtn = ImageButton(this@MainActivity).apply {
                    setImageResource(android.R.drawable.ic_menu_delete)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#FFEBEE"))
                        cornerRadius = 24f
                    }
                    setPadding(16, 16, 16, 16)
                }
                headerLayout.addView(deleteBtn)
                root.addView(headerLayout)

                val spinner = Spinner(this@MainActivity).apply {
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#E0F2F1"))
                        cornerRadius = 16f
                    }
                    setPadding(24, 16, 24, 16)
                }
                root.addView(spinner)

                val scrollView = ScrollView(this@MainActivity).apply {
                    isFillViewport = true
                    setPadding(0, 16, 0, 10)
                }

                val contentContainer = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                scrollView.addView(contentContainer)
                root.addView(scrollView)

                fun updateSpinner() {
                    val spinnerTitles = history.mapIndexed { idx, item ->
                        val date = sdf.format(Date(item.timestamp))
                        val json = try { JSONObject(item.jsonData) } catch (e: Exception) { JSONObject() }
                        val diag = json.optString("diagnosis").ifEmpty { json.optString("callReason") }
                        val shortDiag = if (diag.length > 22) diag.take(22) + "..." else diag
                        "Вызов #${history.size - idx} ($date) $shortDiag"
                    }
                    spinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, spinnerTitles)
                }

                fun renderCardView(item: CardHistoryItem) {
                    contentContainer.removeAllViews()
                    val json = try { JSONObject(item.jsonData) } catch (e: Exception) { JSONObject() }

                    if (item.isBuiltin) {
                        deleteBtn.visibility = View.GONE
                    } else {
                        deleteBtn.visibility = View.VISIBLE
                        deleteBtn.setOnClickListener {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Удаление вызова")
                                .setMessage("Удалить этот вызов из архива?")
                                .setPositiveButton("Удалить") { _, _ ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val ok = dbHelper.deleteCard(item.id)
                                        withContext(Dispatchers.Main) {
                                            if (ok) {
                                                history.remove(item)
                                                Toast.makeText(this@MainActivity, "Вызов удален", Toast.LENGTH_SHORT).show()
                                                if (history.isEmpty()) {
                                                    bottomSheet.dismiss()
                                                } else {
                                                    updateSpinner()
                                                    renderCardView(history[0])
                                                }
                                            }
                                        }
                                    }
                                }
                                .setNegativeButton("Отмена", null)
                                .show()
                        }
                    }

                    val badgesLayout = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 12, 0, 12)
                    }

                    fun addBadge(text: String, bgColor: String) {
                        val badge = TextView(this@MainActivity).apply {
                            this.text = text
                            textSize = 12f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.WHITE)
                            setPadding(20, 8, 20, 8)
                            background = GradientDrawable().apply {
                                setColor(Color.parseColor(bgColor))
                                cornerRadius = 20f
                            }
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { rightMargin = 12 }
                        }
                        badgesLayout.addView(badge)
                    }

                    val bp = json.optString("bp").ifEmpty { json.optString("bpAdapted") }
                    if (bp.isNotEmpty()) addBadge("❤️ $bp", "#E53935")
                    if (json.optString("pulse").isNotEmpty()) addBadge("💓 ${json.optString("pulse")}", "#D81B60")
                    if (json.optString("saturation").isNotEmpty()) addBadge("🫁 ${json.optString("saturation")}%", "#00897B")
                    if (json.optString("glucose").isNotEmpty()) addBadge("🩸 ${json.optString("glucose")}", "#8E24AA")
                    if (json.optString("glasgowScore").isNotEmpty()) addBadge("🧠 ${json.optString("glasgowScore")} б", "#3949AB")

                    val hScroll = HorizontalScrollView(this@MainActivity).apply {
                        isHorizontalScrollBarEnabled = false
                    }
                    hScroll.addView(badgesLayout)
                    contentContainer.addView(hScroll)

                    val detailsText = TextView(this@MainActivity).apply {
                        textSize = 15f
                        setTextIsSelectable(true)
                        setTextColor(Color.parseColor("#37474F"))
                        setLineSpacing(6f, 1.15f)
                        text = formatFullCard(item.jsonData)
                        setPadding(10, 10, 10, 30)
                    }
                    contentContainer.addView(detailsText)
                }

                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        renderCardView(history[position])
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }

                updateSpinner()
                renderCardView(history[0])

                bottomSheet.setContentView(root)
                bottomSheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
                bottomSheet.behavior.peekHeight = 1400
                bottomSheet.show()
            }
        }
    }

    private fun formatFullCard(jsonData: String): String {
        return try {
            val json = JSONObject(jsonData)
            StringBuilder().apply {
                if (json.optString("birthDate").isNotEmpty()) append("🎂 Родился: ${json.optString("birthDate")}\n")
                if (json.optString("gender").isNotEmpty()) append("⚧ Пол: ${json.optString("gender")}\n")
                if (json.optString("address").isNotEmpty()) append("📍 Адрес: ${json.optString("address")}\n")
                if (json.optString("callReason").isNotEmpty()) append("🚨 Повод: ${json.optString("callReason")}\n")

                append("\n📋 ДИАГНОЗ:\n")
                if (json.optString("diagnosis").isNotEmpty()) append("• ${json.optString("diagnosis")}\n")
                if (json.optString("mkb").isNotEmpty()) append("• Код МКБ: ${json.optString("mkb")}\n")

                append("\n🩺 ОБЪЕКТИВНЫЙ СТАТУС:\n")
                if (json.optString("generalState").isNotEmpty()) append("• Состояние: ${json.optString("generalState")}\n")
                if (json.optString("consciousness").isNotEmpty()) append("• Сознание: ${json.optString("consciousness")}\n")

                val gScore = json.optString("glasgowScore")
                val gTest = json.optString("glasgowTest")
                if (gScore.isNotEmpty() || gTest.isNotEmpty()) {
                    append("• Шкала Глазго: ${if (gScore.isNotEmpty()) "$gScore баллов" else ""} ${if (gTest.isNotEmpty()) "($gTest)" else ""}\n")
                }

                if (json.optString("bpAdapted").isNotEmpty()) append("• Привычное АД: ${json.optString("bpAdapted")}\n")
                if (json.optString("bp").isNotEmpty()) append("• АД на вызове: ${json.optString("bp")}\n")
                if (json.optString("pulse").isNotEmpty()) a
