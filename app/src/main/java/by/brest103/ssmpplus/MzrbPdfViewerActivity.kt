package by.brest103.ssmpplus

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Встроенный просмотр PDF каталога МЗ РБ.
 *
 * Использует только системный PdfRenderer (API 21+) — без новых Gradle-зависимостей.
 * Файл скачивается с официальной ссылки minzdrav.gov.by в кэш приложения,
 * поэтому протокол открывается прямо внутри приложения. Кнопка «Внешний»
 * оставляет запасной путь через системный просмотрщик.
 */
class MzrbPdfViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_pdf_url"
        const val EXTRA_TITLE = "extra_pdf_title"
    }

    private lateinit var titleView: TextView
    private lateinit var pageView: TextView
    private lateinit var statusView: TextView
    private lateinit var imageView: ImageView
    private lateinit var verticalScroll: ScrollView
    private lateinit var horizontalScroll: HorizontalScrollView
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button

    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var bitmap: Bitmap? = null
    private var pageCount = 0
    private var currentPage = 0
    private var zoom = 1f
    private var pdfUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pdfUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val rawTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val title = rawTitle.ifBlank { "Протокол МЗ РБ" }

        if (pdfUrl.isBlank()) {
            Toast.makeText(this, "Ссылка на протокол не передана", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        buildUi(title)
        loadPdf()
    }

    private fun buildUi(title: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 8)
        }
        titleView = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.parseColor("#1565C0"))
            setLineSpacing(2f, 1.1f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        header.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        statusView = TextView(this).apply {
            text = "Загрузка протокола..."
            textSize = 12.5f
            setTextColor(Color.parseColor("#546E7A"))
            setPadding(16, 4, 16, 8)
        }
        root.addView(statusView, LinearLayout.LayoutParams(-1, -2))

        verticalScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#ECEFF1"))
            isFillViewport = false
        }
        horizontalScroll = HorizontalScrollView(this).apply {
            isFillViewport = true
        }
        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER
            setBackgroundColor(Color.WHITE)
        }
        horizontalScroll.addView(imageView, ViewGroup.LayoutParams(-2, -2))
        verticalScroll.addView(horizontalScroll, ViewGroup.LayoutParams(-1, -2))
        root.addView(verticalScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Перелистывание — под документом, сразу над остальными действиями.
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 12, 2)
        }
        prevButton = Button(this).apply {
            text = "◀"
            textSize = 16f
            isEnabled = false
            setAllCaps(false)
            layoutParams = LinearLayout.LayoutParams(72, -2).apply { rightMargin = 8 }
        }
        pageView = TextView(this).apply {
            text = "—"
            gravity = Gravity.CENTER
            textSize = 13.5f
            setTextColor(Color.parseColor("#546E7A"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        nextButton = Button(this).apply {
            text = "▶"
            textSize = 16f
            isEnabled = false
            setAllCaps(false)
            layoutParams = LinearLayout.LayoutParams(72, -2).apply { leftMargin = 8 }
        }
        nav.addView(prevButton)
        nav.addView(pageView)
        nav.addView(nextButton)
        root.addView(nav, LinearLayout.LayoutParams(-1, -2))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 10, 12, 14)
        }
        val zoomOut = actionButton("−")
        val zoomIn = actionButton("＋")
        val external = actionButton("Внешний")
        val close = actionButton("Закрыть")
        listOf(zoomOut, zoomIn, external, close).forEach { actions.addView(it) }
        root.addView(actions, LinearLayout.LayoutParams(-1, -2))

        prevButton.setOnClickListener { showPage(currentPage - 1) }
        nextButton.setOnClickListener { showPage(currentPage + 1) }
        zoomOut.setOnClickListener { changeZoom(-0.5f) }
        zoomIn.setOnClickListener { changeZoom(0.5f) }
        external.setOnClickListener { openExternal() }
        close.setOnClickListener { finish() }

        setContentView(root)
    }

    private fun actionButton(text: String): Button = Button(this).apply {
        this.text = text
        textSize = 12.5f
        setAllCaps(false)
        setTextColor(Color.WHITE)
        background = MedicalTools.roundBg("#1565C0", 10f)
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = 4
            rightMargin = 4
        }
    }

    private fun isActiveScreen(): Boolean = !isFinishing && !isDestroyed

    private fun loadPdf() {
        statusView.visibility = View.VISIBLE
        statusView.text = "Загрузка протокола..."

        lifecycleScope.launch(Dispatchers.IO) {
            var target: File? = null
            try {
                val dir = File(cacheDir, "mzrb_pdf")
                if (!dir.exists() && !dir.mkdirs()) throw Exception("Не удалось создать кэш PDF")
                val file = File(dir, "protocol_" + Integer.toHexString(pdfUrl.hashCode()) + ".pdf")
                target = file

                val weekMs = 7L * 24 * 60 * 60 * 1000
                val stale = file.exists() && file.lastModified() < System.currentTimeMillis() - weekMs
                if (!hasPdfHeader(file) || stale) {
                    val temp = File(dir, file.name + ".tmp")
                    try {
                        downloadPdf(pdfUrl, temp)
                        if (hasPdfHeader(temp)) {
                            if (!temp.renameTo(file)) {
                                temp.copyTo(file, overwrite = true)
                                temp.delete()
                            }
                        } else {
                            temp.delete()
                            if (!hasPdfHeader(file)) throw Exception("Сервер вернул не PDF")
                        }
                    } catch (e: Exception) {
                        temp.delete()
                        if (!hasPdfHeader(file)) throw e
                        // Есть рабочий старый кэш — показываем его, если сеть не ответила.
                    }
                }
                if (!hasPdfHeader(file)) throw Exception("Сервер вернул не PDF")

                withContext(Dispatchers.Main) {
                    if (!isActiveScreen()) return@withContext
                    openRenderer(file)
                }
            } catch (e: Throwable) {
                target?.let { if (it.exists() && !hasPdfHeader(it)) it.delete() }
                val message = e.message ?: "неизвестная ошибка"
                withContext(Dispatchers.Main) {
                    if (!isActiveScreen()) return@withContext
                    statusView.visibility = View.VISIBLE
                    statusView.text = "Не удалось открыть PDF: $message\n" +
                        "Можно открыть внешним просмотрщиком."
                    prevButton.isEnabled = false
                    nextButton.isEnabled = false
                }
            }
        }
    }

    private fun downloadPdf(url: String, target: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "SSMP-Plus/3.10")
            conn.setRequestProperty("Accept", "application/pdf,*/*")
            val code = conn.responseCode
            if (code !in 200..299) throw Exception("HTTP $code")
            conn.inputStream.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output, 16 * 1024) }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun hasPdfHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            FileInputStream(file).use { input ->
                val head = ByteArray(4)
                input.read(head) == 4 &&
                    head[0] == 37.toByte() && head[1] == 80.toByte() &&
                    head[2] == 68.toByte() && head[3] == 70.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun openRenderer(file: File) {
        releaseRenderer()
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        descriptor = fd
        val r = PdfRenderer(fd)
        renderer = r
        pageCount = r.pageCount

        if (pageCount == 0) {
            statusView.visibility = View.VISIBLE
            statusView.text = "PDF не содержит страниц"
            return
        }

        zoom = 1f
        statusView.visibility = View.GONE
        showPage(0)
    }

    private fun changeZoom(delta: Float) {
        val next = (zoom + delta).coerceIn(1f, 2.5f)
        if (next == zoom) return
        zoom = next
        showPage(currentPage)
    }

    private fun showPage(index: Int) {
        val r = renderer ?: return
        if (pageCount == 0 || index !in 0 until pageCount) return

        try {
            val page = r.openPage(index)
            val nextBitmap: Bitmap
            try {
                var scale = (resources.displayMetrics.widthPixels * zoom) / page.width.toFloat()
                val maxWidth = 2400f
                val maxHeight = 3000f
                if (page.width > 0 && page.width * scale > maxWidth) scale = maxWidth / page.width
                if (page.height > 0 && page.height * scale > maxHeight) scale = maxHeight / page.height
                val width = maxOf(1, (page.width * scale).toInt())
                val height = maxOf(1, (page.height * scale).toInt())

                nextBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                nextBitmap.eraseColor(Color.WHITE)
                page.render(nextBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally {
                page.close()
            }

            val old = bitmap
            bitmap = nextBitmap
            imageView.setImageBitmap(nextBitmap)
            old?.let { if (!it.isRecycled) it.recycle() }

            // Новая страница должна начинаться с верхнего левого угла,
            // иначе остаётся прокрутка предыдущей страницы.
            verticalScroll.post {
                verticalScroll.scrollTo(0, 0)
                horizontalScroll.scrollTo(0, 0)
            }

            currentPage = index
            pageView.text = "${index + 1} / $pageCount  •  ${Math.round(zoom * 100)}%"
            prevButton.isEnabled = index > 0
            nextButton.isEnabled = index < pageCount - 1
        } catch (e: OutOfMemoryError) {
            if (zoom > 1f) {
                zoom = 1f
                Toast.makeText(this, "Не хватило памяти — уменьшил масштаб", Toast.LENGTH_SHORT).show()
                showPage(index)
            } else {
                statusView.visibility = View.VISIBLE
                statusView.text = "Не хватило памяти для отображения страницы"
            }
        } catch (e: Exception) {
            statusView.visibility = View.VISIBLE
            statusView.text = "Ошибка отображения: ${e.message}"
        }
    }

    private fun openExternal() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pdfUrl))
        try {
            startActivity(Intent.createChooser(intent, "Открыть PDF внешним просмотрщиком"))
        } catch (_: Exception) {
            Toast.makeText(this, "Не найден внешний просмотрщик PDF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun releaseRenderer() {
        try { renderer?.close() } catch (_: Exception) {}
        renderer = null
        try { descriptor?.close() } catch (_: Exception) {}
        descriptor = null
        bitmap?.let { if (!it.isRecycled) it.recycle() }
        bitmap = null
        if (::imageView.isInitialized) imageView.setImageBitmap(null)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (renderer != null) showPage(currentPage)
    }

    override fun onDestroy() {
        releaseRenderer()
        super.onDestroy()
    }
}
