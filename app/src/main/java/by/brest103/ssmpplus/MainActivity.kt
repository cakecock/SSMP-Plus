package by.brest103.ssmpplus

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var dbHelper: DatabaseHelper

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dbHelper = DatabaseHelper(this)
        webView = findViewById(R.id.webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
        }

        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectScript()
            }
        }

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
                        // Экранируем одинарные кавычки для безопасной передачи в JS
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
