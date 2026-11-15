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

object MedicalTools {

    private fun roundBg(hex: String, r: Float, strokeHex: String? = null) = GradientDrawable().apply {
        setColor(Color.parseColor(hex))
        cornerRadius = r
        strokeHex?.let { setStroke(2, Color.parseColor(it)) }
    }

    private fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "📋 Скопировано: $text", Toast.LENGTH_SHORT).show()
    }

    private fun createBaseSheet(context: Context, title: String, colorHex: String): Pair<BottomSheetDialog, LinearLayout> {
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

    private fun showDetailDialog(context: Context, title: String, body: String, accentHex: String = "#00695C") {
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

    // =========================================================
    // 1. ПЕДИАТРИЯ: НОРМЫ, ЭКСТРЕННЫЕ ДОЗЫ, ТРУБКИ, ИВЛ, СЛР
    // =========================================================
    private data class AgePreset(
        val label: String,
        val defaultWeight: Double,
        val hrNorm: String,
        val rrNorm: String,
        val bpNorm: String,
        val ettUncuffed: String,
        val ettCuffed: String,
        val ettDepth: String,
        val lmaSize: String,
        val gastricWashLiters: String
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
        val (bottomSheet, root) = createBaseSheet(context, "👶 Педиатрия: Нормы, Дозы, ИВЛ", "#0277BD")

        var selectedPresetIndex = 2 // 1 год
        val chipsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, 12)
        }
        val chipsLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        chipsScroll.addView(chipsLayout)
        root.addView(chipsScroll)

        val weightRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }
        weightRow.addView(TextView(context).apply {
            text = "⚖️ Вес ребенка (кг): "
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#263238"))
        })
        val weightInput = EditText(context).apply {
            setText("10.0")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(30, 18, 30, 18)
            background = roundBg("#E1F5FE", 16f, "#81D4FA")
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        weightRow.addView(weightInput)
        root.addView(weightRow)

        val scrollView = NestedScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
            isNestedScrollingEnabled = true
        }
        val resultText = TextView(context).apply {
            textSize = 14.5f
            setTextIsSelectable(true)
            setTextColor(Color.parseColor("#263238"))
            setLineSpacing(6f, 1.18f)
            setPadding(8, 8, 8, 30)
        }
        scrollView.addView(resultText)
        root.addView(scrollView)

        fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v).replace(".00", "")

        fun recalculate() {
            val w = weightInput.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
            val p = agePresets[selectedPresetIndex]

            val sb = StringBuilder()
            sb.append("📊 ВОЗРАСТНЫЕ НОРМЫ (${p.label}):\n")
            sb.append("• Пульс (ЧСС): ${p.hrNorm} уд/мин\n")
            sb.append("• Дыхание (ЧДД): ${p.rrNorm} в мин\n")
            sb.append("• АД норма: ${p.bpNorm} мм рт.ст.\n\n")

            if (w > 0) {
                val tvMin = (w * 6.0).toInt()
                val tvMax = (w * 8.0).toInt()
                val defib1 = (w * 2.0).toInt()
                val defib2 = (w * 4.0).toInt()

                sb.append("🫁 РЕСПИРАТОРНАЯ ПОДДЕРЖКА И СЛР НА ${fmt(w)} КГ:\n")
                sb.append("• ЭТТ без манжеты: Ø ${p.ettUncuffed} мм | с манжетой: Ø ${p.ettCuffed} мм\n")
                sb.append("• Глубина ЭТТ от угла рта: ${p.ettDepth}\n")
                sb.append("• Ларингеальная маска (LMA): ${p.lmaSize}\n")
                sb.append("• ИВЛ дыхательный объем (ДО 6–8 мл/кг): $tvMin – $tvMax мл\n")
                sb.append("• Дефибрилляция (ФЖ/ЖТ): 1-й разряд $defib1 Дж (2 Дж/кг), далее $defib2 Дж (4 Дж/кг)\n")
                sb.append("• СЛР у детей: старт с 5 вдохов, далее 15:2 (для 2 спасателей) или 30:2, глубина 1/3 грудной клетки\n")
                sb.append("• Объем для промывания желудка: ${p.gastricWashLiters} (100–150 мл/кг у грудничков, до 1 л/год жизни)\n\n")

                sb.append("💉 ЭКСТРЕННЫЕ ДОЗИРОВКИ НА ${fmt(w)} КГ:\n")
                sb.append("• Адреналин 0,1% (СЛР в/в): ${fmt(min(1.0, w * 0.01))} мл (0.01 мг/кг; при разведении 1 мл + 9 мл NaCl = ${fmt(min(10.0, w * 0.1))} мл)\n")
                sb.append("• Адреналин 0,1% (Анафилаксия в/м): ${fmt(min(0.5, w * 0.01))} мл (неразведенный в бедро)\n")
                sb.append("• Амиодарон 5% (СЛР ФЖ/ЖТ в/в): ${fmt(min(6.0, w * 0.1))} мл (${fmt(min(300.0, w * 5.0))} мг из расчета 5 мг/кг)\n")
                sb.append("• Атропин 0,1% (брадикардия): ${fmt(min(0.5, (w * 0.02).coerceAtLeast(0.1)))} мл (0.02 мг/кг, мин. 0.1 мг)\n")
                sb.append("• Преднизолон (30 мг/мл): ${fmt(min(90.0, w * 2.0))} мг = ${fmt(min(3.0, w * 2.0 / 30.0))} мл (2 мг/кг)\n")
                sb.append("• Дексаметазон (4 мг/мл): ${fmt(min(16.0, w * 0.4))} мг = ${fmt(min(4.0, w * 0.1))} мл (0.3–0.6 мг/кг при крупе)\n")
                sb.append("• Диазепам 0,5% (судороги в/в, в/м): ${fmt(min(2.0, w * 0.05))} – ${fmt(min(2.0, w * 0.06))} мл (0.25–0.3 мг/кг)\n")
                sb.append("• Магния сульфат 25% (астма/судороги): ${fmt(min(8.0, w * 0.15))} мл (25–50 мг/кг в/в капельно)\n")
                sb.append("• Анальгин 50% (метамизол): ${fmt(min(2.0, w * 0.02))} мл (10 мг/кг, т.е. 0.1 мл на год жизни)\n")
                sb.append("• Димедрол 1% / Супрастин 2%: ${fmt(min(1.0, w * 0.02))} – ${fmt(min(1.0, w * 0.03))} мл\n")
                sb.append("• Папаверин 2% / Но-шпа 2%: ${fmt(min(2.0, w * 0.02))} – ${fmt(min(2.0, w * 0.03))} мл\n")
                sb.append("• Фуросемид 1% (10 мг/мл): ${fmt(min(4.0, w * 0.1))} мл (1 мг/кг)\n")
                sb.append("• Глюкоза 10% (гипогликемия в/в): ${fmt(w * 2.0)} – ${fmt(w * 4.0)} мл (2–4 мл/кг болюсом)\n")
                sb.append("• NaCl 0,9% (болюс при шоке/эксикозе): ${fmt(w * 20.0)} мл (20 мл/кг за 15–20 мин)\n")
                sb.append("• Парацетамол (внутрь/свечи): ${fmt(min(500.0, w * 15.0))} мг (15 мг/кг)\n")
                sb.append("• Ибупрофен (внутрь): ${fmt(min(400.0, w * 10.0))} мг (10 мг/кг)\n")
                sb.append("• Беродуал (небулайзер): ${min(20, (w * 1.0).toInt()).coerceAtLeast(5)} капель + 2–3 мл NaCl 0.9%\n")
                sb.append("• Пульмикорт (ложный круп): 0.5 – 2.0 мг через небулайзер\n")
            }
            resultText.text = sb.toString()
        }

        fun updateChips() {
            chipsLayout.removeAllViews()
            agePresets.forEachIndexed { idx, preset ->
                val isSel = idx == selectedPresetIndex
                chipsLayout.addView(TextView(context).apply {
                    text = "${preset.label} (${preset.defaultWeight} кг)"
                    textSize = 12f
                    typeface = if (isSel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(if (isSel) Color.WHITE else Color.parseColor("#01579B"))
                    setPadding(20, 10, 20, 10)
                    background = roundBg(if (isSel) "#0277BD" else "#E1F5FE", 16f)
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8 }
                    setOnClickListener {
                        selectedPresetIndex = idx
                        weightInput.setText(preset.defaultWeight.toString())
                        updateChips()
                        recalculate()
                    }
                })
            }
        }

        weightInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { recalculate() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })

        updateChips()
        recalculate()
        bottomSheet.show()
    }

    // =========================================================
    // 2. КЛИНИЧЕСКИЕ ШКАЛЫ И ИНДЕКСЫ (10 В 1)
    // =========================================================
    fun showCalculatorsSheet(context: Context) {
        val (bottomSheet, root) = createBaseSheet(context, "🧠 Клинические шкалы и индексы СМП", "#5E35B1")

        val scaleTabs = listOf(
            "👁️ Глазго (Взр/Дет)",
            "❤️ Шок-индекс и Кровопотеря",
            "🫁 Шкала ОДН",
            "🩸 ТЭЛА (Wells / Женева)",
            "🤢 Аппендицит (Альварадо)",
            "🧠 Инсульт (NIHSS / FAST)",
            "🫀 ХСН (ШОКС) и Алкоголь (CIWA)"
        )
        var currentTab = 0

        val tabsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, 12)
        }
        val tabsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        tabsScroll.addView(tabsRow)
        root.addView(tabsScroll)

        val scrollView = NestedScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
            isNestedScrollingEnabled = true
        }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 4, 0, 24) }
        scrollView.addView(content)
        root.addView(scrollView)

        fun buildChecklistScale(
            title: String,
            items: List<Pair<String, Double>>,
            interpreter: (Double) -> String
        ) {
            content.addView(TextView(context).apply {
                text = title
                textSize = 15.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#4527A0"))
                setPadding(0, 8, 0, 10)
            })
            val banner = TextView(context).apply {
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(22, 18, 22, 18)
                background = roundBg("#5E35B1", 14f)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 }
            }
            content.addView(banner)

            val selected = BooleanArray(items.size)
            fun updateScore() {
                var sum = 0.0
                for (i in items.indices) if (selected[i]) sum += items[i].second
                val sumStr = if (sum % 1.0 == 0.0) sum.toInt().toString() else String.format(Locale.US, "%.1f", sum)
                val interp = interpreter(sum)
                banner.text = "Сумма: $sumStr б. — $interp\n📋 Нажмите, чтобы скопировать"
                banner.setOnClickListener {
                    copyToClipboard(context, title, "$title: $sumStr б. ($interp)")
                }
            }

            items.forEachIndexed { idx, (label, pts) ->
                val ptsStr = if (pts % 1.0 == 0.0) "+${pts.toInt()}" else "+$pts"
                val row = TextView(context).apply {
                    text = "☐  $label ($ptsStr б.)"
                    textSize = 13.5f
                    setTextColor(Color.parseColor("#263238"))
                    setPadding(24, 18, 24, 18)
                    background = roundBg("#F3E5F5", 12f)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }
                    setOnClickListener {
                        selected[idx] = !selected[idx]
                        val on = selected[idx]
                        text = "${if (on) "☑️" else "☐"}  $label ($ptsStr б.)"
                        typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        setTextColor(if (on) Color.WHITE else Color.parseColor("#263238"))
                        background = roundBg(if (on) "#7E57C2" else "#F3E5F5", 12f)
                        updateScore()
                    }
                }
                content.addView(row)
            }
            updateScore()
        }

        fun renderCurrentTab() {
            content.removeAllViews()
            when (currentTab) {
                // 0. ШКАЛА ГЛАЗГО (ВЗРОСЛЫЕ И ДЕТИ)
                0 -> {
                    var isPediatric = false
                    var scoreE = 4
                    var scoreV = 5
                    var scoreM = 6

                    val modeSwitch = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 0, 0, 12)
                    }
                    val gcsBanner = TextView(context).apply {
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        setPadding(24, 20, 24, 20)
                        background = roundBg("#00796B", 16f)
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 14 }
                    }
                    val groupsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

                    fun updateGcs() {
                        val total = scoreE + scoreV + scoreM
                        val state = when {
                            total == 15 -> "Ясное сознание"
                            total in 13..14 -> "Умеренное оглушение"
                            total in 11..12 -> "Глубокое оглушение"
                            total in 9..10 -> "Сопор"
                            total in 7..8 -> "Кома I ст."
                            total in 5..6 -> "Кома II ст."
                            else -> "Кома III ст."
                        }
                        gcsBanner.text = "ШКГ (${if (isPediatric) "Дети" else "Взрослые"}): E$scoreE V$scoreV M$scoreM = $total б ($state)\n📋 Нажмите, чтобы скопировать"
                        gcsBanner.setOnClickListener {
                            copyToClipboard(context, "GCS", "ШКГ: E$scoreE V$scoreV M$scoreM = $total б ($state)")
                        }
                    }

                    fun buildGcsGroups() {
                        groupsContainer.removeAllViews()
                        fun addGroup(title: String, opts: List<Pair<Int, String>>, getCur: () -> Int, setCur: (Int) -> Unit) {
                            groupsContainer.addView(TextView(context).apply {
                                text = title; textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD
                                setTextColor(Color.parseColor("#311B92")); setPadding(0, 10, 0, 6)
                            })
                            val hs = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
                            val lay = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                            hs.addView(lay); groupsContainer.addView(hs)
                            fun draw() {
                                lay.removeAllViews()
                                opts.forEach { (pts, lbl) ->
                                    val sel = pts == getCur()
                                    lay.addView(TextView(context).apply {
                                        text = "$pts — $lbl"; textSize = 12f
                                        typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                                        setTextColor(if (sel) Color.WHITE else Color.parseColor("#263238"))
                                        setPadding(20, 12, 20, 12)
                                        background = roundBg(if (sel) "#5E35B1" else "#EDE7F6", 14f)
                                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8 }
                                        setOnClickListener { setCur(pts); draw(); updateGcs() }
                                    })
                                }
                            }
                            draw()
                        }

                        addGroup("👁️ Открывание глаз (E):", listOf(
                            4 to "Спонтанное", 3 to "На звук/речь", 2 to "На боль", 1 to "Отсутствует"
                        ), { scoreE }) { scoreE = it }

                        addGroup(if (isPediatric) "👶 Речь / Гуление у детей (V):" else "🗣️ Речевая реакция (V):",
                            if (isPediatric) listOf(
                                5 to "Улыбается, гулит", 4 to "Плачет, но успокаивается", 3 to "Крик на боль, стон", 2 to "Беспокоен, стонет", 1 to "Нет реакции"
                            ) else listOf(
                                5 to "Связная", 4 to "Спутанная", 3 to "Отд. слова", 2 to "Нечленоразд. звуки", 1 to "Отсутствует"
                            ), { scoreV }) { scoreV = it }

                        addGroup("✋ Двигательная реакция (M):", listOf(
                            6 to (if (isPediatric) "Спонтанные движения" else "По команде"),
                            5 to "Локализует боль", 4 to "Отдергивает на боль",
                            3 to "Декортикация (сгибание)", 2 to "Децеребрация (разгибание)", 1 to "Отсутствует"
                        ), { scoreM }) { scoreM = it }
                    }

                    fun drawModeBtns() {
                        modeSwitch.removeAllViews()
                        listOf(false to "🧑 Взрослые", true to "👶 Дети (до 4 лет)").forEach { (ped, lbl) ->
                            val sel = isPediatric == ped
                            modeSwitch.addView(TextView(context).apply {
                                text = lbl; gravity = Gravity.CENTER; textSize = 13f
                                typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                                setTextColor(if (sel) Color.WHITE else Color.parseColor("#4527A0"))
                                setPadding(20, 12, 20, 12); background = roundBg(if (sel) "#5E35B1" else "#EDE7F6", 12f)
                                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 8 }
                                setOnClickListener { isPediatric = ped; drawModeBtns(); buildGcsGroups(); updateGcs() }
                            })
                        }
                    }

                    content.addView(modeSwitch)
                    content.addView(gcsBanner)
                    content.addView(groupsContainer)
                    drawModeBtns(); buildGcsGroups(); updateGcs()
                }

                // 1. ШОК-ИНДЕКС, ОСТРАЯ КРОВОПОТЕРЯ И ДОГОСПИТАЛЬНЫЙ ОБЪЕМ ИНФУЗИИ
                1 -> {
                    content.addView(TextView(context).apply {
                        text = "❤️ Шок-индекс Альговера (ЧСС / САД), кровопотеря и объем инфузии"
                        textSize = 15.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#C62828"))
                        setPadding(0, 6, 0, 12)
                    })
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 12) }
                    val hrInp = EditText(context).apply {
                        hint = "Пульс (ЧСС)"; inputType = InputType.TYPE_CLASS_NUMBER; setPadding(24, 18, 24, 18)
                        background = roundBg("#FFEBEE", 14f, "#FFCDD2"); setTextColor(Color.BLACK)
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 10 }
                    }
                    val sbpInp = EditText(context).apply {
                        hint = "САД (верхнее АД)"; inputType = InputType.TYPE_CLASS_NUMBER; setPadding(24, 18, 24, 18)
                        background = roundBg("#FFEBEE", 14f, "#FFCDD2"); setTextColor(Color.BLACK)
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    }
                    row.addView(hrInp); row.addView(sbpInp); content.addView(row)

                    val shockRes = TextView(context).apply {
                        textSize = 14.5f; setTextColor(Color.parseColor("#263238")); setLineSpacing(6f, 1.18f)
                        setPadding(22, 18, 22, 18); background = roundBg("#FFF8E1", 14f, "#FFE082")
                    }
                    content.addView(shockRes)

                    fun calc() {
                        val hr = hrInp.text.toString().toDoubleOrNull()
                        val sbp = sbpInp.text.toString().toDoubleOrNull()
                        if (hr != null && sbp != null && sbp > 0) {
                            val idx = hr / sbp
                            val info = when {
                                idx < 0.8 -> "• Степень: Норма (0.5–0.7)\n• Дефицит ОЦК: нет (<10%)\n• Инфузия на СМП: поддерживающая 200–400 мл кристаллоидов"
                                idx < 1.0 -> "• Степень: Шок I ст. (компенсированный)\n• Потеря ОЦК: 15–20% (750–1000 мл)\n• Догоспитальный объем инфузии: 800–1200 мл кристаллоидов (NaCl 0.9% / Рингер)"
                                idx < 1.5 -> "• Степень: Шок II ст. (субкомпенсированный)\n• Потеря ОЦК: 25–35% (1200–1800 мл)\n• Догоспитальный объем инфузии: 1500–2000 мл (кристаллоиды + коллоиды 2:1 в 2 вены)"
                                else -> "• Степень: Шок III–IV ст. (декомпенсированный)\n• Потеря ОЦК: >40% (>2000 мл)\n• Догоспитальный объем инфузии: струйно в 2 катетера 2000–2500 мл (кристаллоиды + коллоиды 1:1) + подключение вазопрессоров (Норадреналин / Допамин)"
                            }
                            shockRes.text = String.format(Locale.US, "📊 Шок-индекс: %.2f\n%s\n\n📋 Нажмите, чтобы скопировать ШИ", idx, info)
                            shockRes.setOnClickListener {
                                copyToClipboard(context, "ШИ", String.format(Locale.US, "Шоковый индекс = %.2f", idx))
                            }
                        } else {
                            shockRes.text = "Введите ЧСС и систолическое АД для расчета шокового индекса, объема кровопотери и догоспитальной инфузии.\n\n📌 Ориентировочная кровопотеря при переломах:\n• Кости голени: 500–800 мл\n• Бедренная кость: 1000–1800 мл\n• Кости таза: 1500–3000 мл\n• Плечо / предплечье: 300–600 мл\n• Гемоторакс / гемоперитонеум: до 2000–3000 мл"
                        }
                    }
                    val tw = object : TextWatcher {
                        override fun afterTextChanged(s: Editable?) = calc()
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    }
                    hrInp.addTextChangedListener(tw); sbpInp.addTextChangedListener(tw); calc()
                }

                // 2. ШКАЛА ОДН (ОСТРАЯ ДЫХАТЕЛЬНАЯ НЕДОСТАТОЧНОСТЬ)
                2 -> {
                    content.addView(TextView(context).apply {
                        text = "🫁 Классификация и шкала степеней ОДН\n\n" +
                                "🟢 ОДН I степени (компенсированная):\n" +
                                "• Сознание: ясное, беспокойство, эйфория или тревога\n" +
                                "• ЧДД: 22–28 в мин\n" +
                                "• SpO2: 90–94%\n" +
                                "• Кожа: бледная, умеренный акроцианоз\n" +
                                "• ЧСС и АД: умеренная тахикардия, АД норма или слегка повышено\n\n" +
                                "🟡 ОДН II степени (субкомпенсированная):\n" +
                                "• Сознание: возбуждение, спутанность или заторможенность\n" +
                                "• ЧДД: 30–38 в мин, участие вспомогательной мускулатуры\n" +
                                "• SpO2: 75–89%\n" +
                                "• Кожа: выраженный диффузный цианоз, липкий пот\n" +
                                "• ЧСС и АД: тахикардия >110–120/мин, артериальная гипертензия\n\n" +
                                "🔴 ОДН III степени (декомпенсированная / прекоматозная):\n" +
                                "• Сознание: сопор или кома, судороги\n" +
                                "• ЧДД: >40 в мин (поверхностное) или брадипноэ <8–10 в мин\n" +
                                "• SpO2: <75%\n" +
                                "• Кожа: багрово-синюшная или мраморно-бледная\n" +
                                "• ЧСС и АД: гипотония, аритмия, переход в брадикардию → показана экстренная интубация и ИВЛ!"
                        textSize = 14.5f; setTextColor(Color.parseColor("#263238")); setLineSpacing(6f, 1.16f)
                        setPadding(22, 18, 22, 18); background = roundBg("#F3E5F5", 14f)
                    })
                }

                // 3. ШКАЛА УЭЛЛСА (WELLS) И ЖЕНЕВСКАЯ ШКАЛА (ТЭЛА)
                3 -> {
                    buildChecklistScale("🩸 Шкала Уэллса (Wells) — вероятность ТЭЛА:", listOf(
                        "Клинические признаки ТГВ (отек и боль в голени)" to 3.0,
                        "ТЭЛА более вероятна, чем альтернативный диагноз" to 3.0,
                        "Тахикардия (ЧСС > 100 в минуту)" to 1.5,
                        "Иммобилизация (>3 дней) или операция за последние 4 недели" to 1.5,
                        "ТГВ или ТЭЛА в анамнезе" to 1.5,
                        "Кровохарканье" to 1.0,
                        "Онкологическое заболевание (активное или лечение за 6 мес)" to 1.0
                    )) { sum ->
                        when {
                            sum <= 1.0 -> "Низкий риск ТЭЛА (<2%)"
                            sum <= 6.0 -> "Умеренный риск ТЭЛА (20–30%)"
                            else -> "Высокий риск ТЭЛА (>60%)"
                        }
                    }

                    content.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(-1, 20) })

                    buildChecklistScale("🇨🇭 Женевская шкала (пересмотренная) — ТЭЛА:", listOf(
                        "Возраст старше 65 лет" to 1.0,
                        "ТГВ или ТЭЛА в анамнезе" to 3.0,
                        "Операция или перелом конечности за последний 1 месяц" to 2.0,
                        "Активное злокачественное новообразование" to 2.0,
                        "Односторонняя боль в нижней конечности" to 3.0,
                        "Кровохарканье" to 2.0,
                        "ЧСС 75–94 в минуту" to 3.0,
                        "ЧСС >= 95 в минуту (добавить еще +2 б., всего 5 б.)" to 2.0,
                        "Боль при пальпации глубоких вен и односторонний отек" to 4.0
                    )) { sum ->
                        when {
                            sum <= 3.0 -> "Низкая клиническая вероятность ТЭЛА"
                            sum <= 10.0 -> "Средняя клиническая вероятность ТЭЛА"
                            else -> "Высокая клиническая вероятность ТЭЛА"
                        }
                    }
                }

                // 4. ШКАЛА АЛЬВАРАДО (ОСТРЫЙ АППЕНДИЦИТ)
                4 -> {
                    buildChecklistScale("🤢 Шкала Альварадо (MANTRELS) — острый аппендицит:", listOf(
                        "Миграция боли в правую подвздошную область (симптом Кохера)" to 1.0,
                        "Анорексия (отсутствие аппетита)" to 1.0,
                        "Тошнота или однократная рвота" to 1.0,
                        "Болезненность при пальпации в правой подвздошной области" to 2.0,
                        "Положительный симптом Щеткина-Блюмберга" to 1.0,
                        "Повышение температуры тела >= 37.3 °C" to 1.0,
                        "Лейкоцитоз > 10×10⁹/л (на госпитальном этапе)" to 2.0,
                        "Нейтрофильный сдвиг влево (на госпитальном этапе)" to 1.0
                    )) { sum ->
                        when {
                            sum <= 4.0 -> "Аппендицит маловероятен"
                            sum <= 6.0 -> "Возможен острый аппендицит (показана доставка в хирургию)"
                            else -> "Высокая вероятность острого аппендицита (экстренная госпитализация)"
                        }
                    }
                }

                // 5. ИНСУЛЬТ (ЭКСПРЕСС NIHSS / LAMS / FAST)
                5 -> {
                    buildChecklistScale("🧠 Догоспитальная оценка тяжести инсульта (LAMS / экспресс-NIHSS):", listOf(
                        "Асимметрия лица (опущение угла рта, парез мимической мускулатуры)" to 1.0,
                        "Парез руки: рука медленно опускается" to 1.0,
                        "Плегия руки: рука быстро падает / нет движений (еще +1 б.)" to 1.0,
                        "Снижение силы сжатия кисти" to 1.0,
                        "Отсутствие сжатия кисти (еще +1 б.)" to 1.0,
                        "Нарушение речи (дизартрия или моторная/сенсорная афазия)" to 2.0,
                        "Парез взора (насильственный поворот глаз и головы в сторону очага)" to 2.0,
                        "Угнетение сознания (оглушение / сопор)" to 2.0
                    )) { sum ->
                        when {
                            sum == 0.0 -> "Очагового неврологического дефицита не выявлено"
                            sum <= 3.0 -> "Умеренный дефицит (Ишемический инсульт / ТИА)"
                            else -> "Тяжелый инсульт (высокий риск окклюзии крупной артерии — экстренное окно тромболизиса/тромбэктомии)"
                        }
                    }
                }

                // 6. ШКАЛА ШОКС (ХСН) И CIWA-Ar (ОТМЕНА АЛКОГОЛЯ)
                6 -> {
                    buildChecklistScale("🫀 Шкала ШОКС (оценка тяжести ХСН по Марееву):", listOf(
                        "Одышка в покое (2 б.) или при нагрузке (1 б.)" to 2.0,
                        "Изменение веса за последнюю неделю (задержка жидкости)" to 1.0,
                        "Перебои в работе сердца" to 1.0,
                        "Положение в постели: ортопноэ / высокое изголовье" to 2.0,
                        "Набухшие шейные вены (лежа или стоя)" to 2.0,
                        "Влажные хрипы в легких (в нижних отделах или над всей поверхностью)" to 2.0,
                        "Ритм галопа при аускультации сердца" to 1.0,
                        "Увеличение печени (гепатомегалия)" to 2.0,
                        "Отеки нижних конечностей / анасарка" to 2.0,
                        "САД < 100 мм рт. ст." to 2.0
                    )) { sum ->
                        when {
                            sum == 0.0 -> "Признаков ХСН нет"
                            sum <= 3.0 -> "I ФК ХСН"
                            sum <= 6.0 -> "II ФК ХСН"
                            sum <= 9.0 -> "III ФК ХСН"
                            else -> "IV ФК ХСН (тяжелая декомпенсация)"
                        }
                    }

                    content.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(-1, 20) })

                    buildChecklistScale("🍺 Экспресс-оценка отмены алкоголя (CIWA-Ar):", listOf(
                        "Тошнота и рвота" to 4.0,
                        "Выраженный тремор вытянутых рук и пальцев" to 4.0,
                        "Профузная потливость" to 4.0,
                        "Тревога, внутреннее напряжение" to 4.0,
                        "Психомоторное возбуждение / ажитация" to 5.0,
                        "Тактильные, слуховые или зрительные обманы восприятия (предделирий)" to 5.0,
                        "Дезориентация во времени и месте" to 4.0
                    )) { sum ->
                        when {
                            sum <= 8.0 -> "Легкий синдром отмены"
                            sum <= 15.0 -> "Умеренный синдром отмены"
                            else -> "Тяжелая абстиненция, высокий риск алкогольного делирия и судорог"
                        }
                    }
                }
            }
        }

        fun drawTabs() {
            tabsRow.removeAllViews()
            scaleTabs.forEachIndexed { idx, title ->
                val sel = idx == currentTab
                tabsRow.addView(TextView(context).apply {
                    text = title; textSize = 12.5f
                    typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(if (sel) Color.WHITE else Color.parseColor("#4527A0"))
                    setPadding(22, 12, 22, 12)
                    background = roundBg(if (sel) "#5E35B1" else "#EDE7F6", 16f)
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8 }
                    setOnClickListener { currentTab = idx; drawTabs(); renderCurrentTab() }
                })
            }
        }

        drawTabs()
        renderCurrentTab()
        bottomSheet.show()
    }

    // =========================================================
    // 3. ШПАРГАЛКИ И КАЛЬКУЛЯТОРЫ СМП
    // =========================================================
    private data class CheatSheetItem(val title: String, val subtitle: String, val content: String)

    private val cheatSheets = listOf(
        CheatSheetItem(
            "📍 Локализация инфаркта миокарда (ИМ)",
            "По отведениям ЭКГ + реципрокные изменения и артерия",
            "• Передне-перегородочный ИМ:\n  Элевация ST: V1–V2 (V3)\n  Реципрокная депрессия: II, III, aVF\n\n" +
            "• Передний распространенный ИМ:\n  Элевация ST: I, aVL, V1–V6\n  Реципрокная депрессия: II, III, aVF (бассейн ПМЖВ ЛКА)\n\n" +
            "• Боковой ИМ:\n  Элевация ST: I, aVL, V5–V6\n  Реципрокная депрессия: II, III, aVF (Огибающая артерия)\n\n" +
            "• Нижний (диафрагмальный) ИМ:\n  Элевация ST: II, III, aVF\n  Реципрокная депрессия: I, aVL, V1–V3 (ПКА в 85% случаев)\n\n" +
            "• Задне-базальный ИМ:\n  Прямые признаки: V7–V9 (элевация ST >= 0.5 мм)\n  Реципрокные в V1–V3: высокий зубец R (R > S), глубокая горизонтальная депрессия ST, высокий положительный T\n\n" +
            "• ИМ правого желудочка (часто с нижним ИМ!):\n  Элевация ST в II, III, aVF + в правых отведениях V3R–V4R >= 1 мм.\n  ⚠️ Противопоказаны нитраты и морфин из-за риска коллапса!"
        ),
        CheatSheetItem(
            "📈 Анализ ЭКГ: зубцы, интервалы и ЭОС",
            "Нормы интервалов и положение электрической оси сердца",
            "📏 Скорость пленки:\n• При 25 мм/с: 1 маленькая клетка (1 мм) = 0.04 с; 1 большая (5 мм) = 0.20 с.\n• При 50 мм/с: 1 мм = 0.02 с; 5 мм = 0.10 с.\n\n" +
            "⏱️ Нормы зубцов и интервалов:\n• Зубец P: до 0.10 с, амплитуда до 2.5 мм\n• Интервал PQ (PR): 0.12 – 0.20 с (>0.20 с — АВ-блокада I ст.)\n• Комплекс QRS: 0.06 – 0.10 с (>=0.12 с — полная блокада ножки пучка Гиса)\n• Интервал QTc: в норме до 0.44 с у мужчин и до 0.46 с у женщин\n\n" +
            "🧭 Положение ЭОС по I, II, III отведениям:\n• Нормальная ЭОС (+30°..+69°): R(II) > R(I) > R(III), в III зубец R > S\n• Горизонтальная / отклонение влево (<0°): высокий R в I и aVL, глубокий S в III и aVF (SIII > RIII)\n• Вертикальная / отклонение вправо (>+90°): глубокий S в I (SI > RI), высокий R в III и aVF"
        ),
        CheatSheetItem(
            "💓 Расчет ЧСС по ЭКГ",
            "Формулы для скорости 25 мм/с и 50 мм/с и при ФП",
            "• При правильном ритме:\n" +
            "  — Скорость 25 мм/с: ЧСС = 300 / число больших клеток (по 5 мм) между R–R (или 1500 / число мм)\n" +
            "  — Скорость 50 мм/с: ЧСС = 600 / число больших клеток (по 5 мм) между R–R (или 3000 / число мм)\n\n" +
            "• Быстрая шкала для 25 мм/с (по большим клеткам):\n" +
            "  1 кл = 300 | 2 кл = 150 | 3 кл = 100 | 4 кл = 75 | 5 кл = 60 | 6 кл = 50 уд/мин\n\n" +
            "• Быстрая шкала для 50 мм/с (по большим клеткам):\n" +
            "  2 кл = 300 | 4 кл = 150 | 6 кл = 100 | 8 кл = 75 | 10 кл = 60 | 12 кл = 50 уд/мин\n\n" +
            "• При фибрилляции предсердий (неправильный ритм):\n" +
            "  Подсчитать число комплексов QRS за 3 секунды (15 больших клеток при 25 мм/с или 30 больших клеток при 50 мм/с) и умножить на 20!"
        ),
        CheatSheetItem(
            "🔥 Площадь и степени ожога (взрослые и дети)",
            "Правило девяток Уоллеса, правило ладони и степени",
            "✋ Правило ладони (И.И. Глумов):\n• Площадь ладони пострадавшего (вместе с пальцами) = 1% поверхности тела.\n\n" +
            "🧍 Правило «девяток» у взрослых:\n• Голова и шея — 9%\n• Верхняя конечность — по 9% (всего 18%)\n• Передняя поверхность туловища — 18% (грудь 9% + живот 9%)\n• Задняя поверхность туловища — 18%\n• Нижняя конечность — по 18% (бедро 9% + голень и стопа 9%)\n• Промежность — 1%\n\n" +
            "👶 Особенности у детей (по Ланду и Браудеру):\n• До 1 года: Голова и шея = 18–21%, каждая нога = 14%\n• 5 лет: Голова и шея = 14%, каждая нога = 16%\n\n" +
            "🌡️ Степени термических ожогов:\n• I ст. — гиперемия и отек кожи\n• II ст. — пузыри с прозрачным серозным содержимым\n• IIIА ст. — некроз эпидермиса и верхних слоев дермы, крупные напряженные/вскрывшиеся пузыри с желеобразным или геморрагическим содержимым\n• IIIБ ст. — тотальный некроз всей толщи кожи (плотный струп)\n• IV ст. — обугливание кожи, мышц и костей"
        ),
        CheatSheetItem(
            "💧 ОКИ: степени дегидротации (эксикоза)",
            "Клиническая оценка обезвоживания у детей и взрослых",
            "🟢 I степень (легкая, потеря массы до 3–5%):\n• Сознание ясное, жажда умеренная, слизистые влажные или слегка суховатые, тургор тканей сохранен, диурез не снижен.\n\n" +
            "🟡 II степень (средняя, потеря массы 6–9%):\n• Беспокойство или вялость, выраженная жажда, слизистые сухие, язык обложен, глаза и большой родничок запавшие, кожная складка расправляется за 1–2 сек, тахикардия, олигурия.\n\n" +
            "🔴 III степень (тяжелая / гиповолемический шок, потеря >= 10%):\n• Сопор/кома, пить не может, кожа холодная, мраморная, акроцианоз, кожная складка не расправляется (>2 сек), черты лица заострены, тахикардия, гипотония, анурия.\n• Помощь: немедленный болюс NaCl 0.9% / Рингер 20 мл/кг в/в струйно!"
        ),
        CheatSheetItem(
            "🔪 Локализация острой боли в животе",
            "Хирургические заболевания и основные симптомы",
            "• Правая подвздошная область:\n  — Острый аппендицит (с-мы Кохера, Ровзинга, Ситковского, Бартомье-Михельсона, Щеткина-Блюмберга)\n  — Апоплексия правого яичника / внематочная беременность (с-м Промптова, Куленкампфа)\n  — Правосторонняя почечная колика\n\n" +
            "• Правое подреберье:\n  — Острый холецистит / печеночная колика (с-мы Ортнера, Мерфи, Кера, Мюсси-Георгиевского)\n\n" +
            "• Эпигастрий и опоясывающая боль:\n  — Перфоративная язва («кинжальная» боль, доскообразный живот, с-м Спижарного)\n  — Острый панкреатит (опоясывающая боль, с-мы Мейо-Робсона, Воскресенского, Мондора)\n  — Абдоминальная форма ИМ нижней стенки (всегда снимать ЭКГ!)\n\n" +
            "• Левая подвздошная область:\n  — Острый дивертикулит сигмовидной кишки, левосторонняя почечная колика, гинекологическая патология слева\n\n" +
            "• Схваткообразная боль по всему животу + вздутие:\n  — Острая кишечная непроходимость (с-мы Валя, Склярова «шум плеска», Спасокукоцкого)"
        ),
        CheatSheetItem(
            "🫁 Респираторная поддержка и параметры ИВЛ",
            "Взрослые и дети при самостоятельном дыхании и ИВЛ",
            "• Показания к оксигенотерапии:\n  SpO2 < 92–94% (при ХОБЛ целевая SpO2 88–92%, поток 1–3 л/мин).\n  Через носовые канюли: 2–6 л/мин (FiO2 28–44%); через лицевую маску: 6–10 л/мин (FiO2 40–60%).\n\n" +
            "• Показания к интубации трахеи и ИВЛ на СМП:\n  — Апноэ или патологические ритмы дыхания (агональное, Чейна-Стокса, Биота)\n  — ЧДД > 38–40 в мин или < 8 в мин\n  — Стойкая гипоксемия SpO2 < 80% на фоне подачи 100% O2\n  — Кома <= 8 баллов по Шкале Глазго\n\n" +
            "• Стартовые параметры ИВЛ у взрослых:\n  — Дыхательный объем (ДО, Vt): 6–8 мл/кг идеальной массы тела (обычно 450–600 мл)\n  — Частота дыхания (ЧД, f): 12–16 в мин\n  — Минутный объем вентиляции (МОВ): 6–8 л/мин\n  — PEEP (ПДКВ): 5 см вод. ст. (при отеке легких до 8–10 см вод. ст.)"
        )
    )

    fun showCheatSheetsSheet(context: Context) {
        val (bottomSheet, root) = createBaseSheet(context, "📋 Шпаргалки и быстрые расчеты СМП", "#EF6C00")

        val scrollView = NestedScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isFillViewport = true
            isNestedScrollingEnabled = true
        }
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 4, 0, 24) }
        scrollView.addView(container)
        root.addView(scrollView)

        // --- МИКРО-КАЛЬКУЛЯТОР 1: ПРОЦЕНТНЫЙ РАСТВОР В МГ/МЛ ---
        container.addView(TextView(context).apply {
            text = "🧮 1. Расчет вещества в мг из % раствора"
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#E65100"))
            setPadding(0, 4, 0, 8)
        })
        val pctRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 6) }
        val pctInp = EditText(context).apply {
            hint = "Раствор % (напр. 25)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(20, 14, 20, 14); background = roundBg("#FFF3E0", 12f, "#FFE0B2"); setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 8 }
        }
        val mlInp = EditText(context).apply {
            hint = "Объем мл (напр. 10)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(20, 14, 20, 14); background = roundBg("#FFF3E0", 12f, "#FFE0B2"); setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        pctRow.addView(pctInp); pctRow.addView(mlInp); container.addView(pctRow)
        val pctOut = TextView(context).apply {
            text = "Правило: 1% = 10 мг/мл (0.1% = 1 мг/мл, 25% = 250 мг/мл)"
            textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#37474F"))
            setPadding(8, 4, 8, 16)
        }
        container.addView(pctOut)

        val pctWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val p = pctInp.text.toString().replace(",", ".").toDoubleOrNull()
                val m = mlInp.text.toString().replace(",", ".").toDoubleOrNull() ?: 1.0
                if (p != null) {
                    val mgPerMl = p * 10.0
                    val totalMg = mgPerMl * m
                    pctOut.text = String.format(Locale.US, "💉 В 1 мл содержится %.1f мг | В %.1f мл = %.1f мг вещества", mgPerMl, m, totalMg).replace(".0 ", " ")
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        }
        pctInp.addTextChangedListener(pctWatcher); mlInp.addTextChangedListener(pctWatcher)

        // --- МИКРО-КАЛЬКУЛЯТОР 2: РАСЧЕТ ДОПАМИНА И КИСЛОРОДА ---
        container.addView(TextView(context).apply {
            text = "🧮 2. Калькулятор Допамина и запаса O2 в баллоне"
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#E65100"))
            setPadding(0, 8, 0, 8)
        })
        val dopRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 6) }
        val dopWeight = EditText(context).apply {
            hint = "Вес (кг)"; setText("70"); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(20, 14, 20, 14); background = roundBg("#FFF3E0", 12f, "#FFE0B2"); setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 8 }
        }
        val dopDose = EditText(context).apply {
            hint = "мкг/кг/мин"; setText("5"); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(20, 14, 20, 14); background = roundBg("#FFF3E0", 12f, "#FFE0B2"); setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 8 }
        }
        val o2Atm = EditText(context).apply {
            hint = "O2 атм (2л)"; setText("150"); inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(20, 14, 20, 14); background = roundBg("#E1F5FE", 12f, "#81D4FA"); setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        dopRow.addView(dopWeight); dopRow.addView(dopDose); dopRow.addView(o2Atm); container.addView(dopRow)

        val dopOut = TextView(context).apply {
            textSize = 13.5f; setTextColor(Color.parseColor("#263238")); setLineSpacing(4f, 1.15f)
            setPadding(18, 12, 18, 16); background = roundBg("#FFF8E1", 12f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16 }
        }
        container.addView(dopOut)

        fun calcDopAndO2() {
            val w = dopWeight.text.toString().replace(",", ".").toDoubleOrNull() ?: 70.0
            val d = dopDose.text.toString().replace(",", ".").toDoubleOrNull() ?: 5.0
            val atm = o2Atm.text.toString().toDoubleOrNull() ?: 150.0
            // Разведение: 200 мг Допамина (5 мл 4% или 40 мл 0.5%) на 200 мл NaCl = 1000 мкг/мл (1 мл = 20 капель -> 1 капля = 50 мкг)
            // На 400 мл NaCl = 500 мкг/мл (1 капля = 25 мкг)
            val mcgMin = w * d
            val drops200 = mcgMin / 50.0
            val drops400 = mcgMin / 25.0
            val mlHr20 = (mcgMin * 60.0) / 10000.0 // Шприцевой дозатор: 200 мг в 20 мл = 10 000 мкг/мл
            val o2Liters = atm * 2.0 // для 2-литрового баллона КИ-3М / КРИ
            dopOut.text = String.format(Locale.US,
                "💧 Допамин (%.0f мкг/мин):\n" +
                "• Капельница (200 мг на 200 мл NaCl): %.0f кап/мин\n" +
                "• Капельница (200 мг на 400 мл NaCl): %.0f кап/мин\n" +
                "• Дозатор (200 мг в 20 мл): %.2f мл/час\n" +
                "🫁 Баллон 2 л (%.0f атм = %.0f л O2): при 5 л/мин хватит на %.0f мин, при 10 л/мин — на %.0f мин",
                mcgMin, drops200, drops400, mlHr20, atm, o2Liters, o2Liters / 5.0, o2Liters / 10.0
            )
        }
        val dWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = calcDopAndO2()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        }
        dopWeight.addTextChangedListener(dWatcher); dopDose.addTextChangedListener(dWatcher); o2Atm.addTextChangedListener(dWatcher)
        calcDopAndO2()

        // --- СПИСОК КАРТОЧЕК-ШПАРГАЛОК ---
        container.addView(TextView(context).apply {
            text = "📚 Справочные шпаргалки (нажмите для открытия):"
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#E65100"))
            setPadding(0, 4, 0, 10)
        })

        cheatSheets.forEach { item ->
            container.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(26, 20, 26, 20)
                background = roundBg("#FFF3E0", 14f, "#FFE0B2")
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
                addView(TextView(context).apply {
                    text = item.title; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#263238"))
                })
                addView(TextView(context).apply {
                    text = item.subtitle; textSize = 12.5f; setTextColor(Color.parseColor("#607D8B")); setPadding(0, 4, 0, 0)
                })
                setOnClickListener { showDetailDialog(context, item.title, item.content, "#EF6C00") }
            })
        }

        bottomSheet.show()
    }

    // =========================================================
    // 4. АКУШЕРСТВО И ИНТЕРАКТИВНАЯ ШКАЛА АПГАР
    // =========================================================
    private val obstetricsGuides = listOf(
        CheatSheetItem(
            "🤰 Акушерское пособие в родах (головное предлежание)",
            "Последовательность действий при приеме физиологических родов",
            "1. Сгибание головки плода:\n   Ладонью левой руки располагаются на лобке и задерживают преждевременное разгибание головки, пока она не подойдет подзатылочной ямкой под нижний край лонного сочленения.\n\n" +
            "2. Выведение теменных бугров и защита промежности:\n   Вне потуги бережно сдвигают ткани вульварного кольца с головки, правой рукой через стерильную салфетку поддерживают промежность.\n\n" +
            "3. Разгибание головки:\n   Когда подзатылочная ямка фиксируется под лонным сочленением, вне схватки головке дают медленно разогнуться — рождаются лоб, личико и подбородок. Проверяют отсутствие обвития пуповины вокруг шеи.\n\n" +
            "4. Внутренний поворот плечиков и наружный поворот головки:\n   Головка поворачивается личиком к правому (при I позиции) или левому (при II позиции) бедру матери.\n\n" +
            "5. Освобождение плечевого пояса и туловища:\n   Головку захватывают обеими ладонями за височно-щечные области и осторожно отводят книзу до рождения переднего плечика из-под лона. Затем приподнимают головку кверху — рождается заднее плечико, после чего плавно извлекают туловище.\n\n" +
            "6. Первичный туалет новорожденного:\n   Обсушивание теплой пеленкой, выкладывание на живот матери («кожа к коже»), оценка по Апгар на 1-й и 5-й минуте, пересечение пуповины после прекращения пульсации."
        ),
        CheatSheetItem(
            "🔄 Механизм родов при тазовых предлежаниях",
            "Биомеханизм и особенности ведения",
            "• 1 момент: Внутренний поворот ягодиц (диаметр таза плода устанавливается в прямом размере выхода таза матери). Первой врезывается передняя ягодица.\n\n" +
            "• 2 момент: Боковое сгибание поясничной части позвоночника плода — рождается задняя ягодица, затем ножки и туловище до нижнего угла лопаток.\n\n" +
            "• 3 момент: Внутренний поворот плечиков и освобождение плечевого пояса.\n\n" +
            "• 4 момент: Внутренний поворот и сгибание последующей головки (подзатылочная ямка фиксируется под лоном, рождаются подбородок, лицо, лоб и темя).\n\n" +
            "⚠️ Главное правило на СМП: не тянуть плод за тазовый конец или ножки (иначе запрокинутся ручки и разогнется головка)!"
        ),
        CheatSheetItem(
            "🤲 Пособие по Цовьянову I (чисто ягодичное предлежание)",
            "Сохранение нормального членорасположения плода",
            "• Цель: удержать ножки плода вытянутыми вдоль туловища, чтобы они прижимали скрещенные на груди ручки и поддерживали сгибание головки.\n\n" +
            "• Техника:\n" +
            "1. С момента прорезывания ягодиц их захватывают обеими руками так, чтобы большие пальцы прижимали вытянутые ножки плода к его животу и груди, а остальные четыре пальца располагались на крестце плода.\n" +
            "2. По мере рождения туловища руки акушера «перехватывают» туловище ближе к половой щели роженицы, продолжая прижимать ножки к туловищу.\n" +
            "3. Туловище направляют несколько кзади (книзу) до рождения переднего плечика из-под лонной дуги, затем поднимают кверху для рождения заднего плечика и ручек.\n" +
            "4. После рождения плечевого пояса туловище направляют круто кверху (на живот матери) для рождения согнутой головки."
        ),
        CheatSheetItem(
            "🦶 Пособие по Цовьянову II (ножные предлежания)",
            "Превращение ножного предлежания в смешанное ягодично-ножное",
            "• Цель: не дать ножкам родиться преждевременно при неполном раскрытии шейки матки, чтобы не ущемилась последующая головка.\n\n" +
            "• Техника:\n" +
            "1. Вульварное кольцо накрывают стерильной салфеткой и ладонью оказывают противодействие показывающимся стопам плода во время потуг.\n" +
            "2. Плод как бы «приседает» на корточки (переходит в смешанное ягодично-ножное предлежание), вызывая полное раскрытие зева.\n" +
            "3. Когда ягодицы опускаются на тазовое дно и начинают распирать промежность (позывы на потуги становятся неудержимыми), противодействие прекращают и принимают роды."
        ),
        CheatSheetItem(
            "👶 Признаки недоношенного ребенка",
            "Внешние признаки и степени недоношенности",
            "• Критерии недоношенности: срок гестации менее 37 полных недель, масса тела < 2500 г, длина тела < 45 см.\n\n" +
            "• Клинические признаки:\n" +
            "1. Непропорциональное телосложение: большая голова (1/3 длины тела), короткие ножки, пупочное кольцо расположено ближе к лобку.\n" +
            "2. Кожа тонкая, ярко-розовая/красная, обильно покрыта пушковыми волосами (лануго), подкожно-жировой слой истончен или отсутствует.\n" +
            "3. Ушные раковины мягкие (слабо развит хрящ), легко прижимаются и медленно расправляются.\n" +
            "4. Кости черепа податливые, открыты как большой, так и малый родничок.\n" +
            "5. Ногти не доходят до кончиков пальцев.\n" +
            "6. Половые органы: у мальчиков яички не опущены в мошонку; у девочек большие половые губы не прикрывают малые (половая щель зияет).\n" +
            "7. Мышечная гипотония, слабый писклявый крик, быстрое охлаждение (требуется тщательный обогрев при транспортировке!)."
        )
    )

    fun showObstetricsSheet(context: Context) {
        val (bottomSheet, root) = createBaseSheet(context, "🤰 Акушерство и Шкала Апгар", "#D81B60")

        val scrollView = NestedScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isFillViewport = true
            isNestedScrollingEnabled = true
        }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 4, 0, 24) }
        scrollView.addView(content)
        root.addView(scrollView)

        // --- ИНТЕРАКТИВНАЯ ШКАЛА АПГАР ---
        content.addView(TextView(context).apply {
            text = "👶 Интерактивный калькулятор Шкалы Апгар (0–10 баллов):"
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#AD1457"))
            setPadding(0, 4, 0, 10)
        })

        val apgarScores = IntArray(5) { 2 }
        val apgarBanner = TextView(context).apply {
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setPadding(22, 18, 22, 18)
            background = roundBg("#D81B60", 14f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 }
        }
        content.addView(apgarBanner)

        fun updateApgar() {
            val total = apgarScores.sum()
            val status = when {
                total >= 8 -> "Удовлетворительное состояние (норма)"
                total in 6..7 -> "Легкая асфиксия (требуется санация ВДП, согревание, O2)"
                total in 4..5 -> "Средняя (умеренная) асфиксия (ИВЛ мешком Амбу, санация)"
                else -> "Тяжелая асфиксия (немедленная реанимация новорожденного!)"
            }
            apgarBanner.text = "Оценка по Апгар: $total из 10 б.\n$status\n📋 Нажмите, чтобы скопировать"
            apgarBanner.setOnClickListener {
                copyToClipboard(context, "Apgar", "Оценка по шкале Апгар: $total б. ($status)")
            }
        }

        val apgarCriteria = listOf(
            "A — Окраска кожи:" to listOf(0 to "Бледная / цианоз", 1 to "Акроцианоз", 2 to "Розовая вся"),
            "P — Пульс (ЧСС):" to listOf(0 to "Отсутствует", 1 to "< 100 в мин", 2 to ">= 100 в мин"),
            "G — Рефлексы (гримаса):" to listOf(0 to "Нет реакции", 1 to "Слабая гримаса", 2 to "Крик / кашель / чихание"),
            "A — Мышечный тонус:" to listOf(0 to "Атония (вялый)", 1 to "Снижен (легкое сгиб.)", 2 to "Активные движения"),
            "R — Дыхание и крик:" to listOf(0 to "Отсутствует", 1 to "Нерегулярное, слабый крик", 2 to "Нормальное, громкий крик")
        )

        apgarCriteria.forEachIndexed { cIdx, (title, opts) ->
            content.addView(TextView(context).apply {
                text = title; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#880E4F"))
                setPadding(0, 6, 0, 4)
            })
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 8) }
            content.addView(row)
            fun drawRow() {
                row.removeAllViews()
                opts.forEach { (pts, lbl) ->
                    val sel = apgarScores[cIdx] == pts
                    row.addView(TextView(context).apply {
                        text = "$pts — $lbl"; textSize = 11.5f; gravity = Gravity.CENTER
                        typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        setTextColor(if (sel) Color.WHITE else Color.parseColor("#263238"))
                        setPadding(10, 12, 10, 12)
                        background = roundBg(if (sel) "#D81B60" else "#FCE4EC", 12f)
                        layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = 6 }
                        setOnClickListener { apgarScores[cIdx] = pts; drawRow(); updateApgar() }
                    })
                }
            }
            drawRow()
        }
        updateApgar()

        // --- АКУШЕРСКИЕ ПОСОБИЯ СО СКРИНШОТА ---
        content.addView(TextView(context).apply {
            text = "📖 Акушерские пособия и справочник:"
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#AD1457"))
            setPadding(0, 14, 0, 10)
        })
        obstetricsGuides.forEach { item ->
            content.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(26, 20, 26, 20)
                background = roundBg("#FCE4EC", 14f, "#F8BBD0")
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
                addView(TextView(context).apply {
                    text = item.title; textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#263238"))
                })
                addView(TextView(context).apply {
                    text = item.subtitle; textSize = 12.5f; setTextColor(Color.parseColor("#607D8B")); setPadding(0, 4, 0, 0)
                })
                setOnClickListener { showDetailDialog(context, item.title, item.content, "#D81B60") }
            })
        }

        bottomSheet.show()
    }

    // =========================================================
    // 5. ЯДЫ И АНТИДОТЫ (ТОКСИКОЛОГИЯ С ПОИСКОМ)
    // =========================================================
    private val antidotesList = listOf(
        "Адреналин / Норадреналин / Мезатон" to "Фентоламин 5–10 мг в/в, бета-блокаторы (Метопролол), Нитроглицерин.",
        "Акрихин" to "Тетраэтиламмоний (Уротропин).",
        "Алупент (Орципреналин)" to "Фентоламин, бета-адреноблокаторы.",
        "Алкоголь метиловый (Метанол) и Этиленгликоль" to "Этанол 30% внутрь (100 мл) или 5–10% в/в капельно; Фомепизол; Натрия гидрокарбонат 4% при ацидозе; Фолиевая к-та (при метаноле), Тиамин + Пиридоксин (при этиленгликоле).",
        "Алкоголь этиловый (Этанол)" to "Специфического антидота нет. Промывание желудка (после интубации при коме), Тиамин (В1) 100 мг в/в + Глюкоза 40% 40–60 мл в/в, инфузионная терапия, Налоксон 0.4–0.8 мг при угнетении дыхания.",
        "Амидопирин (Пирамидон)" to "Натрия гидрокарбонат 4% в/в капельно, противосудорожные (Диазепам).",
        "Амизил (Центральные М-холиноблокаторы)" to "Физостигмина салицилат, Галантамин, Аминостигмин 1–2 мг в/в или в/м.",
        "Амитриптилин (Трициклические антидепрессанты)" to "Натрия гидрокарбонат 4% 100–200 мл в/в (при расширении QRS > 0.10 с и ЖТ), Магния сульфат, Диазепам при судорогах.",
        "Аммиак (пары и растворы)" to "Ингаляции увлажненного кислорода, промывание слизистых водой / слабым р-ром лимонной к-ты, Преднизолон при отеке гортани.",
        "Анальгин (Метамизол)" to "Натрия гидрокарбонат 4%, форсированный диурез.",
        "Анилин и анилиновые красители (метгемоглобинообразователи)" to "Метиленовый синий 1% (Хромосмон) 1–2 мг/кг в/в, Аскорбиновая кислота (Витамин С) 5% до 10 мл в/в. ⚠️ Не давать молоко, касторовое масло, жиры и спирт!",
        "Антибиотики" to "Специфического антидота нет. При анафилаксии — Адреналин 0.1% 0.3–0.5 мл в/м, Преднизолон 90–120 мг / Гидрокортизон, Супрастин / Димедрол.",
        "Аспирин (Ацетилсалициловая кислота, салицилаты)" to "Натрия гидрокарбонат (бикарбонат натрия) 4% в/в для ощелачивания мочи, Витамин К (Викасол) при кровоточивости.",
        "Атропин, Белладонна (Красавка), Бесалол, Дурман, Белена" to "Галантамин, Физостигмина салицилат, Аминостигмин (или Прозерин 0.05% 1–2 мл) в/в или п/к; при возбуждении — Диазепам.",
        "Барбитураты (Фенобарбитал, Корвалол)" to "Натрия гидрокарбонат 4% (ощелачивание крови и мочи), Бемегрид, при сопутствующем приеме бензодиазепинов/опиоидов — Флумазенил, Налоксон.",
        "Бария соли" to "Натрия сульфат или Магния сульфат 1% (20–30 г внутрь для связывания в нерастворимый сульфат бария) + тиосульфат натрия 30% 10–20 мл в/в.",
        "Бензодиазепины (Диазепам, Феназепам, Клоназепам)" to "Флумазенил (Анексат) 0.2–0.5 мг в/в титрованно (макс. до 1–2 мг).",
        "Берлинская лазурь / Цианиды / Синильная кислота" to "Амилнитрит (ингаляционно), Натрия нитрит 1% 10 мл в/в медленно, Натрия тиосульфат 30% 50 мл в/в, Глюкоза 40% 20–40 мл, Гидроксикобаламин.",
        "Бета-адреноблокаторы (Бисопролол, Метопролол, Анаприлин)" to "Атропин 0.1% 1–2 мл в/в, Глюкагон 5–10 мг в/в, Кальция хлорид / глюконат 10% 10–20 мл в/в, Адреналин / Допамин при шоке.",
        "Блокаторы кальциевых каналов (Верапамил, Амлодипин, Нифедипин)" to "Кальция хлорид 10% 10–20 мл (или Кальция глюконат 10% 20–30 мл) в/в медленно, Атропин, Глюкагон, инфузия Норадреналина / Допамина.",
        "Боразол (триборинтриамин) и Борная кислота" to "Гидрокарбонат натрия 4% в/в, Рибофлавин (Витамин B2).",
        "Бром и бромиды" to "Натрия хлорид 0.9% (большие объемы физраствора в/в + Фуросемид для ускорения выведения брома).",
        "Бутадион и НПВС" to "Натрия гидрокарбонат, инфузионная терапия, гастропротекторы.",
        "Варфарин и антикоагулянты непрямого действия (кумарины, крысиный яд)" to "Витамин К1 (Фитоменадион) / Викасол 1% 2–5 мл в/в или в/м, свежезамороженная плазма / протромбиновый комплекс.",
        "Висмут и соли тяжелых металлов (Ртуть, Свинец, Мышьяк, Медь)" to "Унитиол 5% 5–10 мл в/м или в/в (1 мл на 10 кг), Натрия тиосульфат 30% 10–20 мл в/в, Тетацин-кальций (ЭДТА).",
        "Галоперидол и Нейролептики (Аминазин, Трифтазин)" to "При острой дистонии — Бипериден (Акинетон) 2.5–5 мг в/м или в/в, Тригексифенидил (Циклодол / Трифен), Диазепам 10 мг в/в, Димедрол 1% 1–2 мл.",
        "Гепарин" to "Протамина сульфат 1% в/в медленно (1 мг протамина нейтрализует 100 ЕД гепарина; максимум 50 мг за 10 мин).",
        "Грибы: Бледная поганка (фаллоидиновый синдром)" to "Бензилпенициллин в высоких дозах, Силибинин (Легалон), Тиоктовая (липоевая) кислота, ранняя энтеросорбция и инфузионная терапия.",
        "Грибы: Мухоморы (мускариновый синдром — брадикардия, саливация, бронхорея)" to "Атропин 0.1% 1–2 мл в/в до сухости слизистых и купирования бронхореи.",
        "Изониазид (Тубазид, фтизиатрические препараты, догхантерский яд)" to "Пиридоксин (Витамин B6) 5% в/в струйно в дозе, равной принятой дозе изониазида (при судорогах 5–10 мл = 250–500 мг до 5 г в/в) + Диазепам 10–20 мг в/в.",
        "Инсулин и сахароснижающие препараты (сульфонилмочевина)" to "Глюкоза 40% 40–100 мл в/в струйно (детям 10–20% 2–4 мл/кг), Глюкагон 1 мг в/м или п/к.",
        "Йод (спиртовой раствор)" to "Натрия тиосульфат 30% 10–20 мл в/в и 5% внутрь, промывание желудка раствором крахмала (до исчезновения синего окрашивания).",
        "Калия перманганат (Марганцовка)" to "Аскорбиновая кислота 5% 5–10 мл, Метиленовый синий 1%, обработка слизистых 1% раствором аскорбиновой кислоты.",
        "Калия препараты (гиперкалиемия)" to "Кальция глюконат / хлорид 10% 10–20 мл в/в медленно (кардиопротекция!), Глюкоза 40% 40 мл + Инсулин 8–10 ЕД в/в, Натрия гидрокарбонат 4%, Сальбутамол ингаляционно.",
        "Кислоты крепкие (Уксусная эссенция, серная, соляная)" to "⚠️ НЕ вызывать рвоту и НЕ применять соду внутрь (риск перфорации желудка газом CO2)! Наркотическое обезболивание, зондовое промывание холодной водой, Натрия гидрокарбонат 4% в/в от ацидоза и гемолиза, Преднизолон.",
        "Клофелин (Клонидин)" to "Атропин 0.1% 1–2 мл в/в при брадикардии, Налоксон 0.4–2 мг в/в (пробуждающий эффект), Допамин / Мезатон при гипотонии, инфузионная терапия.",
        "Магния сульфат (передозировка, угнетение дыхания и арефлексия)" to "Кальция хлорид 10% 10 мл (или Кальция глюконат 10% 10–20 мл) в/в медленно.",
        "Местный анестетик (Лидокаин, Бупивакаин — системная токсичность)" to "Жировая эмульсия 20% (Липофундин / Интралипид) 1.5 мл/кг болюсом в/в, Диазепам при судорогах, Натрия гидрокарбонат при широком QRS.",
        "Мышьяк и его соединения" to "Унитиол 5% 5–10 мл в/м или в/в, Натрия тиосульфат 30% 10–20 мл в/в.",
        "Нитриты и Нитраты (Нитроглицерин, метгемоглобинемия)" to "Метиленовый синий 1% 1–2 мг/кг в/в, Аскорбиновая кислота 5% 10 мл в/в, при гипотонии — горизонтальное положение, NaCl 0.9%, Мезатон.",
        "Опиоиды (Морфин, Героин, Фентанил, Метадон, Трамадол)" to "Налоксон 0.4–0.8 мг в/в (при отсутствии в/в доступа — в/м или интраназально), при необходимости повторно каждые 2–3 мин до 2–4 мг (до восстановления дыхания).",
        "Парацетамол (Ацетаминофен)" to "Ацетилцистеин (АЦЦ) внутрь или в/в капельно (нагрузочная доза 140–150 мг/кг).",
        "Сердечные гликозиды (Дигоксин, Строфантин, Коргликон)" to "Унитиол 5% 5–10 мл в/м или в/в, препараты Калия и Магния (Панангин 10–20 мл в/в, при отсутствии АВ-блокады!), Атропин 0.1% 1 мл при брадикардии/блокаде, Лидокаин при ЖТ.",
        "Угарный газ (Окись углерода, CO)" to "100% увлажненный кислород (ингаляция 10–15 л/мин или ИВЛ), Ацизол 6% 1 мл в/м как можно раньше, цитопротекторы.",
        "Укусы гадюки / ядовитых змей" to "Иммобилизация конечности (⚠️ жгут НЕ накладывать!), холод местно, Преднизолон 60–90 мг в/в, Супрастин / Димедрол, инфузионная терапия, в стационаре — сыворотка «Антигюрза» / против яда гадюки.",
        "ФОС (Фосфорорганические соединения: Дихлофос, Карбофос, Зарин)" to "Атропин 0.1% 2–4 мл в/в каждые 5–10 мин до признаков «атропинизации» (сухость кожи и языка, ЧСС 90–100, прекращение бронхореи) + реактиваторы холинэстеразы (Дипироксим 15% 1 мл, Карбоксим) + Диазепам при судорогах.",
        "Фтор и фториды" to "Кальция хлорид или Кальция глюконат 10% 10–20 мл в/в, промывание желудка раствором кальция (молоко, мел).",
        "Щелочи едкие (Каустическая сода, нашатырный спирт, «Крот»)" to "Обезболивание (Морфин/Промедол), спазмолитики, промывание через мягкий смазанный зонд холодной водой. ⚠️ Нельзя нейтрализовать кислотой в желудке!"
    )

    fun showAntidotesSheet(context: Context) {
        val (bottomSheet, root) = createBaseSheet(context, "🧪 Яды и антидоты (${antidotesList.size} позиций)", "#C62828")

        val searchInp = EditText(context).apply {
            hint = "Поиск (фос, морфин, клофелин, уксус, грибы)..."
            setPadding(30, 22, 30, 22)
            background = roundBg("#FFEBEE", 18f, "#FFCDD2")
            setTextColor(Color.BLACK)
        }
        root.addView(searchInp)

        val listView = ListView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isNestedScrollingEnabled = true
            divider = null
            dividerHeight = 12
            setPadding(0, 14, 0, 0)
        }
        root.addView(listView)

        fun updateList(query: String) {
            val q = query.trim().lowercase()
            val filtered = antidotesList.filter { (poison, antidote) ->
                q.isEmpty() || poison.lowercase().contains(q) || antidote.lowercase().contains(q)
            }
            val displayItems = filtered.map { (p, a) -> "☠️ $p\n💉 Антидот: $a" }
            listView.adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, displayItems) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val tv = super.getView(position, convertView, parent) as TextView
                    tv.setPadding(26, 20, 26, 20)
                    tv.setTextColor(Color.parseColor("#263238"))
                    tv.textSize = 13.8f
                    tv.setLineSpacing(5f, 1.15f)
                    tv.background = roundBg("#FFF8F8", 16f, "#FFCDD2")
                    return tv
                }
            }
            listView.setOnItemClickListener { _, _, pos, _ ->
                val (p, a) = filtered[pos]
                showDetailDialog(context, "☠️ $p", "💉 СПЕЦИФИЧЕСКИЙ АНТИДОТ И ПОМОЩЬ:\n\n$a", "#C62828")
            }
        }

        searchInp.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updateList(s.toString())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })

        updateList("")
        bottomSheet.show()
    }
}
