package by.brest103.ssmpplus

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import by.brest103.assistant.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var db: AppDatabase

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = AppDatabase.getInstance(applicationContext)
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
                // Автоматически внедряем наш JS-скрипт при открытии любой страницы
                injectScript()
            }
        }

        webView.loadUrl("https://soft.103brest.by/ssmp/")
    }

    private fun injectScript() {
        val jsCode = assets.open("injector.js").bufferedReader().use { it.readText() }
        webView.evaluateJavascript(jsCode, null)
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun saveCardData(jsonStr: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                val json = JSONObject(jsonStr)
                val fio = json.optString("fio").trim()
                if (fio.isEmpty()) return@launch

                var patient = db.patientDao().findPatientByFio(fio)
                if (patient == null) {
                    val pId = db.patientDao().insertPatient(
                        Patient(fio = fio, birthDate = json.optString("birthDate"))
                    )
                    patient = Patient(id = pId, fio = fio, birthDate = json.optString("birthDate"))
                }

                // Расчет полноты карты (упрощенно)
                var score = 0
                if (json.optString("anamnesis").length > 10) score += 40
                if (json.optString("complaints").isNotEmpty()) score += 30
                if (json.optString("bpAdapted").isNotEmpty()) score += 30

                db.patientDao().insertCard(
                    CardRecord(
                        patientId = patient.id,
                        completenessScore = score,
                        rawJsonData = jsonStr
                    )
                )

                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Карта сохранена в локальную базу", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun requestAutofill(fio: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                val patient = db.patientDao().findPatientByFio(fio.trim())
                if (patient == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "Пациент не найден в базе", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val bestCard = db.patientDao().getBestCardForAutofill(patient.id)
                if (bestCard != null) {
                    withContext(Dispatchers.Main) {
                        // Передаем данные обратно в JS для вставки в поля
                        webView.evaluateJavascript("applyAutofill('${bestCard.rawJsonData}');", null)
                        Toast.makeText(applicationContext, "Данные автозаполнены (полнота: ${bestCard.completenessScore}%)", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
