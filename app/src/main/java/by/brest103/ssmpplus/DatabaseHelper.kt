package by.brest103.ssmpplus

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

data class PatientSummary(
    val fio: String,
    val birthDate: String,
    val callCount: Int,
    val lastTimestamp: Long,
    val lastReason: String
)

data class CardHistoryItem(
    val id: Long,
    val timestamp: Long,
    val score: Int,
    val jsonData: String,
    val isBuiltin: Boolean
)

data class BestCardSample(
    val id: Long,
    val title: String,
    val diagnosis: String,
    val mkb: String,
    val jsonData: String,
    val isCustom: Boolean = false
)

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, "ssmp_plus.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cards (id INTEGER PRIMARY KEY AUTOINCREMENT, fio TEXT NOT NULL, birth_date TEXT, timestamp INTEGER NOT NULL, score INTEGER NOT NULL, json_data TEXT NOT NULL, is_builtin INTEGER DEFAULT 0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) { try { db.execSQL("ALTER TABLE cards ADD COLUMN is_builtin INTEGER DEFAULT 0") } catch (_: Exception) {} }
    }

    fun saveOrUpdateCard(fio: String, birthDate: String, score: Int, jsonData: String, isBuiltin: Boolean = false) {
        val db = writableDatabase
        val cleanFio = fio.trim().lowercase()
        val now = System.currentTimeMillis()
        val sixHoursAgo = now - (6 * 60 * 60 * 1000)
        val cursor = db.rawQuery("SELECT id FROM cards WHERE fio = ? AND timestamp > ? AND is_builtin = 0 ORDER BY timestamp DESC LIMIT 1", arrayOf(cleanFio, sixHoursAgo.toString()))
        val existingId = if (cursor.moveToFirst()) cursor.getLong(0) else null
        cursor.close()

        val values = ContentValues().apply {
            put("fio", cleanFio); put("birth_date", birthDate); put("timestamp", now)
            put("score", score); put("json_data", jsonData); put("is_builtin", if (isBuiltin) 1 else 0)
        }
        if (existingId != null) db.update("cards", values, "id = ?", arrayOf(existingId.toString()))
        else db.insert("cards", null, values)
    }

    fun saveCustomTemplate(title: String, rawJsonData: String) {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return
        val cleanedJson = try {
            JSONObject(rawJsonData).apply {
                remove("fio"); remove("birthDate"); remove("age"); remove("ageUnit"); remove("address"); remove("apo")
            }.toString()
        } catch (_: Exception) { rawJsonData }

        val values = ContentValues().apply {
            put("fio", cleanTitle); put("birth_date", ""); put("timestamp", System.currentTimeMillis())
            put("score", 100); put("json_data", cleanedJson); put("is_builtin", 2)
        }
        writableDatabase.insert("cards", null, values)
    }

    fun deleteCustomTemplate(templateId: Long): Boolean =
        writableDatabase.delete("cards", "id = ? AND is_builtin = 2", arrayOf(templateId.toString())) > 0

    fun deleteCard(cardId: Long): Boolean =
        writableDatabase.delete("cards", "id = ? AND is_builtin = 0", arrayOf(cardId.toString())) > 0

    fun getBestCardForPatient(fio: String): String? {
        val cleanFio = fio.trim().lowercase()
        val surname = cleanFio.split(" ").firstOrNull()?.trim() ?: cleanFio
        val cursor = readableDatabase.rawQuery(
            "SELECT id, score, json_data FROM cards WHERE is_builtin = 0 AND (fio = ? OR fio LIKE ?) ORDER BY (fio = ?) DESC, timestamp DESC LIMIT 5",
            arrayOf(cleanFio, "%$surname%", cleanFio)
        )
        val list = mutableListOf<Pair<Int, String>>()
        while (cursor.moveToNext()) list.add(cursor.getInt(1) to cursor.getString(2))
        cursor.close()
        if (list.isEmpty()) return null
        if (list.size == 1 || list[0].first >= 60) return list[0].second
        val bestPast = list.drop(1).maxByOrNull { it.first }
        return if (bestPast != null && bestPast.first > list[0].first) bestPast.second else list[0].second
    }

    fun searchPatientSummaries(query: String): List<PatientSummary> {
        val results = mutableListOf<PatientSummary>()
        val q = "%${query.trim().lowercase()}%"
        val cursor = readableDatabase.rawQuery(
            "SELECT fio, birth_date, COUNT(*), MAX(timestamp), json_data FROM cards WHERE is_builtin = 0 AND (fio LIKE ? OR json_data LIKE ?) GROUP BY fio ORDER BY MAX(timestamp) DESC LIMIT 40",
            arrayOf(q, q)
        )
        while (cursor.moveToNext()) {
            val j = try { JSONObject(cursor.getString(4)) } catch (_: Exception) { JSONObject() }
            results.add(PatientSummary(cursor.getString(0), cursor.getString(1), cursor.getInt(2), cursor.getLong(3), j.optString("diagnosis").ifEmpty { j.optString("callReason") }))
        }
        cursor.close()
        return results
    }

    fun getPatientHistory(fio: String): List<CardHistoryItem> {
        val list = mutableListOf<CardHistoryItem>()
        val cursor = readableDatabase.query("cards", arrayOf("id", "timestamp", "score", "json_data", "is_builtin"), "fio = ? AND is_builtin = 0", arrayOf(fio.trim().lowercase()), null, null, "timestamp DESC", "15")
        while (cursor.moveToNext()) list.add(CardHistoryItem(cursor.getLong(0), cursor.getLong(1), cursor.getInt(2), cursor.getString(3), cursor.getInt(4) == 1))
        cursor.close()
        return list
    }

    fun exportDatabaseToJson(): String {
        val cursor = readableDatabase.rawQuery("SELECT fio, birth_date, timestamp, score, json_data, is_builtin FROM cards WHERE is_builtin IN (0, 2)", null)
        val arr = JSONArray()
        while (cursor.moveToNext()) {
            arr.put(JSONObject().apply {
                put("fio", cursor.getString(0)); put("birth_date", cursor.getString(1))
                put("timestamp", cursor.getLong(2)); put("score", cursor.getInt(3))
                put("json_data", cursor.getString(4)); put("is_builtin", cursor.getInt(5))
            })
        }
        cursor.close()
        return arr.toString()
    }

    fun importDatabaseFromJson(jsonStr: String): Int {
        val arr = JSONArray(jsonStr)
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val isB = obj.optInt("is_builtin", 0)
                val fio = if (isB == 2) obj.optString("fio").trim() else obj.optString("fio").trim().lowercase()
                val jd = obj.optString("json_data")
                if (fio.isNotEmpty() && jd.isNotEmpty()) {
                    db.insert("cards", null, ContentValues().apply {
                        put("fio", fio); put("birth_date", obj.optString("birth_date"))
                        put("timestamp", obj.optLong("timestamp", System.currentTimeMillis()))
                        put("score", obj.optInt("score", 80)); put("json_data", jd); put("is_builtin", if (isB == 2) 2 else 0)
                    })
                    count++
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return count
    }

    fun searchBuiltInTemplates(query: String): List<BestCardSample> {
        val q = query.trim().lowercase()
        val combined = mutableListOf<BestCardSample>()
        val cursor = readableDatabase.rawQuery("SELECT id, fio, json_data FROM cards WHERE is_builtin = 2 AND (fio LIKE ? OR json_data LIKE ?) ORDER BY timestamp DESC", arrayOf("%$q%", "%$q%"))
        while (cursor.moveToNext()) {
            val jd = cursor.getString(2)
            val j = try { JSONObject(jd) } catch (_: Exception) { JSONObject() }
            combined.add(BestCardSample(cursor.getLong(0), cursor.getString(1), j.optString("diagnosis"), j.optString("mkb").ifEmpty { j.optString("diagnosis") }, jd, isCustom = true))
        }
        cursor.close()
        combined.addAll(builtInTemplates.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.diagnosis.lowercase().contains(q) || it.mkb.lowercase().contains(q) })
        return combined
    }

    private fun makeSample(
        id: Long, title: String, mkb: String, diagnosis: String, complaints: String, anamnesis: String,
        pastDiseases: String = "ИБС, АГ", generalState: String = "Удовлетворительное", consciousness: String = "Ясное",
        position: String = "АКТИВНОЕ", behavior: String = "СПОКОЙНОЕ", glasgowScore: String = "15",
        bp: String = "130/80", bpAdapted: String = "120/80", pulse: String = "78", pulseExists: String = "ДА",
        rr: String = "16", temp: String = "36,6", sat: String = "98", gluc: String = "5,4",
        heartSounds: String = "Ритмичные, Ясные", lungs: String = "Свободное, Везикулярное",
        percussion: String = "Ясный легочный звук", abdomen: String = "Участвует в акте дыхания, Мягкий, Безболезненный",
        skin: String = "Теплая, Сухая, Нормальная", tongue: String = "Влажный, не обложен", mucous: String = "Чистые",
        pupils: String = "", lightReaction: String = "ДА", speech: String = "СОХРАНЕНА", face: String = "СИММЕТРИЧНОЕ",
        gait: String = "СВОБОДНАЯ", nystagmus: String = "НЕТ", rash: String = "НЕТ", ascites: String = "НЕТ",
        edema: String = "НЕТ", alcohol: String = "НЕТ", acetone: String = "НЕТ", peritoneal: String = "НЕТ",
        liver: String = "НЕ УВЕЛИЧЕНА", urination: String = "НЕ НАРУШЕНО", stool: String = "НЕ НАРУШЕН",
        localStatus: String = "", ecgBefore: String = "Ритм синусовый, правильный, ЧСС 78 в мин. Острой очаговой патологии нет.",
        ecgAfter: String = "Без динамики.", attack: String = "КУПИРОВАН", effect: String = "УЛУЧШЕНИЕ",
        callResult: String = "ОСТАВЛЕН НА МЕСТЕ", recommendedTherapy: String = ""
    ): BestCardSample {
        val json = JSONObject().apply {
            put("diagnosis", diagnosis); put("mkb", mkb); put("complaints", complaints); put("anamnesis", anamnesis)
            put("pastDiseases", pastDiseases); put("allergy", "НЕТ"); put("generalState", generalState)
            put("consciousness", consciousness); put("position", position); put("behavior", behavior)
            put("glasgowScore", glasgowScore); put("bp", bp); put("bpAdapted", bpAdapted); put("pulse", pulse)
            put("pulseExists", pulseExists); put("rr", rr); put("temp", temp); put("saturation", sat); put("glucose", gluc)
            put("heartSounds", heartSounds); put("lungs", lungs); put("percussion", percussion); put("abdomen", abdomen)
            put("skin", skin); put("tongue", tongue); put("mucous", mucous); put("pupils", pupils)
            put("lightReaction", lightReaction); put("speech", speech); put("face", face); put("gait", gait)
            put("nystagmus", nystagmus); put("rash", rash); put("ascites", ascites); put("edema", edema)
            put("alcohol", alcohol); put("acetone", acetone); put("peritoneal", peritoneal); put("liver", liver)
            put("peristalsis", if (generalState.contains("смерть")) "НЕТ" else "ДА")
            put("urination", urination); put("stool", stool); put("localStatus", localStatus)
            put("ecgBefore", ecgBefore); put("ecgAfter", ecgAfter); put("attack", attack)
            put("therapyEffect", effect); put("callResult", callResult)
            if (recommendedTherapy.isNotEmpty()) put("recommendedTherapy", recommendedTherapy)
        }
        return BestCardSample(id, title, diagnosis, mkb, json.toString(), isCustom = false)
    }

    private val builtInTemplates: List<BestCardSample> by lazy {
        listOf(
            // --- РЕАНИМАЦИЯ, СЛР И КОНСТАТАЦИЯ ---
            makeSample(1, "🚨 СЛР (Клиническая смерть, безуспешная)", "[I46.9] Остановка сердца",
                "Внезапная сердечная смерть. Клиническая смерть. Безуспешная СЛР. Биологическая смерть.",
                "Жалоб нет ввиду развития клинической смерти.",
                "В присутствии бригады (или со слов родственников) внезапно потерял сознание, прекратились дыхание и пульс на сонных артериях. Немедленно начата расширенная СЛР (НМС 100-120/мин, ИВЛ, мониторинг ЭКГ, в/в доступ). Через 30 минут СЛР спонтанное кровообращение не восстановлено, констатирована биологическая смерть.",
                "ИБС, ПИКС, АГ", "Клиническая смерть", "Отсутствует", "ПАССИВНОЕ", "СПОКОЙНОЕ", "3", "0/0", "130/80", "0", "НЕТ", "0", "0", "0", "0",
                "Отсутствуют", "Отсутствует", skin = "Бледная, Цианоз, Холодная", pupils = "МИДРИАЗ", lightReaction = "НЕТ", speech = "ОТСУТСТВУЕТ", gait = "НЕ ОЦЕНИВАЛАСЬ",
                stool = "ОТСУТСТВУЕТ",
                localStatus = "Сознание отсутствует (ШКГ 3 б), пульсации на магистральных артериях нет, спонтанного дыхания нет, зрачки широкие (D=S), фотореакция отсутствует. После 30 мин СЛР — стойкая асистолия.",
                ecgBefore = "Асистолия (или ФЖ, перешедшая в асистолию).", ecgAfter = "Изолиния.", attack = "НЕ КУПИРОВАН", effect = "БЕЗ ЭФФЕКТА",
                recommendedTherapy = "Адреналин 1 мг в/в каждые 3-5 мин; при ФЖ/ЖТ без пульса — разряд 200 Дж + Амиодарон 300 мг в/в."),
            makeSample(2, "⚡ СЛР (Успешная реанимация)", "[I46.0] Остановка сердца с восстановлением",
                "ИБС: Остановка кровообращения по механизму ФЖ. Клиническая смерть. Успешная СЛР.",
                "Не предъявляет ввиду угнетения сознания.",
                "Внезапная потеря сознания с остановкой дыхания и кровообращения. Немедленно начата СЛР: НМС, ИВЛ, на ЭКГ зафиксирована крупноволновая ФЖ, выполнена дефибрилляция. На 8-й минуте восстановлено спонтанное кровообращение.",
                "ИБС, АГ", "Крайне тяжелое", "Кома", "ПАССИВНОЕ", "СПОКОЙНОЕ", "6", "90/60", "130/80", "98", "ДА", "14", "36,2", "94", "7,8",
                "Ритмичные, Глухие", "Везикулярное", skin = "Бледная, Влажная", pupils = "МИДРИАЗ", lightReaction = "НЕТ", speech = "ОТСУТСТВУЕТ", gait = "НЕ ОЦЕНИВАЛАСЬ",
                ecgBefore = "Крупноволновая фибрилляция желудочков.", ecgAfter = "Ритм синусовый, ЧСС 98 в мин.", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ",
                recommendedTherapy = "Дефибрилляция 200 Дж, Адреналин 1 мг в/в, Амиодарон 300 мг в/в, О2, инфузия NaCl 0.9%."),
            makeSample(3, "🕊️ Констатация биологической смерти (до СМП)", "[R96.0] Мгновенная смерть",
                "Биологическая смерть до прибытия бригады СМП.", "Жалоб нет ввиду биологической смерти.",
                "Обнаружен родственниками без признаков жизни около 1,5 часов назад. Реанимация не проводилась ввиду достоверных признаков биологической смерти.",
                "ИБС, АГ, ХСН", "Биологическая смерть", "Отсутствует", "ПАССИВНОЕ", "СПОКОЙНОЕ", "3", "0/0", "130/80", "0", "НЕТ", "0", "0", "0", "0",
                "Отсутствуют", "Отсутствует", skin = "Бледная, Холодная, Гипостатические пятна", pupils = "МИДРИАЗ", lightReaction = "НЕТ", speech = "ОТСУТСТВУЕТ", gait = "НЕ ОЦЕНИВАЛАСЬ",
                stool = "ОТСУТСТВУЕТ",
                localStatus = "Кожные покровы бледные, холодные. Зрачки широкие, фотореакция отсутствует, с-м Белоглазова положительный. Трупные пятна по заднебоковой поверхности, трупное окоченение. Признаков насильственной смерти нет.",
                ecgBefore = "Изолиния.", ecgAfter = "Изолиния.", attack = "НЕ КУПИРОВАН", effect = "БЕЗ ЭФФЕКТА"),

            // --- КАРДИОЛОГИЯ ---
            makeSample(4, "Гипертонический криз (неосложненный)", "[I10] Эссенциальная гипертензия",
                "Артериальная гипертензия 2 ст., риск 3. Неосложненный гипертонический криз.",
                "На головную боль в затылочной области, головокружение, мелькание мушек перед глазами, слабость.",
                "Ухудшение около 2 часов назад после стресса, АД дома 180/100 мм рт. ст. Каптоприл 25 мг без достаточного эффекта.",
                "АГ, ИБС", "Средней степени тяжести", bp = "180/100", bpAdapted = "130/80", pulse = "84", heartSounds = "Ритмичные, Глухие",
                ecgBefore = "Ритм синусовый, ЧСС 84 в мин. ЭОС влево, признаки ГЛЖ.", ecgAfter = "Ритм синусовый, ЧСС 76 в мин.",
                recommendedTherapy = "Каптоприл 25-50 мг (или Моксонидин 0.2-0.4 мг), Магния сульфат 25% 5-10 мл в/в медленно."),
            makeSample(5, "Гипертонический криз (осложненный, церебральный)", "[I10] Эссенциальная гипертензия",
                "Артериальная гипертензия 3 ст., риск 4. Осложненный гипертонический криз (острая энцефалопатия).",
                "На интенсивную распирающую головную боль, головокружение, тошноту, двукратную рвоту, нечеткость зрения.",
                "Ухудшение 3 часа назад на фоне подъема АД до 210/115 мм рт. ст.",
                "АГ 3 ст., ДЭП", "Тяжелое", position = "ВЫНУЖДЕННОЕ", behavior = "ВОЗБУЖДЕННОЕ", bp = "210/115", bpAdapted = "130/80", pulse = "92", heartSounds = "Ритмичные, Глухие",
                ecgBefore = "Ритм синусовый, ЧСС 92. Перегрузка ЛЖ.", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ",
                recommendedTherapy = "Эбрантил 12.5-25 мг в/в (или Эналаприлат 1.25 мг в/в), Магния сульфат 25% 10 мл в/в, Фуросемид 20-40 мг в/в."),
            makeSample(6, "Пароксизм фибрилляции предсердий (ФП)", "[I48] Фибрилляция и трепетание предсердий",
                "ИБС: АТС кардиосклероз. АГ 2 ст., риск 4. Персистирующая форма ФП, тахисистолический вариант, пароксизм. Н1.",
                "На приступ учащенного неритмичного сердцебиения, перебои в работе сердца, слабость.",
                "Ухудшение сегодня утром, внезапно ощутил срыв ритма. Бисопролол без эффекта.",
                "ИБС, АГ, пароксизмальная ФП", "Средней степени тяжести", bp = "130/80", bpAdapted = "120/80", pulse = "128", heartSounds = "Аритмичные, Ясные",
                ecgBefore = "Фибрилляция предсердий с ЧЖС 120-135 в мин.", ecgAfter = "Ритм синусовый, ЧСС 76 в мин.",
                recommendedTherapy = "Пароксизм < 48ч: Амиодарон 5% 300 мг в/в на 5% глюкозе (или Новокаинамид 10% 10 мл + Мезатон)."),
            makeSample(7, "Постоянная форма ФП (декомпенсация)", "[I48] Фибрилляция и трепетание предсердий",
                "ИБС: кардиосклероз. Постоянная форма ФП, тахисистолия. АГ 2 ст., риск 4. ХСН ФК III.",
                "На одышку при нагрузке, учащенное сердцебиение, отечность голеней.",
                "Ухудшение за несколько дней на фоне пропуска плановой терапии.",
                "ИБС, постоянная ФП, ХСН", "Средней степени тяжести", bp = "135/85", bpAdapted = "125/80", pulse = "116", rr = "20", sat = "95",
                heartSounds = "Аритмичные, Глухие", edema = "ДА",
                ecgBefore = "ФП с ЧЖС 110-120 в мин.", ecgAfter = "ФП с ЧЖС 84-90 в мин.",
                recommendedTherapy = "Метопролол 2.5-5 мг в/в (или Дигоксин 0.025% 1-2 мл) + Панангин + Фуросемид 40 мг в/в."),
            makeSample(8, "Пароксизмальная наджелудочковая тахикардия (СВТ)", "[I47.1] Наджелудочковая тахикардия",
                "ИБС: кардиосклероз. Пароксизмальная АВ-узловая наджелудочковая тахикардия.",
                "На внезапный приступ частого ритмичного сердцебиения, пульсацию в шее, тревогу.",
                "Приступ начался внезапно 40 мин назад. Вагусные пробы без эффекта.",
                "СВТ", "Средней степени тяжести", bp = "115/75", bpAdapted = "120/80", pulse = "175", rr = "18", heartSounds = "Ритмичные, Ясные",
                ecgBefore = "Узкокомплексная тахикардия ЧСС 175 в мин.", ecgAfter = "Ритм синусовый, ЧСС 78 в мин.",
                recommendedTherapy = "Вагусные приемы ➔ АТФ 1% 1-2 мл быстро болюсом в/в (или Верапамил 0.25% 2-4 мл в/в медленно)."),
            makeSample(9, "Экстрасистолия", "[I49.3] Желудочковая экстрасистолия",
                "ИБС: кардиосклероз. Желудочковая экстрасистолия. АГ 2 ст., риск 3.",
                "На перебои и «замирания» в работе сердца, тревожность.",
                "Перебои появились сегодня после эмоционального стресса.",
                "ИБС, АГ", "Удовлетворительное", bp = "135/80", bpAdapted = "120/80", pulse = "76", heartSounds = "Аритмичные, Глухие",
                ecgBefore = "Ритм синусовый, ЧСС 76, одиночные ЖЭС.", ecgAfter = "Ритм синусовый, ЧСС 72."),
            makeSample(10, "ОКС без подъема ST (Нестабильная стенокардия)", "[I20.0] Нестабильная стенокардия",
                "ИБС: ОКС без подъема сегмента ST. Нестабильная стенокардия. АГ 2 ст., риск 4.",
                "На давящие боли за грудиной с иррадиацией в левую лопатку и плечо, нехватку воздуха.",
                "Боли в покое около 1 часа, нитроглицерин с кратковременным неполным эффектом.",
                "ИБС, АГ", "Средней степени тяжести", bp = "140/90", bpAdapted = "130/80", pulse = "88", rr = "18", sat = "96", gluc = "6,2", heartSounds = "Ритмичные, Глухие",
                ecgBefore = "Ритм синусовый, ЧСС 88. Депрессия ST до 1,5 мм в V4-V6.", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ",
                recommendedTherapy = "АСК 250-300 мг + Клопидогрел 300 мг / Тикагрелор 180 мг + Гепарин 4000 ЕД в/в + Нитроглицерин + Морфин при боли."),
            makeSample(11, "Стабильная стенокардия напряжения", "[I20.8] Другие формы стенокардии",
                "ИБС: Стабильная стенокардия напряжения ФК II. АГ 2 ст., риск 4.",
                "На момент осмотра болей нет. До СМП — сжимающая боль за грудиной при ходьбе.",
                "Около 30 мин назад после нагрузки возникла боль длительностью 5 мин, полностью купировалась нитроглицерином.",
                "ИБС, АГ", "Удовлетворительное", bp = "135/80", bpAdapted = "130/80", pulse = "74", heartSounds = "Ритмичные, Глухие",
                ecgBefore = "Ритм синусовый, ЧСС 74. Без отрицательной динамики."),
            makeSample(12, "ОКС с подъемом ST (ОИМ передней стенки)", "[I21.0] Острый трансмуральный инфаркт передней стенки",
                "ИБС: ОКС с подъемом ST. Острый крупноочаговый инфаркт миокарда передней стенки ЛЖ.",
                "На интенсивные жгучие боли за грудиной с иррадиацией в левую руку и челюсть, холодный пот, страх смерти.",
                "Заболел остро 40 мин назад. Нитраты без эффекта.",
                "ИБС, АГ", "Тяжелое", position = "ВЫНУЖДЕННОЕ", behavior = "ВОЗБУЖДЕННОЕ", bp = "110/70", bpAdapted = "130/80", pulse = "96", rr = "20", sat = "94", gluc = "7,1",
                heartSounds = "Ритмичные, Глухие", skin = "Холодная, Влажная, Бледная",
                ecgBefore = "Ритм синусовый, ЧСС 96. Элевация ST 3-4 мм в V1-V4, реципрокная депрессия в III, aVF.", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ",
                recommendedTherapy = "Морфин 1% дробно в/в + Нитроглицерин (САД > 100) + АСК 250 мг + Тикагрелор 180 мг + Гепарин 4000 ЕД в/в. Экстренная доставка на ЧКВ."),
            makeSample(13, "ОКС с подъемом ST (ОИМ нижней стенки)", "[I21.1] Острый трансмуральный инфаркт нижней стенки",
                "ИБС: ОКС с подъемом ST. Острый крупноочаговый инфаркт нижней стенки ЛЖ.",
                "На интенсивные давящие боли за грудиной и в эпигастрии, тошноту, слабость, холодный пот.",
                "Заболел остро около 1 часа назад в покое.",
                "ИБС, АГ", "Тяжелое", position = "ПАССИВНОЕ", bp = "100/65", bpAdapted = "130/80", pulse = "62", rr = "19", sat = "95", gluc = "6,8",
                heartSounds = "Ритмичные, Глухие", skin = "Холодная, Влажная, Бледная",
                ecgBefore = "ЧСС 62. Элевация ST на 3 мм в II, III, aVF.", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(14, "Кардиогенный шок", "[R57.0] Кардиогенный шок",
                "ИБС: Острый ИМ. Осложнение: Истинный кардиогенный шок.",
                "На боли за грудиной, резкую слабость, заторможенность.",
                "Боли около 2 часов, последние 30 мин — резкое падение АД.",
                "ИБС, ПИКС", "Крайне тяжелое", consciousness = "Приглушенное", position = "ПАССИВНОЕ", bp = "70/40", bpAdapted = "130/80", pulse = "114", rr = "26", sat = "88", gluc = "8,2",
                heartSounds = "Ритмичные, Глухие", skin = "Холодная, Влажная, Цианоз", urination = "ЗАДЕРЖКА МОЧЕИСПУСКАНИЯ",
                attack = "НЕ КУПИРОВАН", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ",
                recommendedTherapy = "Допамин / Норадреналин в/в титрованно до САД >= 90, О2, обезболивание, АСК + Гепарин."),
            makeSample(15, "Отек легких / ОЛЖН", "[I50.1] Левожелудочковая недостаточность",
                "ИБС: ПИКС. АГ 3 ст., риск 4. Острая левожелудочковая недостаточность, альвеолярный отек легких.",
                "На выраженное удушье, клокочущее дыхание, кашель с пенистой мокротой, положение ортопноэ.",
                "Ухудшение за последний час на фоне подъема АД до 200/110 мм рт. ст.",
                "ИБС, ПИКС, АГ, ХСН", "Тяжелое", position = "ВЫНУЖДЕННОЕ", behavior = "ВОЗБУЖДЕННОЕ", bp = "190/110", bpAdapted = "130/80", pulse = "112", rr = "28", sat = "86", gluc = "6,8",
                heartSounds = "Ритмичные, Глухие", lungs = "Затрудненное, Хрипы влажные, Хрипы справа, Хрипы слева", skin = "Холодная, Влажная, Цианоз", edema = "ДА",
                ecgBefore = "Синусовая тахикардия ЧСС 112, перегрузка ЛЖ.", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ",
                recommendedTherapy = "Положение сидя + О2 через спирт + Морфин 1% 0.5-1 мл в/в + Нитроглицерин титрованно + Фуросемид 40-80 мг в/в."),
            makeSample(16, "ТЭЛА", "[I26.9] Легочная эмболия",
                "Тромбоэмболия ветвей легочной артерии (ТЭЛА). ОДН 2 ст.",
                "На внезапную резкую одышку в покое, боль в груди при дыхании, сердцебиение.",
                "Одышка возникла внезапно 40 мин назад. В анамнезе варикозная болезнь / тромбофлебит.",
                "Варикозная болезнь", "Тяжелое", position = "ВЫНУЖДЕННОЕ", bp = "95/60", bpAdapted = "130/80", pulse = "118", rr = "26", sat = "88",
                heartSounds = "Ритмичные, Глухие", skin = "Бледная, Цианоз",
                ecgBefore = "Синусовая тахикардия 118, синдром SI-QIII-TIII.", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ",
                recommendedTherapy = "О2 + Гепарин 5000-10000 ЕД в/в болюсом + обезболивание + инфузия/вазопрессоры при гипотонии."),

            // --- НЕВРОЛОГИЯ ---
            makeSample(17, "ОНМК / Ишемический инсульт", "[I63.9] Инфаркт мозга неуточненный",
                "ОНМК по ишемическому типу в бассейне левой СМА. Правосторонний гемипарез, моторная афазия.",
                "На слабость в правых конечностях, нарушение речи, перекос лица.",
                "Заболел остро 1,5 часа назад: нарушилась речь, ослабли правая рука и нога.",
                "АГ, ЦВБ", "Тяжелое", consciousness = "Приглушенное", position = "ПАССИВНОЕ", glasgowScore = "13", bp = "170/100", bpAdapted = "130/80", pulse = "82", rr = "18", sat = "96", gluc = "6,0",
                heartSounds = "Ритмичные, Глухие", speech = "НАРУШЕНА", face = "АСИММЕТРИЧНОЕ", gait = "НАРУШЕНА",
                localStatus = "Сглажена правая носогубная складка, девиация языка вправо, правосторонний гемипарез 2 балла.",
                attack = "НЕ КУПИРОВАН", effect = "БЕЗ ЭФФЕКТА", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(18, "ОНМК / Геморрагический инсульт", "[I61.9] Внутримозговое кровоизлияние",
                "ОНМК по геморрагическому типу. Левосторонняя гемиплегия. Кома 1 ст.",
                "Контакт невозможен ввиду комы.",
                "На фоне высокого АД внезапно пожаловался на резкую головную боль, была рвота, затем быстро утратил сознание.",
                "АГ 3 ст., ЦВБ", "Тяжелое", consciousness = "Кома", position = "ПАССИВНОЕ", glasgowScore = "7", bp = "220/120", bpAdapted = "140/90", pulse = "58", rr = "22", sat = "91", gluc = "7,4",
                heartSounds = "Ритмичные, Глухие", pupils = "АНИЗОКАРИЯ", lightReaction = "НЕТ", speech = "ОТСУТСТВУЕТ", face = "АСИММЕТРИЧНОЕ", gait = "НЕ ОЦЕНИВАЛАСЬ",
                localStatus = "Кома 1 ст. (ШКГ 7 б). Анизокория D>S, с-м «паруса» слева, левосторонняя гемиплегия, ригидность затылка +2 см.",
                attack = "НЕ КУПИРОВАН", effect = "БЕЗ ЭФФЕКТА", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(19, "Транзиторная ишемическая атака (ТИА)", "[G45.9] ТИА неуточненная",
                "ЦВБ: Транзиторная ишемическая атака в ВББ. АГ 2 ст., риск 4.",
                "На системное головокружение, шаткость, преходящее онемение лица.",
                "Час назад возникло головокружение и нечеткость речи, регрессировавшие к осмотру.",
                "АГ, ДЭП", "Средней степени тяжести", bp = "160/90", bpAdapted = "120/80", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(20, "Обморок / Синкопальное состояние", "[R55] Обморок [синкопе] и коллапс",
                "Постсинкопальное состояние. Ортостатическая гипотензия.",
                "На общую слабость, легкое головокружение.",
                "В душном помещении кратковременно (до 1 мин) потерял сознание без судорог, пришел в себя сам, головой не ударялся.",
                "ВСД", "Удовлетворительное", bp = "110/70", bpAdapted = "120/80", pulse = "72"),
            makeSample(21, "Эпилепсия (после судорожного припадка)", "[G40.9] Эпилепсия неуточненная",
                "Эпилепсия. Состояние после генерализованного тонико-клонического припадка.",
                "На общую слабость, головную боль, сонливость.",
                "15 минут назад развился приступ судорог до 2 минут с прикусом языка. Страдает эпилепсией.",
                "Эпилепсия", "Средней степени тяжести", bp = "130/80", bpAdapted = "120/80", pulse = "86", tongue = "Следы прикуса"),
            makeSample(22, "Эпилептический статус", "[G41.0] Эпилептический статус",
                "Эпилепсия. Тонико-клонический эпилептический статус.", "Сбор жалоб невозможен.",
                "Серия из 3 припадков за 30 минут, между ними в сознание не приходил.",
                "Эпилепсия", "Тяжелое", consciousness = "Кома", position = "ПАССИВНОЕ", glasgowScore = "6", bp = "150/90", pulse = "118", rr = "24", sat = "90", gluc = "6,1",
                speech = "ОТСУТСТВУЕТ", gait = "НЕ ОЦЕНИВАЛАСЬ", tongue = "Следы прикуса", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ",
                recommendedTherapy = "О2 + Диазепам 0.5% 2 мл (10 мг) в/в медленно (повтор до 20 мг) + Вальпроат / Магния сульфат 25% 10 мл в/в."),
            makeSample(23, "ДЭП (головокружение, атаксия)", "[I67.2] Церебральный атеросклероз",
                "ЦВБ: ДЭП 2 ст. сложного генеза, декомпенсация. Вестибуло-атактический синдром.",
                "На головокружение, шум в голове, шаткость при ходьбе, слабость.",
                "Ухудшение 2 суток на фоне колебаний АД. Очаговой симптоматики нет.",
                "ЦВБ, ДЭП, АГ", "Удовлетворительное", bp = "150/85", bpAdapted = "130/80", pulse = "76", heartSounds = "Ритмичные, Глухие", gait = "НАРУШЕНА",
                localStatus = "В позе Ромберга пошатывание. ПНП с легкой интенцией. Парезов нет."),
            makeSample(24, "Мигрень", "[G43.9] Мигрень неуточненная",
                "Мигрень без ауры, приступный период.",
                "На пульсирующую одностороннюю головную боль в лобно-височной области, фотофобию, тошноту.",
                "Приступ длится 4 часа, анальгетики дома не помогли.",
                "Мигрень", "Средней степени тяжести", bp = "120/75", bpAdapted = "115/75", pulse = "78"),
            makeSample(25, "Дорсалгия / Люмбоишиалгия", "[M54.5] Боль внизу спины",
                "Остеохондроз ПОП. Острая люмбоишиалгия с выраженным болевым синдромом.",
                "На острую стреляющую боль в пояснице с иррадиацией в левую ногу, скованность.",
                "Боль возникла после подъема тяжести. Таблетки без эффекта.",
                "Остеохондроз", "Удовлетворительное", position = "ВЫНУЖДЕННОЕ",
                localStatus = "Болезненность паравертебральных точек L4-S1, дефанс мышц спины. С-м Ласега + слева 45°."),
            makeSample(26, "Межреберная невралгия (торакалгия)", "[G54.6] Торакалгия",
                "Остеохондроз ГОП. Вертеброгенная торакалгия (межреберная невралгия).",
                "На колющую боль в левой половине грудной клетки, усиливающуюся при вдохе и поворотах.",
                "Боль после неловкого движения и переохлаждения. На ЭКГ без острой патологии.",
                "Остеохондроз", "Удовлетворительное", bp = "125/80", bpAdapted = "120/80", pulse = "74",
                localStatus = "Болезненность при пальпации по ходу V-VI межреберья слева."),

            // --- ДЫХАНИЕ, ЛОР И ИНФЕКЦИИ ---
            makeSample(27, "Приступ бронхиальной астмы", "[J45.9] Астма неуточненная",
                "Бронхиальная астма, средней тяжести, приступный период. ДН 1-2 ст.",
                "На экспираторное удушье, свистящее дыхание, сухой кашель.",
                "Страдает БА. Ухудшение 2 часа назад, сальбутамол без достаточного эффекта.",
                "Бронхиальная астма", "Средней степени тяжести", position = "ВЫНУЖДЕННОЕ", bp = "130/80", bpAdapted = "120/80", pulse = "98", rr = "24", sat = "93",
                lungs = "Затрудненное, Хрипы сухие", percussion = "Коробочный звук справа, Коробочный звук слева",
                recommendedTherapy = "Небулайзер: Беродуал 20 кап + Пульмикорт 0.5-1 мг на NaCl; Преднизолон 60-90 мг в/в (или Дексаметазон 8-12 мг)."),
            makeSample(28, "Астматический статус", "[J46] Астматический статус",
                "Бронхиальная астма, тяжелое жизнеугрожающее обострение (астматический статус). ОДН 2-3 ст.",
                "На тяжелое удушье, невозможность говорить фразами, слабость.",
                "Удушье более 6 часов, ингаляторы без эффекта.",
                "Бронхиальная астма", "Тяжелое", position = "ВЫНУЖДЕННОЕ", behavior = "ВОЗБУЖДЕННОЕ", bp = "140/90", pulse = "122", rr = "30", sat = "87",
                lungs = "Ослабленное, Затрудненное, Хрипы сухие", percussion = "Коробочный звук справа, Коробочный звук слева", skin = "Влажная, Цианоз",
                callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(29, "Обострение ХОБЛ", "[J44.1] ХОБЛ с обострением",
                "ХОБЛ, тяжелое течение, стадия обострения. ХДН 2 ст.",
                "На усиление одышки в покое, кашель со слизисто-гнойной мокротой.",
                "Ухудшение 3 дня после переохлаждения.",
                "ХОБЛ", "Средней степени тяжести", position = "ВЫНУЖДЕННОЕ", bp = "135/85", bpAdapted = "130/80", pulse = "94", rr = "22", temp = "37,2", sat = "92",
                lungs = "Затрудненное, Жесткое, Хрипы сухие", percussion = "Коробочный звук справа, Коробочный звук слева"),
            makeSample(30, "Внебольничная пневмония", "[J18.9] Пневмония неуточненная",
                "Внебольничная правосторонняя нижнедолевая пневмония, средней тяжести. ДН 1 ст.",
                "На температуру 38,8°C, озноб, кашель с мокротой, боль справа при вдохе, одышку.",
                "Болеет 4-й день, жаропонижающие без эффекта, наросла одышка.",
                "ОРВИ", "Средней степени тяжести", bp = "120/75", bpAdapted = "120/80", pulse = "96", rr = "22", temp = "38,6", sat = "94",
                lungs = "Ослабленное, Хрипы влажные, Хрипы справа", percussion = "Притупление звука справа",
                callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(31, "ОРВИ / Гипертермия", "[J06.9] Острая инфекция ВДП",
                "ОРИ, ринофарингит. Гипертермический синдром.",
                "На температуру 39,0°C, озноб, ломоту в теле, першение в горле, насморк.",
                "Болеет 2-е сутки, ибупрофен со слабым кратковременным эффектом.",
                "ОРИ", "Средней степени тяжести", bp = "120/80", bpAdapted = "120/80", pulse = "92", rr = "18", temp = "38,9", sat = "98",
                skin = "Теплая, Сухая, Гиперемия", mucous = "Гиперемированы"),
            makeSample(32, "Ложный круп (стенозирующий ларинготрахеит)", "[J05.0] Острый обструктивный ларингит",
                "ОРИ. Острый стенозирующий ларинготрахеит, стеноз гортани 1-2 ст.",
                "На грубый лающий кашель, осиплость голоса, шумный вдох при беспокойстве.",
                "Ребенок болеет 2-й день, ночью проснулся от лающего кашля и затрудненного вдоха.",
                "По возрасту", "Средней степени тяжести", bp = "100/60", bpAdapted = "100/60", pulse = "116", rr = "28", temp = "37,8", sat = "96",
                lungs = "Жесткое", localStatus = "Голос осиплый, кашель лающий, инспираторный стридор при волнении.",
                callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ",
                recommendedTherapy = "Ингаляция Пульмикорт 1-2 мг через небулайзер + Дексаметазон 0.3-0.6 мг/кг в/м или в/в + увлажненный O2."),
            makeSample(33, "Острый тонзиллит (ангина)", "[J03.9] Острый тонзиллит",
                "Острый лакунарный тонзиллит. Гипертермический синдром.",
                "На резкую боль в горле при глотании, температуру 39,2°C, озноб, слабость.",
                "Заболел остро вчера вечером.",
                "Хр. тонзиллит", "Средней степени тяжести", bp = "120/80", pulse = "94", temp = "39,1", skin = "Теплая, Сухая, Гиперемия",
                mucous = "Гиперемированы, миндалины гипертрофированы с гнойным налетом в лакунах", localStatus = "Подчелюстные лимфоузлы увеличены, болезненны."),
            makeSample(34, "Носовое кровотечение (эпистаксис)", "[R04.0] Носовое кровотечение",
                "Переднее носовое кровотечение. АГ 2 ст., риск 3.",
                "На выделение алой крови из носовых ходов, головокружение.",
                "Началось спонтанно 30 мин назад на фоне подъема АД.",
                "АГ", "Удовлетворительное", bp = "165/95", bpAdapted = "130/80", pulse = "82",
                localStatus = "Из правого носового хода кровотечение, по задней стенке глотки не стекает. Передняя тампонада, гемостаз."),
            makeSample(35, "Острый гастроэнтерит (ОКИ / ПТИ)", "[A09] Диарея и гастроэнтерит",
                "Острый гастроэнтерит (ПТИ), средней степени тяжести.",
                "На многократный жидкий стул, тошноту, рвоту, боли вокруг пупка, температуру 37,6°C.",
                "Заболел сегодня ночью после употребления недоброкачественной пищи.",
                "Хр. гастрит", "Средней степени тяжести", bp = "110/70", bpAdapted = "120/80", pulse = "90", temp = "37,6",
                abdomen = "Участвует в акте дыхания, Мягкий, Болезненный в области", tongue = "Суховат, обложен", stool = "УЧАЩЕН",
                callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),

            // --- ХИРУРГИЯ, УРОЛОГИЯ, АКУШЕРСТВО ---
            makeSample(36, "Острый аппендицит", "[K35.8] Острый аппендицит",
                "Острый аппендицит.", "На постоянные боли в правой подвздошной области, тошноту, однократную рвоту.",
                "Заболел 8 ч назад в эпигастрии, затем боли сместились в правую подвздошную область (с-м Кохера).",
                "Отрицает", "Средней степени тяжести", bp = "120/80", pulse = "88", rr = "17", temp = "37,5", sat = "99",
                abdomen = "Напряженный, Болезненный в области", tongue = "Сухой, обложен", peritoneal = "ДА",
                localStatus = "Пальпация болезненна и напряжена в правой подвздошной области. Симптомы Ровзинга, Ситковского, Щеткина-Блюмберга +.",
                attack = "НЕ КУПИРОВАН", effect = "БЕЗ ЭФФЕКТА", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(37, "Острый панкреатит", "[K85] Острый панкреатит",
                "Острый панкреатит.", "На опоясывающие боли в верхних отделах живота, многократную рвоту без облегчения.",
                "Ухудшение через 3 часа после жирной пищи.",
                "Хр. панкреатит", "Средней степени тяжести", position = "ВЫНУЖДЕННОЕ", bp = "130/80", pulse = "92", rr = "18", temp = "37,1",
                abdomen = "Напряженный, Болезненный в области", tongue = "Суховат, обложен",
                localStatus = "Болезненность в эпигастрии и точке Мейо-Робсона.", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(38, "Острый холецистит / Печеночная колика", "[K80.0] Острый холецистит",
                "ЖКБ: Острый калькулезный холецистит. Печеночная колика.",
                "На острые боли в правом подреберье с иррадиацией в правую лопатку, горечь во рту, тошноту.",
                "После погрешности в диете 4 ч назад, дротаверин без эффекта.",
                "ЖКБ", "Средней степени тяжести", bp = "135/85", pulse = "86", rr = "17", temp = "37,3",
                abdomen = "Участвует в акте дыхания, Мягкий, Болезненный в области",
                localStatus = "Положительные симптомы Ортнера, Мерфи, Мюсси.", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(39, "Перфоративная язва желудка / ДПК", "[K25.1] Язва желудка с прободением",
                "Язвенная болезнь, осложненная перфорацией. Разлитой перитонит.",
                "На внезапную нестерпимую «кинжальную» боль в эпигастрии и по всему животу, холодный пот.",
                "1 час назад внезапно возникла «кинжальная» боль.",
                "Язвенная болезнь", "Тяжелое", position = "ВЫНУЖДЕННОЕ", bp = "100/60", pulse = "104", rr = "22", temp = "37,4",
                skin = "Холодная, Влажная, Бледная", abdomen = "Напряженный, Болезненный в области", tongue = "Сухой, обложен", peritoneal = "ДА",
                localStatus = "Доскообразный живот, с-м Щеткина-Блюмберга + во всех отделах, исчезновение печеночной тупости.",
                attack = "НЕ КУПИРОВАН", effect = "БЕЗ ЭФФЕКТА", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(40, "Острая кишечная непроходимость (ОКН)", "[K56.6] Непроходимость кишечника",
                "Острая спаечная кишечная непроходимость.",
                "На сильные схваткообразные боли, вздутие живота, многократную рвоту, задержку стула и газов.",
                "Беспокоит 10 часов, газы не отходят.",
                "Спаечная болезнь", "Тяжелое", position = "ВЫНУЖДЕННОЕ", bp = "115/75", pulse = "98", rr = "20", temp = "37,2",
                abdomen = "Напряженный, Болезненный в области", tongue = "Сухой, обложен", stool = "ОТСУТСТВУЕТ",
                localStatus = "Симптомы Валя, Склярова («шум плеска») +.", attack = "НЕ КУПИРОВАН", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(41, "Ущемленная паховая грыжа", "[K40.3] Паховая грыжа с непроходимостью",
                "Ущемленная правосторонняя паховая грыжа.",
                "На резкую боль в паху, невправимость грыжевого выпячивания, тошноту.",
                "2 часа назад после подъема тяжести вышла грыжа и перестала вправляться.",
                "Паховая грыжа", "Средней степени тяжести", position = "ВЫНУЖДЕННОЕ", bp = "130/80", pulse = "88",
                localStatus = "В правой паховой области плотноэластическое резко болезненное невправимое образование 5х4 см, с-м кашлевого толчка отрицательный.",
                attack = "НЕ КУПИРОВАН", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(42, "Желудочно-кишечное кровотечение (ЖКК)", "[K92.2] ЖКК неуточненное",
                "Состоявшееся желудочно-кишечное кровотечение. Постгеморрагическая анемия.",
                "На рвоту «кофейной гущей», черный дегтеобразный стул (мелена), резкую слабость.",
                "Со вчерашнего вечера черный стул, утром рвота темным содержимым.",
                "Язвенная болезнь", "Тяжелое", position = "ПАССИВНОЕ", bp = "95/60", bpAdapted = "120/80", pulse = "108", rr = "20", temp = "36,4", sat = "96",
                skin = "Холодная, Влажная, Бледная", localStatus = "Следы мелены.",
                attack = "НЕ КУПИРОВАН", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(43, "Почечная колика (МКБ)", "[N20.0] Камни почки",
                "Мочекаменная болезнь. Правосторонняя почечная колика.",
                "На нестерпимые схваткообразные боли в правой пояснице с иррадиацией в пах, частое болезненное мочеиспускание.",
                "Боли остро 2 часа назад. Пациент беспокоен.",
                "МКБ", "Средней степени тяжести", position = "ВЫНУЖДЕННОЕ", behavior = "ВОЗБУЖДЕННОЕ", bp = "140/90", pulse = "88", rr = "18", temp = "36,7",
                urination = "УЧАЩЕНО", localStatus = "Симптом поколачивания резко положительный справа."),
            makeSample(44, "Острая задержка мочи (ОЗМ)", "[R33] Задержка мочи",
                "ДГПЖ. Острая задержка мочи.",
                "На невозможность мочеиспускания 10 часов, распирающие боли над лоном.",
                "Страдает ДГПЖ. Мочеиспускание отсутствует с утра.",
                "ДГПЖ", "Средней степени тяжести", position = "ВЫНУЖДЕННОЕ", bp = "145/85", pulse = "84", urination = "ЗАДЕРЖКА МОЧЕИСПУСКАНИЯ",
                localStatus = "Над лоном пальпируется увеличенный, напряженный мочевой пузырь."),
            makeSample(45, "Острый пиелонефрит", "[N10] Острый пиелонефрит",
                "Острый правосторонний пиелонефрит.",
                "На тупые боли в пояснице справа, температуру 38,9°C с ознобом, слабость.",
                "Болеет 2 дня после переохлаждения.",
                "Хр. пиелонефрит", "Средней степени тяжести", bp = "120/75", pulse = "94", temp = "38,8", urination = "УЧАЩЕНО",
                localStatus = "Симптом поколачивания положительный справа.", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(46, "Угроза прерывания беременности", "[O20.0] Угрожающий аборт",
                "Беременность 10 недель. Угрожающий самопроизвольный выкидыш.",
                "На тянущие боли внизу живота и пояснице, мажущие кровянистые выделения.",
                "Боли и выделения появились около 2 часов назад.",
                "Отрицает", "Средней степени тяжести", bp = "110/70", bpAdapted = "110/70", pulse = "82",
                abdomen = "Участвует в акте дыхания, Мягкий, Болезненный в области", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(47, "Срочные роды (I период родов)", "[O80.0] Самопроизвольные роды",
                "Беременность 39 недель. Первый период срочных родов.",
                "На регулярные схватки каждые 7-8 минут по 30 секунд.",
                "Родовая деятельность началась около 2 часов назад. Воды не изливались, шевеление плода ощущает.",
                "Отрицает", "Удовлетворительное", bp = "115/75", bpAdapted = "110/70", pulse = "84",
                localStatus = "Матка в тонусе во время схватки. Сердцебиение плода ясное, ритмичное 140 в мин.", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),

            // --- ЭНДОКРИН, АЛЛЕРГИЯ, ТОКСИКО, ВСД ---
            makeSample(48, "Гипогликемия (купированная)", "[E16.2] Гипогликемия неуточненная",
                "Сахарный диабет 1 типа. Гипогликемическое состояние, купированное.",
                "На умеренную слабость после введения глюкозы. До помощи — потливость, тремор, спутанность.",
                "После инъекции инсулина пропустил прием пищи, покрылся холодным потом.",
                "Сахарный диабет", "Средней степени тяжести", bp = "120/80", pulse = "84", temp = "36,4", gluc = "2,4"),
            makeSample(49, "Гипергликемия / Кетоацидоз", "[E10.1] Сахарный диабет с кетоацидозом",
                "Сахарный диабет 1 типа, декомпенсация. Диабетический кетоацидоз.",
                "На жажду, сухость во рту, полиурию, тошноту, рвоту, сонливость.",
                "Ухудшение 2 дня на фоне пропуска инсулина.",
                "Сахарный диабет", "Тяжелое", consciousness = "Приглушенное", bp = "105/65", pulse = "102", rr = "22", gluc = "24,6",
                skin = "Сухая, Теплая", tongue = "Сухой, обложен", acetone = "ДА", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(50, "Отек Квинке / Крапивница", "[T78.3] Ангионевротический отек",
                "Острая аллергическая реакция (отек Квинке, генерализованная крапивница).",
                "На отек губ и век, зудящую сыпь по всему телу.",
                "Через 30 минут после еды/препарата появилась сыпь и отек лица. Дыхание свободное.",
                "Аллергический дерматит", "Средней степени тяжести", bp = "120/75", pulse = "84", rash = "ДА",
                localStatus = "Плотный отек губ и параорбитальной клетчатки, уртикарная сыпь. Голос звучный, стридора нет."),
            makeSample(51, "Анафилактический шок", "[T78.2] Анафилактический шок",
                "Анафилактический шок, гемодинамический вариант, средней степени тяжести.",
                "На резкую слабость, потемнение в глазах, чувство жара, нехватку воздуха, зуд.",
                "Развился остро через 10 мин после приема препарата / укуса насекомого.",
                "Аллергия в анамнезе", "Тяжелое", consciousness = "Приглушенное", position = "ПАССИВНОЕ", behavior = "ВОЗБУЖДЕННОЕ",
                bp = "70/40", bpAdapted = "120/80", pulse = "118", rr = "24", temp = "36,4", sat = "91", gluc = "6,0",
                skin = "Холодная, Влажная, Бледная", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ",
                recommendedTherapy = "Адреналин 0.1% 0.3-0.5 мл в/м в бедро + NaCl 0.9% 500-1000 мл в/в струйно + Преднизолон 90-120 мг в/в + Супрастин/Димедрол, О2."),
            makeSample(52, "Острое алкогольное отравление", "[T51.0] Токсическое действие этанола",
                "Острое бытовое отравление этанолом тяжелой степени.", "Сбор жалоб затруднен.",
                "Обнаружен окружающими после употребления алкоголя, резкий запах алкоголя.",
                "Хр. алкоголизм", "Тяжелое", consciousness = "Сопор", position = "ПАССИВНОЕ", glasgowScore = "9", bp = "105/65", pulse = "92", rr = "14", temp = "36,0", sat = "94", gluc = "4,5",
                speech = "НАРУШЕНА", gait = "НАРУШЕНА", skin = "Гиперемия, Влажная", alcohol = "ДА",
                callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(53, "Отравление опиоидами", "[T40.2] Отравление другими опиоидами",
                "Острое отравление веществом опиоидного ряда. Кома 1-2 ст. ОДН.", "Контакт невозможен.",
                "Обнаружен без сознания. По данным очевидцев, употребляет психоактивные вещества.",
                "Наркозависимость", "Тяжелое", consciousness = "Кома", position = "ПАССИВНОЕ", glasgowScore = "6", bp = "90/50", pulse = "56", rr = "6", temp = "36,0", sat = "82", gluc = "4,8",
                pupils = "МИОЗ", lightReaction = "НЕТ", speech = "ОТСУТСТВУЕТ", gait = "НЕ ОЦЕНИВАЛАСЬ", skin = "Бледная, Цианоз",
                localStatus = "Зрачки точечные, брадипноэ. Следы в/в инъекций.", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ",
                recommendedTherapy = "ИВЛ / О2 + Налоксон 0.4-0.8 мг в/в (до 2 мг) титрованно."),
            makeSample(54, "Отравление угарным газом (CO)", "[T58] Токсическое действие окиси углерода",
                "Острое ингаляционное отравление угарным газом.",
                "На сильную головную боль, головокружение, шум в ушах, тошноту, рвоту, одышку.",
                "Пострадал в задымленном помещении. Эвакуирован на свежий воздух.",
                "Отрицает", "Средней степени тяжести", bp = "130/80", pulse = "102", rr = "22", sat = "92", skin = "Гиперемия",
                callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(55, "Паническая атака / ВСД", "[F45.3] Соматоформная дисфункция ВНС",
                "Соматоформная дисфункция вегетативной нервной системы, криз (паническая атака).",
                "На нехватку воздуха, сердцебиение, внутреннюю дрожь, страх.",
                "Приступ развился 30 минут назад после эмоционального стресса.",
                "ВСД", "Удовлетворительное", behavior = "ВОЗБУЖДЕННОЕ", bp = "135/85", bpAdapted = "115/75", pulse = "98", rr = "20", sat = "99",
                ecgBefore = "Синусовая тахикардия ЧСС 98 в мин. Очаговой патологии нет."),

            // --- ТРАВМАТОЛОГИЯ И ОЖОГИ ---
            makeSample(56, "ЗЧМТ / Сотрясение головного мозга (СГМ)", "[S06.0] Сотрясение головного мозга",
                "ЗЧМТ: Сотрясение головного мозга. Ушиб мягких тканей головы.",
                "На головную боль, головокружение, тошноту, боль в месте ушиба.",
                "Час назад упал на улице, ударился затылком. Кратковременная потеря сознания, однократная рвота.",
                "Отрицает", "Средней степени тяжести", bp = "125/80", bpAdapted = "120/80", pulse = "76", nystagmus = "ГОРИЗОНТАЛЬНЫЙ",
                localStatus = "В затылочной области припухлость 3х3 см, кожа цела. В позе Ромберга пошатывание, мелкоразмашистый горизонтальный нистагм.",
                callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(57, "Тяжелая ЧМТ / Ушиб головного мозга", "[S06.2] Диффузная травма головного мозга",
                "ЗЧМТ тяжелой степени: Ушиб головного мозга со сдавлением. Субдуральная гематома. Кома 1 ст.",
                "Контакт невозможен.", "Падение с высоты (или ДТП) 40 мин назад. С момента травмы без сознания.",
                "Неизвестен", "Тяжелое", consciousness = "Кома", position = "ПАССИВНОЕ", glasgowScore = "7", bp = "160/95", pulse = "56", rr = "20", sat = "92",
                pupils = "АНИЗОКАРИЯ", lightReaction = "НЕТ", speech = "ОТСУТСТВУЕТ", gait = "НЕ ОЦЕНИВАЛАСЬ",
                localStatus = "В теменно-височной области гематома 6х5 см. Анизокория D>S, брадикардия, ригидность затылочных мышц.",
                attack = "НЕ КУПИРОВАН", callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(58, "Перелом шейки бедра", "[S72.0] Перелом шейки бедра",
                "Закрытый перелом шейки левой бедренной кости со смещением.",
                "На боль в области левого тазобедренного сустава, невозможность опоры и движения.",
                "Час назад упала дома с высоты собственного роста на левый бок.",
                "ИБС, АГ, остеопороз", "Средней степени тяжести", position = "ВЫНУЖДЕННОЕ", bp = "140/85", bpAdapted = "130/80", pulse = "84", gait = "НАРУШЕНА",
                localStatus = "Левая нога ротирована кнаружи, укорочена на 2 см. Положительный симптом «прилипшей пятки». Пульсация на стопе сохранена.",
                callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(59, "Перелом лучевой кости в типичном месте", "[S52.5] Перелом лучевой кости",
                "Закрытый перелом нижней трети левой лучевой кости в типичном месте со смещением.",
                "На боль, отек и деформацию левого лучезапястного сустава.",
                "40 минут назад упал с упором на вытянутую левую руку.",
                "Отрицает", "Удовлетворительное", bp = "130/80", pulse = "80",
                localStatus = "В нижней трети предплечья штыкообразная деформация, отек, резкая болезненность. Кровообращение и чувствительность в пальцах сохранены.",
                callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(60, "Перелом лодыжек / голени", "[S82.8] Перелом лодыжек",
                "Закрытый двухлодыжечный перелом правой голени.",
                "На резкую боль в правом голеностопном суставе, отек, невозможность наступить на ногу.",
                "30 минут назад подвернул ногу на лестнице.",
                "Отрицает", "Средней степени тяжести", bp = "130/80", pulse = "82", gait = "НАРУШЕНА",
                localStatus = "Голеностопный сустав деформирован, отек и болезненность в области обеих лодыжек. Пульсация на стопе сохранена.",
                callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(61, "Перелом ребер / Ушиб грудной клетки", "[S22.3] Перелом ребра",
                "Закрытая травма грудной клетки. Перелом VI-VII ребер слева.",
                "На острую боль в левой половине груди, усиливающуюся при вдохе и кашле.",
                "Упал 2 часа назад, ударился левым боком о край мебели.",
                "АГ", "Средней степени тяжести", bp = "135/80", pulse = "84", rr = "19", sat = "97", lungs = "Ослабленное",
                localStatus = "Резкая локальная болезненность и крепитация в проекции VI-VII ребер слева по среднеподмышечной линии. Подкожной эмфиземы нет.",
                callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(62, "Ушибленная / резаная рана мягких тканей", "[S01.0] Открытая рана головы",
                "Ушибленная рана теменной области головы.", "На боль и умеренное кровотечение из раны головы.",
                "30 минут назад ударился головой в быту. Сознание не терял, рвоты не было.",
                "Отрицает", "Удовлетворительное", bp = "125/80", pulse = "78",
                localStatus = "В теменной области линейная рана 3,0х0,5 см, умеренное капиллярное кровотечение. Асептическая повязка, гемостаз.",
                callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ"),
            makeSample(63, "Термический ожог I-II степени", "[T30.2] Термический ожог второй степени",
                "Термический ожог (кипятком) I-II степени правого предплечья и кисти, площадь 4%.",
                "На жгучую боль в области ожога правой руки, покраснение и пузыри.",
                "20 минут назад случайно пролил на руку кипяток.",
                "Отрицает", "Средней степени тяжести", bp = "130/80", pulse = "88",
                localStatus = "Яркая гиперемия, отек, эпидермальные пузыри с серозным содержимым на коже правого предплечья и кисти (около 4% ПТ).",
                callResult = "ТРАНСПОРТИРОВАН В ОРГАНИЗАЦИЮ ЗДРАВООХРАНЕНИЯ")
        )
    }
}
