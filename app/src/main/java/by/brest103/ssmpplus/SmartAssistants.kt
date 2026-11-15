package by.brest103.ssmpplus

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.*
import androidx.core.widget.NestedScrollView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

object SmartAssistants {

    private fun insertToWebField(context: Context, webView: WebView, fieldKw: String, text: String, append: Boolean = false) {
        MedicalTools.copyToClipboard(context, fieldKw, text)
        val js = "window.insertIntoCardField && window.insertIntoCardField(${JSONObject.quote(fieldKw)}, ${JSONObject.quote(text)}, $append);"
        webView.evaluateJavascript(js, null)
        Toast.makeText(context, "⚡ Вставлено в «$fieldKw» (и скопировано)!", Toast.LENGTH_SHORT).show()
    }

    // =========================================================
    // 1. ИНТЕРАКТИВНЫЙ КОНСТРУКТОР ЭКГ (С ВСТАВКОЙ В КАРТУ)
    // =========================================================
    fun showEcgConstructorSheet(context: Context, webView: WebView) {
        val (bs, root) = MedicalTools.createBaseSheet(context, "📈 Конструктор описания ЭКГ", "#00897B")

        val scroll = NestedScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isFillViewport = true
            isNestedScrollingEnabled = true
        }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 4, 0, 20) }
        scroll.addView(content)
        root.addView(scroll)

        var selRhythm = "Ритм синусовый, правильный."
        var hrVal = "78"
        var selEos = "ЭОС нормальная."
        val selConduction = linkedSetOf<String>()
        val selIschemia = linkedSetOf<String>()

        val previewBox = EditText(context).apply {
            textSize = 14.5f
            setTextColor(Color.parseColor("#004D40"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(24, 20, 24, 20)
            background = MedicalTools.roundBg("#E0F2F1", 16f, "#26A69A")
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 }
        }
        content.addView(previewBox)

        fun updatePreview() {
            val sb = StringBuilder()
            sb.append(selRhythm)
            if (hrVal.isNotEmpty()) {
                if (selRhythm.contains("Фибрилляция") || selRhythm.contains("Трепетание")) {
                    sb.append(" ЧЖС $hrVal в мин.")
                } else {
                    sb.append(" ЧСС $hrVal в мин.")
                }
            }
            if (selEos.isNotEmpty()) sb.append(" $selEos")
            if (selConduction.isNotEmpty()) sb.append(" ${selConduction.joinToString(" ")}")
            if (selIschemia.isNotEmpty()) sb.append(" ${selIschemia.joinToString(" ")}")
            else sb.append(" Острой очаговой патологии нет.")
            previewBox.setText(sb.toString().replace("  ", " ").trim())
        }

        // Кнопки прямой вставки в карту
        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 14) }
        fun makeInsertBtn(title: String, color: String, kw: String) = TextView(context).apply {
            text = title; textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(16, 18, 16, 18); background = MedicalTools.roundBg(color, 14f)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 8 }
            setOnClickListener {
                insertToWebField(context, webView, kw, previewBox.text.toString().trim(), false)
                bs.dismiss()
            }
        }
        btnRow.addView(makeInsertBtn("⚡ В ЭКГ ДО ПОМОЩИ", "#00796B", "экг до оказания"))
        btnRow.addView(makeInsertBtn("⚡ В ЭКГ ПОСЛЕ", "#0277BD", "экг после оказания"))
        content.addView(btnRow)

        // Ввод ЧСС
        val hrRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 10) }
        hrRow.addView(TextView(context).apply { text = "💓 ЧСС / ЧЖС (в мин): "; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#263238")) })
        val hrInp = EditText(context).apply {
            setText("78"); inputType = InputType.TYPE_CLASS_TEXT; setPadding(24, 12, 24, 12)
            background = MedicalTools.roundBg("#F0F4F4", 12f, "#B0BEC5"); setTextColor(Color.BLACK)
            setHintTextColor(Color.parseColor("#546E7A"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { hrVal = s.toString().trim(); updatePreview() }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            })
        }
        hrRow.addView(hrInp)
        content.addView(hrRow)

        fun buildSingleGroup(title: String, opts: List<Pair<String, String>>, getCur: () -> String, setCur: (String) -> Unit) {
            content.addView(TextView(context).apply { text = title; textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#00695C")); setPadding(0, 8, 0, 6) })
            val hs = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            hs.addView(row); content.addView(hs)
            fun draw() {
                row.removeAllViews()
                opts.forEach { (shortLbl, fullTxt) ->
                    val sel = getCur() == fullTxt
                    row.addView(TextView(context).apply {
                        text = shortLbl; textSize = 12f; typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        setTextColor(if (sel) Color.WHITE else Color.parseColor("#263238"))
                        setPadding(20, 12, 20, 12); background = MedicalTools.roundBg(if (sel) "#00897B" else "#E0F2F1", 14f)
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8 }
                        setOnClickListener { setCur(fullTxt); draw(); updatePreview() }
                    })
                }
            }
            draw()
        }

        fun buildMultiGroup(title: String, opts: List<Pair<String, String>>, set: MutableSet<String>) {
            content.addView(TextView(context).apply { text = title; textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#00695C")); setPadding(0, 10, 0, 6) })
            val hs = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            hs.addView(row); content.addView(hs)
            fun draw() {
                row.removeAllViews()
                opts.forEach { (shortLbl, fullTxt) ->
                    val sel = set.contains(fullTxt)
                    row.addView(TextView(context).apply {
                        text = "${if (sel) "✓ " else ""}$shortLbl"; textSize = 12f; typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        setTextColor(if (sel) Color.WHITE else Color.parseColor("#263238"))
                        setPadding(20, 12, 20, 12); background = MedicalTools.roundBg(if (sel) "#0277BD" else "#E1F5FE", 14f)
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8 }
                        setOnClickListener {
                            if (sel) set.remove(fullTxt) else set.add(fullTxt)
                            draw(); updatePreview()
                        }
                    })
                }
            }
            draw()
        }

        buildSingleGroup("1. Водитель ритма:", listOf(
            "Синусовый прав." to "Ритм синусовый, правильный.",
            "Синус. тахикардия" to "Синусовая тахикардия.",
            "Синус. брадикардия" to "Синусовая брадикардия.",
            "Синус. аритмия" to "Синусовая аритмия.",
            "ФП (мерцательная)" to "Фибрилляция предсердий, тахисистолическая форма.",
            "ФП нормосистолия" to "Фибрилляция предсердий, нормосистолическая форма.",
            "Трепетание предсердий" to "Трепетание предсердий, правильная форма 2:1.",
            "Пароксизм СВТ" to "Пароксизмальная наджелудочковая тахикардия.",
            "Пароксизм ЖТ" to "Пароксизмальная ширококомплексная желудочковая тахикардия.",
            "Ритм ЭКС" to "Ритм искусственного водителя ритма (ЭКС), навязывание эффективное."
        ), { selRhythm }) { selRhythm = it }

        buildSingleGroup("2. Положение ЭОС:", listOf(
            "Нормальная" to "ЭОС нормальная.",
            "Горизонтальная" to "ЭОС горизонтальная.",
            "Отклонена влево" to "ЭОС отклонена влево.",
            "Резко влево" to "ЭОС резко отклонена влево.",
            "Вертикальная" to "ЭОС вертикальная.",
            "Отклонена вправо" to "ЭОС отклонена вправо."
        ), { selEos }) { selEos = it }

        buildMultiGroup("3. Гипертрофия, блокады и экстрасистолия (можно несколько):", listOf(
            "Признаки ГЛЖ" to "Признаки гипертрофии левого желудочка (ГЛЖ).",
            "Перегрузка ЛЖ" to "Признаки систолической перегрузки ЛЖ.",
            "Перегрузка ПЖ (SI-QIII)" to "Перегрузка правых отделов сердца (синдром SI-QIII-TIII).",
            "Неполная БПНПГ" to "Неполная блокада правой ножки пучка Гиса.",
            "Полная БПНПГ" to "Полная блокада правой ножки пучка Гиса (БПНПГ).",
            "Полная БЛНПГ" to "Полная блокада левой ножки пучка Гиса (БЛНПГ).",
            "БПВЛНПГ" to "Блокада передней ветви левой ножки пучка Гиса.",
            "АВ-блокада I ст." to "АВ-блокада I степени (PQ > 0.20 с).",
            "АВ-блокада II ст." to "АВ-блокада II степени.",
            "АВ-блокада III ст." to "Полная АВ-блокада III степени.",
            "Одиночная ЖЭС" to "Одиночная желудочковая экстрасистолия.",
            "Частая ЖЭС / бигеминия" to "Частая желудочковая экстрасистолия по типу бигеминии.",
            "Наджелудочковая ЭС" to "Одиночная наджелудочковая экстрасистолия.",
            "ПИКС (рубцовые)" to "Рубцовые изменения миокарда (зубец Q)."
        ), selConduction)

        buildMultiGroup("4. Ишемия / ОКС (ST и зубец T):", listOf(
            "Элевация ST V1-V4 (передний)" to "Элевация сегмента ST до 3–4 мм в V1–V4, реципрокная депрессия ST в II, III, aVF.",
            "Элевация ST I, aVL, V5-V6 (боковой)" to "Элевация сегмента ST в I, aVL, V5–V6.",
            "Элевация ST II, III, aVF (нижний)" to "Элевация сегмента ST до 3 мм в II, III, aVF, реципрокная депрессия ST в I, aVL, V1–V3.",
            "Депрессия ST V4-V6" to "Косонисходящая/горизонтальная депрессия сегмента ST до 1.5–2 мм в V4–V6.",
            "Депрессия ST II, III, aVF" to "Депрессия сегмента ST в II, III, aVF.",
            "Отрицательный T V1-V4" to "Отрицательные коронарные зубцы T в V1–V4.",
            "Отрицательный T V4-V6" to "Отрицательные зубцы T в V4–V6.",
            "Диффузные изменения" to "Диффузные изменения миокарда."
        ), selIschemia)

        updatePreview()
        bs.show()
    }

    // =========================================================
    // 2. ТАЙМЕР-ЛОГГЕР РЕАНИМАЦИИ (СЛР) С ПОМИНУТНЫМ ПРОТОКОЛОМ
    // =========================================================
    fun showCprTimerSheet(context: Context, webView: WebView) {
        val (bs, root) = MedicalTools.createBaseSheet(context, "⏱️ Реанимационный таймер и логгер СЛР", "#D32F2F")

        val handler = Handler(Looper.getMainLooper())
        var isRunning = false
        var elapsedSec = 0
        var adrCount = 0
        var amioCount = 0
        var shockCount = 0
        val logEntries = mutableListOf<String>()
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        val timerBanner = TextView(context).apply {
            text = "00:00\nНажмите СТАРТ для отсчета СЛР"
            textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24); background = MedicalTools.roundBg("#C62828", 18f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 }
        }
        root.addView(timerBanner)

        val logText = TextView(context).apply {
            text = "Журнал реанимации пуст. Нажимайте кнопки ниже при введении препаратов и разрядах..."
            textSize = 14f; setTextColor(Color.parseColor("#263238")); setLineSpacing(5f, 1.15f)
            setPadding(20, 16, 20, 20)
        }

        fun addLog(action: String) {
            val min = elapsedSec / 60
            val sec = elapsedSec % 60
            val stamp = String.format(Locale.US, "[%s | %02d:%02d]", timeFmt.format(Date()), min, sec)
            logEntries.add("$stamp $action")
            logText.text = logEntries.joinToString("\n")
        }

        val runnable = object : Runnable {
            override fun run() {
                if (!isRunning) return
                elapsedSec++
                val m = elapsedSec / 60
                val s = elapsedSec % 60
                val cycleSec = elapsedSec % 120
                val hint = when {
                    elapsedSec >= 1800 -> "⚠️ Прошло 30 минут СЛР! Оценить целесообразность / констатацию"
                    cycleSec == 0 && elapsedSec > 0 -> "🔔 2 МИНУТЫ! Оцените ритм на ЭКГ и пульс!"
                    elapsedSec % 180 == 0 && elapsedSec > 0 -> "💉 Прошло 3 мин! Время ввести Адреналин 1 мг в/в!"
                    else -> "НМС 100–120/мин (глубина 5–6 см), соотношение 30:2"
                }
                timerBanner.background = MedicalTools.roundBg(if (cycleSec < 8 && elapsedSec > 10) "#FF6F00" else "#C62828", 18f)
                timerBanner.text = String.format(Locale.US, "⏱️ СЛР: %02d:%02d  (Адр: %d | Разряд: %d)\n%s", m, s, adrCount, shockCount, hint)
                handler.postDelayed(this, 1000)
            }
        }

        val ctrlRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 10) }
        val startBtn = TextView(context).apply {
            text = "▶️ СТАРТ СЛР"; textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(16, 18, 16, 18); background = MedicalTools.roundBg("#2E7D32", 14f)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 8 }
            setOnClickListener {
                if (!isRunning) {
                    isRunning = true
                    if (elapsedSec == 0) addLog("Начата расширенная СЛР (НМС, ИВЛ, мониторинг ЭКГ, в/в доступ)")
                    text = "⏸️ ПАУЗА"
                    background = MedicalTools.roundBg("#F57C00", 14f)
                    handler.postDelayed(runnable, 1000)
                } else {
                    isRunning = false
                    text = "▶️ ПРОДОЛЖИТЬ"
                    background = MedicalTools.roundBg("#2E7D32", 14f)
                }
            }
        }
        val roscBtn = TextView(context).apply {
            text = "❤️ ВСК / ФИНАЛ"; textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(16, 18, 16, 18); background = MedicalTools.roundBg("#0277BD", 14f)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener {
                isRunning = false
                addLog("Завершение СЛР (длительность ${elapsedSec / 60} мин ${elapsedSec % 60} сек). Всего: Адреналин — $adrCount мг, Разрядов — $shockCount, Амиодарон — $amioCount.")
            }
        }
        ctrlRow.addView(startBtn); ctrlRow.addView(roscBtn); root.addView(ctrlRow)

        // Быстрые кнопки событий СЛР
        val actionsGrid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 10) }
        fun makeActionRow(b1Lbl: String, b1Act: () -> Unit, b2Lbl: String, b2Act: () -> Unit) {
            val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 } }
            fun btn(t: String, mr: Int, act: () -> Unit) = TextView(context).apply {
                text = t; textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#B71C1C")); gravity = Gravity.CENTER
                setPadding(12, 16, 12, 16); background = MedicalTools.roundBg("#FFEBEE", 12f, "#FFCDD2")
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = mr }
                setOnClickListener { act() }
            }
            r.addView(btn(b1Lbl, 8, b1Act)); r.addView(btn(b2Lbl, 0, b2Act)); actionsGrid.addView(r)
        }

        makeActionRow(
            "💉 +Адреналин 1 мг", { adrCount++; addLog("Введен Эпинефрин (Адреналин) 1 мг в/в (#$adrCount)") },
            "⚡ +Разряд 200 Дж", { shockCount++; addLog("Дефибрилляция разрядом 200 Дж (#$shockCount)") }
        )
        makeActionRow(
            "💉 +Амиодарон 300/150", { amioCount++; addLog("Введен Амиодарон ${if (amioCount == 1) "300 мг" else "150 мг"} в/в") },
            "🫁 Интубация / Ларинг.", { addLog("Обеспечена проходимость ВДП (ЭТТ / Ларингеальная маска), ИВЛ 100% O2") }
        )
        makeActionRow(
            "📈 Ритм: Асистолия", { addLog("Контроль ритма на ЭКГ: Асистолия / ЭМД") },
            "📈 Ритм: ФЖ / ЖТ", { addLog("Контроль ритма на ЭКГ: Фибрилляция желудочков (ФЖ)") }
        )
        root.addView(actionsGrid)

        val scroll = NestedScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            background = MedicalTools.roundBg("#FAFAFA", 14f, "#E0E0E0")
        }
        scroll.addView(logText)
        root.addView(scroll)

        val insertRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 12, 0, 0) }
        insertRow.addView(TextView(context).apply {
            text = "⚡ ДОБАВИТЬ ПРОТОКОЛ СЛР В АНАМНЕЗ КАРТЫ"
            textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(20, 20, 20, 20); background = MedicalTools.roundBg("#00695C", 14f)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setOnClickListener {
                val summary = "Хронология СЛР:\n" + logEntries.joinToString("; ")
                insertToWebField(context, webView, "анамнез заболевания", summary, true)
                bs.dismiss()
            }
        })
        root.addView(insertRow)

        bs.setOnDismissListener { isRunning = false; handler.removeCallbacksAndMessages(null) }
        bs.show()
    }

    // =========================================================
    // 3. ШАБЛОНЫ ПРИМЕЧАНИЙ, ОТКАЗОВ И ЛОКАЛЬНОГО СТАТУСА
    // =========================================================
    private data class PhraseTemplate(val title: String, val targetFieldKw: String, val text: String)

    private val quickPhrases = listOf(
        PhraseTemplate(
            "📝 Согласие + Транспортировка со спецсигналами",
            "примечания",
            "Дано устное согласие на медицинское вмешательство. Транспортировка на носилках со спецсигналами. Непрерывный мониторинг АД, ЧСС, SpO2 в пути."
        ),
        PhraseTemplate(
            "🚨 Оповещение стационара (ТРИАЖ — ОКС / ОНМК / ТЭЛА / Шок)",
            "примечания",
            "Дано устное согласие на медицинское вмешательство. Через диспетчера СМП и систему ТРИАЖ приемное отделение стационара заблаговременно оповещено о доставке тяжелого пациента."
        ),
        PhraseTemplate(
            "✋ Юридически грамотный отказ от госпитализации",
            "примечания",
            "От предложенной госпитализации в стационар пациент в ясном сознании категорически отказался. В доступной форме разъяснены возможные осложнения и последствия отказа, в том числе риск внезапного ухудшения состояния и неблагоприятного (летального) исхода. Даны подробные рекомендации по приему плановых препаратов и вызову СМП при ухудшении. Оставлен актив в поликлинику."
        ),
        PhraseTemplate(
            "🪑 Отказ пациента от переноски на носилках",
            "примечания",
            "От предложенной транспортировки на носилках пациент категорически отказался, до автомобиля СМП прошел в сопровождении бригады."
        ),
        PhraseTemplate(
            "🍺 Отказ от мед. вмешательства в алкогольном опьянении",
            "примечания",
            "От осмотра, проведения ЭКГ, медицинских манипуляций и госпитализации пациент категорически отказался в присутствии родственников/свидетелей. Последствия отказа разъяснены в доступной форме. Угрожающих жизни состояний на момент осмотра не выявлено."
        ),
        PhraseTemplate(
            "🩹 Локальный статус: Чистый неврологический осмотр (норма)",
            "локальный статус",
            "В сознании, контактен, ориентирован в месте, времени и собственной личности верно. Зрачки D=S, фотореакция живая. Нистагма нет. Лицо симметричное, язык по средней линии. Менингеальных знаков нет. Парезов и параличей нет, чувствительность сохранена. В позе Ромберга устойчив, ПНП выполняет уверенно."
        ),
        PhraseTemplate(
            "🩹 Локальный статус: Ушиб мягких тканей / Гематома",
            "локальный статус",
            "При осмотре в области травмы определяется умеренный локальный отек и подкожная гематома 3,0х3,0 см, кожные покровы целы, пальпация умеренно болезненна. Крепитации и патологической подвижности нет. Чувствительность, пульсация и активные движения в дистальных отделах сохранены в полном объеме."
        ),
        PhraseTemplate(
            "🩹 Локальный статус: Ушибленная / Резаная рана",
            "локальный статус",
            "При осмотре определяется линейная рана длиной 3,0 см с ровными краями, дном раны является подкожно-жировая клетчатка. Кровотечение капиллярное, необильное. Наложена асептическая повязка, гемостаз."
        )
    )

    fun showQuickPhrasesSheet(context: Context, webView: WebView) {
        val (bs, root) = MedicalTools.createBaseSheet(context, "📝 Быстрые фразы: Отказы, Примечания, Статус", "#5D4037")

        val listView = ListView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isNestedScrollingEnabled = true
            divider = null
            dividerHeight = 12
            setPadding(0, 8, 0, 0)
        }
        root.addView(listView)

        val items = quickPhrases.map { "${it.title}\n➔ Поле: «${it.targetFieldKw.uppercase()}»\n${it.text}" }
        listView.adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = (super.getView(position, convertView, parent) as TextView).apply {
                setPadding(26, 20, 26, 20)
                setTextColor(Color.parseColor("#263238"))
                textSize = 13.5f
                setLineSpacing(5f, 1.15f)
                background = MedicalTools.roundBg("#EFEBE9", 16f, "#D7CCC8")
            }
        }
        listView.setOnItemClickListener { _, _, pos, _ ->
            val p = quickPhrases[pos]
            insertToWebField(context, webView, p.targetFieldKw, p.text, true)
            bs.dismiss()
        }

        bs.show()
    }
}
