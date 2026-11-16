package by.brest103.ssmpplus

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.*
import kotlin.math.min

data class GuideCard(val title: String, val subtitle: String, val content: String)

object MedicalTools {

    fun roundBg(hex: String, r: Float, strokeHex: String? = null) = GradientDrawable().apply {
        setColor(Color.parseColor(hex))
        cornerRadius = r
        strokeHex?.let { setStroke(2, Color.parseColor(it)) }
    }

    fun styleEditText(
        et: EditText,
        bgHex: String = "#F5F7F8",
        strokeHex: String = "#B0BEC5",
        corner: Float = 14f
    ) {
        et.setTextColor(Color.parseColor("#111111"))
        et.setHintTextColor(Color.parseColor("#546E7A"))
        et.background = roundBg(bgHex, corner, strokeHex)
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "📋 Скопировано: $text", Toast.LENGTH_SHORT).show()
    }

    fun createBaseSheet(context: Context, title: String, colorHex: String): Pair<BottomSheetDialog, LinearLayout> {
        val bottomSheet = BottomSheetDialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(35, 18, 35, 24)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(90, 8).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 14
            }
            background = roundBg("#B0BEC5", 4f)
        })
        root.addView(TextView(context).apply {
            text = title
            textSize = 18.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(colorHex))
            setPadding(0, 0, 0, 12)
        })
        bottomSheet.setContentView(root)
        bottomSheet.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            peekHeight = 1500
        }
        return bottomSheet to root
    }

    fun showDetailDialog(context: Context, title: String, body: String, accentHex: String = "#00695C") {
        val pad = 42
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 32, pad, 28)
            background = roundBg("#FFFFFF", 20f)
        }
        root.addView(TextView(context).apply {
            text = title
            textSize = 17.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(accentHex))
            setPadding(0, 0, 0, 16)
        })
        val scroll = NestedScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        scroll.addView(TextView(context).apply {
            text = body
            textSize = 14.5f
            setTextIsSelectable(true)
            setTextColor(Color.parseColor("#263238"))
            setLineSpacing(6f, 1.16f)
        })
        root.addView(scroll)

        val dialog = AlertDialog.Builder(context).setView(root).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 20, 0, 0)
        }
        btnRow.addView(TextView(context).apply {
            text = "📋 КОПИРОВАТЬ"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(accentHex))
            setPadding(28, 16, 28, 16)
            setOnClickListener { copyToClipboard(context, title, "$title:\n$body") }
        })
        btnRow.addView(TextView(context).apply {
            text = "ЗАКРЫТЬ"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(32, 16, 32, 16)
            background = roundBg(accentHex, 12f)
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(btnRow)
        dialog.show()
    }

    private fun makeLabeledInput(
        context: Context,
        labelText: String,
        hintText: String,
        defaultVal: String = "",
        bgHex: String = "#FFF3E0",
        strokeHex: String = "#FFB74D",
        rightMarginPx: Int = 0,
        isDecimal: Boolean = true
    ): Pair<LinearLayout, EditText> {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = rightMarginPx
            }
        }
        col.addView(TextView(context).apply {
            text = labelText
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#37474F"))
            setPadding(6, 0, 0, 4)
        })
        val inp = EditText(context).apply {
            hint = hintText
            if (defaultVal.isNotEmpty()) setText(defaultVal)
            inputType = if (isDecimal) (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL) else InputType.TYPE_CLASS_NUMBER
            setPadding(22, 16, 22, 16)
            textSize = 14.5f
            styleEditText(this, bgHex, strokeHex, 12f)
        }
        col.addView(inp)
        return col to inp
    }

    // =========================================================
    // 1. ПЕДИАТРИЯ: НОРМЫ, ДОЗЫ, ТРУБКИ, ИВЛ, СЛР (Пост. №118)
    // =========================================================
    private data class AgePreset(
        val label: String, val defaultWeight: Double, val hrNorm: String, val rrNorm: String,
        val bpNorm: String, val ettUncuffed: String, val ettCuffed: String, val ettDepth: String,
        val lmaSize: String, val gastricWashLiters: String
    )

    private val agePresets = listOf(
        AgePreset("Новор.", 3.5, "120–160", "40–60", "65–85 / 45–55", "3.0–3.5", "3.0", "9–10 см", "№1 (<5 кг)", "0.3–0.5 л"),
        AgePreset("6 мес", 7.5, "110–140", "30–40", "80–95 / 50–65", "3.5–4.0", "3.0–3.5", "10.5–11 см", "№1.5 (5–10 кг)", "0.8–1.0 л"),
        AgePreset("1 год", 10.0, "105–130", "28–35", "90–105 / 55–70", "4.0–4.5", "3.5–4.0", "12 см", "№2 (10–20 кг)", "1.0–1.5 л"),
        AgePreset("3 года", 15.0, "95–120", "24–28", "95–110 / 60–70", "4.5–5.0", "4.0–4.5", "13.5 см", "№2 (10–20 кг)", "3.0–4.0 л"),
        AgePreset("5 лет", 19.0, "90–110", "22–25", "98–112 / 60–72", "5.0–5.5", "4.5–5.0", "14.5 см", "№2–2.5", "4.0–5.0 л"),
        AgePreset("7 лет", 23.0, "85–105", "20–24", "102–116 / 62–75", "5.5–6.0", "5.0–5.5", "15.5 см", "№2.5 (20–30 кг)", "5.0–6.0 л"),
        AgePreset("10 лет", 32.0, "75–95", "18–22", "108–122 / 65–78", "6.0–6.5", "6.0", "17 см", "№3 (30–50 кг)", "7.0–8.0 л"),
        AgePreset("14 лет", 50.0, "65–85", "16–20", "110–128 / 70–80", "7.0–7.5", "6.5–7.0", "19–20 см", "№3–4 (50+ кг)", "8.0–10.0 л")
    )

    fun showPediatricsSheet(context: Context) {
        val (bottomSheet, root) = createBaseSheet(context, "👶 Педиатрия: Нормы, Дозы, ИВЛ (Пост. №118)", "#0277BD")
        var selectedPresetIndex = 2
        val chipsScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; setPadding(0, 0, 0, 12) }
        val chipsLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        chipsScroll.addView(chipsLayout); root.addView(chipsScroll)

        val weightRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 12) }
        weightRow.addView(TextView(context).apply { text = "⚖️ Вес ребенка (кг): "; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#263238")) })
        val weightInput = EditText(context).apply {
            setText("10.0"); hint = "Введите вес в кг"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(30, 18, 30, 18)
            styleEditText(this, "#E1F5FE", "#29B6F6", 16f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        weightRow.addView(weightInput); root.addView(weightRow)

        val scrollView = NestedScrollView(context).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); isFillViewport = true; isNestedScrollingEnabled = true }
        val resultText = TextView(context).apply { textSize = 14.5f; setTextIsSelectable(true); setTextColor(Color.parseColor("#263238")); setLineSpacing(6f, 1.18f); setPadding(8, 8, 8, 30) }
        scrollView.addView(resultText); root.addView(scrollView)

        fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v).replace(".00", "")

        fun recalculate() {
            val w = weightInput.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
            val p = agePresets[selectedPresetIndex]
            val sb = StringBuilder()
            sb.append("📊 ВОЗРАСТНЫЕ НОРМЫ (${p.label}):\n")
            sb.append("• Пульс (ЧСС): ${p.hrNorm} уд/мин | Дыхание (ЧДД): ${p.rrNorm} в мин\n")
            sb.append("• АД норма: ${p.bpNorm} мм рт.ст.\n\n")

            if (w > 0) {
                sb.append("🫁 РЕСПИРАТОРНАЯ ПОДДЕРЖКА И СЛР НА ${fmt(w)} КГ:\n")
                sb.append("• ЭТТ без манжеты: Ø ${p.ettUncuffed} мм | с манжетой: Ø ${p.ettCuffed} мм\n")
                sb.append("• Глубина ЭТТ от угла рта: ${p.ettDepth} | Ларинг. маска: ${p.lmaSize}\n")
                sb.append("• ИВЛ дыхательный объем (6–8 мл/кг): ${(w * 6).toInt()} – ${(w * 8).toInt()} мл\n")
                sb.append("• Дефибрилляция (ФЖ/ЖТ): 1-й разряд ${(w * 2).toInt()} Дж (2 Дж/кг), далее ${(w * 4).toInt()} Дж (4 Дж/кг)\n")
                sb.append("• СЛР у детей: старт с 5 вдохов, далее 15:2 (или 30:2), глубина 1/3 грудной клетки\n")
                sb.append("• Объем для промывания желудка: ${p.gastricWashLiters}\n\n")

                sb.append("💉 ЭКСТРЕННЫЕ ДОЗИРОВКИ НА ${fmt(w)} КГ (Пост. МЗ РБ №118):\n")
                sb.append("• Адреналин 0,18% (СЛР в/в, в/к): ${fmt(min(1.0, w * 0.01))} мл (в разведении 1 мл + 9 мл NaCl = ${fmt(min(10.0, w * 0.1))} мл)\n")
                // Пункт 13 ревью: строгий лимит 0,3 мл у детей при анафилаксии по Пост. №118
                sb.append("• Адреналин 0,18% (Анафилаксия в/м): ${fmt(min(0.3, w * 0.01))} мл в переднебоковую поверхность бедра (макс. 0.3 мл)\n")
                sb.append("• Амиодарон 5% (СЛР ФЖ/ЖТ в/в): ${fmt(min(6.0, w * 0.1))} мл (${fmt(min(300.0, w * 5.0))} мг из расчета 5 мг/кг)\n")
                sb.append("• Атропин 0,1% (брадикардия): ${fmt(min(0.5, (w * 0.02).coerceAtLeast(0.1)))} мл (0.02 мг/кг, мин. 0.1 мг)\n")
                sb.append("• Преднизолон (30 мг/мл): ${fmt(min(90.0, w * 2.0))} мг = ${fmt(min(3.0, w * 2.0 / 30.0))} мл (2 мг/кг)\n")
                sb.append("• Дексаметазон (4 мг/мл): ${fmt(min(16.0, w * 0.4))} мг = ${fmt(min(4.0, w * 0.1))} мл (0.3–0.6 мг/кг при крупе)\n")
                sb.append("• Диазепам 0,5% (судороги в/в, в/м): ${fmt(min(2.0, w * 0.05))} – ${fmt(min(2.0, w * 0.06))} мл (0.25–0.3 мг/кг)\n")
                sb.append("• Магния сульфат 25%: ${fmt(min(8.0, w * 0.15))} мл (25–50 мг/кг в/в капельно)\n")
                sb.append("• Метамизол (Анальгин 50%): ${fmt(min(2.0, w * 0.02))} мл (10 мг/кг = 0.1 мл/год)\n")
                sb.append("• Димедрол 1% / Супрастин 2%: ${fmt(min(1.0, w * 0.02))} – ${fmt(min(1.0, w * 0.03))} мл\n")
                sb.append("• Фуросемид 1% (10 мг/мл): ${fmt(min(4.0, w * 0.1))} мл (1 мг/кг)\n")
                sb.append("• Декстроза 10% (гипогликемия в/в): ${fmt(w * 2.0)} – ${fmt(w * 4.0)} мл (2–4 мл/кг)\n")
                sb.append("• NaCl 0,9% (болюс при шоке/эксикозе): ${fmt(w * 20.0)} мл (20 мл/кг)\n")
                sb.append("• Парацетамол (внутрь/свечи): ${fmt(min(500.0, w * 15.0))} мг (15 мг/кг)\n")
                sb.append("• Ибупрофен (внутрь): ${fmt(min(400.0, w * 10.0))} мг (10 мг/кг)\n")
                sb.append("• Беродуал (небулайзер): ${min(20, (w * 1.0).toInt()).coerceAtLeast(5)} капель + 2–3 мл NaCl 0.9%\n")
                sb.append("• Будесонид / Пульмикорт (круп): 0.5 – 2.0 мг через небулайзер\n")
            }
            resultText.text = sb.toString()
        }

        fun updateChips() {
            chipsLayout.removeAllViews()
            agePresets.forEachIndexed { idx, preset ->
                val isSel = idx == selectedPresetIndex
                chipsLayout.addView(TextView(context).apply {
                    text = "${preset.label} (${preset.defaultWeight} кг)"; textSize = 12f
                    typeface = if (isSel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(if (isSel) Color.WHITE else Color.parseColor("#01579B"))
                    setPadding(20, 10, 20, 10); background = roundBg(if (isSel) "#0277BD" else "#E1F5FE", 16f)
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8 }
                    setOnClickListener { selectedPresetIndex = idx; weightInput.setText(preset.defaultWeight.toString()); updateChips(); recalculate() }
                })
            }
        }
        weightInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = recalculate()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })
        updateChips(); recalculate(); bottomSheet.show()
    }

    // =========================================================
    // 2. КЛИНИЧЕСКИЕ ШКАЛЫ И ИНДЕКСЫ СМП
    // =========================================================
    fun showCalculatorsSheet(context: Context) {
        val (bottomSheet, root) = createBaseSheet(context, "🧠 Клинические шкалы и индексы СМП", "#5E35B1")
        val scaleTabs = listOf("👁️ Глазго (Взр/Дет)", "❤️ Шок-индекс и Инфузия", "🫁 Шкала ОДН", "🩸 ТЭЛА (Wells/Женева)", "🤢 Аппендицит (Альварадо)", "🧠 Инсульт (NIHSS)", "🫀 ХСН (ШОКС) / CIWA")
        var currentTab = 0

        val tabsScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; setPadding(0, 0, 0, 12) }
        val tabsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        tabsScroll.addView(tabsRow); root.addView(tabsScroll)

        val scrollView = NestedScrollView(context).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); isFillViewport = true; isNestedScrollingEnabled = true }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 4, 0, 24) }
        scrollView.addView(content); root.addView(scrollView)

        fun buildChecklistScale(
            title: String,
            items: List<Pair<String, Double>>,
            mutuallyExclusivePairs: List<Pair<Int, Int>> = emptyList(),
            interpreter: (Double) -> String
        ) {
            content.addView(TextView(context).apply { text = title; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#4527A0")); setPadding(0, 8, 0, 10) })
            val banner = TextView(context).apply {
                textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setPadding(22, 18, 22, 18); background = roundBg("#5E35B1", 14f)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 }
            }
            content.addView(banner)
            val selected = BooleanArray(items.size)
            val rowViews = mutableListOf<TextView>()

            fun updateScore() {
                var sum = 0.0
                for (i in items.indices) if (selected[i]) sum += items[i].second
                val sumStr = if (sum % 1.0 == 0.0) sum.toInt().toString() else String.format(Locale.US, "%.1f", sum)
                val interp = interpreter(sum)
                banner.text = "Сумма: $sumStr б. — $interp\n📋 Нажмите, чтобы скопировать"
                banner.setOnClickListener { copyToClipboard(context, title, "$title: $sumStr б. ($interp)") }
            }

            fun updateRowStyle(idx: Int) {
                val on = selected[idx]
                val (label, pts) = items[idx]
                val ptsStr = if (pts % 1.0 == 0.0) "+${pts.toInt()}" else "+$pts"
                val tv = rowViews[idx]
                tv.text = "${if (on) "☑️" else "☐"}  $label ($ptsStr б.)"
                tv.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                tv.setTextColor(if (on) Color.WHITE else Color.parseColor("#263238"))
                tv.background = roundBg(if (on) "#7E57C2" else "#F3E5F5", 12f)
            }

            items.forEachIndexed { idx, (label, pts) ->
                val ptsStr = if (pts % 1.0 == 0.0) "+${pts.toInt()}" else "+$pts"
                val tv = TextView(context).apply {
                    text = "☐  $label ($ptsStr б.)"; textSize = 13.5f; setTextColor(Color.parseColor("#263238"))
                    setPadding(24, 18, 24, 18); background = roundBg("#F3E5F5", 12f)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }
                    setOnClickListener {
                        selected[idx] = !selected[idx]

                        // Пункт 12 ревью: авто-снятие взаимоисключающих пунктов (например, ЧСС 75-94 и ЧСС >= 95)
                        if (selected[idx]) {
                            for (pair in mutuallyExclusivePairs) {
                                if (pair.first == idx && selected[pair.second]) {
                                    selected[pair.second] = false
                                    updateRowStyle(pair.second)
                                } else if (pair.second == idx && selected[pair.first]) {
                                    selected[pair.first] = false
                                    updateRowStyle(pair.first)
                                }
                            }
                        }

                        updateRowStyle(idx)
                        updateScore()
                    }
                }
                rowViews.add(tv)
                content.addView(tv)
            }
            updateScore()
        }

        fun renderCurrentTab() {
            content.removeAllViews()
            when (currentTab) {
                0 -> {
                    var isPediatric = false; var scoreE = 4; var scoreV = 5; var scoreM = 6
                    val modeSwitch = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 12) }
                    val gcsBanner = TextView(context).apply {
                        textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                        setPadding(24, 20, 24, 20); background = roundBg("#00796B", 16f)
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 14 }
                    }
                    val groupsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

                    fun updateGcs() {
                        val total = scoreE + scoreV + scoreM
                        val state = when {
                            total == 15 -> "Ясное сознание"; total in 13..14 -> "Умеренное оглушение"
                            total in 11..12 -> "Глубокое оглушение"; total in 9..10 -> "Сопор"
                            total in 7..8 -> "Кома I ст."; total in 5..6 -> "Кома II ст."; else -> "Кома III ст."
                        }
                        gcsBanner.text = "ШКГ (${if (isPediatric) "Дети" else "Взрослые"}): E$scoreE V$scoreV M$scoreM = $total б ($state)\n📋 Нажмите, чтобы скопировать"
                        gcsBanner.setOnClickListener { copyToClipboard(context, "GCS", "ШКГ: E$scoreE V$scoreV M$scoreM = $total б ($state)") }
                    }

                    fun buildGcsGroups() {
                        groupsContainer.removeAllViews()
                        fun addGroup(title: String, opts: List<Pair<Int, String>>, getCur: () -> Int, setCur: (Int) -> Unit) {
                            groupsContainer.addView(TextView(context).apply { text = title; textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#311B92")); setPadding(0, 10, 0, 6) })
                            val hs = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
                            val lay = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                            hs.addView(lay); groupsContainer.addView(hs)
                            fun draw() {
                                lay.removeAllViews()
                                opts.forEach { (pts, lbl) ->
                                    val sel = pts == getCur()
                                    lay.addView(TextView(context).apply {
                                        text = "$pts — $lbl"; textSize = 12f; typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                                        setTextColor(if (sel) Color.WHITE else Color.parseColor("#263238"))
                                        setPadding(20, 12, 20, 12); background = roundBg(if (sel) "#5E35B1" else "#EDE7F6", 14f)
                                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8 }
                                        setOnClickListener { setCur(pts); draw(); updateGcs() }
                                    })
                                }
                            }
                            draw()
                        }
                        addGroup("👁️ Открывание глаз (E):", listOf(4 to "Спонтанное", 3 to "На звук/речь", 2 to "На боль", 1 to "Отсутствует"), { scoreE }) { scoreE = it }
                        addGroup(if (isPediatric) "👶 Речь / Гуление у детей (V):" else "🗣️ Речевая реакция (V):",
                            if (isPediatric) listOf(5 to "Улыбается, гулит", 4 to "Плачет, успокаивается", 3 to "Крик на боль, стон", 2 to "Беспокоен, стонет", 1 to "Нет реакции")
                            else listOf(5 to "Связная", 4 to "Спутанная", 3 to "Отд. слова", 2 to "Нечленоразд. звуки", 1 to "Отсутствует"), { scoreV }) { scoreV = it }
                        addGroup("✋ Двигательная реакция (M):", listOf(6 to (if (isPediatric) "Спонтанные движения" else "По команде"), 5 to "Локализует боль", 4 to "Отдергивает", 3 to "Сгибание", 2 to "Разгибание", 1 to "Отсутствует"), { scoreM }) { scoreM = it }
                    }

                    fun drawModeBtns() {
                        modeSwitch.removeAllViews()
                        listOf(false to "🧑 Взрослые", true to "👶 Дети (до 4 лет)").forEach { (ped, lbl) ->
                            val sel = isPediatric == ped
                            modeSwitch.addView(TextView(context).apply {
                                text = lbl; gravity = Gravity.CENTER; textSize = 13f; typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                                setTextColor(if (sel) Color.WHITE else Color.parseColor("#4527A0"))
                                setPadding(20, 12, 20, 12); background = roundBg(if (sel) "#5E35B1" else "#EDE7F6", 12f)
                                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 8 }
                                setOnClickListener { isPediatric = ped; drawModeBtns(); buildGcsGroups(); updateGcs() }
                            })
                        }
                    }
                    content.addView(modeSwitch); content.addView(gcsBanner); content.addView(groupsContainer)
                    drawModeBtns(); buildGcsGroups(); updateGcs()
                }
                1 -> {
                    content.addView(TextView(context).apply { text = "❤️ Шок-индекс Альговера (ЧСС / САД), кровопотеря и объем инфузии"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#C62828")); setPadding(0, 6, 0, 12) })
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 12) }
                    val (hrCol, hrInp) = makeLabeledInput(context, "💓 Пульс (ЧСС, уд/мин):", "Напр. 110", "", "#FFEBEE", "#EF9A9A", 12, false)
                    val (sbpCol, sbpInp) = makeLabeledInput(context, "🩸 САД (верхнее АД):", "Напр. 90", "", "#FFEBEE", "#EF9A9A", 0, false)
                    row.addView(hrCol); row.addView(sbpCol); content.addView(row)
                    val shockRes = TextView(context).apply { textSize = 14.5f; setTextColor(Color.parseColor("#263238")); setLineSpacing(6f, 1.18f); setPadding(22, 18, 22, 18); background = roundBg("#FFF8E1", 14f, "#FFE082") }
                    content.addView(shockRes)

                    fun calc() {
                        val hr = hrInp.text.toString().toDoubleOrNull(); val sbp = sbpInp.text.toString().toDoubleOrNull()
                        if (hr != null && sbp != null && sbp > 0) {
                            val idx = hr / sbp
                            val info = when {
                                idx < 0.8 -> "• Норма (0.5–0.7)\n• Дефицит ОЦК: нет (<10%)\n• Инфузия: поддерживающая 200–400 мл кристаллоидов"
                                idx < 1.0 -> "• Шок I ст. (компенсированный)\n• Потеря ОЦК: 15–20% (750–1000 мл)\n• Догоспитальный объем инфузии: 800–1200 мл кристаллоидов"
                                idx < 1.5 -> "• Шок II ст. (субкомпенсированный)\n• Потеря ОЦК: 25–35% (1200–1800 мл)\n• Догоспитальный объем инфузии: 1500–2000 мл (кристаллоиды + коллоиды 2:1 в 2 вены)"
                                else -> "• Шок III–IV ст. (декомпенсированный)\n• Потеря ОЦК: >40% (>2000 мл)\n• Инфузия: струйно в 2 катетера 2000–2500 мл (1:1) + Норадреналин / Допамин"
                            }
                            shockRes.text = String.format(Locale.US, "📊 Шок-индекс: %.2f\n%s\n\n📋 Нажмите, чтобы скопировать", idx, info)
                            shockRes.setOnClickListener { copyToClipboard(context, "ШИ", String.format(Locale.US, "Шоковый индекс = %.2f", idx)) }
                        } else {
                            shockRes.text = "Введите ЧСС и САД для расчета шокового индекса и объема инфузии.\n\n📌 Ориентировочная кровопотеря при переломах:\n• Голень: 500–800 мл | Бедро: 1000–1800 мл\n• Кости таза: 1500–3000 мл | Плечо/предплечье: 300–600 мл"
                        }
                    }
                    val tw = object : TextWatcher { override fun afterTextChanged(s: Editable?) = calc(); override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}; override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {} }
                    hrInp.addTextChangedListener(tw); sbpInp.addTextChangedListener(tw); calc()
                }
                2 -> {
                    content.addView(TextView(context).apply {
                        text = "🫁 Классификация степеней ОДН:\n\n🟢 ОДН I ст.: сознание ясное/беспокойство, ЧДД 22–28/мин, SpO2 90–94%, акроцианоз, умеренная тахикардия.\n\n🟡 ОДН II ст.: возбуждение/заторможенность, ЧДД 30–38/мин, участие вспом. мышц, SpO2 75–89%, диффузный цианоз, пот, ЧСС >110–120.\n\n🔴 ОДН III ст.: сопор/кома, судороги, ЧДД >40 или <8/мин, SpO2 <75%, гипотония, аритмия → экстренная интубация и ИВЛ!"
                        textSize = 14.5f; setTextColor(Color.parseColor("#263238")); setLineSpacing(6f, 1.16f); setPadding(22, 18, 22, 18); background = roundBg("#F3E5F5", 14f)
                    })
                }
                3 -> {
                    buildChecklistScale("🩸 Шкала Уэллса (Wells) — вероятность ТЭЛА:", listOf(
                        "Клинические признаки ТГВ (отек и боль в голени)" to 3.0, "ТЭЛА более вероятна, чем альтернативный диагноз" to 3.0,
                        "Тахикардия (ЧСС > 100 в минуту)" to 1.5, "Иммобилизация (>3 дней) или операция за 4 недели" to 1.5,
                        "ТГВ или ТЭЛА в анамнезе" to 1.5, "Кровохарканье" to 1.0, "Онкологическое заболевание" to 1.0
                    )) { sum -> if (sum <= 1.0) "Низкий риск ТЭЛА (<2%)" else if (sum <= 6.0) "Умеренный риск ТЭЛА (20–30%)" else "Высокий риск ТЭЛА (>60%)" }

                    content.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(-1, 20) })

                    // Пункт 12 ревью: исправление ловушки Женевской шкалы (+3 б. при 75-94 и +5 б. при >=95 взаимно исключают друг друга)
                    buildChecklistScale(
                        title = "🇨🇭 Женевская шкала (пересмотренная) — ТЭЛА:",
                        items = listOf(
                            "Возраст старше 65 лет" to 1.0,
                            "ТГВ или ТЭЛА в анамнезе" to 3.0,
                            "Операция или перелом конечности за 1 месяц" to 2.0,
                            "Активная онкология" to 2.0,
                            "Односторонняя боль в нижней конечности" to 3.0,
                            "Кровохарканье" to 2.0,
                            "ЧСС 75–94 в минуту" to 3.0, // индекс 6
                            "ЧСС >= 95 в минуту" to 5.0, // индекс 7 (полные 5 баллов!)
                            "Боль при пальпации вен и односторонний отек" to 4.0
                        ),
                        mutuallyExclusivePairs = listOf(6 to 7)
                    ) { sum ->
                        if (sum <= 3.0) "Низкая вероятность ТЭЛА (<10%)"
                        else if (sum <= 10.0) "Средняя вероятность ТЭЛА (30%)"
                        else "Высокая вероятность ТЭЛА (>65%)"
                    }
                }
                4 -> {
                    buildChecklistScale("🤢 Шкала Альварадо (MANTRELS) — острый аппендицит:", listOf(
                        "Миграция боли в правую подвздошную область (с-м Кохера)" to 1.0, "Анорексия" to 1.0, "Тошнота или рвота" to 1.0,
                        "Болезненность в правой подвздошной области" to 2.0, "Положительный с-м Щеткина-Блюмберга" to 1.0,
                        "Температура тела >= 37.3 °C" to 1.0, "Лейкоцитоз > 10×10⁹/л" to 2.0, "Нейтрофильный сдвиг влево" to 1.0
                    )) { sum -> if (sum <= 4.0) "Аппендицит маловероятен" else if (sum <= 6.0) "Возможен аппендицит" else "Высокая вероятность острого аппендицита" }
                }
                5 -> {
                    buildChecklistScale("🧠 Догоспитальная оценка инсульта (LAMS / NIHSS):", listOf(
                        "Асимметрия лица (парез мимической мускулатуры)" to 1.0, "Парез руки (медленно опускается)" to 1.0,
                        "Плегия руки (быстро падает / нет движений, еще +1 б.)" to 1.0, "Снижение силы сжатия кисти" to 1.0,
                        "Отсутствие сжатия кисти (еще +1 б.)" to 1.0, "Нарушение речи (дизартрия / афазия)" to 2.0,
                        "Парез взора (поворот глаз в сторону очага)" to 2.0, "Угнетение сознания" to 2.0
                    )) { sum -> if (sum == 0.0) "Очагового дефицита нет" else if (sum <= 3.0) "Умеренный дефицит (Инсульт / ТИА)" else "Тяжелый инсульт (окно ТЛТ 4,5 ч)" }
                }
                6 -> {
                    buildChecklistScale("🫀 Шкала ШОКС (оценка тяжести ХСН):", listOf(
                        "Одышка в покое (2 б.) или при нагрузке" to 2.0, "Изменение веса за неделю" to 1.0, "Перебои в работе сердца" to 1.0,
                        "Ортопноэ" to 2.0, "Набухшие шейные вены" to 2.0, "Влажные хрипы в легких" to 2.0, "Ритм галопа" to 1.0,
                        "Увеличение печени" to 2.0, "Отеки ног / анасарка" to 2.0, "САД < 100 мм рт. ст." to 2.0
                    )) { sum -> if (sum == 0.0) "Признаков ХСН нет" else if (sum <= 3.0) "I ФК ХСН" else if (sum <= 6.0) "II ФК ХСН" else if (sum <= 9.0) "III ФК ХСН" else "IV ФК ХСН" }
                    content.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(-1, 20) })
                    buildChecklistScale("🍺 Отмена алкоголя (CIWA-Ar):", listOf(
                        "Тошнота и рвота" to 4.0, "Выраженный тремор рук" to 4.0, "Профузная потливость" to 4.0, "Тревога" to 4.0,
                        "Психомоторное возбуждение" to 5.0, "Зрительные/слуховые обманы (предделирий)" to 5.0, "Дезориентация" to 4.0
                    )) { sum -> if (sum <= 8.0) "Легкий синдром отмены" else if (sum <= 15.0) "Умеренный синдром отмены" else "Тяжелая абстиненция, риск делирия" }
                }
            }
        }

        fun drawTabs() {
            tabsRow.removeAllViews()
            scaleTabs.forEachIndexed { idx, title ->
                val sel = idx == currentTab
                tabsRow.addView(TextView(context).apply {
                    text = title; textSize = 12.5f; typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(if (sel) Color.WHITE else Color.parseColor("#4527A0"))
                    setPadding(22, 12, 22, 12); background = roundBg(if (sel) "#5E35B1" else "#EDE7F6", 16f)
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8 }
                    setOnClickListener { currentTab = idx; drawTabs(); renderCurrentTab() }
                })
            }
        }
        drawTabs(); renderCurrentTab(); bottomSheet.show()
    }

    // =========================================================
    // 3. ШПАРГАЛКИ И 3 ОТДЕЛЬНЫХ КАЛЬКУЛЯТОРА (% РАСТВОР, ДОПАМИН, КИСЛОРОД В МПа)
    // =========================================================
    private val cheatSheets = listOf(
        GuideCard("📍 Локализация инфаркта миокарда (ИМ)", "По отведениям ЭКГ + реципрокные изменения",
            "• Передне-перегородочный ИМ: Элевация ST в V1–V2 (V3) | Реципрокно: II, III, aVF\n" +
            "• Передний распространенный ИМ: Элевация ST в I, aVL, V1–V6 | Реципрокно: II, III, aVF\n" +
            "• Боковой ИМ: Элевация ST в I, aVL, V5–V6 | Реципрокно: II, III, aVF\n" +
            "• Нижний (диафрагмальный) ИМ: Элевация ST в II, III, aVF | Реципрокно: I, aVL, V1–V3\n" +
            "• Задне-базальный ИМ: Прямые V7–V9 (элевация ST >= 0.5 мм) | Реципрокно в V1–V3: высокий R > S, депрессия ST, высокий положительный T\n" +
            "• ИМ правого желудочка: Элевация ST в II, III, aVF + V3R–V4R >= 1 мм (⚠️ противопоказаны нитраты, морфин и диуретики!)"),
        GuideCard("📈 Анализ ЭКГ: зубцы, интервалы и ЭОС", "Нормы интервалов и положение ЭОС",
            "• При 25 мм/с: 1 мм = 0.04 с, 5 мм = 0.20 с | При 50 мм/с: 1 мм = 0.02 с, 5 мм = 0.10 с.\n" +
            "• Нормы: P до 0.10 с; PQ 0.12–0.20 с (>0.20 с — АВ-блокада I ст.); QRS 0.06–0.10 с (>=0.12 с — полная блокада ножки пучка Гиса); QTc до 0.44 с.\n" +
            "• ЭОС: Нормальная — R(II) > R(I) > R(III); Влево — высокий R в I, aVL и глубокий S в III (SIII > RIII); Вправо — глубокий S в I (SI > RI) и высокий R в III."),
        GuideCard("💓 Расчет ЧСС по ЭКГ", "Формулы для 25 мм/с и 50 мм/с и при ФП",
            "• 25 мм/с: ЧСС = 300 / число больших клеток (по 5 мм) между R–R (1 кл=300, 2=150, 3=100, 4=75, 5=60, 6=50).\n" +
            "• 50 мм/с: ЧСС = 600 / число больших клеток между R–R (2 кл=300, 4=150, 6=100, 8=75, 10=60, 12=50).\n" +
            "• При ФП: число комплексов QRS за 3 секунды (15 больших клеток при 25 мм/с или 30 больших клеток при 50 мм/с) умножить на 20."),
        GuideCard("🔥 Площадь и степени ожога", "Правило девяток Уоллеса, правило ладони и степени",
            "• Правило ладони: ладонь с пальцами = 1% поверхности тела.\n" +
            "• Правило девяток у взрослых: голова и шея 9%, рука по 9%, грудь+живот 18%, спина+поясница 18%, нога по 18%, промежность 1%.\n" +
            "• Степени: I ст. — гиперемия и отек; II ст. — пузыри с прозрачным серозным содержимым; IIIА ст. — напряженные/вскрывшиеся пузыри с желеобразным/геморрагическим содержимым; IIIБ ст. — тотальный некроз кожи (струп); IV ст. — обугливание."),
        GuideCard("💧 ОКИ: степени дегидротации (эксикоза)", "Клиническая оценка обезвоживания",
            "• I ст. (до 5%): жажда умеренная, слизистые влажные, тургор и диурез сохранены.\n" +
            "• II ст. (6–9%): выраженная жажда, слизистые сухие, глаза/родничок запавшие, кожная складка расправляется за 1–2 с, тахикардия, олигурия.\n" +
            "• III ст. (>=10%, шок): сопор/кома, кожа холодная мраморная, складка >2 с, гипотония, анурия → болюс NaCl 0.9% 20 мл/кг струйно!"),
        GuideCard("🫁 Респираторная поддержка и параметры ИВЛ", "Показания к интубации и стартовые режимы ИВЛ",
            "• Оксигенотерапия: при SpO2 < 92–94% (при ХОБЛ целевая SpO2 88–92%, поток 1–3 л/мин).\n" +
            "• Показания к интубации и ИВЛ: апноэ, ЧДД > 38–40 или < 8 в мин, SpO2 < 80% на фоне O2, кома <= 8 баллов по ШКГ.\n" +
            "• Параметры ИВЛ у взрослых: ДО (Vt) = 6–8 мл/кг (450–600 мл), ЧД = 12–16/мин, МОВ = 6–8 л/мин, PEEP = 5 см вод. ст.")
    )

    fun showCheatSheetsSheet(context: Context) {
        val (bottomSheet, root) = createBaseSheet(context, "📋 Шпаргалки и быстрые расчеты СМП", "#EF6C00")
        val scrollView = NestedScrollView(context).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); isFillViewport = true; isNestedScrollingEnabled = true }
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 4, 0, 24) }
        scrollView.addView(container); root.addView(scrollView)

        // БЛОК 1. РАСЧЕТ ВЕЩЕСТВА В МГ ИЗ % РАСТВОРА
        container.addView(TextView(context).apply { text = "🧮 1. Расчет вещества в мг из % раствора"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#E65100")); setPadding(0, 4, 0, 8) })
        val pctRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 8) }
        val (pctCol, pctInp) = makeLabeledInput(context, "Концентрация (%):", "Напр. 25", "", "#FFF3E0", "#FFB74D", 10)
        val (mlCol, mlInp) = makeLabeledInput(context, "Объем ампулы (мл):", "Напр. 10", "", "#FFF3E0", "#FFB74D", 0)
        pctRow.addView(pctCol); pctRow.addView(mlCol); container.addView(pctRow)
        val pctOut = TextView(context).apply {
            text = "💡 Правило: 1% = 10 мг/мл (0.1% = 1 мг/мл, 2.4% = 24 мг/мл, 25% = 250 мг/мл)"
            textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#263238"))
            setPadding(18, 12, 18, 12); background = roundBg("#FFF8E1", 12f, "#FFE082")
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16 }
        }
        container.addView(pctOut)

        val pctWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val p = pctInp.text.toString().replace(",", ".").toDoubleOrNull()
                val m = mlInp.text.toString().replace(",", ".").toDoubleOrNull() ?: 1.0
                if (p != null) {
                    pctOut.text = String.format(Locale.US, "💉 В 1 мл содержится %.1f мг\n📦 В %.1f мл раствора = %.1f мг вещества", p * 10.0, m, p * 10.0 * m).replace(".0 ", " ")
                } else {
                    pctOut.text = "💡 Правило: 1% = 10 мг/мл (0.1% = 1 мг/мл, 2.4% = 24 мг/мл, 25% = 250 мг/мл)"
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        }
        pctInp.addTextChangedListener(pctWatcher); mlInp.addTextChangedListener(pctWatcher)

        // БЛОК 2. КАЛЬКУЛЯТОР ИНФУЗИИ ДОПАМИНА
        container.addView(TextView(context).apply { text = "💧 2. Калькулятор инфузии Допамина (200 мг = 5 мл 4%)"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#C62828")); setPadding(0, 4, 0, 8) })
        val dopRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 8) }
        val (dopWCol, dopWeight) = makeLabeledInput(context, "⚖️ Вес пациента (кг):", "Напр. 70", "70", "#FFEBEE", "#EF9A9A", 10)
        val (dopDCol, dopDose) = makeLabeledInput(context, "💉 Доза (мкг/кг/мин):", "Напр. 5", "5", "#FFEBEE", "#EF9A9A", 0)
        dopRow.addView(dopWCol); dopRow.addView(dopDCol); container.addView(dopRow)

        val dopOut = TextView(context).apply {
            textSize = 13.5f; setTextColor(Color.parseColor("#263238")); setLineSpacing(5f, 1.15f)
            setPadding(18, 14, 18, 14); background = roundBg("#FFF8F8", 12f, "#FFCDD2")
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16 }
        }
        container.addView(dopOut)

        fun calcDopamine() {
            val w = dopWeight.text.toString().replace(",", ".").toDoubleOrNull() ?: 70.0
            val d = dopDose.text.toString().replace(",", ".").toDoubleOrNull() ?: 5.0
            val mcgMin = w * d
            val effect = when {
                d <= 3.0 -> "Почечная доза (диуретический эффект)"
                d <= 10.0 -> "Кардиотоническая доза (бета1 — рост сердечного выброса и САД)"
                else -> "Вазопрессорная доза (альфа1 — сужение сосудов и подъем АД)"
            }
            dopOut.text = String.format(Locale.US,
                "⚡ Скорость введения: %.0f мкг/мин (%s)\n" +
                "• Капельница (200 мг на 200 мл NaCl): %.0f капель/мин\n" +
                "• Капельница (200 мг на 400 мл NaCl): %.0f капель/мин\n" +
                "• Шприцевой дозатор (200 мг в 20 мл): %.2f мл/час",
                mcgMin, effect, mcgMin / 50.0, mcgMin / 25.0, (mcgMin * 60.0) / 10000.0
            )
        }
        val dWatcher = object : TextWatcher { override fun afterTextChanged(s: Editable?) = calcDopamine(); override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}; override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {} }
        dopWeight.addTextChangedListener(dWatcher); dopDose.addTextChangedListener(dWatcher); calcDopamine()

        // БЛОК 3. РАСЧЕТ ЗАПАСА КИСЛОРОДА В БАЛЛОНЕ (В МПа)
        container.addView(TextView(context).apply { text = "🫁 3. Расчет запаса кислорода в баллоне (в МПа)"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#0277BD")); setPadding(0, 4, 0, 8) })

        var cylinderVolLiters = 2.0
        val cylSwitchRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 10) }
        container.addView(cylSwitchRow)

        val o2InputsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 8) }
        val (mpaCol, mpaInp) = makeLabeledInput(context, "⏱️ Давление манометра (МПа):", "Напр. 15", "15", "#E1F5FE", "#4FC3F7", 10)
        val (flowCol, flowInp) = makeLabeledInput(context, "💨 Поток O2 (л/мин):", "Напр. 6", "6", "#E1F5FE", "#4FC3F7", 0)
        o2InputsRow.addView(mpaCol); o2InputsRow.addView(flowCol); container.addView(o2InputsRow)

        val o2Out = TextView(context).apply {
            textSize = 13.5f; setTextColor(Color.parseColor("#263238")); setLineSpacing(5f, 1.15f)
            setPadding(18, 14, 18, 14); background = roundBg("#E1F5FE", 12f, "#81D4FA")
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 18 }
        }
        container.addView(o2Out)

        fun fmtTime(minutes: Double): String {
            val totalMin = minutes.toInt()
            return if (totalMin < 60) "$totalMin мин" else "${totalMin / 60} ч ${totalMin % 60} мин ($totalMin мин)"
        }

        fun calcOxygen() {
            val mpa = mpaInp.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
            val flow = flowInp.text.toString().replace(",", ".").toDoubleOrNull() ?: 6.0
            val atm = mpa * 10.0
            val totalGasLiters = atm * cylinderVolLiters
            val usableGasLiters = ((mpa - 1.0).coerceAtLeast(0.0) * 10.0) * cylinderVolLiters
            val timeForFlow = if (flow > 0) totalGasLiters / flow else 0.0
            val usableTime = if (flow > 0) usableGasLiters / flow else 0.0

            o2Out.text = String.format(Locale.US,
                "🛢️ Баллон %.0f л при %.1f МПа (%.0f атм):\n" +
                "• Полный объем O2: %.0f л (полезный до 1 МПа: %.0f л)\n" +
                "• При потоке %.1f л/мин хватит на: %s (до 1 МПа — %s)\n" +
                "• Шпаргалка времени: 4 л/мин → %s | 10 л/мин → %s | 15 л/мин → %s",
                cylinderVolLiters, mpa, atm, totalGasLiters, usableGasLiters,
                flow, fmtTime(timeForFlow), fmtTime(usableTime),
                fmtTime(totalGasLiters / 4.0), fmtTime(totalGasLiters / 10.0), fmtTime(totalGasLiters / 15.0)
            ).replace(".0 ", " ")
        }

        fun drawCylBtns() {
            cylSwitchRow.removeAllViews()
            listOf(1.0 to "🛢️ Баллон 1 л", 2.0 to "🛢️ Баллон 2 л", 10.0 to "🛢️ Баллон 10 л").forEach { (vol, title) ->
                val sel = cylinderVolLiters == vol
                cylSwitchRow.addView(TextView(context).apply {
                    text = title; textSize = 12.5f; gravity = Gravity.CENTER
                    typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(if (sel) Color.WHITE else Color.parseColor("#01579B"))
                    setPadding(14, 14, 14, 14)
                    background = roundBg(if (sel) "#0277BD" else "#E1F5FE", 12f)
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = if (vol < 10.0) 8 else 0 }
                    setOnClickListener { cylinderVolLiters = vol; drawCylBtns(); calcOxygen() }
                })
            }
        }

        val o2Watcher = object : TextWatcher { override fun afterTextChanged(s: Editable?) = calcOxygen(); override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}; override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {} }
        mpaInp.addTextChangedListener(o2Watcher); flowInp.addTextChangedListener(o2Watcher)
        drawCylBtns(); calcOxygen()

        container.addView(TextView(context).apply { text = "📚 Справочные шпаргалки (нажмите для открытия):"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#E65100")); setPadding(0, 4, 0, 10) })
        cheatSheets.forEach { item ->
            container.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(26, 20, 26, 20); background = roundBg("#FFF3E0", 14f, "#FFE0B2")
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
                addView(TextView(context).apply { text = item.title; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#263238")) })
                addView(TextView(context).apply { text = item.subtitle; textSize = 12.5f; setTextColor(Color.parseColor("#607D8B")); setPadding(0, 4, 0, 0) })
                setOnClickListener { showDetailDialog(context, item.title, item.content, "#EF6C00") }
            })
        }
        bottomSheet.show()
    }

    // =========================================================
    // 4. АКУШЕРСТВО И ИНТЕРАКТИВНАЯ ШКАЛА АПГАР
    // =========================================================
    private val obstetricsGuides = listOf(
        GuideCard("🤰 Акушерское пособие в родах (головное предлежание)", "Последовательность действий при приеме родов",
            "1. Сгибание головки плода: задерживают преждевременное разгибание головки, пока она не подойдет подзатылочной ямкой под лонное сочленение.\n" +
            "2. Выведение теменных бугров и защита промежности: вне потуги бережно сдвигают ткани вульварного кольца с головки, правой рукой поддерживают промежность.\n" +
            "3. Разгибание головки: вне схватки головке дают медленно разогнуться — рождаются лоб, личико и подбородок. Проверяют отсутствие обвития пуповины.\n" +
            "4. Внутренний поворот плечиков и наружный поворот головки (личиком к правому бедру при I позиции или к левому при II позиции).\n" +
            "5. Освобождение плечевого пояса: головку отводят книзу до рождения переднего плечика, затем приподнимают кверху — рождается заднее плечико.\n" +
            "6. Первичный туалет новорожденного: обсушивание теплой пеленкой, выкладывание на живот матери, оценка по Апгар на 1-й и 5-й минуте."),
        GuideCard("🔄 Механизм родов при тазовых предлежаниях", "Биомеханизм и особенности ведения",
            "• 1 момент: Внутренний поворот ягодиц (первой врезывается передняя ягодица).\n" +
            "• 2 момент: Боковое сгибание поясничной части позвоночника — рождается задняя ягодица и туловище до нижнего угла лопаток.\n" +
            "• 3 момент: Внутренний поворот плечиков и освобождение плечевого пояса.\n" +
            "• 4 момент: Внутренний поворот и сгибание последующей головки.\n" +
            "⚠️ Главное правило на СМП: не тянуть плод за тазовый конец или ножки (иначе запрокинутся ручки и разогнется головка)!"),
        GuideCard("🤲 Пособие по Цовьянову I (чисто ягодичное предлежание)", "Сохранение нормального членорасположения плода",
            "• Цель: удержать ножки плода вытянутыми вдоль туловища, чтобы они прижимали скрещенные ручки и поддерживали сгибание головки.\n" +
            "• Техника: большие пальцы прижимают вытянутые ножки плода к животу и груди, остальные 4 пальца лежат на крестце плода. По мере рождения туловища руки перехватывают ближе к половой щели, направляя туловище книзу до рождения переднего плечика, затем кверху для рождения заднего плечика и согнутой головки."),
        GuideCard("🦶 Пособие по Цовьянову II (ножные предлежания)", "Превращение ножного предлежания в смешанное",
            "• Цель: не дать ножкам родиться преждевременно при неполном раскрытии шейки матки, чтобы не ущемилась последующая головка.\n" +
            "• Техника: вульварное кольцо накрывают стерильной салфеткой и ладонью оказывают противодействие показывающимся стопам плода во время потуг, пока плод не «присядет на корточки» и ягодицы не опустятся на тазовое дно при полном раскрытии."),
        GuideCard("👶 Признаки недоношенного ребенка", "Критерии и внешние признаки",
            "• Критерии: срок < 37 недель, масса тела < 2500 г, длина < 45 см.\n" +
            "• Признаки: большая голова (1/3 длины тела), пупочное кольцо смещено к лобку, кожа тонкая ярко-розовая, обильное лануго, мягкие ушные раковины, открыт малый родничок, ногти не доходят до кончиков пальцев, яички не опущены в мошонку / половая щель зияет, мышечная гипотония, быстрое охлаждение.")
    )

    fun showObstetricsSheet(context: Context) {
        val (bottomSheet, root) = createBaseSheet(context, "🤰 Акушерство и Шкала Апгар", "#D81B60")
        val scrollView = NestedScrollView(context).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); isFillViewport = true; isNestedScrollingEnabled = true }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 4, 0, 24) }
        scrollView.addView(content); root.addView(scrollView)

        content.addView(TextView(context).apply { text = "👶 Интерактивный калькулятор Шкалы Апгар (0–10 баллов):"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#AD1457")); setPadding(0, 4, 0, 10) })
        val apgarScores = IntArray(5) { 2 }
        val apgarBanner = TextView(context).apply {
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(22, 18, 22, 18)
            background = roundBg("#D81B60", 14f); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 }
        }
        content.addView(apgarBanner)

        fun updateApgar() {
            val total = apgarScores.sum()
            val status = when {
                total >= 8 -> "Удовлетворительное состояние (норма)"
                total in 6..7 -> "Легкая асфиксия (санация ВДП, согревание, O2)"
                total in 4..5 -> "Средняя асфиксия (ИВЛ мешком Амбу, санация)"
                else -> "Тяжелая асфиксия (немедленная реанимация новорожденного!)"
            }
            apgarBanner.text = "Оценка по Апгар: $total из 10 б.\n$status\n📋 Нажмите, чтобы скопировать"
            apgarBanner.setOnClickListener { copyToClipboard(context, "Apgar", "Оценка по шкале Апгар: $total б. ($status)") }
        }

        val apgarCriteria = listOf(
            "A — Окраска кожи:" to listOf(0 to "Бледная / цианоз", 1 to "Акроцианоз", 2 to "Розовая вся"),
            "P — Пульс (ЧСС):" to listOf(0 to "Отсутствует", 1 to "< 100 в мин", 2 to ">= 100 в мин"),
            "G — Рефлексы (гримаса):" to listOf(0 to "Нет реакции", 1 to "Слабая гримаса", 2 to "Крик / кашель"),
            "A — Мышечный тонус:" to listOf(0 to "Атония (вялый)", 1 to "Снижен", 2 to "Активные движения"),
            "R — Дыхание и крик:" to listOf(0 to "Отсутствует", 1 to "Слабый крик", 2 to "Громкий крик")
        )
        apgarCriteria.forEachIndexed { cIdx, (title, opts) ->
            content.addView(TextView(context).apply { text = title; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#880E4F")); setPadding(0, 6, 0, 4) })
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 8) }
            content.addView(row)
            fun drawRow() {
                row.removeAllViews()
                opts.forEach { (pts, lbl) ->
                    val sel = apgarScores[cIdx] == pts
                    row.addView(TextView(context).apply {
                        text = "$pts — $lbl"; textSize = 11.5f; gravity = Gravity.CENTER; typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        setTextColor(if (sel) Color.WHITE else Color.parseColor("#263238")); setPadding(10, 12, 10, 12)
                        background = roundBg(if (sel) "#D81B60" else "#FCE4EC", 12f)
                        layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = 6 }
                        setOnClickListener { apgarScores[cIdx] = pts; drawRow(); updateApgar() }
                    })
                }
            }
            drawRow()
        }
        updateApgar()

        content.addView(TextView(context).apply { text = "📖 Акушерские пособия и справочник:"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#AD1457")); setPadding(0, 14, 0, 10) })
        obstetricsGuides.forEach { item ->
            content.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(26, 20, 26, 20); background = roundBg("#FCE4EC", 14f, "#F8BBD0")
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
                addView(TextView(context).apply { text = item.title; textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#263238")) })
                addView(TextView(context).apply { text = item.subtitle; textSize = 12.5f; setTextColor(Color.parseColor("#607D8B")); setPadding(0, 4, 0, 0) })
                setOnClickListener { showDetailDialog(context, item.title, item.content, "#D81B60") }
            })
        }
        bottomSheet.show()
    }

    // =========================================================
    // 5. ЯДЫ, АНТИДОТЫ И ТОКСИКОЛОГИЯ (ВСЕ 130+ ПОЗИЦИЙ)
    // =========================================================
    private val antidotesList = listOf(
        "Адреналин (Эпинефрин)" to "Фентоламин 5–10 мг в/в, бета-адреноблокаторы.",
        "Акрихин" to "Тетраэтиламмоний (Уротропин).",
        "Алупент (Орципреналин)" to "Фентоламин, бета-адреноблокаторы.",
        "Амидопирин (пирамидон)" to "Натрия гидрокарбонат 4% в/в.",
        "Амизил" to "Физостигмина салицилат, Галантамин, Аминостигмин 1–2 мг в/в или в/м.",
        "Амитриптилин (трициклические антидепрессанты)" to "Натрия гидрокарбонат 4% 100–200 мл в/в (при расширении QRS > 0.10 с и ЖТ), Диазепам при судорогах.",
        "Анальгин (Метамизол)" to "Натрия гидрокарбонат.",
        "Анилин и красители" to "Витамин С (аскорбиновая к-та 5%), Метиленовый синий 1% (1–2 мг/кг в/в). ⚠️ Не давать молоко, касторовое масло, жиры, спирт!",
        "Антибиотики" to "Специфического антидота нет. При аллергии/анафилаксии — эпинефрин (адреналин), преднизолон, гидрокортизон, супрастин, димедрол.",
        "Аспирин (кислота ацетилсалициловая, салицилаты)" to "Бикарбонат натрия (Натрия гидрокарбонат 4% в/в), Витамин К.",
        "Атропин" to "Физостигмина салицилат, Галантамин, Аминостигмин (или Прозерин); при возбуждении — Диазепам.",
        "Барбитураты" to "Натрия гидрокарбонат 4% (ощелачивание мочи), Бемегрид, при смешанных отравлениях — Налоксон, Флумазенил.",
        "Бария соли" to "Натрия сульфат или Магния сульфат (20–30 г внутрь для связывания бария в нерастворимый сульфат) + Натрия тиосульфат в/в.",
        "Белладонна (Красавка)" to "Физостигмин, Галантамин, Прозерин, Натрия гидрокарбонат, Натрия хлорид, Калия хлорид.",
        "Бензодиазепины" to "Флумазенил (Анексат) 0.2–0.5 мг в/в титрованно (до 1–2 мг).",
        "Берлинская лазурь" to "Натрия нитрит, Амилнитрит, Натрия тиосульфат, Хромосмон, Глюкоза, Гидроксикобаламин, ЭДТА.",
        "Бесалол" to "Физостигмина салицилат, Галантамин, Аминостигмин.",
        "Бета-адреноблокаторы (Анаприлин, Метопролол, Бисопролол)" to "Атропин 0.1% 1–2 мл в/в, Глюкагон 5–10 мг в/в, Кальция хлорид/глюконат 10% 10–20 мл, Допамин/Адреналин.",
        "Боразол (триборинтриамин)" to "Гидрокарбонат натрия.",
        "Бром и бромиды" to "Натрия хлорид 0.9% в/в + Фуросемид.",
        "Бутадион" to "Натрия гидрокарбонат.",
        "Верапамил, Нифедипин, Амлодипин (БМКК)" to "Кальция хлорид 10% 10–20 мл или Кальция глюконат 10% 20 мл в/в медленно, Атропин, Допамин/Норадреналин.",
        "Висмут и его соли" to "Натрия тиосульфат, Тетацин-кальций, Унитиол.",
        "Галоперидол и нейролептики" to "Трифен (Циклодол / Тригексифенидил), Бипериден (Акинетон), Диазепам, Димедрол.",
        "Гексенал" to "Бемегрид, Налоксон, Флумазенил.",
        "Гепарин" to "Протамина сульфат 1% в/в (1 мг на 100 ЕД гепарина), Витамин К.",
        "Героин" to "Налоксон 0.4–2 мг в/в, в/м или интраназально (Налмефен, Налтрексон).",
        "Гидразин (диамид)" to "Специфический антидот отсутствует (Пиридоксин В6 при судорогах, инфузионная терапия, лечение ОППН, гемодиализ).",
        "Гитален" to "Унитиол 5%, Дигибайнд, препараты Калия и Магния.",
        "Гликозиды сердечные" to "Унитиол 5% 5–10 мл, Дигибайнд, Калия и Магния аспарагинат (Панангин), Атропин при брадикардии.",
        "Делагил (Хлорохин)" to "Диазепам в высоких дозах, Натрия гидрокарбонат, Эпинефрин, Тетраэтиламмоний (Уротропин).",
        "Дерматол" to "Натрия тиосульфат, Тетацин-кальций.",
        "Диазепам" to "Флумазенил (Анексат) 0.2–0.5 мг в/в.",
        "Дигиталис (Наперстянка)" to "Унитиол, Дигибайнд, Панангин, Атропин.",
        "Дикумарин и Варфарин" to "Витамин К (Фитоменадион / Викасол 1% 2–5 мл), свежезамороженная плазма.",
        "Динитрокрезол (ДНОК, ДИНОК)" to "Дипироксим, Изонитрозин, Атропин, физическое охлаждение.",
        "Дихлорэтан" to "N-ацетилцистеин (АЦЦ), ранняя гемосорбция, инфузионная терапия.",
        "Дихлофос и аналоги (ФОС)" to "Атропин 0.1% 2–4 мл в/в повторно до сухости слизистых и ЧСС 90–100 + Дипироксим, Пралидоксим, Изонитрозин.",
        "Дормикум (Мидазолам)" to "Флумазенил (Анексат).",
        "Железа препараты" to "Дефероксамин (Десферал) в/в или в/м.",
        "Зеленина капли" to "Натрия гидрокарбонат, Натрия хлорид, Калия хлорид, Прозерин. ⚠️ Атропин перед промыванием желудка не вводить!",
        "Змеиный яд (укус гадюки)" to "Сыворотка «Антигюрза» / Антивенин (в стационаре), Преднизолон 60–90 мг в/в, Супрастин/Димедрол, иммобилизация, инфузия. (⚠️ Жгут и гепарин при укусе гадюки не применять!).",
        "Изониазид" to "Витамин В6 (Пиридоксин 5% 5–10 мл и более в/в струйно) + Диазепам при судорогах.",
        "Йод, настойка йода" to "Натрия тиосульфат 30% 10–20 мл в/в и 5% внутрь, промывание желудка раствором крахмала.",
        "Калия перманганат" to "Витамин С (аскорбиновая к-та), Метиленовый синий, обработка слизистых 1% аскорбиновой к-той.",
        "Кальция соли" to "Трилон B (Sequestrol), Натрия хлорид 0.9% + Фуросемид, Кальцитонин.",
        "Карбофос" to "Атропин 0.1% в/в до атропинизации + Дипироксим.",
        "Кислота карболовая (Фенол)" to "Глицерин-моноацетат, растительные масла, Токоферола ацетат (витамин Е).",
        "Кислота синильная" to "Амилнитрит, Тиосульфат натрия 30% 50 мл в/в, Натрия нитрит 1% 10 мл в/в.",
        "Кислота уксусная (уксусная эссенция)" to "Натрия гидрокарбонат 4% в/в (от ацидоза и гемолиза), наркотическое обезболивание, зондовое промывание холодной водой. ⚠️ Соду внутрь НЕ давать!",
        "Кислоты органические (муравьиная, лимонная, молочная)" to "Обезболивание, промывание желудка через зонд водой, Алмагель, Молоко, Натрия гидрокарбонат в/в.",
        "Клещевина (рицин)" to "Унитиол, Натрия тиосульфат, ранняя энтеросорбция и регидратация.",
        "Клонидин (Клофелин)" to "Налоксон 0.4–2 мг в/в, Атропин 0.1% 1–2 мл в/в при брадикардии, Метоклопрамид, Допамин/Мезатон при гипотонии.",
        "Кодеин" to "Налоксон, Налмефен, Налтрексон.",
        "Коргликон" to "Унитиол, Дигибайнд, Панангин, Атропин.",
        "Коронтин" to "Унитиол, Дигибайнд.",
        "Крысид (АНТУ)" to "Натрия гидрокарбонат, Цистеин, оксигенотерапия.",
        "Ксероформ" to "Натрия тиосульфат, Тетацин-кальций, Унитиол.",
        "Ляпис (нитрат серебра)" to "Натрия хлорид (раствор поваренной соли образует нерастворимый хлорид серебра).",
        "М-холиноблокаторы" to "Физостигмина салицилат, Галантамин, Аминостигмин, Прозерин.",
        "Марганцовка" to "Витамин С, Метиленовый синий.",
        "Медный купорос (меди сульфат)" to "Унитиол 5% 5–10 мл в/м или в/в, Магнезия жженая, Натрия тиосульфат.",
        "Мезапам" to "Флумазенил (Анексат).",
        "Металлы тяжелые" to "Унитиол 5%, комплексообразующие соединения (Десферал, Тетацин-кальций, Трилон Б), Тиосульфат натрия.",
        "Метанол (метиловый спирт)" to "Этанол (30% 100 мл внутрь или 5–10% в/в капельно), Фомепизол, Натрия гидрокарбонат 4% в/в, Фолиевая к-та.",
        "Морфин" to "Налоксон 0.4–2 мг в/в (Налмефен, Налтрексон, Налорфин).",
        "Мухомор" to "Атропин 0.1% 1–2 мл в/в (при мускариновом синдроме с бронхореей и брадикардией); Пиридоксин, Диазепам (при возбуждении).",
        "Мышьяк" to "Унитиол 5% 5–10 мл в/м или в/в, Тиосульфат натрия, Тетацин-кальций, Трилон Б.",
        "Наркотические анальгетики (опиоиды)" to "Налоксон 0.4–2 мг в/в, в/м или интраназально (Налмефен, Налтрексон).",
        "Нафтизин (санорин, ксилометазолин)" to "Атропин 0.1% при брадикардии, Налоксон при угнетении сознания, согревание.",
        "Никотин" to "Атропин (при холинергическом синдроме), Диазепам при судорогах, Токоферола ацетат (витамин Е).",
        "Нитраты и нитриты" to "Метиленовый синий 1%, Аскорбиновая к-та 5%, при гипотонии — горизонтальное положение, NaCl 0.9%, Мезатон, Норадреналин.",
        "Нитробензол, динитробензол (мирбановое масло)" to "Натрия тиосульфат, Метиленовый синий 1%, Витамин С.",
        "Нитроглицерин" to "Горизонтальное положение с поднятыми ногами, NaCl 0.9%, Мезатон/Норадреналин, при метгемоглобинемии — Метиленовый синий.",
        "Нитропентон" to "Мезатон, Норадреналин, Раствор метиленового синего.",
        "Нитросорбид" to "Мезатон, Норадреналин, Раствор метиленового синего.",
        "Окись углерода (Угарный газ)" to "100% кислород (ингаляция 10–15 л/мин, ИВЛ, ГБО), Ацизол 6% 1 мл в/м, Аскорбиновая кислота, Глюкоза.",
        "Омнопон" to "Налоксон, Налмефен, Налтрексон.",
        "Опий" to "Налоксон, Налмефен, Налтрексон.",
        "Папаверин / Дротаверин (Но-шпа)" to "Кальция хлорид / Кальция глюконат 10% 10–20 мл в/в медленно, Атропин при АВ-блокаде, инфузия NaCl 0.9%, Мезатон/Норадреналин.",
        "Парацетамол" to "Ацетилцистеин (АЦЦ, Флуимуцил) внутрь или в/в, Метионин.",
        "Пахикарпин" to "Натрия гидрокарбонат, Прозерин, Атропин при брадикардии.",
        "Пилокарпин" to "Атропина сульфат 0.1% 1–2 мл в/в или п/к.",
        "Платифиллина гидротартрат" to "Физостигмина салицилат, Галантамин, Аминостигмин, Прозерин.",
        "Поганка бледная" to "Силибинин (Легалон), Бензилпенициллин, Тиоктовая (липоевая) кислота, Контрикал, ранняя энтеросорбция.",
        "Промедол" to "Налоксон, Налмефен, Налтрексон.",
        "Прометрин" to "Атропин, инфузионная терапия.",
        "Противотуберкулезные препараты (ГИНК)" to "Витамин В6 (Пиридоксин 5% 5–10 мл в/в) + Диазепам.",
        "Радедорм (Нитразепам)" to "Флумазенил (Анексат).",
        "Ракитник (акация белая, цитизин)" to "Промывание желудка, энтеросорбенты, противосудорожные, оксигенотерапия.",
        "Реладорм" to "Бемегрид, Налоксон, Флумазенил.",
        "Реопирин" to "Натрия гидрокарбонат.",
        "Ртуть" to "Унитиол 5% 5–10 мл, Тиосульфат натрия, Тетацин-кальций, Трилон Б.",
        "Рудотель (Медазепам)" to "Флумазенил (Анексат).",
        "Свинец" to "Тетацин-кальций (ЭДТА), Унитиол, Тиосульфат натрия, Трилон Б.",
        "Седуксен (Диазепам)" to "Флумазенил (Анексат).",
        "Серебра нитрат, серебро азотнокислое" to "Натрия хлорид (промывание 1–2% раствором поваренной соли).",
        "Сернистый ангидрид (сернистый газ, двуокись серы)" to "Ингаляции увлажненного кислорода, Натрия гидрокарбонат, бронхолитики, ГКС.",
        "Сибазон (Диазепам)" to "Флумазенил (Анексат).",
        "Синильная кислота" to "Амилнитрит, Натрия нитрит 1% 10 мл в/в, Тиосульфат натрия 30% 50 мл в/в, Глюкоза 40%.",
        "Скополамина гидрохлорид" to "Физостигмина салицилат, Галантамин, Аминостигмин.",
        "Соли тяжелых металлов" to "Унитиол 5%, Десферал, Тиосульфат натрия, Тетацин-кальций, Трилон Б.",
        "Солутан" to "Натрия гидрокарбонат, Натрия хлорид, Калия хлорид, Прозерин. ⚠️ Атропин перед промыванием желудка не вводить!",
        "Спирт метиловый" to "Этанол (30% внутрь или 5–10% в/в капельно), Натрия гидрокарбонат 4%.",
        "Сулема (хлорид ртути)" to "Унитиол 5%, Тетацин-кальций, Тиосульфат натрия.",
        "Сульфамиды, сульфаниламиды" to "Натрия гидрокарбонат (обильное щелочное питье / инфузия), Гидрокортизон.",
        "Тазепам (Оксазепам)" to "Флумазенил (Анексат).",
        "Таллий, цезий" to "Берлинская лазурь (Ферроцин / Prussian blue), Унитиол, Тиосульфат натрия.",
        "Тетраэтилсвинец (этиловая жидкость, ТЭС)" to "Натрия гидрокарбонат, Диазепам при возбуждении.",
        "ТМТД (тиурам, тетурам)" to "Натрия гидрокарбонат, Аскорбиновая кислота 5%, инфузионная терапия.",
        "Трамал (Трамадол)" to "Налоксон 0.4–2 мг в/в, Диазепам при судорогах.",
        "Угарный газ" to "100% кислород (ингаляция 10–15 л/мин, ИВЛ, ГБО), Ацизол 6% 1 мл в/м, Аскорбиновая кислота, Глюкоза.",
        "Углерод четыреххлористый" to "N-ацетилцистеин (АЦЦ), Тиосульфат натрия, гепатопротекторы.",
        "Фенилин" to "Витамин К (Викасол / Фитоменадион), свежезамороженная плазма.",
        "Фенол" to "Растительное масло / Глицерин, Токоферола ацетат (витамин Е), Тиосульфат натрия.",
        "Фентанил" to "Налоксон 0.4–2 мг в/в (повторно каждые 2–3 мин), ИВЛ.",
        "Фенобарбитал" to "Натрия гидрокарбонат 4% в/в (ощелачивание мочи), Бемегрид, Налоксон, Флумазенил.",
        "Феррум Лек и препараты железа" to "Дефероксамин (Десферал).",
        "Формалин (формальдегид)" to "Аммония хлорид / карбонат (1–3% раствор связывает формальдегид в уротропин), мочевина.",
        "Фосфорорганические соединения (ФОС)" to "Атропин 0.1% 2–4 мл в/в каждые 5–10 мин до атропинизации + Дипироксим, Пралидоксим, Изонитрозин + Диазепам.",
        "Фтивазид" to "Витамин В6 (Пиридоксин 5% в/в).",
        "Фтора соли (фториды)" to "Кальция глюконат или Кальция хлорид 10% 10–20 мл в/в, промывание желудка с препаратами кальция.",
        "Хингамин" to "Диазепам, Натрия гидрокарбонат, Тетраэтиламмоний (Уротропин).",
        "Хинин" to "Натрия гидрокарбонат, Тетраэтиламмоний (Уротропин).",
        "Хлозепид" to "Флумазенил (Анексат).",
        "Хлордиазепоксид" to "Флумазенил (Анексат).",
        "Хлорид калия (KCl, гиперкалиемия)" to "Кальция глюконат / хлорид 10% 10–20 мл в/в медленно, Глюкоза 40% + Инсулин, Натрия гидрокарбонат 4%, Натрия хлорид 0.9%.",
        "Хлорофос" to "Атропин 0.1% в/в до атропинизации + Дипироксим.",
        "Цианамид кальция" to "Метиленовый синий, Аскорбиновая кислота, инфузионная терапия.",
        "Цианиды" to "Натрия нитрит, Амилнитрит, Натрия тиосульфат 30% 50 мл в/в, Хромосмон, Глюкоза 40%, Гидроксикобаламин, ЭДТА.",
        "Цинка сульфат" to "Натрия гидрокарбонат, Унитиол, Тетацин-кальций.",
        "Цинка фосфид (цинк фосфористый)" to "Натрия гидрокарбонат, вазелиновое масло. ⚠️ Противопоказаны растительные и животные жиры, яйца, молоко!",
        "Чернила" to "Витамин С, Метиленовый синий. ⚠️ Не давать молоко, касторовое масло, жиры, спирт!",
        "Элениум" to "Флумазенил (Анексат).",
        "Эринит" to "Мезатон, Норадреналин, Раствор метиленового синего.",
        "Этиленгликоль (антифриз, тормозная жидкость)" to "Этанол (30% 100 мл внутрь или 5–10% в/в капельно), Натрия гидрокарбонат 4% в/в, Кальция глюконат 10%."
    )

    private val toxSyndromesText =
        "📌 КЛИНИЧЕСКИЕ СИМПТОМЫ ОТРАВЛЕНИЙ (17.html–18.html):\n\n" +
        "• Атропин (красавка, белена, дурман):\n  Мидриаз (широкие зрачки без фотореакции), сухость и гиперемия кожи и слизистых, тахикардия, гипертермия, возбуждение, галлюцинации, судороги.\n\n" +
        "• ФОС (дихлофос, карбофос, хлорофос):\n  Точечный миоз, бледность, потливость, гиперсаливация, бронхорея, бронхоспазм, боли в животе, рвота, брадикардия, гипотония, фибрилляции мышц, судороги.\n\n" +
        "• Селитра, нитраты, анилин:\n  Разлитой серо-синюшный цианоз, не уменьшающийся от 100% кислорода («шоколадная» кровь), одышка, тахикардия.\n\n" +
        "• Угарный газ (CO):\n  Головная боль («обруч»), головокружение, рвота, ярко-розовая окраска кожи (затем бледность), судороги, кома, отек легких.\n\n" +
        "• Бледная поганка (фаллоидин):\n  Латентный период 6–24 ч! Неукротимая рвота, боли в животе, холероподобный понос, печеночно-почечная недостаточность, коллапс, кома.\n\n" +
        "• Мухомор (мускарин):\n  Латентный период 1–2 ч. Слюнотечение, рвота, понос, миоз, бронхорея, брадикардия, галлюцинации, бред.\n\n" +
        "• Действие ядов на ЖКТ:\n  — Боли в эпигастрии: салицилаты, кислоты/щелочи, железо, ртуть, мышьяк, грибы.\n  — Слюнотечение: ФОС, мускарин, никотин.\n  — Сухость слизистых и парез кишечника: атропин, димедрол, ТЦА, опиаты."

    private val toxSpecificSignsText =
        "🎨 ЦВЕТ РВОТНЫХ МАСС (19.html):\n" +
        "• Фиолетовый/розовый — калия перманганат, соли кобальта\n" +
        "• Голубой/зеленоватый — соли меди (медный купорос)\n" +
        "• Желтый — пикриновая и азотная кислота\n" +
        "• Черный / «кофейная гуща» — крепкие кислоты, препараты железа, салицилаты\n\n" +
        "👃 СПЕЦИФИЧЕСКИЕ ЗАПАХИ ИЗО РТА:\n" +
        "• Горького миндаля — цианиды, синильная кислота, нитробензол\n" +
        "• Чеснока — ФОС, мышьяк, фосфор, таллий\n" +
        "• Тухлых яиц — сероводород, меркаптаны, тетурам\n" +
        "• Ацетона (сладкий фруктовый) — изопропанол, хлороформ, кетоацидоз\n" +
        "• Дезинфектантов (гуаши) — фенол, креозот\n" +
        "• Моркови — цикута (вех ядовитый)\n\n" +
        "👅 «МЕТАЛЛИЧЕСКИЙ» ПРИВКУС ВО РТУ:\n" +
        "• Свинец, медь, мышьяк, соли железа, ртуть, йод.\n\n" +
        "👁️ ЗРАЧКИ И ЗРЕНИЕ:\n" +
        "• Точечный миоз: опиоиды, ФОС, мухомор, клофелин, барбитураты.\n" +
        "• Широкий мидриаз: атропин, белена, дурман, димедрол, амитриптилин, метанол (слепота).\n" +
        "• Предметы в желто-зеленом цвете (ксантопсия): сердечные гликозиды."

    fun showAntidotesSheet(context: Context) {
        val (bottomSheet, root) = createBaseSheet(context, "🧪 Токсикология: Яды и Антидоты (${antidotesList.size})", "#C62828")
        var tabIdx = 0
        val tabRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 12) }
        root.addView(tabRow)
        val contentFrame = FrameLayout(context).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        root.addView(contentFrame)

        fun renderToxTab() {
            contentFrame.removeAllViews()
            when (tabIdx) {
                0 -> {
                    val lay = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = FrameLayout.LayoutParams(-1, -1) }
                    val searchInp = EditText(context).apply {
                        hint = "Поиск из ${antidotesList.size} ядов (фос, сибазон, крысид, уксус)..."
                        setPadding(30, 20, 30, 20); styleEditText(this, "#FFEBEE", "#EF9A9A", 18f)
                    }
                    lay.addView(searchInp)
                    val listView = ListView(context).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); isNestedScrollingEnabled = true; divider = null; dividerHeight = 12; setPadding(0, 12, 0, 0) }
                    lay.addView(listView); contentFrame.addView(lay)

                    fun updateList(query: String) {
                        val q = query.trim().lowercase()
                        val filtered = antidotesList.filter { (p, a) -> q.isEmpty() || p.lowercase().contains(q) || a.lowercase().contains(q) }
                        val displayItems = filtered.map { (p, a) -> "☠️ $p\n💉 Антидот: $a" }
                        listView.adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, displayItems) {
                            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = (super.getView(position, convertView, parent) as TextView).apply {
                                setPadding(26, 18, 26, 18); setTextColor(Color.parseColor("#263238")); textSize = 13.8f; setLineSpacing(5f, 1.15f)
                                background = roundBg("#FFF8F8", 16f, "#FFCDD2")
                            }
                        }
                        listView.setOnItemClickListener { _, _, pos, _ ->
                            val (p, a) = filtered[pos]
                            showDetailDialog(context, "☠️ $p", "💉 СПЕЦИФИЧЕСКИЙ АНТИДОТ И ПОМОЩЬ:\n\n$a", "#C62828")
                        }
                    }
                    searchInp.addTextChangedListener(object : TextWatcher { override fun afterTextChanged(s: Editable?) = updateList(s.toString()); override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}; override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {} })
                    updateList("")
                }
                1, 2 -> {
                    val sv = NestedScrollView(context).apply { layoutParams = FrameLayout.LayoutParams(-1, -1); isFillViewport = true }
                    sv.addView(TextView(context).apply {
                        text = if (tabIdx == 1) toxSyndromesText else toxSpecificSignsText
                        textSize = 14.5f; setTextIsSelectable(true); setTextColor(Color.parseColor("#263238")); setLineSpacing(6f, 1.16f); setPadding(20, 16, 20, 30)
                        background = roundBg("#FFF8F8", 16f, "#FFCDD2")
                    })
                    contentFrame.addView(sv)
                }
            }
        }

        fun drawToxTabs() {
            tabRow.removeAllViews()
            listOf("☠️ Антидоты (${antidotesList.size})", "🤢 Клиника", "👃 Запахи и Рвота").forEachIndexed { i, t ->
                val sel = i == tabIdx
                tabRow.addView(TextView(context).apply {
                    text = t; textSize = 12f; gravity = Gravity.CENTER; typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(if (sel) Color.WHITE else Color.parseColor("#B71C1C")); setPadding(14, 14, 14, 14)
                    background = roundBg(if (sel) "#C62828" else "#FFEBEE", 14f)
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = if (i < 2) 8 else 0 }
                    setOnClickListener { tabIdx = i; drawToxTabs(); renderToxTab() }
                })
            }
        }
        drawToxTabs(); renderToxTab(); bottomSheet.show()
    }
}
