package by.brest103.ssmpplus

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, "ssmp_plus.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE cards (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                fio TEXT NOT NULL,
                birth_date TEXT,
                timestamp INTEGER NOT NULL,
                score INTEGER NOT NULL,
                json_data TEXT NOT NULL,
                is_builtin INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE cards ADD COLUMN is_builtin INTEGER DEFAULT 0")
            } catch (e: Exception) {}
        }
    }

    // Сохранение вызова с маркером ручного/системного ввода
    fun saveOrUpdateCard(fio: String, birthDate: String, score: Int, jsonData: String, isBuiltin: Boolean = false) {
        val db = writableDatabase
        val cleanFio = fio.trim().lowercase()
        val now = System.currentTimeMillis()
        val sixHoursAgo = now - (6 * 60 * 60 * 1000)

        val cursor = db.rawQuery(
            "SELECT id FROM cards WHERE fio = ? AND timestamp > ? ORDER BY timestamp DESC LIMIT 1",
            arrayOf(cleanFio, sixHoursAgo.toString())
        )

        val existingId = if (cursor.moveToFirst()) cursor.getLong(0) else null
        cursor.close()

        val values = ContentValues().apply {
            put("fio", cleanFio)
            put("birth_date", birthDate)
            put("timestamp", now)
            put("score", score)
            put("json_data", jsonData)
            put("is_builtin", if (isBuiltin) 1 else 0)
        }

        if (existingId != null) {
            db.update("cards", values, "id = ?", arrayOf(existingId.toString()))
        } else {
            db.insert("cards", null, values)
        }
    }

    // Удаление карточки (только если она не системная!)
    fun deleteCard(cardId: Long): Boolean {
        val db = writableDatabase
        val deletedRows = db.delete("cards", "id = ? AND is_builtin = 0", arrayOf(cardId.toString()))
        return deletedRows > 0
    }

    fun getBestCardForPatient(fio: String): String? {
        val db = readableDatabase
        val cursor = db.query(
            "cards",
            arrayOf("id", "score", "json_data"),
            "fio = ?",
            arrayOf(fio.trim().lowercase()),
            null,
            null,
            "timestamp DESC",
            "2"
        )

        val list = mutableListOf<Pair<Int, String>>()
        while (cursor.moveToNext()) {
            val score = cursor.getInt(cursor.getColumnIndexOrThrow("score"))
            val json = cursor.getString(cursor.getColumnIndexOrThrow("json_data"))
            list.add(Pair(score, json))
        }
        cursor.close()

        if (list.isEmpty()) return null
        if (list.size == 1) return list[0].second

        val latest = list[0]
        val previous = list[1]

        return if (latest.first < 40 && previous.first >= 60) {
            previous.second
        } else {
            latest.second
        }
    }

    fun searchPatientSummaries(query: String): List<PatientSummary> {
        val results = mutableListOf<PatientSummary>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT fio, birth_date, COUNT(*) as call_count, MAX(timestamp) as last_time, json_data 
            FROM cards 
            WHERE fio LIKE ? 
            GROUP BY fio 
            ORDER BY last_time DESC 
            LIMIT 25
            """.trimIndent(),
            arrayOf("%${query.trim().lowercase()}%")
        )

        while (cursor.moveToNext()) {
            val rawJson = cursor.getString(cursor.getColumnIndexOrThrow("json_data"))
            val json = try { JSONObject(rawJson) } catch (e: Exception) { JSONObject() }
            val reason = json.optString("callReason").ifEmpty { json.optString("diagnosis") }

            results.add(
                PatientSummary(
                    fio = cursor.getString(cursor.getColumnIndexOrThrow("fio")),
                    birthDate = cursor.getString(cursor.getColumnIndexOrThrow("birth_date")),
                    callCount = cursor.getInt(cursor.getColumnIndexOrThrow("call_count")),
                    lastTimestamp = cursor.getLong(cursor.getColumnIndexOrThrow("last_time")),
                    lastReason = reason
                )
            )
        }
        cursor.close()
        return results
    }

    fun getPatientHistory(fio: String): List<CardHistoryItem> {
        val list = mutableListOf<CardHistoryItem>()
        val db = readableDatabase
        val cursor = db.query(
            "cards",
            arrayOf("id", "timestamp", "score", "json_data", "is_builtin"),
            "fio = ?",
            arrayOf(fio.trim().lowercase()),
            null,
            null,
            "timestamp DESC",
            "15"
        )

        while (cursor.moveToNext()) {
            list.add(
                CardHistoryItem(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                    score = cursor.getInt(cursor.getColumnIndexOrThrow("score")),
                    jsonData = cursor.getString(cursor.getColumnIndexOrThrow("json_data")),
                    isBuiltin = cursor.getInt(cursor.getColumnIndexOrThrow("is_builtin")) == 1
                )
            )
        }
        cursor.close()
        return list
    }
}
