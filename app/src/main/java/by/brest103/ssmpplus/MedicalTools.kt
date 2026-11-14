package by.brest103.ssmpplus

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
import android.widget.*
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.*
import kotlin.math.min

object MedicalTools {

    private data class AgePreset(
        val label: String,
        val defaultWeight: Double,
        val hrNorm: String,
        val rrNorm: String,
        val bpNorm: String
    )

    private val agePresets = listOf(
        AgePreset("Новор.", 3.5, "120–160", "40–60", "65–85 / 45–55"),
        AgePreset("6 мес", 7.5, "110–140", "30–40", "80–95 / 50–65"),
        AgePreset("1 год", 10.0, "105–130", "28–35", "90–105 / 55–70"),
        AgePreset("3 года", 15.0, "95–120", "24–28", "95–110 / 60–70"),
        AgePreset("5 лет", 19.0, "90–110", "22–25", "98–112 / 60–72"),
        AgePreset("7 лет", 23.0, "85–105", "20–24", "102–116 / 62–75"),
        AgePreset("10 лет", 32.0, "75–95", "18–22", "108–122 / 65–78"),
        AgePreset("14 лет", 50.0, "65–85", "16–20", "110–128 / 70–80")
    )

    // =========================================================
    // 1. ПЕДИАТРИЯ: ВОЗРАСТНЫЕ НОРМЫ И КАЛЬКУЛЯТОР ДОЗ
    // =========================================================
    fun showPediatricsSheet(context: Context) {
        val bottomSheet = BottomSheetDialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(35, 20, 35, 25)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(90, 8).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 14
            }
            background = GradientDrawable().apply { setColor(Color.parseColor("#B0BEC5")); cornerRadius = 4f }
        })

        root.addView(TextView(context).apply {
            text = "👶 Педиатрия: Нормы и Дозы"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00695C"))
            setPadding(0, 0, 0, 12)
        })

        var selectedPresetIndex = 2 // 1 год по умолчанию
        val chipsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, 12)
        }
        val chipsLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        chipsScroll.addView(chipsLayout)
        root.addView(chipsScroll)

        // Ввод веса
        val weightRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }
        weightRow.addView(TextView(context).apply {
            text = "⚖️ Вес (кг): "
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#263238"))
        })
        val weightInput = EditText(context).apply {
            setText("10.0")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(30, 18, 30, 18)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E0F2F1"))
                cornerRadius = 16f
            }
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
            setPadding(10, 10, 10, 30)
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
                sb.append("💉 ЭКСТРЕННЫЕ ДОЗИРОВКИ НА ${fmt(w)} КГ:\n")
                sb.append("• Адреналин 0,1% (СЛР в/в): ${fmt(min(1.0, w * 0.01))} мл (0.01 мл/кг в разведении)\n")
                sb.append("• Адреналин 0,1% (Анафилаксия в/м): ${fmt(min(0.5, w * 0.01))} мл\n")
                sb.append("• Преднизолон (30 мг/мл): ${fmt(min(90.0, w * 2.0))} мг (${fmt(min(3.0, w * 2.0 / 30.0))} мл)\n")
                sb.append("• Дексаметазон (4 мг/мл): ${fmt(min(16.0, w * 0.4))} мг (${fmt(min(4.0, w * 0.1))} мл)\n")
                sb.append("• Анальгин 50% (метамизол): ${fmt(min(2.0, w * 0.02))} мл (10 мг/кг)\n")
                sb.append("• Димедрол 1% / Супрастин 2%: ${fmt(min(1.0, w * 0.02))} – ${fmt(min(1.0, w * 0.03))} мл\n")
                sb.append("• Диазепам 0,5% (судороги в/в): ${fmt(min(2.0, w * 0.05))} мл (0.25 мг/кг)\n")
                sb.append("• Амиодарон 5% (СЛР/ФЖ в/в): ${fmt(min(6.0, w * 0.1))} мл (5 мг/кг)\n")
                sb.append("• Глюкоза 10% (гипогликемия в/в): ${fmt(w * 2.0)} – ${fmt(w * 4.0)} мл (2-4 мл/кг)\n")
                sb.append("• NaCl 0,9% (болюс при шоке): ${fmt(w * 10.0)} – ${fmt(w * 20.0)} мл\n")
                sb.append("• Парацетамол (внутрь/свечи): ${fmt(min(500.0, w * 15.0))} мг (15 мг/кг)\n")
                sb.append("• Ибупрофен (внутрь): ${fmt(min(400.0, w * 10.0))} мг (10 мг/кг)\n")
                sb.append("• Беродуал (небулайзер): ${min(20, (w * 1.0).toInt()).coerceAtLeast(5)} капель на 2 мл NaCl\n")
                sb.append("• Пульмикорт (ложный круп): 0.5 – 1.0 мг через небулайзер\n")
            }
            resultText.text = sb.toString()
        }

        fun updateChips() {
            chipsLayout.removeAllViews()
            agePresets.forEachIndexed { idx, preset ->
                val isSel = idx == selectedPresetIndex
                val chip = TextView(context).apply {
                    text = "${preset.label} (${preset.defaultWeight} кг)"
                    textSize = 12f
                    typeface = if (isSel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(if (isSel) Color.WHITE else Color.parseColor("#004D40"))
                    setPadding(20, 10, 20, 10)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(if (isSel) "#00796B" else "#E0F2F1"))
                        cornerRadius = 16f
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = 10 }
                    setOnClickListener {
                        selectedPresetIndex = idx
                        weightInput.setText(preset.defaultWeight.toString())
                        updateChips()
                        recalculate()
                    }
                }
                chipsLayout.addView(chip)
            }
        }

        weightInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { recalculate() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })

        updateChips()
        recalculate()

        bottomSheet.setContentView(root)
        bottomSheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheet.behavior.skipCollapsed = true
        bottomSheet.behavior.peekHeight = 1400
        bottomSheet.show()
    }

    // =========================================================
    // 2. ШКАЛА КОМЫ ГЛАЗГО (E+V+M) И ШОК-ИНДЕКС АЛЬГОВЕРА
    // =========================================================
    fun showCalculatorsSheet(context: Context) {
        val bottomSheet = BottomSheetDialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(35, 20, 35, 25)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(90, 8).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 14
            }
            background = GradientDrawable().apply { setColor(Color.parseColor("#B0BEC5")); cornerRadius = 4f }
        })

        root.addView(TextView(context).apply {
            text = "🧠 Шкала Глазго и Шок-индекс"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00695C"))
            setPadding(0, 0, 0, 10)
        })

        val scrollView = NestedScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
            isNestedScrollingEnabled = true
        }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(content)
        root.addView(scrollView)

        var scoreE = 4
        var scoreV = 5
        var scoreM = 6

        val gcsResultBanner = TextView(context).apply {
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(24, 22, 24, 22)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00796B"))
                cornerRadius = 16f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }
        content.addView(gcsResultBanner)

        fun updateGcsBanner() {
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
            gcsResultBanner.text = "E$scoreE V$scoreV M$scoreM = $total б ($state)\n📋 Нажмите, чтобы скопировать"
            gcsResultBanner.setOnClickListener {
                val clipText = "E$scoreE V$scoreV M$scoreM = $total б ($state)"
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("GCS", clipText))
                Toast.makeText(context, "📋 Скопировано: $clipText", Toast.LENGTH_SHORT).show()
            }
        }

        fun buildGcsGroup(title: String, options: List<Pair<Int, String>>, getCurrent: () -> Int, onSelect: (Int) -> Unit) {
            content.addView(TextView(context).apply {
                text = title
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#004D40"))
                setPadding(0, 10, 0, 6)
            })
            val row = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
            val lay = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(lay)
            content.addView(row)

            fun renderOpts() {
                lay.removeAllViews()
                options.forEach { (pts, label) ->
                    val sel = pts == getCurrent()
                    lay.addView(TextView(context).apply {
                        text = "$pts — $label"
                        textSize = 12f
                        typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        setTextColor(if (sel) Color.WHITE else Color.parseColor("#263238"))
                        setPadding(20, 12, 20, 12)
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor(if (sel) "#0288D1" else "#F0F4F4"))
                            cornerRadius = 14f
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { rightMargin = 8 }
                        setOnClickListener {
                            onSelect(pts)
                            renderOpts()
                            updateGcsBanner()
                        }
                    })
                }
            }
            renderOpts()
        }

        buildGcsGroup("👁️ Открывание глаз (E):", listOf(
            4 to "Спонтанное", 3 to "На речь", 2 to "На боль", 1 to "Отсутствует"
        ), { scoreE }) { scoreE = it }

        buildGcsGroup("🗣️ Речевая реакция (V):", listOf(
            5 to "Связная", 4 to "Спутанная", 3 to "Отд. слова", 2 to "Звуки", 1 to "Нет"
        ), { scoreV }) { scoreV = it }

        buildGcsGroup("✋ Двигательная реакция (M):", listOf(
            6 to "По команде", 5 to "Локализует", 4 to "Отдергивает", 3 to "Сгибание", 2 to "Разгибание", 1 to "Нет"
        ), { scoreM }) { scoreM = it }

        updateGcsBanner()

        // --- ШОК-ИНДЕКС АЛЬГОВЕРА ---
        content.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                topMargin = 28
                bottomMargin = 18
            }
            setBackgroundColor(Color.parseColor("#E0EAEA"))
        })

        content.addView(TextView(context).apply {
            text = "❤️ Шок-индекс Альговера (ЧСС / САД)"
            textSize = 16.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#C62828"))
            setPadding(0, 0, 0, 12)
        })

        val shockInputs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 12)
        }
        val hrInput = EditText(context).apply {
            hint = "Пульс (ЧСС)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(24, 18, 24, 18)
            background = GradientDrawable().apply { setColor(Color.parseColor("#FFEBEE")); cornerRadius = 14f }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 12 }
        }
        val sbpInput = EditText(context).apply {
            hint = "САД (верхнее)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(24, 18, 24, 18)
            background = GradientDrawable().apply { setColor(Color.parseColor("#FFEBEE")); cornerRadius = 14f }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        shockInputs.addView(hrInput)
        shockInputs.addView(sbpInput)
        content.addView(shockInputs)

        val shockOut = TextView(context).apply {
            text = "Введите пульс и верхнее давление..."
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#37474F"))
            setPadding(10, 8, 10, 30)
        }
        content.addView(shockOut)

        fun calcShock() {
            val hr = hrInput.text.toString().toDoubleOrNull()
            val sbp = sbpInput.text.toString().toDoubleOrNull()
            if (hr != null && sbp != null && sbp > 0) {
                val idx = hr / sbp
                val status = when {
                    idx < 0.7 -> "Норма (0.5–0.7)"
                    idx < 1.0 -> "Легкая гиповолемия / кровопотеря до 15%"
                    idx < 1.4 -> "Шок I ст. (кровопотеря 15–25%)"
                    idx < 2.0 -> "Шок II ст. (кровопотеря 25–35%)"
                    else -> "Тяжелый шок III ст. (кровопотеря >35%)"
                }
                shockOut.text = String.format(Locale.US, "Шок-индекс: %.2f — %s", idx, status)
            } else {
                shockOut.text = "Введите пульс и верхнее давление..."
            }
        }

        val shockWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { calcShock() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        }
        hrInput.addTextChangedListener(shockWatcher)
        sbpInput.addTextChangedListener(shockWatcher)

        bottomSheet.setContentView(root)
        bottomSheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheet.behavior.skipCollapsed = true
        bottomSheet.behavior.peekHeight = 1400
        bottomSheet.show()
    }
}
