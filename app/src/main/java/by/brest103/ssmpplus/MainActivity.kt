package by.brest103.ssmpplus

import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.*
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.*
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.webkit.*
import com.google.android.material.bottomsheet.*
import kotlinx.coroutines.*
import org.json.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

data class MkbCodeItem(val code: String, val name: String, val letter: String)

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var dbHelper: DatabaseHelper
    private var backPressedTime: Long = 0
    private var mkbLoadJob: Job? = null

    @Volatile
    private var fullMkbList: List<MkbCodeItem> = emptyList()

    private val targetHost = "soft.103brest.by"
    private val prefsName = "ssmp_plus_prefs"
    private val keyMedCatalog = "med_catalog_json"
    private val keySiteMkb = "site_mkb_json"

    @Volatile
    private var siteMkbList: List<MkbCodeItem> = emptyList()

    private var activeMedSearchReqId: String = ""
    private var onMedSearchCallback: ((List<Pair<String, String>>) -> Unit)? = null
    private var onMedCatalogUpdated: ((String) -> Unit)? = null
    private var activeMkbSearchReqId: String = ""
    private var onMkbSearchCallback: ((List<MkbCodeItem>) -> Unit)? = null
    private var pendingApkInstallFile: File? = null

    // --- Камера / загрузка изображений из WebView ---
    private var pendingFilePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var pendingAfterCameraPermission: (() -> Unit)? = null
    private var cameraPhotoUri: Uri? = null
    private var cameraPhotoFile: File? = null

    private fun deliverFileChooserResult(uris: Array<Uri>?) {
        pendingFilePathCallback?.onReceiveValue(uris)
        pendingFilePathCallback = null
        cameraPhotoUri = null
        cameraPhotoFile = null
    }

    private fun cancelFileChooser() = deliverFileChooserResult(null)

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingAfterCameraPermission
        pendingAfterCameraPermission = null
        if (granted) {
            action?.invoke()
        } else {
            toast("Разрешение на камеру не получено")
            pendingWebPermissionRequest?.deny()
            pendingWebPermissionRequest = null
            cancelFileChooser()
        }
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val file = cameraPhotoFile
            val uri = if (file != null && file.exists() && file.length() > 0) {
                try {
                    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                } catch (_: Exception) {
                    cameraPhotoUri
                }
            } else {
                result.data?.data ?: cameraPhotoUri
            }
            if (uri != null) deliverFileChooserResult(arrayOf(uri)) else cancelFileChooser()
        } else {
            cancelFileChooser()
        }
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uris = mutableListOf<Uri>()
            result.data?.let { data ->
                data.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
            }
            if (uris.isNotEmpty()) deliverFileChooserResult(uris.toTypedArray()) else cancelFileChooser()
        } else {
            cancelFileChooser()
        }
    }

    private fun launchCameraCapture() {
        try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            cameraPhotoFile = file
            cameraPhotoUri = uri

            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                clipData = ClipData.newUri(contentResolver, "photo", uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (intent.resolveActivity(packageManager) == null) {
                toast("Нет приложения камеры")
                cancelFileChooser()
                return
            }
            cameraLauncher.launch(intent)
        } catch (e: Exception) {
            toast("Не удалось открыть камеру: ${e.message}")
            cancelFileChooser()
        }
    }

    private fun showImageChooser(params: WebChromeClient.FileChooserParams?) {
        val items = arrayOf("📷 Сделать фото", "🖼 Выбрать из галереи")
        AlertDialog.Builder(this)
            .setTitle("Загрузка изображения")
            .setItems(items) { _, which ->
                if (which == 0) {
                    // ACTION_IMAGE_CAPTURE не требует разрешения CAMERA нашему приложению:
                    // камера получает content:// Uri через EXTRA_OUTPUT + ClipData.
                    launchCameraCapture()
                } else {
                    val mime = params?.acceptTypes?.firstOrNull()
                        ?.takeIf { it.startsWith("image/") } ?: "image/*"
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = mime
                        addCategory(Intent.CATEGORY_OPENABLE)
                        putExtra(
                            Intent.EXTRA_ALLOW_MULTIPLE,
                            params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                        )
                    }
                    galleryLauncher.launch(Intent.createChooser(intent, "Выбрать изображение"))
                }
            }
            .setOnCancelListener { cancelFileChooser() }
            .show()
    }

    private val installPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val f = pendingApkInstallFile
        pendingApkInstallFile = null
        if (f != null && f.exists()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
                launchApkInstaller(f)
            } else {
                toast("Разрешите установку приложений для завершения обновления")
            }
        }
    }

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
                    withContext(Dispatchers.Main) { toast("📥 Импортировано новых записей: $count") }
                } catch (e: Exception) { withContext(Dispatchers.Main) { toast("Ошибка импорта файла") } }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun roundBg(hex: String, r: Float, strokeHex: String? = null) = GradientDrawable().apply {
        setColor(Color.parseColor(hex)); cornerRadius = r; strokeHex?.let { setStroke(2, Color.parseColor(it)) }
    }

    private fun saveCachedMedCatalog(jsonStr: String) {
        try {
            getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString(keyMedCatalog, jsonStr).apply()
        } catch (_: Exception) {}
    }

    private fun parseMedCatalogJson(raw: String): Pair<List<Pair<String, String>>, List<String>> {
        val meds = mutableListOf<Pair<String, String>>()
        val routes = mutableListOf<String>()
        try {
            val o = JSONObject(raw)
            val ma = o.optJSONArray("meds") ?: JSONArray()
            for (i in 0 until ma.length()) {
                val it = ma.optJSONObject(i) ?: continue
                val n = it.optString("n").trim()
                val u = it.optString("u").trim()
                if (n.isNotEmpty()) meds.add(n to u)
            }
            val ra = o.optJSONArray("routes") ?: JSONArray()
            for (i in 0 until ra.length()) {
                val r = ra.optString(i).trim()
                if (r.isNotEmpty()) routes.add(r)
            }
        } catch (_: Exception) {}
        return meds to routes
    }

    private fun parseMedItemsArray(raw: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val n = it.optString("n").trim()
                val u = it.optString("u").trim()
                if (n.isNotEmpty()) out.add(n to u)
            }
        } catch (_: Exception) {}
        return out
    }

    private fun loadCachedMedCatalog(): Pair<List<Pair<String, String>>, List<String>> {
        val raw = getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(keyMedCatalog, null)
            ?: return emptyList<Pair<String, String>>() to emptyList()
        return parseMedCatalogJson(raw)
    }

    private fun parseMkbItemsArray(raw: String): List<MkbCodeItem> {
        val out = mutableListOf<MkbCodeItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val c = it.optString("c").trim().uppercase()
                val n = it.optString("n").trim()
                if (c.length >= 3) out.add(MkbCodeItem(c, n, c[0].toString()))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun loadCachedSiteMkb() {
        val raw = getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(keySiteMkb, null) ?: return
        val parsed = parseMkbItemsArray(raw)
        if (parsed.isNotEmpty()) siteMkbList = parsed
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dbHelper = DatabaseHelper(this)
        webView = findViewById(R.id.webView)
        loadCachedSiteMkb()
        mkbLoadJob = loadFullMkbAsync()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else if (System.currentTimeMillis() - backPressedTime < 2000) finish()
                else { backPressedTime = System.currentTimeMillis(); toast("Нажмите назад ещё раз для выхода") }
            }
        })

        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(webView, true) }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
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

            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                cancelFileChooser()
                pendingFilePathCallback = filePathCallback
                if (fileChooserParams?.isCaptureEnabled == true) {
                    launchCameraCapture()
                } else {
                    showImageChooser(fileChooserParams)
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                val resources = request?.resources ?: return
                val video = PermissionRequest.RESOURCE_VIDEO_CAPTURE
                val onlyVideo = resources.isNotEmpty() && resources.all { it == video }
                if (!onlyVideo) {
                    request.deny()
                    return
                }
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        android.Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    request.grant(resources)
                } else {
                    pendingWebPermissionRequest = request
                    pendingAfterCameraPermission = {
                        pendingWebPermissionRequest?.grant(resources)
                        pendingWebPermissionRequest = null
                    }
                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: SslError?) {
                val reqHost = try { Uri.parse(e?.url ?: "").host?.lowercase() } catch (_: Exception) { null }
                val primary = e?.primaryError ?: SslError.SSL_INVALID
                // Исключение делаем только для своего домена и только для «неизвестного CSP»,
                // чтобы не ломать сайт из-за неполной цепочки сертификатов.
                // Истёкший / чужой / неверный сертификат — жёстко блокируем.
                val canProceed = reqHost == targetHost && primary == SslError.SSL_UNTRUSTED
                if (canProceed) h?.proceed() else h?.cancel()
            }

            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                if (r?.isForMainFrame != true) return false
                val url = r.url ?: return false
                val host = url.host?.lowercase() ?: ""
                return if (host == targetHost || host.endsWith(".103brest.by")) {
                    false
                } else {
                    try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: Exception) {}
                    true
                }
            }

            override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) {
                super.onPageStarted(v, u, f)
                v?.evaluateJavascript(polyfill, null)
            }
            override fun onPageFinished(v: WebView?, u: String?) {
                super.onPageFinished(v, u)
                injectScript()
            }
        }
        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")
        webView.loadUrl("https://soft.103brest.by/ssmp/")
    }

    override fun onPause() {
        super.onPause()
        if (::webView.isInitialized) webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
            // Принудительно перерисовываем WebView после снятия блокировки экрана,
            // иначе иногда остаётся чёрный surface.
            webView.post {
                webView.invalidate()
                webView.requestLayout()
                webView.evaluateJavascript("window.dispatchEvent(new Event('resize'));", null)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::webView.isInitialized) {
            webView.post {
                webView.invalidate()
                webView.requestLayout()
            }
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            // Не оставляем WebView «висеть» после закрытия приложения:
            // это приводило к утечке surface и чёрному экрану при повторном открытии.
            cancelFileChooser()
            pendingWebPermissionRequest?.deny()
            pendingWebPermissionRequest = null
            webView.stopLoading()
            webView.removeAllViews()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        try { CookieManager.getInstance().flush() } catch (_: Exception) {}
        if (::dbHelper.isInitialized) {
            dbHelper.close()
        }
        super.onDestroy()
    }

    private fun loadFullMkbAsync(): Job = lifecycleScope.launch(Dispatchers.IO) {
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

    @Suppress("DEPRECATION")
    private fun createSheet(title: String, titleColor: String, fixedHeightRatio: Float? = null): Pair<BottomSheetDialog, LinearLayout> {
        val bs = BottomSheetDialog(this)
        bs.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 18, 36, 24); setBackgroundColor(Color.WHITE) }
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) }
            background = roundBg("#B0BEC5", 4f)
        })
        root.addView(TextView(this).apply {
            text = title; textSize = 18.5f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(titleColor)); setPadding(0, 0, 0, 12)
        })
        if (fixedHeightRatio != null) {
            val h = (resources.displayMetrics.heightPixels * fixedHeightRatio).toInt()
            bs.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
        } else {
            bs.setContentView(root)
        }
        bs.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            peekHeight = (resources.displayMetrics.heightPixels * 0.85).toInt()
        }
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

    private fun showMenuDialog(title: String, scrollable: Boolean = false, builders: (android.app.Dialog, LinearLayout) -> Unit) {
        val dialog = android.app.Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE); window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) }
        val cardWidth = (resources.displayMetrics.widthPixels * 0.88).toInt().coerceAtMost(dp(420))
        val cardHeight = if (scrollable) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(44, 36, 44, 40); background = roundBg("#FFFFFF", 18f); layoutParams = ViewGroup.LayoutParams(cardWidth, cardHeight) }
        val hdr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 20) }
        hdr.addView(TextView(this).apply { text = title; textSize = 19.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#212121")); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        hdr.addView(TextView(this).apply { text = "✕"; textSize = 20f; setTextColor(Color.GRAY); setPadding(16, 4, 8, 4); setOnClickListener { dialog.dismiss() } })
        card.addView(hdr)
        builders(dialog, card)
        dialog.setContentView(card)
        dialog.show()
    }

    private fun addDialogBtn(dialog: android.app.Dialog, parent: LinearLayout, title: String, color: String = "#008577", onClick: () -> Unit) {
        parent.addView(TextView(this).apply {
            text = title; textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(22, 24, 22, 24); background = roundBg(color, 10f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 14 }
            setOnClickListener { dialog.dismiss(); onClick() }
        })
    }

    // ГЛАВНОЕ МЕНЮ
    private fun showMainModalMenu() = showMenuDialog("Меню SSMP Plus") { d, card ->
        addDialogBtn(d, card, "⭐ ОБРАЗЦЫ КАРТ") { openSearchSheet(true) }
        addDialogBtn(d, card, "💊 ШАБЛОНЫ ПРЕПАРАТОВ", "#6A1B9A") { openMedPresetsSheet() }
        addDialogBtn(d, card, "📂 ОБЩИЙ АРХИВ ВЫЗОВОВ") { openSearchSheet(false) }
        addDialogBtn(d, card, "🧰 ПОМОЩНИК СМП", "#0277BD") { showHelperSubMenu() }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
        fun half(t: String, mr: Int, act: () -> Unit) = TextView(this).apply {
            text = t; textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 1
            setPadding(12, 26, 12, 26); background = roundBg("#00695C", 10f)
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = mr }
            setOnClickListener { d.dismiss(); act() }
        }
        row.addView(half("📥 ИМПОРТ", 14) { importDbLauncher.launch(arrayOf("*/*")) })
        row.addView(half("📤 ЭКСПОРТ", 0) { exportDbLauncher.launch("ssmp_archive_${SimpleDateFormat("dd_MM_yyyy", Locale.getDefault()).format(Date())}.json") })
        card.addView(row)
        card.addView(TextView(this).apply {
            text = "🔄 ПРОВЕРИТЬ ОБНОВЛЕНИЕ APK (GITHUB)"
            textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(18, 22, 18, 22); background = roundBg("#1565C0", 10f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14 }
            setOnClickListener { d.dismiss(); checkGithubAppUpdate() }
        })
    }

    private fun isRemoteVersionNewer(remoteTag: String, localVerName: String): Boolean {
        fun parseParts(v: String): List<Int> =
            v.trim().removePrefix("v").removePrefix("V")
                .substringBefore("-")
                .split(".")
                .map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        val r = parseParts(remoteTag)
        val l = parseParts(localVerName)
        val len = maxOf(r.size, l.size)
        for (i in 0 until len) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun fetchGithubJson(urlStr: String, userAgent: String): Pair<Int, String> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", userAgent)
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to body
        } finally {
            conn.disconnect()
        }
    }

    private fun checkGithubAppUpdate() {
        val pInfo = try { packageManager.getPackageInfo(packageName, 0) } catch (_: Exception) { null }
        val localVerName = pInfo?.versionName ?: "3.10.0"
        val ua = "SSMP-Plus-Android/$localVerName"
        toast("🔄 Проверка обновлений...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var (code, body) = fetchGithubJson("https://api.github.com/repos/cakecock/SSMP-Plus/releases/latest", ua)
                var rel: JSONObject? = if (code in 200..299 && body.isNotBlank()) JSONObject(body) else null
                if (rel == null) {
                    val (c2, b2) = fetchGithubJson("https://api.github.com/repos/cakecock/SSMP-Plus/releases?per_page=1", ua)
                    code = c2
                    if (c2 in 200..299 && b2.isNotBlank()) {
                        val arr = JSONArray(b2)
                        if (arr.length() > 0) rel = arr.optJSONObject(0)
                    }
                }
                if (rel == null) {
                    withContext(Dispatchers.Main) { toast("Не удалось проверить обновления (HTTP $code)") }
                    return@launch
                }

                val tagName = rel.optString("tag_name").trim()
                val relTitle = rel.optString("name").ifEmpty { tagName }
                val notes = rel.optString("body").trim()

                var apkUrl = ""
                var apkName = ""
                var apkSizeMb = ""
                val assets = rel.optJSONArray("assets") ?: JSONArray()
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val nm = a.optString("name")
                    if (nm.endsWith(".apk", ignoreCase = true)) {
                        apkName = nm
                        apkUrl = a.optString("browser_download_url")
                        val sz = a.optLong("size", 0L)
                        if (sz > 0L) apkSizeMb = String.format(Locale.US, "%.1f МБ", sz / (1024.0 * 1024.0))
                        break
                    }
                }

                val hasUpdate = tagName.isNotEmpty() && isRemoteVersionNewer(tagName, localVerName)
                if (!hasUpdate) {
                    withContext(Dispatchers.Main) { toast("Обновлений нет") }
                    return@launch
                }

                if (apkUrl.isEmpty()) {
                    withContext(Dispatchers.Main) { toast("Найдена версия $tagName, но APK-файл ещё собирается") }
                    return@launch
                }

                val msg = buildString {
                    append("Текущая версия: v$localVerName\n")
                    append("Новая версия: ${relTitle.ifEmpty { tagName }}\n")
                    if (apkName.isNotEmpty()) {
                        append("Файл: $apkName${if (apkSizeMb.isNotEmpty()) " ($apkSizeMb)" else ""}\n")
                    }
                    if (notes.isNotEmpty()) {
                        append("\nЧто нового:\n${notes.take(600)}")
                    }
                }

                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("🆕 Доступно обновление $tagName")
                        .setMessage(msg)
                        .setPositiveButton("⬇️ Скачать и установить") { _, _ ->
                            downloadAndInstallApk(apkUrl, apkName.ifEmpty { "SSMP-Plus-$tagName.apk" })
                        }
                        .setNegativeButton("Позже", null)
                        .show()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    toast("Не удалось проверить обновления (нет связи)")
                }
            }
        }
    }

    private fun openConnectionFollowingRedirects(startUrl: String): HttpURLConnection {
        var currentUrl = startUrl
        repeat(6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "SSMP-Plus-Android")
                setRequestProperty("Accept", "application/octet-stream, */*")
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IllegalStateException("Empty redirect")
                currentUrl = URL(URL(currentUrl), loc).toString()
            } else {
                return conn
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    private fun downloadAndInstallApk(apkUrl: String, apkName: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(52, 36, 52, 24)
        }
        val statusTv = TextView(this).apply {
            text = "⏳ Подключение к серверу..."
            textSize = 14f
            setTextColor(Color.parseColor("#263238"))
            setPadding(0, 0, 0, 18)
        }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
        }
        box.addView(statusTv)
        box.addView(progressBar)

        var downloadJob: Job? = null
        val dlg = AlertDialog.Builder(this)
            .setTitle("⬇️ Загрузка обновления")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Отмена") { _, _ ->
                downloadJob?.cancel()
                toast("Загрузка отменена")
            }
            .create()
        dlg.show()

        downloadJob = lifecycleScope.launch(Dispatchers.IO) {
            val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
            updatesDir.listFiles()?.forEach { old -> if (old.isFile) old.delete() }
            val safeName = apkName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val outFile = File(updatesDir, safeName)
            var conn: HttpURLConnection? = null
            try {
                conn = openConnectionFollowingRedirects(apkUrl)
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code")
                }
                val totalBytes = conn.contentLengthLong
                withContext(Dispatchers.Main) {
                    if (totalBytes > 0L) {
                        progressBar.isIndeterminate = false
                        progressBar.progress = 0
                    }
                }

                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buf = ByteArray(16 * 1024)
                        var downloaded = 0L
                        var lastUiMs = 0L
                        while (true) {
                            ensureActive()
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastUiMs > 120L) {
                                lastUiMs = now
                                val dlMb = String.format(Locale.US, "%.1f", downloaded / (1024.0 * 1024.0))
                                val pct = if (totalBytes > 0L) ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100) else -1
                                val totMb = if (totalBytes > 0L) String.format(Locale.US, "%.1f", totalBytes / (1024.0 * 1024.0)) else "?"
                                withContext(Dispatchers.Main) {
                                    if (dlg.isShowing) {
                                        if (pct >= 0) {
                                            progressBar.isIndeterminate = false
                                            progressBar.progress = pct
                                            statusTv.text = "⬇️ Загружено $dlMb / $totMb МБ ($pct%)"
                                        } else {
                                            statusTv.text = "⬇️ Загружено $dlMb МБ..."
                                        }
                                    }
                                }
                            }
                        }
                        output.flush()
                    }
                }

                withContext(Dispatchers.Main) {
                    if (dlg.isShowing) dlg.dismiss()
                    launchApkInstaller(outFile)
                }
            } catch (_: CancellationException) {
                outFile.delete()
            } catch (e: Exception) {
                outFile.delete()
                withContext(Dispatchers.Main) {
                    if (dlg.isShowing) dlg.dismiss()
                    toast("Ошибка загрузки APK: ${e.message ?: "сбой сети"}")
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun launchApkInstaller(apkFile: File) {
        if (!apkFile.exists()) {
            toast("Файл обновления не найден")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            pendingApkInstallFile = apkFile
            AlertDialog.Builder(this)
                .setTitle("Разрешение на установку")
                .setMessage("Разрешите установку приложений для «SSMP Plus» в настройках, затем вернитесь назад — установка продолжится автоматически.")
                .setPositiveButton("Открыть настройки") { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                    installPermissionLauncher.launch(intent)
                }
                .setNegativeButton("Отмена") { _, _ -> pendingApkInstallFile = null }
                .show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
        } catch (e: Exception) {
            toast("Не удалось запустить установку: ${e.message ?: "ошибка"}")
        }
    }

    // ПОДМЕНЮ: ПОМОЩНИК СМП (13 МОДУЛЕЙ)
    private fun showHelperSubMenu() = showMenuDialog("Помощник СМП", scrollable = true) { d, card ->
        val scroll = NestedScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val listLay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scroll.addView(listLay)
        card.addView(scroll)

        addDialogBtn(d, listLay, "🩺 АУДИТ ОТКРЫТОЙ КАРТЫ (ПРОВЕРКА)", "#1565C0") { runCardAudit() }
        addDialogBtn(d, listLay, "📈 КОНСТРУКТОР ЭКГ (В КАРТУ)", "#00897B") { SmartAssistants.showEcgConstructorSheet(this, webView) }
        addDialogBtn(d, listLay, "⏱️ ТАЙМЕР И ПРОТОКОЛ СЛР", "#D32F2F") { SmartAssistants.showCprTimerSheet(this, webView) }
        addDialogBtn(d, listLay, "📝 ФРАЗЫ: ОТКАЗЫ И ПРИМЕЧАНИЯ", "#5D4037") { SmartAssistants.showQuickPhrasesSheet(this, webView) }
        addDialogBtn(d, listLay, "📖 СПРАВОЧНИК МКБ-10", "#008577") { openMkbSheet() }
        addDialogBtn(d, listLay, "👶 ПЕДИАТРИЯ: НОРМЫ, ДОЗЫ, ИВЛ", "#0277BD") { MedicalTools.showPediatricsSheet(this) }
        addDialogBtn(d, listLay, "🧠 ШКАЛЫ И ИНДЕКСЫ СМП", "#5E35B1") { MedicalTools.showCalculatorsSheet(this) }
        addDialogBtn(d, listLay, "📋 ШПАРГАЛКИ И РАСЧЕТЫ (O2 МПа)", "#EF6C00") { MedicalTools.showCheatSheetsSheet(this) }
        addDialogBtn(d, listLay, "🤰 АКУШЕРСТВО И ШКАЛА АПГАР", "#D81B60") { MedicalTools.showObstetricsSheet(this) }
        addDialogBtn(d, listLay, "🧪 ЯДЫ И АНТИДОТЫ", "#C62828") { MedicalTools.showAntidotesSheet(this) }
        addDialogBtn(d, listLay, "💊 ЛЕКАРСТВА И ОФОРМЛЕНИЕ КАРТ", "#00695C") { MedicalReference.showDrugsAndCardGuideSheet(this) }
        addDialogBtn(d, listLay, "📜 ТЛТ И ПРИКАЗЫ", "#37474F") { MedicalReference.showOrdersAndProtocolsSheet(this) }
        addDialogBtn(d, listLay, "📚 КЛИНИЧЕСКИЕ ПРОТОКОЛЫ МЗ РБ (PDF)", "#1565C0") { MzrbCatalog.showSheet(this) }
    }

    // ==========================================
    // ОТДЕЛЬНОЕ МЕНЮ: ШАБЛОНЫ ПРЕПАРАТОВ И РАСХОДНИКОВ
    // ==========================================
    private fun openMedPresetsSheet() {
        val (bs, root) = createSheet("💊 Шаблоны препаратов", "#6A1B9A")

        root.addView(TextView(this).apply {
            text = "➕ СОЗДАТЬ НАБОР ПРЕПАРАТОВ"
            textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#6A1B9A")); gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16); background = roundBg("#F3E5F5", 14f, "#8E24AA")
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
            setOnClickListener {
                bs.dismiss()
                showCreateMedPresetDialog(null) { openMedPresetsSheet() }
            }
        })

        val qInp = EditText(this).apply {
            hint = "Поиск набора или препарата (перчатки, рана, ГК)..."
            setPadding(30, 22, 30, 22)
            MedicalTools.styleEditText(this, "#F3E5F5", "#CE93D8", 18f)
        }
        root.addView(qInp)
        val lv = makeListView(root)

        fun run(q: String) = lifecycleScope.launch(Dispatchers.IO) {
            val list = dbHelper.searchMedPresets(q)
            withContext(Dispatchers.Main) {
                if (list.isEmpty()) {
                    setupAdapter(
                        lv,
                        listOf("📭 Своих наборов пока нет.\nНажмите «➕ СОЗДАТЬ НАБОР ПРЕПАРАТОВ» — можно подтянуть позиции из открытой карты или выбрать из справочника сайта."),
                        "#FAFAFA",
                        "#E0E0E0"
                    ) {}
                } else {
                    setupAdapter(
                        lv,
                        list.map { "${if (it.isCustom) "📌" else "⭐"} ${it.title}\n💊 ${it.itemsSummary.ifEmpty { "Пустой набор" }}" },
                        "#FAF5FF",
                        "#E1BEE7"
                    ) { pos ->
                        bs.dismiss()
                        showMedPresetPreview(list[pos])
                    }
                }
            }
        }
        qInp.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { run(s.toString()) }
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })
        bs.show()
        run("")
    }

    private fun showMedPresetPreview(preset: MedPresetSample) {
        val (bs, root) = createSheet("${if (preset.isCustom) "📌" else "⭐"} ${preset.title}", "#6A1B9A")
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 12) }

        bar.addView(TextView(this).apply {
            text = "💊 Вставить в карту"; textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(24, 16, 24, 16); background = roundBg("#6A1B9A", 16f)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = if (preset.isCustom) 8 else 0 }
            setOnClickListener {
                bs.dismiss()
                toast("⏳ Добавляю препараты в карту...")
                webView.evaluateJavascript("window.applyMedicationsPreset && window.applyMedicationsPreset(${JSONObject.quote(preset.jsonData)});", null)
            }
        })

        if (preset.isCustom) {
            bar.addView(TextView(this).apply {
                text = "✏️"; textSize = 17f; gravity = Gravity.CENTER
                setPadding(20, 14, 20, 14); background = roundBg("#FFF3E0", 16f, "#FFB74D")
                layoutParams = LinearLayout.LayoutParams(-2, -1).apply { rightMargin = 8 }
                setOnClickListener {
                    bs.dismiss()
                    showCreateMedPresetDialog(
                        prefillJson = preset.jsonData,
                        editingId = preset.id,
                        initialTitle = preset.title
                    ) { openMedPresetsSheet() }
                }
            })
            bar.addView(ImageButton(this).apply {
                contentDescription = "Удалить набор"
                setImageResource(android.R.drawable.ic_menu_delete); background = roundBg("#FFEBEE", 16f); setPadding(18, 16, 18, 16)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle("Удалить набор препаратов?").setPositiveButton("Удалить") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            if (dbHelper.deleteMedPreset(preset.id)) {
                                withContext(Dispatchers.Main) { toast("Удалено"); bs.dismiss(); openMedPresetsSheet() }
                            }
                        }
                    }.setNegativeButton("Отмена", null).show()
                }
            })
        }
        root.addView(bar)

        val formatted = try {
            val arr = JSONObject(preset.jsonData).optJSONArray("items") ?: JSONArray()
            if (arr.length() == 0) "Список препаратов пуст." else (0 until arr.length()).joinToString("\n\n") { i ->
                val o = arr.getJSONObject(i)
                val nm = o.optString("name")
                val qt = o.optString("qty").ifEmpty { "1" }
                val rt = o.optString("route")
                val nt = o.optString("note")
                buildString {
                    append("${i + 1}. $nm\n   • Количество: $qt")
                    if (rt.isNotEmpty() && !rt.contains("не определен")) append(" | Путь: $rt")
                    if (nt.isNotEmpty()) append("\n   • Примечание: $nt")
                }
            }
        } catch (_: Exception) { "Ошибка чтения набора" }

        val sv = NestedScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); isFillViewport = true; isNestedScrollingEnabled = true }
        sv.addView(TextView(this).apply { textSize = 14.5f; setTextIsSelectable(true); setTextColor(Color.parseColor("#263238")); setLineSpacing(6f, 1.15f); text = formatted })
        root.addView(sv)
        bs.show()
    }

    private fun showCreateMedPresetDialog(
        prefillJson: String? = null,
        editingId: Long? = null,
        initialTitle: String = "",
        onSaved: (() -> Unit)? = null
    ) {
        val isEdit = editingId != null
        val (bs, root) = createSheet(
            title = if (isEdit) "✏️ Редактировать набор препаратов" else "💊 Новый набор препаратов",
            titleColor = "#6A1B9A",
            fixedHeightRatio = 0.88f
        )

        val items = mutableListOf<JSONObject>()
        if (prefillJson != null) {
            try {
                val arr = JSONObject(prefillJson).optJSONArray("items") ?: JSONArray()
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items.add(it) }
            } catch (_: Exception) {}
        }

        var (catalogMeds, catalogRoutes) = loadCachedMedCatalog()

        val pullBtn = TextView(this).apply {
            text = "📥 ПОДТЯНУТЬ ПРЕПАРАТЫ ИЗ ОТКРЫТОЙ КАРТЫ"
            textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(18, 16, 18, 16); background = roundBg("#0288D1", 12f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
        }
        root.addView(pullBtn)

        val scroll = NestedScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isFillViewport = true
            isNestedScrollingEnabled = true
        }
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 16)
        }
        scroll.addView(lay)
        root.addView(scroll)

        fun lbl(t: String) = lay.addView(TextView(this).apply {
            text = t; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#4A148C")); setPadding(6, 6, 0, 4)
        })

        lbl("Название набора (обязательно):")
        val titleInp = EditText(this).apply {
            hint = "Напр. ГК стандарт / ОКС / Обработка раны"
            if (initialTitle.isNotBlank()) setText(initialTitle)
            setPadding(24, 16, 24, 16)
            MedicalTools.styleEditText(this, "#F3E5F5", "#CE93D8", 12f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
        }
        lay.addView(titleInp)

        lbl("Состав набора:")
        val itemsListLay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
        }
        lay.addView(itemsListLay)

        // Форма добавления одной позиции в набор + живой серверный поиск по базе сайта
        val addBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            background = roundBg("#F5F7F8", 14f, "#CFD8DC")
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }
        }
        addBox.addView(TextView(this).apply {
            text = "➕ Добавить позицию в набор:"; textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#263238")); setPadding(0, 0, 0, 4)
        })
        val catalogStatusTv = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.parseColor("#546E7A"))
            setPadding(0, 0, 0, 8)
        }
        addBox.addView(catalogStatusTv)

        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 4 }
        }
        val nameInp = EditText(this).apply {
            hint = "Введите 2+ буквы для поиска с сервера..."
            setPadding(20, 14, 20, 14)
            MedicalTools.styleEditText(this, "#FFFFFF", "#B0BEC5", 10f)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6 }
        }
        val dropToggleBtn = TextView(this).apply {
            text = "▼"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#4A148C")); gravity = Gravity.CENTER
            setPadding(16, 14, 16, 14); background = roundBg("#EDE7F6", 10f, "#CE93D8")
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 6 }
        }
        val syncCatalogBtn = TextView(this).apply {
            text = "🔄"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#1565C0")); gravity = Gravity.CENTER
            setPadding(14, 14, 14, 14); background = roundBg("#E3F2FD", 10f, "#90CAF9")
        }
        nameRow.addView(nameInp); nameRow.addView(dropToggleBtn); nameRow.addView(syncCatalogBtn)
        addBox.addView(nameRow)

        val suggestionsLay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 4, 0, 6)
        }
        addBox.addView(suggestionsLay)

        val qtyRouteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4; bottomMargin = 8 }
        }
        val qtyInp = EditText(this).apply {
            hint = "Кол-во"
            setText("1")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(20, 14, 20, 14)
            MedicalTools.styleEditText(this, "#FFFFFF", "#B0BEC5", 10f)
            layoutParams = LinearLayout.LayoutParams(0, -2, 0.42f).apply { rightMargin = 8 }
        }
        val routeInp = EditText(this).apply {
            hint = "Путь введения (или пусто)"
            setPadding(20, 14, 20, 14)
            MedicalTools.styleEditText(this, "#FFFFFF", "#B0BEC5", 10f)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        qtyRouteRow.addView(qtyInp); qtyRouteRow.addView(routeInp)
        addBox.addView(qtyRouteRow)

        val routeChipsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setPadding(0, 0, 0, 8) }
        val routeChipsLay = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        routeChipsScroll.addView(routeChipsLay)
        addBox.addView(routeChipsScroll)

        val noteInp = EditText(this).apply {
            hint = "Примечание (необязательно)"
            setPadding(20, 14, 20, 14)
            MedicalTools.styleEditText(this, "#FFFFFF", "#B0BEC5", 10f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
        }
        addBox.addView(noteInp)

        val addPosBtn = TextView(this).apply {
            text = "➕ ДОБАВИТЬ В СПИСОК"; textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#4A148C")); gravity = Gravity.CENTER
            setPadding(14, 14, 14, 14); background = roundBg("#EDE7F6", 10f, "#B39DDB")
        }
        addBox.addView(addPosBtn)
        lay.addView(addBox)

        fun normX(s: String) = s.trim().lowercase().replace('x', 'х')
        var suppressSug = false
        var searchJob: Job? = null

        fun drawSuggestionsList(matches: List<Pair<String, String>>, loadingNote: Boolean = false) {
            suggestionsLay.removeAllViews()
            if (matches.isEmpty() && !loadingNote) {
                suggestionsLay.visibility = View.GONE
                return
            }
            suggestionsLay.visibility = View.VISIBLE
            if (loadingNote) {
                suggestionsLay.addView(TextView(this).apply {
                    text = "⏳ Поиск на сервере СМП..."
                    textSize = 12f; setTextColor(Color.parseColor("#1565C0"))
                    setPadding(10, 6, 10, 8)
                })
            }
            matches.take(25).forEach { (medName, unit) ->
                val display = if (unit.isNotEmpty()) "$medName  [$unit]" else medName
                suggestionsLay.addView(TextView(this).apply {
                    text = display; textSize = 13f; setTextColor(Color.parseColor("#263238"))
                    setPadding(18, 12, 18, 12); background = roundBg("#FFFFFF", 8f, "#CE93D8")
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 4 }
                    setOnClickListener {
                        suppressSug = true
                        nameInp.setText(medName)
                        nameInp.setSelection(medName.length)
                        suppressSug = false
                        suggestionsLay.visibility = View.GONE
                        qtyInp.requestFocus()
                        qtyInp.selectAll()
                    }
                })
            }
        }

        fun getLocalMatches(qn: String): List<Pair<String, String>> =
            catalogMeds.filter { (n, _) -> qn.isEmpty() || normX(n).contains(qn) }

        fun renderSuggestions(forceOpen: Boolean = false) {
            searchJob?.cancel()
            if (suppressSug) {
                suggestionsLay.visibility = View.GONE
                return
            }
            val rawQ = nameInp.text.toString().trim()
            if (!forceOpen && rawQ.isEmpty()) {
                suggestionsLay.visibility = View.GONE
                return
            }
            val qn = normX(rawQ)
            val local = getLocalMatches(qn)
            val shouldQueryServer = rawQ.length >= 2
            drawSuggestionsList(local, shouldQueryServer && local.size < 5)

            if (shouldQueryServer) {
                searchJob = lifecycleScope.launch {
                    delay(260)
                    val reqId = System.currentTimeMillis().toString()
                    activeMedSearchReqId = reqId
                    onMedSearchCallback = { remote ->
                        if (bs.isShowing && !suppressSug) {
                            val seen = HashSet<String>()
                            val curLocal = getLocalMatches(normX(nameInp.text.toString()))
                            val merged = (remote + curLocal).filter { seen.add(it.first.lowercase()) }
                            drawSuggestionsList(merged, false)
                        }
                    }
                    webView.evaluateJavascript(
                        "window.searchMedsOnline && window.searchMedsOnline(${JSONObject.quote(rawQ)}, ${JSONObject.quote(reqId)});",
                        null
                    )
                }
            }
        }

        fun renderRouteChips() {
            routeChipsLay.removeAllViews()
            val merged = LinkedHashSet<String>().apply {
                add("путь введения не определен")
                add("внутривенный")
                add("внутримышечный")
                add("подкожный")
                add("внутрь")
                add("сублингвальный")
                add("ингаляционный")
                catalogRoutes.forEach { if (it.isNotBlank()) add(it) }
            }
            merged.forEach { fullVal ->
                val shortLbl = when {
                    fullVal.contains("не определен") -> "не определен"
                    fullVal.startsWith("внутривенн") -> "в/в"
                    fullVal.startsWith("внутримышечн") -> "в/м"
                    fullVal.startsWith("подкожн") -> "п/к"
                    else -> fullVal
                }
                routeChipsLay.addView(TextView(this).apply {
                    text = shortLbl; textSize = 11.5f; setTextColor(Color.parseColor("#4A148C"))
                    setPadding(16, 8, 16, 8); background = roundBg("#EDE7F6", 12f)
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 6 }
                    setOnClickListener { routeInp.setText(fullVal) }
                })
            }
        }

        fun updateCatalogStatus(customText: String? = null) {
            catalogStatusTv.text = customText ?: if (catalogMeds.isNotEmpty()) {
                "📚 Справочник сайта: ${catalogMeds.size} поз. (живой поиск с сервера)"
            } else {
                "🌐 Живой поиск с сервера — начните ввод или нажмите 🔄"
            }
            renderRouteChips()
        }

        fun triggerCatalogSync(withToast: Boolean) {
            if (withToast) updateCatalogStatus("⏳ Синхронизация справочника бригады...")
            val notifyJs = if (withToast) {
                "window.AndroidBridge && window.AndroidBridge.notifyUser(n ? ('📚 Загружено позиций: ' + n) : 'Не удалось загрузить — проверьте авторизацию на сайте');"
            } else ""
            val js = """
                (async function(){
                    var n = window.syncFullMedCatalog ? await window.syncFullMedCatalog() : 0;
                    if (window.AndroidBridge && window.AndroidBridge.cacheMedCatalog && window.getMedCatalogJson) {
                        window.AndroidBridge.cacheMedCatalog(window.getMedCatalogJson());
                    }
                    $notifyJs
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        }

        onMedCatalogUpdated = { rawCat ->
            if (bs.isShowing) {
                val (m, r) = parseMedCatalogJson(rawCat)
                if (m.isNotEmpty()) catalogMeds = m
                if (r.isNotEmpty()) catalogRoutes = r
                updateCatalogStatus()
            }
        }

        bs.setOnDismissListener {
            searchJob?.cancel()
            activeMedSearchReqId = ""
            onMedSearchCallback = null
            onMedCatalogUpdated = null
        }

        updateCatalogStatus()

        webView.evaluateJavascript("window.getMedCatalogJson ? window.getMedCatalogJson() : '';") { rawRes ->
            val unwrapped = try {
                if (rawRes.isNullOrBlank() || rawRes == "null") ""
                else (JSONTokener(rawRes).nextValue() as? String) ?: ""
            } catch (_: Exception) { "" }
            if (unwrapped.isNotBlank()) {
                val (m, r) = parseMedCatalogJson(unwrapped)
                if (m.size >= catalogMeds.size) {
                    catalogMeds = m
                    if (r.isNotEmpty()) catalogRoutes = r
                    saveCachedMedCatalog(unwrapped)
                    updateCatalogStatus()
                }
            }
            if (catalogMeds.size < 50) {
                triggerCatalogSync(false)
            }
        }

        nameInp.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = renderSuggestions(false)
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })
        dropToggleBtn.setOnClickListener {
            if (suggestionsLay.visibility == View.VISIBLE) {
                suggestionsLay.visibility = View.GONE
            } else if (catalogMeds.isEmpty()) {
                triggerCatalogSync(true)
            } else {
                drawSuggestionsList(getLocalMatches(normX(nameInp.text.toString())), false)
            }
        }
        syncCatalogBtn.setOnClickListener {
            triggerCatalogSync(true)
        }

        fun renderItems() {
            itemsListLay.removeAllViews()
            if (items.isEmpty()) {
                itemsListLay.addView(TextView(this).apply {
                    text = "Пока пусто. Нажмите «📥 Подтянуть из открытой карты» или найдите препарат ниже."
                    textSize = 12.8f; setTextColor(Color.parseColor("#607D8B"))
                    setPadding(16, 14, 16, 14); background = roundBg("#FAFAFA", 10f, "#E0E0E0")
                })
                return
            }
            items.forEachIndexed { idx, obj ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(18, 12, 14, 12); background = roundBg("#FAF5FF", 12f, "#E1BEE7")
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 6 }
                }
                val nm = obj.optString("name")
                val qt = obj.optString("qty").ifEmpty { "1" }
                val rt = obj.optString("route")
                val rtShow = if (rt.isNotEmpty() && !rt.contains("не определен")) " • $rt" else ""
                row.addView(TextView(this).apply {
                    text = "${idx + 1}. $nm — $qt$rtShow"
                    textSize = 13f; setTextColor(Color.parseColor("#263238"))
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    setOnClickListener {
                        suppressSug = true
                        nameInp.setText(nm)
                        suppressSug = false
                        suggestionsLay.visibility = View.GONE
                        qtyInp.setText(qt)
                        routeInp.setText(rt)
                        noteInp.setText(obj.optString("note"))
                        items.removeAt(idx)
                        renderItems()
                    }
                })
                row.addView(TextView(this).apply {
                    text = "✕"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#C62828"))
                    setPadding(14, 6, 10, 6)
                    setOnClickListener { items.removeAt(idx); renderItems() }
                })
                itemsListLay.addView(row)
            }
        }

        addPosBtn.setOnClickListener {
            val nm = nameInp.text.toString().trim()
            if (nm.isEmpty()) { toast("Введите или выберите препарат!"); nameInp.requestFocus(); return@setOnClickListener }
            val qt = qtyInp.text.toString().replace(",", ".").trim().ifEmpty { "1" }
            val rt = routeInp.text.toString().trim()
            val nt = noteInp.text.toString().trim()
            items.add(JSONObject().apply {
                put("name", nm); put("qty", qt); put("route", rt); put("note", nt)
            })
            suppressSug = true
            nameInp.setText("")
            suppressSug = false
            suggestionsLay.visibility = View.GONE
            qtyInp.setText("1"); routeInp.setText(""); noteInp.setText("")
            renderItems()
        }

        pullBtn.setOnClickListener {
            webView.evaluateJavascript("window.getCurrentMedicationsJson ? window.getCurrentMedicationsJson() : '';") { rawRes ->
                val unwrapped = try {
                    if (rawRes.isNullOrBlank() || rawRes == "null") ""
                    else (JSONTokener(rawRes).nextValue() as? String) ?: ""
                } catch (_: Exception) { "" }

                if (unwrapped.isBlank()) {
                    toast("В открытой карте нет добавленных препаратов")
                    return@evaluateJavascript
                }
                try {
                    val arr = JSONObject(unwrapped).optJSONArray("items") ?: JSONArray()
                    if (arr.length() == 0) {
                        toast("В открытой карте нет добавленных препаратов")
                        return@evaluateJavascript
                    }
                    items.clear()
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items.add(it) }
                    renderItems()
                    toast("📥 Подтянуто позиций: ${items.size}")
                } catch (_: Exception) { toast("Ошибка чтения препаратов") }
            }
        }

        renderItems()

        root.addView(TextView(this).apply {
            text = if (isEdit) "💾 СОХРАНИТЬ ИЗМЕНЕНИЯ" else "💾 СОХРАНИТЬ НАБОР ПРЕПАРАТОВ"
            textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(20, 20, 20, 20); background = roundBg("#6A1B9A", 14f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10 }
            setOnClickListener {
                val t = titleInp.text.toString().trim()
                if (t.isEmpty()) { toast("Введите название набора!"); titleInp.requestFocus(); return@setOnClickListener }
                if (items.isEmpty()) { toast("Добавьте хотя бы один препарат!"); return@setOnClickListener }

                val payload = JSONObject().apply {
                    put("items", JSONArray().apply { items.forEach { put(it) } })
                }.toString()

                lifecycleScope.launch(Dispatchers.IO) {
                    if (editingId != null) dbHelper.updateMedPreset(editingId, t, payload)
                    else dbHelper.saveMedPreset(t, payload)
                    withContext(Dispatchers.Main) {
                        toast(if (isEdit) "✏️ Набор «$t» обновлён!" else "💊 Набор «$t» сохранён!")
                        bs.dismiss()
                        onSaved?.invoke()
                    }
                }
            }
        })

        bs.show()
    }

    // ==========================================
    // АУДИТ ОТКРЫТОЙ КАРТЫ (ЭКСПЕРТ-КОНТРОЛЬ)
    // ==========================================
    private fun runCardAudit() {
        val js = """
            (function() {
                if (typeof window.getCurrentCardRawJson === 'function') return window.getCurrentCardRawJson();
                if (window.lastLoadedApiCard) return JSON.stringify(Object.assign({}, window.lastLoadedApiCard, window.b103ScreenCache || {}));
                return '';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { rawRes ->
            val unwrapped = try {
                if (rawRes.isNullOrBlank() || rawRes == "null") ""
                else (JSONTokener(rawRes).nextValue() as? String) ?: ""
            } catch (_: Exception) { "" }

            if (unwrapped.isBlank()) {
                toast("Откройте карту для проверки!")
                return@evaluateJavascript
            }

            try {
                val raw = JSONObject(unwrapped)
                val p = raw.optJSONObject("patient") ?: JSONObject()

                val fio = cleanJsonVal(p.opt("fullName")).ifEmpty { cleanJsonVal(raw.opt("initialFullName")) }
                val ageStr = cleanJsonVal(raw.opt("age"))
                val scrGender = cleanJsonVal(raw.opt("_screenGender")).uppercase()
                val gender = when {
                    scrGender.startsWith("М") -> "МУЖЧИНА"
                    scrGender.startsWith("Ж") -> "ЖЕНЩИНА"
                    p.has("gender") && !p.isNull("gender") -> if (p.optBoolean("gender", false)) "МУЖЧИНА" else "ЖЕНЩИНА"
                    else -> ""
                }

                val diagnosis = cleanJsonVal(raw.opt("diagnosis"))
                val icdObj = raw.optJSONObject("icd")
                val mkbCode = icdObj?.let { cleanJsonVal(it.opt("code")) } ?: ""
                val mkbName = icdObj?.let { cleanJsonVal(it.opt("type")).ifEmpty { cleanJsonVal(it.opt("name")) } } ?: ""
                val mkbFull = if (mkbCode.isNotEmpty()) "[$mkbCode] $mkbName".trim() else ""

                val complaints = cleanJsonVal(raw.opt("complaints"))
                val anamnesis = cleanJsonVal(raw.opt("anamnesis"))
                val localStatus = cleanJsonVal(raw.opt("localStatus"))
                val ecgBefore = cleanJsonVal(raw.opt("electrocardiogramBefore"))

                val generalState = cleanJsonVal(raw.opt("generalState"))
                val consciousness = cleanJsonVal(raw.opt("consciousness"))
                val position = cleanJsonVal(raw.opt("position")).uppercase()
                val speech = cleanJsonVal(raw.opt("speech")).uppercase()
                val gait = cleanJsonVal(raw.opt("gait")).uppercase()
                val abdomen = cleanJsonVal(raw.opt("stomachs")).lowercase()
                val glasgowStr = cleanJsonVal(raw.opt("comaScale"))
                val glasgow = glasgowStr.toIntOrNull()

                val bp = cleanBpString(raw.optString("arterialPressure"))
                val bpParts = bp.split("/")
                val sysBp = bpParts.getOrNull(0)?.trim()?.toIntOrNull()
                val diaBp = bpParts.getOrNull(1)?.trim()?.toIntOrNull()
                val pulseStr = cleanJsonVal(raw.opt("pulse"))
                val pulse = pulseStr.toIntOrNull()
                val pulseExists = if (raw.has("pulseExists") && !raw.isNull("pulseExists")) raw.optBoolean("pulseExists", true) else true
                val rrStr = cleanJsonVal(raw.opt("breathCount"))
                val tempStr = cleanJsonVal(raw.opt("skinTemp")).replace(",", ".")
                val tempVal = tempStr.toDoubleOrNull()
                val satStr = cleanJsonVal(raw.opt("saturation1"))
                val satVal = satStr.toIntOrNull()

                val consent = raw.optBoolean("acceptsSimpleMedProcedure", false)

                val gsL = generalState.lowercase()
                val csL = consciousness.lowercase()
                val isDeath = gsL.contains("смерть") || diagnosis.lowercase().contains("биологическая смерть")
                val isUnconscious = isDeath || csL.contains("кома") || csL.contains("сопор") || csL.contains("отсутств")

                val errors = mutableListOf<String>()
                val warnings = mutableListOf<String>()

                if (fio.isEmpty()) warnings.add("Не указано ФИО пациента")
                if (ageStr.isEmpty()) warnings.add("Не указан возраст пациента")
                if (gender.isEmpty()) warnings.add("Не указан пол пациента")

                if (diagnosis.isEmpty()) errors.add("Не заполнен «Предварительный диагноз»")
                if (mkbCode.isEmpty()) errors.add("Не выбран «Код по МКБ-10»")
                if (complaints.isEmpty()) {
                    errors.add(if (isUnconscious) "Пустое поле «Жалобы» (при коме/смерти укажите: контакт невозможен / жалоб нет ввиду тяжести состояния)" else "Не заполнено поле «Жалобы»")
                }
                if (anamnesis.length < 15) errors.add("Не заполнен или слишком короткий «Анамнез заболевания»")
                if (generalState.isEmpty()) errors.add("Не выбрано «Общее состояние»")
                if (consciousness.isEmpty()) errors.add("Не выбрано «Сознание»")

                if (!isUnconscious && !consent) {
                    errors.add("Не отмечено «Устное согласие на простое мед. вмешательство» (ДА)")
                }

                if (!isDeath) {
                    if (sysBp == null || diaBp == null) errors.add("Не заполнено АД (мм рт.ст.)")
                    if (pulseExists && pulseStr.isEmpty()) errors.add("Не заполнен пульс")
                    if (rrStr.isEmpty()) warnings.add("Не указана частота дыхания (ЧДД)")
                    if (tempStr.isEmpty()) warnings.add("Не указана температура тела (°C)")
                    if (satStr.isEmpty()) warnings.add("Не указана сатурация (SpO₂)")
                }

                // Клинико-экспертные нестыковки
                if (isUnconscious) {
                    if (abdomen.contains("безболезн")) {
                        warnings.add("При коме/сопоре нельзя указывать «живот безболезненный» (нет речевого контакта)")
                    }
                    if (gait == "СВОБОДНАЯ") {
                        warnings.add("При коме/сопоре стоит «Походка: СВОБОДНАЯ» — смените на «НЕ ОЦЕНИВАЛАСЬ»")
                    }
                    if (speech == "СОХРАНЕНА") {
                        warnings.add("При коме/сопоре стоит «Речь: СОХРАНЕНА» — проверьте поле «Речь»")
                    }
                    if (position == "АКТИВНОЕ") {
                        warnings.add("При коме/сопоре стоит «Положение: АКТИВНОЕ» — должно быть «ПАССИВНОЕ»")
                    }
                }

                if (glasgow != null) {
                    if (csL.contains("ясное") && glasgow < 15) {
                        warnings.add("Сознание «Ясное», но по шкале Глазго указано $glasgow б. (для ясного сознания — 15 б.)")
                    }
                    if (csL.contains("кома") && glasgow > 8) {
                        warnings.add("Сознание «Кома», но по шкале Глазго указано $glasgow б. (кома — ≤ 8 б.)")
                    }
                }

                if (!isDeath) {
                    val clinicalBlob = "$diagnosis $mkbFull $complaints".lowercase()
                    val cardioKeywords = listOf("окс", "инфаркт", "стенокард", "фибрилл", "аритм", "тахикард", "тэла", "гипертонический криз", "боль за грудин", "боли за грудин", "давящ", "жгуч", "[i20", "[i21", "[i26", "[i47", "[i48", "[i49", "[i50")
                    val highRiskVitals = (sysBp != null && sysBp >= 180) || (pulse != null && (pulse >= 115 || pulse in 1..50))
                    if ((cardioKeywords.any { clinicalBlob.contains(it) } || highRiskVitals) && ecgBefore.isEmpty()) {
                        warnings.add("Кардио-профиль / высокое АД или ЧСС — не заполнено поле «ЭКГ до оказания помощи»")
                    }

                    val localKeywords = listOf("перелом", "рана", "ушиб", "ожог", "травм", "чмт", "сгм", "онмк", "инсульт", "[s", "[t2", "[t3", "[i61", "[i63")
                    if (localKeywords.any { clinicalBlob.contains(it) } && localStatus.isEmpty()) {
                        warnings.add("При травме / ОНМК рекомендуется подробно заполнить «Локальный статус»")
                    }

                    if (gsL.contains("удовлетворительн")) {
                        if ((sysBp != null && sysBp in 1..89) || (satVal != null && satVal in 1..91) || (tempVal != null && tempVal >= 39.0)) {
                            warnings.add("Выбрано «Удовлетворительное» состояние при критических витальных (АД $bp / SpO₂ $satStr% / Т° $tempStr)")
                        }
                    }
                }

                val summary = StringBuilder().apply {
                    append("👤 Пациент: ${fio.ifEmpty { "—" }} (${ageStr.ifEmpty { "?" }}, ${gender.ifEmpty { "?" }})\n")
                    append("📋 Диагноз: ${diagnosis.ifEmpty { "—" }}\n")
                    append("🏷️ МКБ-10: ${mkbFull.ifEmpty { "—" }}\n")
                    append("🩺 Состояние: ${generalState.ifEmpty { "—" }} | Сознание: ${consciousness.ifEmpty { "—" }} (ШКГ ${glasgowStr.ifEmpty { "—" }})\n")
                    append("❤️ АД: ${if (sysBp != null && diaBp != null) "$sysBp/$diaBp" else "—"} | 💓 ЧСС: ${pulseStr.ifEmpty { "—" }} | 🫁 ЧДД: ${rrStr.ifEmpty { "—" }} | SpO₂: ${satStr.ifEmpty { "—" }}% | 🌡️ ${tempStr.ifEmpty { "—" }}°C\n")
                    append("✍️ Устное согласие: ${if (consent) "ДА ✅" else "НЕТ / не отмечено"}")
                }.toString()

                showAuditResultSheet(errors, warnings, summary)
            } catch (_: Exception) {
                toast("Ошибка анализа карты")
            }
        }
    }

    private fun showAuditResultSheet(errors: List<String>, warnings: List<String>, summary: String) {
        val (bs, root) = createSheet("🩺 Аудит открытой карты", "#1565C0", fixedHeightRatio = 0.84f)

        val bannerColor = when {
            errors.isNotEmpty() -> "#C62828"
            warnings.isNotEmpty() -> "#EF6C00"
            else -> "#2E7D32"
        }
        val bannerText = when {
            errors.isEmpty() && warnings.isEmpty() -> "✅ Карта заполнена грамотно!\nПропусков и нестыковок не найдено."
            errors.isEmpty() -> "⚠️ Критичных пропусков нет\nНайдено рекомендаций: ${warnings.size}"
            else -> "🔴 Найдены пропуски перед сдачей\nКритичных: ${errors.size} | Рекомендаций: ${warnings.size}"
        }
        root.addView(TextView(this).apply {
            text = bannerText; textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(22, 18, 22, 18); background = roundBg(bannerColor, 14f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 }
        })

        val scroll = NestedScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isFillViewport = true
            isNestedScrollingEnabled = true
        }
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 2, 0, 14) }
        scroll.addView(lay)
        root.addView(scroll)

        if (errors.isNotEmpty()) {
            lay.addView(TextView(this).apply {
                text = "🔴 КРИТИЧНЫЕ ПРОПУСКИ:"; textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#B71C1C")); setPadding(4, 2, 0, 6)
            })
            errors.forEach { msg ->
                lay.addView(TextView(this).apply {
                    text = "• $msg"; textSize = 13.5f; setTextColor(Color.parseColor("#263238"))
                    setPadding(20, 14, 20, 14); background = roundBg("#FFEBEE", 12f, "#EF9A9A")
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }
                })
            }
        }

        if (warnings.isNotEmpty()) {
            lay.addView(TextView(this).apply {
                text = "⚠️ ЭКСПЕРТНЫЕ РЕКОМЕНДАЦИИ:"; textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#E65100")); setPadding(4, 6, 0, 6)
            })
            warnings.forEach { msg ->
                lay.addView(TextView(this).apply {
                    text = "• $msg"; textSize = 13.5f; setTextColor(Color.parseColor("#263238"))
                    setPadding(20, 14, 20, 14); background = roundBg("#FFF8E1", 12f, "#FFE082")
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }
                })
            }
        }

        lay.addView(TextView(this).apply {
            text = "📊 СВОДКА СЧИТАННОЙ КАРТЫ:"; textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#37474F")); setPadding(4, 10, 0, 6)
        })
        lay.addView(TextView(this).apply {
            text = summary; textSize = 13.2f; setTextIsSelectable(true); setTextColor(Color.parseColor("#263238"))
            setLineSpacing(5f, 1.15f); setPadding(20, 16, 20, 16); background = roundBg("#F5F7F8", 12f, "#CFD8DC")
        })

        val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 10, 0, 0) }
        bottomRow.addView(TextView(this).apply {
            text = "🔄 ПЕРЕПРОВЕРИТЬ"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(16, 18, 16, 18); background = roundBg("#1565C0", 12f)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 8 }
            setOnClickListener { bs.dismiss(); runCardAudit() }
        })
        bottomRow.addView(TextView(this).apply {
            text = "ЗАКРЫТЬ"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#37474F")); gravity = Gravity.CENTER
            setPadding(16, 18, 16, 18); background = roundBg("#ECEFF1", 12f)
            layoutParams = LinearLayout.LayoutParams(0, -2, 0.7f)
            setOnClickListener { bs.dismiss() }
        })
        root.addView(bottomRow)
        bs.show()
    }

    private fun defaultTemplateJson(): JSONObject = JSONObject().apply {
        put("pastDiseases", "ИБС, АГ")
        put("allergy", "НЕТ")
        put("generalState", "Удовлетворительное")
        put("consciousness", "Ясное")
        put("position", "АКТИВНОЕ")
        put("behavior", "СПОКОЙНОЕ")
        put("glasgowScore", "15")
        put("bp", "130/80")
        put("bpAdapted", "120/80")
        put("pulse", "78")
        put("pulseExists", "ДА")
        put("rr", "16")
        put("temp", "36.6")
        put("saturation", "98")
        put("glucose", "5.4")
        put("heartSounds", "Ритмичные, Ясные")
        put("lungs", "Свободное, Везикулярное")
        put("percussion", "Ясный легочный звук")
        put("abdomen", "Участвует в акте дыхания, Мягкий, Безболезненный")
        put("skin", "Теплая, Сухая, Нормальная")
        put("tongue", "Чистые")
        put("mucous", "Бледно-розовые")
        put("pupils", "")
        put("lightReaction", "ДА")
        put("speech", "СОХРАНЕНА")
        put("face", "СИММЕТРИЧНОЕ")
        put("gait", "СВОБОДНАЯ")
        put("nystagmus", "НЕТ")
        put("muscleTone", "Нормальный")
        put("pathologicalReflexes", "Отрицательные")
        put("meningealSymptoms", "Отрицательные")
        put("plegia", "Нет")
        put("rash", "НЕТ")
        put("ascites", "НЕТ")
        put("edema", "НЕТ")
        put("alcohol", "НЕТ")
        put("acetone", "НЕТ")
        put("peritoneal", "НЕТ")
        put("liver", "НЕ УВЕЛИЧЕНА")
        put("peristalsis", "ДА")
        put("urination", "НЕ НАРУШЕНО")
        put("stool", "НЕ НАРУШЕН")
        put("attack", "КУПИРОВАН")
        put("therapyEffect", "УЛУЧШЕНИЕ")
        put("callResult", "ОСТАВЛЕН НА МЕСТЕ")
    }

    private fun showCreateTemplateDialog(
        prefillJson: String? = null,
        editingId: Long? = null,
        initialTitle: String = "",
        onSaved: (() -> Unit)? = null
    ) {
        val isEdit = editingId != null
        val (bs, root) = createSheet(
            title = if (isEdit) "✏️ Редактировать шаблон" else "➕ Новый клинический шаблон",
            titleColor = if (isEdit) "#E65100" else "#00796B",
            fixedHeightRatio = 0.90f
        )
        var baseCard: JSONObject? = try { prefillJson?.let { JSONObject(it) } } catch (_: Exception) { null }
        val state = defaultTemplateJson()
        baseCard?.let { src ->
            val it = src.keys()
            while (it.hasNext()) {
                val k = it.next()
                state.put(k, src.opt(k))
            }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
        }
        val pullBtn = TextView(this).apply {
            text = "📥 ИЗ ОТКРЫТОЙ КАРТЫ"
            textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(14, 15, 14, 15); background = roundBg("#0288D1", 12f)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 8 }
        }
        val resetNormBtn = TextView(this).apply {
            text = "🔄 НОРМА"
            textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#004D40")); gravity = Gravity.CENTER
            setPadding(16, 15, 16, 15); background = roundBg("#E0F2F1", 12f, "#80CBC4")
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
        topRow.addView(pullBtn); topRow.addView(resetNormBtn)
        root.addView(topRow)

        val scroll = NestedScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isFillViewport = true
            isNestedScrollingEnabled = true
        }
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 18)
        }
        scroll.addView(lay)
        root.addView(scroll)

        val binders = mutableListOf<() -> Unit>()
        val readers = mutableListOf<() -> Unit>()

        fun addSection(titleTxt: String, accentHex: String = "#00695C") {
            lay.addView(TextView(this).apply {
                text = titleTxt; textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(accentHex))
                setPadding(16, 12, 16, 12)
                background = roundBg("#EDF7F6", 10f, "#B2DFDB")
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10; bottomMargin = 8 }
            })
        }

        fun addLabel(lbl: String) {
            lay.addView(TextView(this).apply {
                text = lbl; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#004D40")); setPadding(6, 4, 0, 4)
            })
        }

        fun makeEditText(hintTxt: String, rows: Int = 1, bgHex: String = "#F5F7F8", strokeHex: String = "#B0BEC5"): EditText =
            EditText(this).apply {
                hint = hintTxt
                setPadding(24, 14, 24, 14)
                if (rows > 1) {
                    minLines = rows
                    gravity = Gravity.TOP or Gravity.START
                }
                MedicalTools.styleEditText(this, bgHex, strokeHex, 12f)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }
            }

        fun addTextField(
            key: String,
            lbl: String,
            hintTxt: String,
            rows: Int = 1,
            chips: List<String> = emptyList(),
            bgHex: String = "#F5F7F8",
            strokeHex: String = "#B0BEC5"
        ): EditText {
            addLabel(lbl)
            val et = makeEditText(hintTxt, rows, bgHex, strokeHex)
            lay.addView(et)
            if (chips.isNotEmpty()) {
                val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setPadding(0, 0, 0, 8) }
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                chips.forEach { ch ->
                    row.addView(TextView(this).apply {
                        text = ch; textSize = 11.5f; setTextColor(Color.parseColor("#004D40"))
                        setPadding(16, 7, 16, 7); background = roundBg("#E0F2F1", 12f)
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 6 }
                        setOnClickListener { et.setText(ch) }
                    })
                }
                hs.addView(row)
                lay.addView(hs)
            }
            binders.add { et.setText(cleanJsonVal(state.opt(key))) }
            readers.add { state.put(key, et.text.toString().trim()) }
            return et
        }

        fun addPairFields(
            key1: String, lbl1: String, hint1: String,
            key2: String, lbl2: String, hint2: String
        ) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }
            }
            fun col(key: String, lbl: String, hintTxt: String, mr: Int) {
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = mr }
                }
                box.addView(TextView(this).apply {
                    text = lbl; textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#004D40")); setPadding(4, 2, 0, 4)
                })
                val et = makeEditText(hintTxt, 1).apply {
                    layoutParams = LinearLayout.LayoutParams(-1, -2)
                }
                box.addView(et)
                row.addView(box)
                binders.add { et.setText(cleanJsonVal(state.opt(key))) }
                readers.add { state.put(key, et.text.toString().trim()) }
            }
            col(key1, lbl1, hint1, 10)
            col(key2, lbl2, hint2, 0)
            lay.addView(row)
        }

        fun addButtonGroup(key: String, lbl: String, options: List<String>, allowDeselect: Boolean = false) {
            addLabel(lbl)
            val hs = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, 0, 0, 8)
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            hs.addView(row)
            lay.addView(hs)

            var selected = cleanJsonVal(state.opt(key))
            fun render() {
                row.removeAllViews()
                val allOpts = LinkedHashSet<String>(options).apply {
                    if (selected.isNotEmpty() && !options.any { it.equals(selected, true) }) add(selected)
                }
                allOpts.forEach { opt ->
                    val isSel = selected.equals(opt, ignoreCase = true)
                    row.addView(TextView(this).apply {
                        text = opt
                        textSize = 12f
                        typeface = if (isSel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        setTextColor(if (isSel) Color.WHITE else Color.parseColor("#263238"))
                        setPadding(18, 11, 18, 11)
                        background = roundBg(if (isSel) "#00796B" else "#F1F5F6", 12f, if (isSel) "#004D40" else "#CFD8DC")
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8 }
                        setOnClickListener {
                            selected = if (allowDeselect && isSel) "" else opt
                            state.put(key, selected)
                            render()
                        }
                    })
                }
            }
            binders.add {
                selected = cleanJsonVal(state.opt(key))
                render()
            }
            readers.add { state.put(key, selected) }
        }

        fun addSingleDropdown(key: String, lbl: String, options: List<String>) {
            addLabel(lbl)
            val btn = TextView(this).apply {
                textSize = 13f; setTextColor(Color.parseColor("#111111"))
                setPadding(24, 15, 24, 15)
                background = roundBg("#F5F7F8", 12f, "#B0BEC5")
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }
            }
            lay.addView(btn)

            var selected = cleanJsonVal(state.opt(key))
            fun refresh() {
                btn.text = if (selected.isNotEmpty()) "$selected   ▼" else "Не выбрано   ▼"
                btn.setTextColor(Color.parseColor(if (selected.isNotEmpty()) "#111111" else "#546E7A"))
            }
            btn.setOnClickListener {
                val items = LinkedHashSet<String>(options).apply {
                    if (selected.isNotEmpty()) add(selected)
                }.toList()
                val idx = items.indexOfFirst { it.equals(selected, ignoreCase = true) }
                AlertDialog.Builder(this)
                    .setTitle(lbl.trimEnd(':'))
                    .setSingleChoiceItems(items.toTypedArray(), idx) { dlg, which ->
                        selected = items[which]
                        state.put(key, selected)
                        refresh()
                        dlg.dismiss()
                    }
                    .setNeutralButton("Очистить") { _, _ ->
                        selected = ""
                        state.put(key, "")
                        refresh()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            binders.add {
                selected = cleanJsonVal(state.opt(key))
                refresh()
            }
            readers.add { state.put(key, selected) }
        }

        fun addMultiDropdown(key: String, lbl: String, options: List<String>) {
            addLabel(lbl)
            val btn = TextView(this).apply {
                textSize = 13f; setTextColor(Color.parseColor("#111111"))
                setPadding(24, 15, 24, 15)
                background = roundBg("#F5F7F8", 12f, "#B0BEC5")
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }
            }
            lay.addView(btn)

            var selectedList = mutableListOf<String>()
            fun parseVal(raw: String) {
                selectedList = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            }
            fun refresh() {
                val txt = selectedList.joinToString(", ")
                btn.text = if (txt.isNotEmpty()) "$txt   ▼" else "Не выбрано (нажмите для выбора)   ▼"
                btn.setTextColor(Color.parseColor(if (txt.isNotEmpty()) "#111111" else "#546E7A"))
            }
            btn.setOnClickListener {
                val all = LinkedHashSet<String>(options).apply { addAll(selectedList) }.toList()
                val checked = BooleanArray(all.size) { i -> selectedList.any { it.equals(all[i], ignoreCase = true) } }
                AlertDialog.Builder(this)
                    .setTitle(lbl.trimEnd(':'))
                    .setMultiChoiceItems(all.toTypedArray(), checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton("Готово") { _, _ ->
                        selectedList = all.indices.filter { checked[it] }.map { all[it] }.toMutableList()
                        state.put(key, selectedList.joinToString(", "))
                        refresh()
                    }
                    .setNeutralButton("Очистить") { _, _ ->
                        selectedList.clear()
                        state.put(key, "")
                        refresh()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            binders.add {
                parseVal(cleanJsonVal(state.opt(key)))
                refresh()
            }
            readers.add { state.put(key, selectedList.joinToString(", ")) }
        }

        // --- РАЗДЕЛ 1: ОСНОВНОЕ И ДИАГНОЗ (С СИНХРОНИЗИРОВАННЫМ МКБ-10) ---
        addLabel("Название шаблона (обязательно):")
        val titleInp = makeEditText("Напр. ГК с тошнотой / ОКС с блокадой", 1, "#E0F2F1", "#80CBC4")
        lay.addView(titleInp)
        if (initialTitle.isNotBlank()) titleInp.setText(initialTitle)

        addLabel("Код по МКБ-10 (синхронизировано с сайтом):")
        val mkbRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 4 }
        }
        val mkbInp = makeEditText("Поиск кода или диагноза (напр. I10, гипертенз)...", 1).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6 }
        }
        val mkbDropBtn = TextView(this).apply {
            text = "▼"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#004D40")); gravity = Gravity.CENTER
            setPadding(16, 14, 16, 14); background = roundBg("#E0F2F1", 10f, "#80CBC4")
        }
        mkbRow.addView(mkbInp); mkbRow.addView(mkbDropBtn)
        lay.addView(mkbRow)

        val mkbSugLay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 2, 0, 8)
        }
        lay.addView(mkbSugLay)

        var suppressMkbSug = false
        var mkbSearchJob: Job? = null

        fun drawMkbSuggestions(siteItems: List<MkbCodeItem>, loading: Boolean, emptyHint: Boolean = false) {
            mkbSugLay.removeAllViews()
            val seen = HashSet<String>()
            val uniqueSite = siteItems.filter { seen.add(it.code) }

            if (uniqueSite.isEmpty() && !loading && !emptyHint) {
                mkbSugLay.visibility = View.GONE
                return
            }
            mkbSugLay.visibility = View.VISIBLE
            if (loading) {
                mkbSugLay.addView(TextView(this).apply {
                    text = "⏳ Поиск МКБ-10 на сайте СМП..."
                    textSize = 12f; setTextColor(Color.parseColor("#0277BD"))
                    setPadding(8, 4, 8, 6)
                })
            } else if (uniqueSite.isEmpty() && emptyHint) {
                mkbSugLay.addView(TextView(this).apply {
                    text = "🌐 Введите 2+ буквы кода или диагноза для поиска по базе сайта СМП"
                    textSize = 12f; setTextColor(Color.parseColor("#546E7A"))
                    setPadding(12, 10, 12, 10); background = roundBg("#F5F7F8", 8f, "#CFD8DC")
                })
                return
            }
            uniqueSite.take(25).forEach { item ->
                val formatted = if (item.name.isNotEmpty()) "[${item.code}] ${item.name}" else "[${item.code}]"
                mkbSugLay.addView(TextView(this).apply {
                    text = "🌐 $formatted"
                    textSize = 12.8f; setTextColor(Color.parseColor("#263238"))
                    setPadding(18, 12, 18, 12)
                    background = roundBg("#E3F2FD", 8f, "#90CAF9")
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 4 }
                    setOnClickListener {
                        suppressMkbSug = true
                        mkbInp.setText(formatted)
                        mkbInp.setSelection(formatted.length)
                        suppressMkbSug = false
                        mkbSugLay.visibility = View.GONE
                    }
                })
            }
        }

        fun filterSiteMkb(qRaw: String): List<MkbCodeItem> {
            val q = qRaw.trim().removePrefix("[").substringBefore("]").trim().lowercase()
            val qFull = qRaw.trim().lowercase()
            return siteMkbList.filter {
                qFull.isEmpty() || it.code.lowercase().contains(qFull) || it.name.lowercase().contains(qFull) || (q.length >= 2 && it.code.lowercase().contains(q))
            }.take(25)
        }

        fun triggerMkbSearch(forceOpen: Boolean = false) {
            mkbSearchJob?.cancel()
            if (suppressMkbSug) { mkbSugLay.visibility = View.GONE; return }
            val rawQ = mkbInp.text.toString().trim()
            if (!forceOpen && rawQ.length < 2) { mkbSugLay.visibility = View.GONE; return }
            val queryForSite = rawQ.replace("[", "").replace("]", "").trim()
            val sLoc = filterSiteMkb(rawQ)
            drawMkbSuggestions(sLoc, queryForSite.length >= 2, forceOpen && queryForSite.length < 2)
            if (queryForSite.length >= 2) {
                mkbSearchJob = lifecycleScope.launch {
                    delay(260)
                    val reqId = System.currentTimeMillis().toString()
                    activeMkbSearchReqId = reqId
                    onMkbSearchCallback = { remote ->
                        if (bs.isShowing && !suppressMkbSug) {
                            val curS = filterSiteMkb(mkbInp.text.toString())
                            drawMkbSuggestions(remote + curS, false, true)
                        }
                    }
                    webView.evaluateJavascript(
                        "window.searchMkbOnline && window.searchMkbOnline(${JSONObject.quote(queryForSite)}, ${JSONObject.quote(reqId)});",
                        null
                    )
                }
            }
        }

        mkbInp.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = triggerMkbSearch(false)
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })
        mkbDropBtn.setOnClickListener {
            if (mkbSugLay.visibility == View.VISIBLE) mkbSugLay.visibility = View.GONE
            else triggerMkbSearch(true)
        }
        binders.add {
            suppressMkbSug = true
            mkbInp.setText(cleanJsonVal(state.opt("mkb")))
            suppressMkbSug = false
            mkbSugLay.visibility = View.GONE
        }
        readers.add { state.put("mkb", mkbInp.text.toString().trim()) }

        webView.evaluateJavascript("window.getSiteMkbJson ? window.getSiteMkbJson() : '[]';") { rawRes ->
            val unwrapped = try {
                if (rawRes.isNullOrBlank() || rawRes == "null") ""
                else (JSONTokener(rawRes).nextValue() as? String) ?: ""
            } catch (_: Exception) { "" }
            val parsed = parseMkbItemsArray(unwrapped)
            if (parsed.isNotEmpty()) siteMkbList = parsed
        }

        addTextField("diagnosis", "Предварительный диагноз:", "Клинический диагноз...", 2)
        addTextField("complaints", "Жалобы:", "Жалобы пациента...", 2)
        addTextField("anamnesis", "Анамнез заболевания:", "Хронология, обстоятельства...", 3)
        addTextField("pastDiseases", "Перенесенные заболевания:", "ИБС, АГ...", 1, listOf("ИБС, АГ", "Отрицает", "СД 2 типа", "БА", "ХОБЛ"))
        addButtonGroup("allergy", "Аллергия:", listOf("НЕТ", "ДА"))

        // --- РАЗДЕЛ 2: ОБЩЕЕ СОСТОЯНИЕ И ВИТАЛЬНЫЕ ПОКАЗАТЕЛИ ---
        addSection("🩺 Состояние и витальные показатели")
        addSingleDropdown("generalState", "Общее состояние:", listOf(
            "Удовлетворительное", "Средней степени тяжести", "Тяжелое",
            "Крайне тяжелое", "Клиническая смерть", "Биологическая смерть"
        ))
        addSingleDropdown("consciousness", "Сознание:", listOf(
            "Ясное", "Приглушенное", "Оглушение", "Сопор", "Кома", "Отсутствует"
        ))
        addTextField("glasgowScore", "Шкала комы Глазго (баллы):", "15", 1, listOf("15", "14", "13", "8", "3"))
        addButtonGroup("position", "Положение:", listOf("АКТИВНОЕ", "ПАССИВНОЕ", "ВЫНУЖДЕННОЕ"))
        addTextField("positionDescription", "Комментарий (вынужденное положение):", "Напр. сидя, ортопноэ...")
        addButtonGroup("behavior", "Поведение:", listOf("СПОКОЙНОЕ", "ВОЗБУЖДЕННОЕ", "АГРЕССИВНОЕ", "ЗАТОРМОЖЕННОЕ", "НЕАДЕКВАТНОЕ"))
        addPairFields("bp", "АД на вызове:", "130/80", "bpAdapted", "Привычное АД:", "120/80")
        addButtonGroup("pulseExists", "Пульс определяется:", listOf("ДА", "НЕТ"))
        addPairFields("pulse", "Пульс (уд/мин):", "78", "rr", "ЧДД (в мин):", "16")
        addPairFields("temp", "Температура (°C):", "36.6", "saturation", "Сатурация (%):", "98")
        addTextField("glucose", "Гликемия (ммоль/л):", "5.4")

        // --- РАЗДЕЛ 3: КОЖА, СЕРДЦЕ, ЛЕГКИЕ, ЖИВОТ ---
        addSection("🫁 Кожа, сердце, легкие и ЖКТ")
        addMultiDropdown("skin", "Кожа (мультивыбор):", listOf(
            "Теплая", "Холодная", "Сухая", "Влажная", "Нормальная",
            "Бледная", "Гиперемия", "Цианоз", "Желтушность", "Гипостатические пятна"
        ))
        addButtonGroup("rash", "Высыпания:", listOf("НЕТ", "ДА"))
        addButtonGroup("edema", "Периферические отеки:", listOf("НЕТ", "ДА"))
        addTextField("edemaDescription", "Комментарий (отеки):", "Напр. Отечность голеней...")
        addButtonGroup("ascites", "Асцит:", listOf("НЕТ", "ДА"))
        addTextField("tongue", "Язык:", "Чистые", 1, listOf("Чистые", "Сухой, обложен", "Суховат, обложен", "Следы прикуса"))
        addTextField("mucous", "Слизистые:", "Бледно-розовые", 1, listOf("Бледно-розовые", "Гиперемированы", "Бледные", "Сухие"))
        addMultiDropdown("heartSounds", "Тоны сердца (мультивыбор):", listOf(
            "Ритмичные", "Аритмичные", "Ясные", "Приглушенные", "Глухие", "Отсутствуют"
        ))
        addMultiDropdown("lungs", "Дыхание (мультивыбор):", listOf(
            "Свободное", "Везикулярное", "Жесткое", "Ослабленное", "Затрудненное",
            "Отсутствует", "Хрипы сухие", "Хрипы влажные", "Хрипы справа", "Хрипы слева"
        ))
        addMultiDropdown("percussion", "Перкуссия легких (мультивыбор):", listOf(
            "Ясный легочный звук", "Коробочный звук справа", "Коробочный звук слева",
            "Притупление звука справа", "Притупление звука слева", "Тупой звук"
        ))
        addMultiDropdown("abdomen", "Живот (мультивыбор):", listOf(
            "Участвует в акте дыхания", "Не участвует в акте дыхания", "Мягкий",
            "Напряженный", "Вздут", "Безболезненный", "Болезненный в области"
        ))
        addButtonGroup("peritoneal", "Симптомы раздражения брюшины:", listOf("НЕТ", "ДА"))
        addButtonGroup("peristalsis", "Перистальтика:", listOf("ДА", "НЕТ"))
        addButtonGroup("liver", "Печень:", listOf("НЕ УВЕЛИЧЕНА", "УВЕЛИЧЕНА"))
        addButtonGroup("urination", "Мочеиспускание:", listOf("НЕ НАРУШЕНО", "УЧАЩЕНО", "БОЛЕЗНЕННОЕ", "ЗАДЕРЖКА МОЧЕИСПУСКАНИЯ", "НЕДЕРЖАНИЕ"))
        addButtonGroup("stool", "Стул:", listOf("НЕ НАРУШЕН", "УЧАЩЕН", "ЖИДКИЙ", "ЗАДЕРЖКА", "ОТСУТСТВУЕТ"))
        addButtonGroup("alcohol", "Запах алкоголя:", listOf("НЕТ", "ДА"))
        addButtonGroup("acetone", "Запах ацетона:", listOf("НЕТ", "ДА"))

        // --- РАЗДЕЛ 4: НЕВРОЛОГИЧЕСКИЙ СТАТУС ---
        addSection("🧠 Неврологический статус")
        addButtonGroup("pupils", "Зрачки (повторный тап — снять):", listOf("МИДРИАЗ", "МИОЗ", "АНИЗОКАРИЯ"), allowDeselect = true)
        addButtonGroup("lightReaction", "Реакция на свет:", listOf("ДА", "НЕТ"))
        addButtonGroup("speech", "Речь:", listOf("СОХРАНЕНА", "НАРУШЕНА", "ОТСУТСТВУЕТ"))
        addButtonGroup("face", "Лицо:", listOf("СИММЕТРИЧНОЕ", "АСИММЕТРИЧНОЕ"))
        addButtonGroup("gait", "Походка:", listOf("СВОБОДНАЯ", "ШАТКАЯ", "НАРУШЕНА", "НЕ ОЦЕНИВАЛАСЬ"))
        addButtonGroup("nystagmus", "Нистагм:", listOf("НЕТ", "ГОРИЗОНТАЛЬНЫЙ", "ВЕРТИКАЛЬНЫЙ", "ДА"))
        addTextField("muscleTone", "Тонус мышц:", "Нормальный", 1, listOf("Нормальный", "Снижен", "Повышен", "Атония", "Трупное окоченение"))
        addTextField("pathologicalReflexes", "Патологические рефлексы:", "Отрицательные", 1, listOf("Отрицательные", "Положительные", "Симптом Бабинского справа", "Симптом Бабинского слева"))
        addTextField("meningealSymptoms", "Менингеальные знаки:", "Отрицательные", 1, listOf("Отрицательные", "Ригидность затылочных мышц", "Симптом Кернига (+)"))
        addTextField("plegia", "Плегии, парезы:", "Нет", 1, listOf("Нет", "Правосторонний гемипарез", "Левосторонний гемипарез", "Левосторонняя гемиплегия"))

        // --- РАЗДЕЛ 5: ЛОКАЛЬНЫЙ СТАТУС, ЭКГ, ТЕРАПИЯ И ИТОГ ---
        addSection("📄 Локальный статус, ЭКГ и итог")
        addTextField("localStatus", "Локальный статус:", "Локальный статус при травме, ранах...", 2)
        addTextField("ecgBefore", "ЭКГ до оказания помощи:", "Ритм синусовый, правильный...", 2)
        addTextField("ecgAfter", "ЭКГ после оказания помощи:", "Без динамики...", 2)
        addTextField("recommendedTherapy", "Рекомендованная терапия / протокол:", "Препараты по протоколу...", 2)
        addButtonGroup("attack", "Приступ:", listOf("КУПИРОВАН", "НЕ КУПИРОВАН", "ЧАСТИЧНО КУПИРОВАН"))
        addButtonGroup("therapyEffect", "Эффект терапии:", listOf("УЛУЧШЕНИЕ", "БЕЗ ЭФФЕКТА", "УХУДШЕНИЕ"))
        addSingleDropdown("callResult", "Результат выезда:", listOf(
            "ОСТАВЛЕН НА МЕСТЕ",
            "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ",
            "ОТКАЗ ОТ ГОСПИТАЛИЗАЦИИ",
            "КОНСТАТИРОВАНА БИОЛОГИЧЕСКАЯ СМЕРТЬ",
            "ПЕРЕДАН СПЕЦБРИГАДЕ"
        ))

        fun applyObjToFields(j: JSONObject) {
            val it = j.keys()
            while (it.hasNext()) {
                val k = it.next()
                state.put(k, j.opt(k))
            }
            binders.forEach { it() }
            if (titleInp.text.toString().trim().isEmpty()) {
                titleInp.setText(cleanJsonVal(j.opt("diagnosis")).ifEmpty { cleanJsonVal(j.opt("callReason")) }.take(45))
            }
        }

        binders.forEach { it() }

        resetNormBtn.setOnClickListener {
            val def = defaultTemplateJson()
            val it = def.keys()
            while (it.hasNext()) {
                val k = it.next()
                state.put(k, def.opt(k))
            }
            binders.forEach { it() }
            toast("🔄 Поля сброшены к норме")
        }

        pullBtn.setOnClickListener {
            val js = """
                (function() {
                    if (typeof window.getCurrentCardRawJson === 'function') return window.getCurrentCardRawJson();
                    if (window.lastLoadedApiCard) return JSON.stringify(Object.assign({}, window.lastLoadedApiCard, window.b103ScreenCache || {}));
                    return '';
                })();
            """.trimIndent()
            webView.evaluateJavascript(js) { rawRes ->
                val unwrapped = try {
                    if (rawRes.isNullOrBlank() || rawRes == "null") ""
                    else (JSONTokener(rawRes).nextValue() as? String) ?: ""
                } catch (_: Exception) { "" }

                if (unwrapped.isBlank()) {
                    toast("Откройте карту для считывания!")
                    return@evaluateJavascript
                }
                val parsed = parseServerCardJson(unwrapped)
                if (parsed == null) {
                    toast("Не удалось считать данные карты")
                    return@evaluateJavascript
                }
                baseCard = parsed.third.second
                applyObjToFields(parsed.third.second)
                toast("📥 Все поля подтянуты из карты!")
            }
        }

        bs.setOnDismissListener {
            mkbSearchJob?.cancel()
            activeMkbSearchReqId = ""
            onMkbSearchCallback = null
        }

        val saveBtn = TextView(this).apply {
            text = if (isEdit) "💾 СОХРАНИТЬ ИЗМЕНЕНИЯ" else "💾 СОХРАНИТЬ В МОИ ШАБЛОНЫ"
            textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(20, 20, 20, 20); background = roundBg(if (isEdit) "#E65100" else "#00796B", 14f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10 }
            setOnClickListener {
                val t = titleInp.text.toString().trim()
                if (t.isEmpty()) {
                    toast("Введите название шаблона!")
                    titleInp.requestFocus()
                    return@setOnClickListener
                }
                readers.forEach { it() }
                val finalData = JSONObject(state.toString())
                lifecycleScope.launch(Dispatchers.IO) {
                    if (editingId != null) {
                        dbHelper.updateCustomTemplate(editingId, t, finalData.toString())
                    } else {
                        dbHelper.saveCustomTemplate(t, finalData.toString())
                    }
                    withContext(Dispatchers.Main) {
                        toast(if (isEdit) "✏️ Шаблон «$t» обновлён!" else "📌 Шаблон «$t» сохранен!")
                        bs.dismiss()
                        onSaved?.invoke()
                    }
                }
            }
        }
        root.addView(saveBtn)
        bs.show()
    }

    private fun showAutofillPickerSheet(currentFio: String = "") {
        val (bs, root) = createSheet("⚡ Автозаполнение и Помощник:", "#0288D1")

        val topToolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
        }
        topToolsRow.addView(TextView(this).apply {
            text = "🧰 ПОМОЩНИК СМП"
            textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(12, 18, 12, 18); background = roundBg("#00897B", 14f)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1.15f).apply { rightMargin = 8 }
            setOnClickListener { bs.dismiss(); showHelperSubMenu() }
        })
        topToolsRow.addView(TextView(this).apply {
            text = "🩺 АУДИТ КАРТЫ"
            textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(12, 18, 12, 18); background = roundBg("#1565C0", 14f)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { bs.dismiss(); runCardAudit() }
        })
        root.addView(topToolsRow)

        val exactCardContainer = FrameLayout(this)
        root.addView(exactCardContainer)

        val cleanFio = currentFio.trim()
        if (cleanFio.length >= 3) {
            lifecycleScope.launch(Dispatchers.IO) {
                dbHelper.getBestCardForPatient(cleanFio)?.let { card ->
                    withContext(Dispatchers.Main) {
                        exactCardContainer.addView(TextView(this@MainActivity).apply {
                            text = "👤 ЗАПОЛНИТЬ ИЗ АРХИВА: ${cleanFio.uppercase()}"
                            textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                            setPadding(20, 20, 20, 20); background = roundBg("#2E7D32", 16f)
                            layoutParams = FrameLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
                            setOnClickListener { bs.dismiss(); webView.evaluateJavascript("setAndApplyAutofill(${JSONObject.quote(card)});", null); toast("⚡ Заполнено!") }
                        })
                    }
                }
            }
        }

        val builtinCount = dbHelper.getBuiltinTemplateCountOnly()
        var showT = true
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 10) }
        val t1 = TextView(this).apply { text = "⭐ Образцы ($builtinCount)"; gravity = Gravity.CENTER; setPadding(16, 14, 16, 14); layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 8 } }
        val t2 = TextView(this).apply { text = "📂 Мой архив"; gravity = Gravity.CENTER; setPadding(16, 14, 16, 14); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        tabs.addView(t1); tabs.addView(t2); root.addView(tabs)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
        }
        actionRow.addView(TextView(this).apply {
            text = "➕ СВОЙ ШАБЛОН"
            textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#004D40")); gravity = Gravity.CENTER
            setPadding(12, 16, 12, 16); background = roundBg("#E0F2F1", 14f, "#00897B")
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 8 }
            setOnClickListener {
                bs.dismiss()
                showCreateTemplateDialog(null) { showAutofillPickerSheet(currentFio) }
            }
        })
        actionRow.addView(TextView(this).apply {
            text = "💊 ПРЕПАРАТЫ"
            textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#6A1B9A")); gravity = Gravity.CENTER
            setPadding(12, 16, 12, 16); background = roundBg("#F3E5F5", 14f, "#8E24AA")
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener {
                bs.dismiss()
                openMedPresetsSheet()
            }
        })
        root.addView(actionRow)

        val qInp = EditText(this).apply {
            hint = "Поиск (СЛР, диагноз, фамилия)..."
            setPadding(30, 22, 30, 22)
            MedicalTools.styleEditText(this, "#F0F8FF", "#90CAF9", 18f)
        }
        root.addView(qInp)
        val lv = makeListView(root)

        fun update(q: String) {
            t1.typeface = if (showT) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; t1.setTextColor(if (showT) Color.WHITE else Color.parseColor("#004D40")); t1.background = roundBg(if (showT) "#00796B" else "#E0F2F1", 14f)
            t2.typeface = if (!showT) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; t2.setTextColor(if (!showT) Color.WHITE else Color.parseColor("#004D40")); t2.background = roundBg(if (!showT) "#00796B" else "#E0F2F1", 14f)
            lifecycleScope.launch(Dispatchers.IO) {
                if (showT) {
                    val list = dbHelper.searchBuiltInTemplates(q)
                    withContext(Dispatchers.Main) {
                        if (q.isEmpty()) t1.text = "⭐ Образцы (${list.size})"
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
        val initialTitle = if (fullMkbList.isEmpty()) "📖 Справочник МКБ-10 (загрузка...)" else "📖 Справочник МКБ-10 (${fullMkbList.size} кодов)"
        val (bs, root) = createSheet(initialTitle, "#00695C")
        val titleTv = root.getChildAt(1) as? TextView

        val cats = listOf("🌟 Все" to "ALL", "❤️ I Кардио" to "I", "🫁 J Дыхание" to "J", "🧠 G Невро" to "G", "🩹 S Травмы" to "S", "🧪 T Токсико" to "T", "🚨 R Симптомы" to "R", "🤢 K ЖКТ" to "K", "🩸 E Эндокрин" to "E", "🤰 O Акушерство" to "O", "🏥 Z Прочее" to "Z", "🦠 A/B Инфекции" to "AB", "🤪 F Психиатрия" to "F", "🚽 N Урология" to "N", "🦴 M Кости" to "M", "🎗️ C/D Онко" to "CD", "👂 H ЛОР" to "H", "👶 P/Q Дети" to "PQ", "⚠️ V-Y Причины" to "VY")
        var selCat = "ALL"
        val chipsLay = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setPadding(0, 0, 0, 12); addView(chipsLay) })
        val qInp = EditText(this).apply {
            hint = "Поиск (I48, инфаркт, пневмония)..."
            setPadding(30, 22, 30, 22)
            MedicalTools.styleEditText(this, "#E0F2F1", "#80CBC4", 18f)
        }
        root.addView(qInp)
        val lv = makeListView(root)

        fun filter() {
            if (fullMkbList.isEmpty() && mkbLoadJob?.isActive == true) {
                setupAdapter(lv, listOf("⏳ Загрузка базы МКБ-10 (12 500+ кодов)..."), "#F7FAFA", "#E0EAEA") {}
                return
            }
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

        if (fullMkbList.isEmpty() && mkbLoadJob?.isActive == true) {
            lifecycleScope.launch {
                mkbLoadJob?.join()
                if (bs.isShowing) {
                    titleTv?.text = "📖 Справочник МКБ-10 (${fullMkbList.size} кодов)"
                    filter()
                }
            }
        }
    }

    private fun openSearchSheet(isBest: Boolean) {
        val builtinCount = dbHelper.getBuiltinTemplateCountOnly()
        val (bs, root) = createSheet(if (isBest) "⭐ Образцы карт ($builtinCount шаблонов)" else "📂 Общий архив вызовов", if (isBest) "#E65100" else "#004D40")
        val titleTv = root.getChildAt(1) as? TextView

        if (isBest) {
            root.addView(TextView(this).apply {
                text = "➕ СОЗДАТЬ СВОЙ ШАБЛОН"
                textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#E65100")); gravity = Gravity.CENTER
                setPadding(16, 16, 16, 16); background = roundBg("#FFF8E1", 14f, "#E65100")
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
                setOnClickListener {
                    bs.dismiss()
                    showCreateTemplateDialog(null) { openSearchSheet(true) }
                }
            })
        }

        val qInp = EditText(this).apply {
            hint = if (isBest) "Поиск по диагнозу или СЛР..." else "Поиск по фамилии..."
            setPadding(30, 22, 30, 22)
            MedicalTools.styleEditText(this, if (isBest) "#FFF8E1" else "#F0F4F4", if (isBest) "#FFE082" else "#B0BEC5", 18f)
        }
        root.addView(qInp)
        val lv = makeListView(root)
        val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

        fun run(q: String) = lifecycleScope.launch(Dispatchers.IO) {
            if (isBest) {
                val list = dbHelper.searchBuiltInTemplates(q)
                withContext(Dispatchers.Main) {
                    if (q.isEmpty()) titleTv?.text = "⭐ Образцы карт (${list.size} шаблонов)"
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
            setPadding(24, 16, 24, 16); background = roundBg("#0288D1", 16f); layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = if (s.isCustom) 8 else 0 }
            setOnClickListener { bs.dismiss(); webView.evaluateJavascript("setAndApplyAutofill(${JSONObject.quote(s.jsonData)});", null); toast("⚡ Шаблон вставлен!") }
        })
        if (s.isCustom) {
            bar.addView(TextView(this).apply {
                text = "✏️"; textSize = 17f; gravity = Gravity.CENTER
                setPadding(20, 14, 20, 14); background = roundBg("#FFF3E0", 16f, "#FFB74D")
                layoutParams = LinearLayout.LayoutParams(-2, -1).apply { rightMargin = 8 }
                setOnClickListener {
                    bs.dismiss()
                    showCreateTemplateDialog(
                        prefillJson = s.jsonData,
                        editingId = s.id,
                        initialTitle = s.title
                    ) { openSearchSheet(true) }
                }
            })
            bar.addView(ImageButton(this).apply {
                contentDescription = "Удалить шаблон"
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
            val delBtn = ImageButton(this@MainActivity).apply {
                contentDescription = "Удалить вызов"
                setImageResource(android.R.drawable.ic_menu_delete); background = roundBg("#FFEBEE", 14f); setPadding(16, 12, 16, 12)
            }
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
                    val inp = EditText(this@MainActivity).apply {
                        setText(cleanJsonVal(j.opt("diagnosis")).ifEmpty { cleanJsonVal(j.opt("callReason")) }.take(45))
                        hint = "Название шаблона..."
                        setPadding(30, 20, 30, 20)
                        setTextColor(Color.BLACK)
                        setHintTextColor(Color.parseColor("#546E7A"))
                    }
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
                if (cleanJsonVal(j.opt("pulse")).isNotEmpty()) addB("💓 ${cleanJsonVal(j.opt("pulse"))}", "#D81B60")
                if (cleanJsonVal(j.opt("saturation")).isNotEmpty()) addB("🫁 ${cleanJsonVal(j.opt("saturation"))}%", "#00897B")
                if (cleanJsonVal(j.opt("glucose")).isNotEmpty()) addB("🩸 ${cleanJsonVal(j.opt("glucose"))}", "#8E24AA")
                if (cleanJsonVal(j.opt("glasgowScore")).isNotEmpty()) addB("🧠 ${cleanJsonVal(j.opt("glasgowScore"))} б", "#3949AB")

                details.text = formatCardText(itm.jsonData)
                sv.post { sv.scrollTo(0, 0) }
            }
            render(); bs.show()
        }
    }

    private val dateInStringRegex = Regex("""\d{2}\.\d{2}\.\d{4}""")

    private fun cleanJsonVal(v: Any?): String {
        if (v == null || v == JSONObject.NULL) return ""
        if (v is String) {
            val s = v.trim()
            return if (s == "null" || s.startsWith("[object") || s.startsWith("{")) "" else s
        }
        if (v is JSONObject) {
            for (k in listOf("type", "name", "display", "title")) {
                val s = v.optString(k).replace("null", "").trim()
                if (s.isNotEmpty() && !s.startsWith("{")) return s
            }
            return ""
        }
        if (v is JSONArray) {
            return (0 until v.length()).mapNotNull { i ->
                val s = cleanJsonVal(v.opt(i))
                if (s.isNotEmpty()) s else null
            }.joinToString(", ")
        }
        return v.toString().replace("null", "").trim()
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
        return if (s.contains("ул.") || dateInStringRegex.containsMatchIn(s)) "" else s
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
            fun add(label: String, key: String, suf: String = "") {
                val v = cleanJsonVal(j.opt(key))
                if (v.isNotEmpty()) append("$label: $v$suf\n")
            }
            add("🎂 Родился", "birthDate")
            add("⏳ Возраст", "age", " ${cleanJsonVal(j.opt("ageUnit"))}".trimEnd())
            add("⚧ Пол", "gender")
            add("🌍 Гражданство", "citizenship")
            add("📍 Адрес", "address")
            add("🚨 Повод", "callReason")
            add("🏥 АПО", "apo")

            append("\n📋 ДИАГНОЗ:\n• ${cleanJsonVal(j.opt("diagnosis"))}\n")
            add("• Код МКБ", "mkb")
            val th = cleanJsonVal(j.opt("recommendedTherapy"))
            if (th.isNotEmpty()) append("\n💊 ПРОТОКОЛЬНАЯ ТЕРАПИЯ (подсказка):\n$th\n")

            if (cleanJsonVal(j.opt("ecgBefore")).isNotEmpty() || cleanJsonVal(j.opt("ecgAfter")).isNotEmpty()) {
                append("\n📈 ЭКГ:\n")
                add("• До помощи", "ecgBefore")
                add("• После помощи", "ecgAfter")
            }

            append("\n🩺 ОБЪЕКТИВНО:\n")
            add("• Состояние", "generalState")
            add("• Сознание", "consciousness")
            val pDesc = cleanJsonVal(j.opt("positionDescription"))
            add("• Положение", "position", if (pDesc.isNotEmpty()) " ($pDesc)" else "")
            add("• Поведение", "behavior")
            add("• Глазго", "glasgowScore", " б")
            val bpAd = cleanBpString(j.optString("bpAdapted"))
            if (bpAd.isNotEmpty()) append("• Привычное АД: $bpAd\n")
            val bp = cleanBpString(j.optString("bp"))
            if (bp.isNotEmpty()) append("• АД на вызове: $bp\n")
            add("• Пульс", "pulse", " уд/мин")
            add("• ЧДД", "rr", " в мин")
            add("• SpO2", "saturation", "%")
            add("• Сахар", "glucose", " ммоль/л")
            add("• Температура", "temp", "°C")
            add("• Тоны сердца", "heartSounds")
            add("• Дыхание", "lungs")
            add("• Перкуссия", "percussion")
            add("• Живот", "abdomen")
            add("• Асцит", "ascites")
            add("• Кожа", "skin")
            add("• Высыпания", "rash")
            val eDesc = cleanJsonVal(j.opt("edemaDescription"))
            add("• Отеки", "edema", if (eDesc.isNotEmpty()) " ($eDesc)" else "")
            add("• Зрачки", "pupils")
            add("• Реакция на свет", "lightReaction")
            add("• Речь", "speech")
            add("• Лицо", "face")
            add("• Походка", "gait")
            add("• Нистагм", "nystagmus")
            add("• Тонус мышц", "muscleTone")
            add("• Пат. рефлексы", "pathologicalReflexes")
            add("• Менингеальные", "meningealSymptoms")
            add("• Плегии/парезы", "plegia")
            add("• Язык", "tongue")
            add("• Слизистые", "mucous")
            add("• Перистальтика", "peristalsis")
            add("• Печень", "liver")
            add("• Брюшина", "peritoneal")
            add("• Мочеиспускание", "urination")
            add("• Стул", "stool")
            add("• Запах алкоголя", "alcohol")
            add("• Запах ацетона", "acetone")
            add("• Локально", "localStatus")

            if (cleanJsonVal(j.opt("complaints")).isNotEmpty()) append("\n🗣️ Жалобы:\n${cleanJsonVal(j.opt("complaints"))}\n")
            if (cleanJsonVal(j.opt("anamnesis")).isNotEmpty()) append("\n📖 Анамнез:\n${cleanJsonVal(j.opt("anamnesis"))}\n")
            if (cleanJsonVal(j.opt("pastDiseases")).isNotEmpty()) append("\n🩹 Перенесенные: ${cleanJsonVal(j.opt("pastDiseases"))}\n")
            add("⚠️ Аллергия", "allergy")

            val meds = cleanMedicationsString(j.optString("medications"))
            if (meds.isNotEmpty() || cleanJsonVal(j.opt("attack")).isNotEmpty() || cleanJsonVal(j.opt("therapyEffect")).isNotEmpty() || cleanJsonVal(j.opt("callResult")).isNotEmpty()) {
                append("\n💉 ОКАЗАННАЯ ПОМОЩЬ:\n")
                add("• Помощь", "medHelp")
                if (meds.isNotEmpty()) append("• Препараты:\n• $meds\n")
                add("• Приступ", "attack")
                add("• Эффект", "therapyEffect")
                add("• Итог", "callResult")
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
            if (kl.contains("unit") || kl.contains("way") || kl.contains("route") || kl.contains("id") || kl in listOf("count", "amount", "quantity", "number")) continue
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
        val fio = cleanJsonVal(p.opt("fullName")).ifEmpty { cleanJsonVal(raw.opt("initialFullName")) }.trim()
        if (fio.isEmpty()) null else {
            val regDateOnly = raw.optString("dateOfRegistration").substringBefore("T")
            val rawBDate = p.optString("birthDate").replace("null", "")
            val ageUnit = raw.optJSONObject("ageType")?.optString("type")?.ifEmpty { raw.optString("ageType") } ?: raw.optString("ageType")
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
                    val medObj = m.optJSONObject("medication")
                    val name = medObj?.optString("name")?.replace("null", "")?.trim()?.takeIf { it.isNotEmpty() }
                        ?: extractName(m, "medication", "medicine", "drug", "nomenclature", "preparation", "med", "name", "title")
                    val cnt = listOf("number", "count", "amount", "quantity", "dose", "value").firstNotNullOfOrNull { k -> m.optString(k).replace("null", "").trim().takeIf { it.isNotEmpty() && !it.startsWith("{") } } ?: ""
                    val unit = (medObj?.optJSONObject("type") ?: medObj?.optJSONObject("unitsType") ?: m.optJSONObject("unit") ?: m.optJSONObject("measurement"))?.let { it.optString("name").ifEmpty { it.optString("type") }.replace("null", "").trim() } ?: ""
                    val way = (m.optJSONObject("routeDict") ?: m.optJSONObject("wayOfAdministration") ?: m.optJSONObject("way") ?: m.optJSONObject("route"))?.let { it.optString("display").ifEmpty { it.optString("type") }.ifEmpty { it.optString("name") }.replace("null", "").trim() } ?: ""
                    if (name.isEmpty()) null else "$name${listOf(cnt, unit).filter { it.isNotEmpty() }.joinToString(" ").let { if (it.isNotEmpty()) " — $it" else "" }}${if (way.isNotEmpty() && !way.contains("не определен")) " [$way]" else ""}"
                }.joinToString(";\n• ")
            }

            val complaints = cleanJsonVal(raw.opt("complaints"))
            val anamnesis = cleanJsonVal(raw.opt("anamnesis"))
            val diag = cleanJsonVal(raw.opt("diagnosis"))
            val meds = parseMeds()
            val bp = cleanBpString(raw.optString("arterialPressure"))
            val bpAd = cleanBpString(raw.optString("arterialPressureAdaptive"))
            var score = 0; if (anamnesis.length > 10) score += 35; if (complaints.isNotEmpty()) score += 25; if (diag.isNotEmpty()) score += 25; if (meds.isNotEmpty() || bp.isNotEmpty()) score += 15
            val icd = raw.optJSONObject("icd")

            val liverVal = when {
                raw.optJSONObject("liver") != null -> raw.optJSONObject("liver")?.optString("type")?.uppercase() ?: "НЕ УВЕЛИЧЕНА"
                raw.has("liver") && !raw.isNull("liver") -> if (raw.optBoolean("liver", false)) "УВЕЛИЧЕНА" else "НЕ УВЕЛИЧЕНА"
                else -> "НЕ УВЕЛИЧЕНА"
            }

            val attackVal = when {
                raw.optJSONObject("postAttack") != null -> raw.optJSONObject("postAttack")?.optString("type")?.uppercase() ?: ""
                raw.has("postAttack") && !raw.isNull("postAttack") -> {
                    val v = raw.opt("postAttack")
                    if (v is Boolean) {
                        if (v) "КУПИРОВАН" else "НЕ КУПИРОВАН"
                    } else {
                        v.toString().trim().uppercase().replace("NULL", "")
                    }
                }
                else -> ""
            }

            val scrGender = cleanJsonVal(raw.opt("_screenGender")).uppercase()
            val genderVal = when {
                scrGender.startsWith("М") -> "МУЖЧИНА"
                scrGender.startsWith("Ж") -> "ЖЕНЩИНА"
                p.has("gender") && !p.isNull("gender") -> if (p.optBoolean("gender", false)) "МУЖЧИНА" else "ЖЕНЩИНА"
                else -> ""
            }

            val citizenship = cleanJsonVal(raw.opt("citizenship")).ifEmpty {
                cleanJsonVal(p.opt("countryOfRegistration"))
            }

            val settlement = cleanJsonVal(raw.opt("settlement")).ifEmpty {
                cleanJsonVal(p.opt("settlement"))
            }

            val street = cleanJsonVal(raw.opt("street")).ifEmpty {
                cleanJsonVal(p.opt("street"))
            }

            val building = cleanJsonVal(raw.opt("building")).ifEmpty {
                cleanJsonVal(p.opt("building"))
            }

            val flat = cleanJsonVal(raw.opt("flat")).ifEmpty {
                cleanJsonVal(p.opt("flat"))
            }

            val clarificationAddress = cleanJsonVal(raw.opt("clarificationAddress")).ifEmpty {
                cleanJsonVal(p.opt("clarificationAddress"))
            }

            val address = cleanJsonVal(raw.opt("address")).ifEmpty {
                cleanJsonVal(p.opt("placeOfRegistration"))
            }

            val apo = cleanJsonVal(raw.opt("apo")).ifEmpty {
                cleanJsonVal(p.opt("territorialMedicalFacility"))
            }

            val card = JSONObject().apply {
                put("fio", fio)
                put("birthDate", bDate)
                put("age", cleanJsonVal(raw.opt("age")))
                put("ageUnit", cleanJsonVal(ageUnit))
                put("citizenship", citizenship)
                put("settlement", settlement)
                put("street", street)
                put("building", building)
                put("flat", flat)
                put("clarificationAddress", clarificationAddress)
                put("address", address)
                put("apo", apo)
                put("callReason", cleanJsonVal(raw.optJSONObject("reasonToCall")?.opt("type")))
                put("gender", genderVal)
                put("complaints", complaints)
                put("anamnesis", anamnesis)
                put("pastDiseases", cleanJsonVal(raw.opt("pastIllnesses")))
                put("allergy", if (raw.optBoolean("allergy", false)) "ДА" else "НЕТ")
                put("generalState", cleanJsonVal(raw.optJSONObject("generalState")?.opt("type")))
                put("consciousness", cleanJsonVal(raw.optJSONObject("consciousness")?.opt("type")))
                put("position", cleanJsonVal(raw.optJSONObject("position")?.opt("type")).uppercase())
                put("positionDescription", cleanJsonVal(raw.opt("positionDescription")))
                put("behavior", cleanJsonVal(raw.optJSONObject("behavior")?.opt("type")).uppercase())
                put("glasgowScore", cleanJsonVal(raw.opt("comaScale")).ifEmpty { "15" })
                put("bp", bp)
                put("bpAdapted", bpAd)
                put("pulseExists", if (raw.optBoolean("pulseExists", true)) "ДА" else "НЕТ")
                put("pulse", cleanJsonVal(raw.opt("pulse")))
                put("rr", cleanJsonVal(raw.opt("breathCount")))
                put("temp", cleanJsonVal(raw.opt("skinTemp")))
                put("saturation", cleanJsonVal(raw.opt("saturation1")))
                put("glucose", cleanJsonVal(raw.opt("glycemia1")))
                put("heartSounds", jArr("heartSounds"))
                put("lungs", jArr("breaths"))
                put("percussion", jArr("percussionOfLungs"))
                put("abdomen", jArr("stomachs"))
                put("skin", jArr("skins"))
                put("urination", jArr("urinations").uppercase())
                put("stool", cleanJsonVal(raw.optJSONObject("defecatio")?.opt("type")).uppercase())
                put("edema", if (raw.optBoolean("peripheralEdema", false)) "ДА" else "НЕТ")
                put("edemaDescription", cleanJsonVal(raw.opt("peripheralEdemaDescription")))
                put("rash", if (raw.optBoolean("skinRashes", false)) "ДА" else "НЕТ")
                put("ascites", if (raw.optBoolean("ascites", false)) "ДА" else "НЕТ")
                put("peritoneal", if (raw.optBoolean("stomachSymptoms", false)) "ДА" else "НЕТ")
                val hasAlc = raw.optBoolean("alcoholSmell", raw.optBoolean("alcohol", raw.optBoolean("smellAlcohol", false)))
                val hasAct = raw.optBoolean("acetoneSmell", raw.optBoolean("acetone", raw.optBoolean("smellAcetone", false)))
                put("alcohol", if (hasAlc) "ДА" else "НЕТ")
                put("acetone", if (hasAct) "ДА" else "НЕТ")
                put("medHelp", jArr("medicalAssistanceProvided"))
                put("medications", meds)
                put("pupils", cleanJsonVal(raw.optJSONObject("pupils")?.opt("type")).uppercase())
                put("lightReaction", if (raw.optBoolean("pupilsLight", true)) "ДА" else "НЕТ")
                put("speech", cleanJsonVal(raw.optJSONObject("speech")?.opt("type")).uppercase())
                put("gait", cleanJsonVal(raw.optJSONObject("gait")?.opt("type")).uppercase())
                put("face", cleanJsonVal(raw.optJSONObject("face")?.opt("type")).uppercase())
                put("nystagmus", cleanJsonVal(raw.optJSONObject("nystagmus")?.opt("type")).uppercase())
                put("muscleTone", cleanJsonVal(raw.opt("muscleTone")).ifEmpty { "Нормальный" })
                put("pathologicalReflexes", cleanJsonVal(raw.opt("pathologicalReflexes")).ifEmpty { "Отрицательные" })
                put("meningealSymptoms", cleanJsonVal(raw.opt("meningealSymptoms")).ifEmpty { "Отрицательные" })
                put("plegia", cleanJsonVal(raw.opt("plegia")).ifEmpty { "Нет" })
                put("tongue", cleanJsonVal(raw.opt("tongue")).ifEmpty { "Чистые" })
                put("mucous", cleanJsonVal(raw.opt("mucosa")).ifEmpty { "Бледно-розовые" })
                put("liver", liverVal)
                put("peristalsis", if (raw.optBoolean("peristalsis", true)) "ДА" else "НЕТ")
                put("localStatus", cleanJsonVal(raw.opt("localStatus")))
                put("ecgBefore", cleanJsonVal(raw.opt("electrocardiogramBefore")))
                put("ecgAfter", cleanJsonVal(raw.opt("electrocardiogramAfter")))
                put("diagnosis", diag)
                put("mkb", if (icd != null) "[${icd.optString("code")}] ${icd.optString("type").ifEmpty { icd.optString("name") }}".trim() else "")
                put("attack", attackVal)
                put("therapyEffect", cleanJsonVal(raw.optJSONObject("result")?.opt("type")).uppercase())
                put("callResult", cleanJsonVal(raw.optJSONObject("resultDeparture")?.opt("type")).uppercase())
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
        @JavascriptInterface fun showDebugDialog(title: String, body: String) = runOnUiThread {
            AlertDialog.Builder(this@MainActivity)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton("📋 Копировать") { _, _ ->
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("SSMP Debug", body))
                    toast("📋 Лог скопирован")
                }
                .setNegativeButton("Закрыть", null)
                .show()
        }
        @JavascriptInterface fun requestAutofill(fio: String) = runOnUiThread { showAutofillPickerSheet(fio) }

        @JavascriptInterface
        fun cacheMedCatalog(jsonStr: String) {
            saveCachedMedCatalog(jsonStr)
            runOnUiThread { onMedCatalogUpdated?.invoke(jsonStr) }
        }

        @JavascriptInterface
        fun onMedSearchResult(reqId: String, jsonStr: String) = runOnUiThread {
            if (reqId == activeMedSearchReqId) {
                onMedSearchCallback?.invoke(parseMedItemsArray(jsonStr))
            }
        }

        @JavascriptInterface
        fun cacheSiteMkb(jsonStr: String) {
            val parsed = parseMkbItemsArray(jsonStr)
            if (parsed.isNotEmpty()) {
                siteMkbList = parsed
                try {
                    getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString(keySiteMkb, jsonStr).apply()
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun onMkbSearchResult(reqId: String, jsonStr: String) = runOnUiThread {
            if (reqId == activeMkbSearchReqId) {
                onMkbSearchCallback?.invoke(parseMkbItemsArray(jsonStr))
            }
        }

        @JavascriptInterface
        fun saveApiCardForced(jsonStr: String) = lifecycleScope.launch(Dispatchers.IO) {
            parseServerCardJson(jsonStr)?.let { (fio, bDate, sc) ->
                dbHelper.saveOrUpdateCard(fio, bDate, sc.first, sc.second.toString())
                withContext(Dispatchers.Main) { toast("💾 Карта $fio сохранена в архив!") }
            }
        }
    }
}
