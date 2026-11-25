package by.brest103.ssmpplus

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.util.Locale

data class MzrbProtocol(
    val title: String,
    val category: String,
    val date: String,
    val number: String,
    val kind: String,
    val population: String,
    val path: String
)

object MzrbCatalog {
    private const val BASE_URL = "https://minzdrav.gov.by"

    fun openPdf(context: Context, title: String, path: String) {
        if (path.isBlank()) return
        val fullUrl = BASE_URL + Uri.encode(path, "/")
        val intent = Intent(context, MzrbPdfViewerActivity::class.java).apply {
            putExtra(MzrbPdfViewerActivity.EXTRA_TITLE, title)
            putExtra(MzrbPdfViewerActivity.EXTRA_URL, fullUrl)
        }
        context.startActivity(intent)
    }

    private var cached: List<MzrbProtocol>? = null

    private fun load(context: Context): List<MzrbProtocol> {
        cached?.let { return it }
        val raw = context.assets.open("mzrb_protocols.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(raw)
        val list = (0 until arr.length()).map { i ->
            val row = arr.getJSONArray(i)
            MzrbProtocol(
                title = row.optString(0),
                category = row.optString(1),
                date = row.optString(2),
                number = row.optString(3),
                kind = row.optString(4),
                population = row.optString(5),
                path = row.optString(6)
            )
        }
        cached = list
        return list
    }

    private fun prettyDate(raw: String): String {
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})""").matchEntire(raw) ?: return raw
        return "${m.groupValues[3]}.${m.groupValues[2]}.${m.groupValues[1]}"
    }

    private fun spinner(context: Context, items: List<String>): Spinner =
        Spinner(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 8, 0)
            }
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, items).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }

    fun showSheet(context: Context) {
        val all = try {
            load(context)
        } catch (e: Exception) {
            Toast.makeText(context, "Не удалось загрузить каталог: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        val (sheet, root) = MedicalTools.createBaseSheet(
            context,
            "📚 Клинические протоколы МЗ РБ (${all.size})",
            "#1565C0",
            fullHeight = true
        )

        val search = EditText(context).apply {
            hint = "Поиск: название, номер, дата, категория..."
            setPadding(30, 20, 30, 20)
            background = MedicalTools.roundBg("#ECEFF1", 18f, "#CFD8DC")
            setTextColor(Color.BLACK)
            setHintTextColor(Color.parseColor("#546E7A"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 }
        }
        root.addView(search)

        val categories = listOf("Все категории") + all.map { it.category }.distinct().sorted()
        val kinds = listOf("Все типы", "Приказ", "Постановление")
        val populations = listOf("Все", "Взрослые", "Дети", "Взрослые и дети", "Общий")

        val filtersRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }
        }
        val categorySpinner = spinner(context, categories)
        val kindSpinner = spinner(context, kinds)
        val populationSpinner = spinner(context, populations)
        filtersRow.addView(categorySpinner)
        filtersRow.addView(kindSpinner)
        filtersRow.addView(populationSpinner)
        root.addView(filtersRow)

        val countText = TextView(context).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#546E7A"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 6 }
        }
        root.addView(countText)

        val listView = ListView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isNestedScrollingEnabled = true
            divider = null
            dividerHeight = 10
            setPadding(0, 6, 0, 0)
        }
        root.addView(listView)

        fun render() {
            val query = search.text.toString().trim().lowercase(Locale.getDefault())
            val cat = categorySpinner.selectedItem as? String ?: categories[0]
            val kind = kindSpinner.selectedItem as? String ?: kinds[0]
            val pop = populationSpinner.selectedItem as? String ?: populations[0]

            val filtered = all.filter { p ->
                val qOk = query.isEmpty() || listOf(p.title, p.category, p.number, p.date, p.kind, p.population)
                    .any { it.lowercase(Locale.getDefault()).contains(query) }
                val catOk = cat == categories[0] || p.category == cat
                val kindOk = kind == kinds[0] || p.kind == kind
                val popOk = pop == populations[0] || p.population == pop
                qOk && catOk && kindOk && popOk
            }

            countText.text = "Найдено: ${filtered.size} из ${all.size}"
            val display = filtered.map { p ->
                val meta = buildString {
                    if (p.date.isNotEmpty()) append(prettyDate(p.date))
                    if (p.number.isNotEmpty()) {
                        if (isNotEmpty()) append("  •  ")
                        append(p.number)
                    }
                    if (p.population.isNotEmpty()) {
                        if (isNotEmpty()) append("  •  ")
                        append(p.population)
                    }
                    if (p.category.isNotEmpty()) {
                        if (isNotEmpty()) append("  •  ")
                        append(p.category)
                    }
                }
                "${p.title}\n• $meta"
            }

            listView.adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, display) {
                override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        setPadding(26, 18, 26, 18)
                        setTextColor(Color.parseColor("#263238"))
                        textSize = 13.5f
                        setLineSpacing(4f, 1.12f)
                        background = MedicalTools.roundBg("#F5F7F8", 16f, "#CFD8DC")
                    }
            }
            listView.setOnItemClickListener { _, _, pos, _ ->
                val p = filtered[pos]
                openPdf(context, p.title, p.path)
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = render()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        val onSelect = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) = render()
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        categorySpinner.onItemSelectedListener = onSelect
        kindSpinner.onItemSelectedListener = onSelect
        populationSpinner.onItemSelectedListener = onSelect

        render()
        sheet.show()
    }
}
