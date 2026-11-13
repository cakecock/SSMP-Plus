package by.brest103.ssmpplus

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class FoundCard(
    val fio: String,
    val birthDate: String,
    val timestamp: Long,
    val jsonData: String
)

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, "ssmp_plus.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE cards (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                fio TEXT NOT NULL,
                birth_date TEXT,
                timestamp INTEGER NOT NULL,
                score INTEGER NOT NULL,
                json_data TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS cards")
        onCreate(db)
    }

    // Сохранение или обновление (без дубликатов в течение смены)
    fun saveOrUpdateCard(fio: String, birthDate: String, score: Int, jsonData: String) {
        val db = writableDatabase
        val cleanFio = fio.trim().lowercase()
        val now = System.currentTimeMillis()
        val twelveHoursAgo = now - (12 * 60 * 60 * 1000)

        // Ищем: сохраняли ли мы этого пациента за последние 12 часов?
        val cursor = db.rawQuery(
            "SELECT id FROM cards WHERE fio = ? AND timestamp > ? ORDER BY timestamp DESC LIMIT 1",
            arrayOf(cleanFio, twelveHoursAgo.toString())
        )

        val existingId = if (cursor.moveToFirst()) cursor.getLong(0) else null
        cursor.close()

        val values = ContentValues().apply {
            put("fio", cleanFio)
            put("birth_date", birthDate)
            put("timestamp", now)
            put("score", score)
            put("json_data", jsonData)
        }

        if (existingId != null) {
            // Обновляем существующую карту
            db.update("cards", values, "id = ?", arrayOf(existingId.toString()))
        } else {
            // Создаем новую запись (новый вызов)
            db.insert("cards", null, values)
        }
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

    // Поиск по архиву БЕЗ дубликатов (выводит самую свежую карту на каждого человека)
    fun searchPatients(query: String): List<FoundCard> {
        val results = mutableListOf<FoundCard>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT fio, birth_date, MAX(timestamp) as max_time, json_data 
            FROM cards 
            WHERE fio LIKE ? 
            GROUP BY fio 
            ORDER BY max_time DESC 
            LIMIT 20
            """.trimIndent(),
            arrayOf("%${query.trim().lowercase()}%")
        )

        while (cursor.moveToNext()) {
            results.add(
                FoundCard(
                    fio = cursor.getString(cursor.getColumnIndexOrThrow("fio")),
                    birthDate = cursor.getString(cursor.getColumnIndexOrThrow("birth_date")),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("max_time")),
                    jsonData = cursor.getString(cursor.getColumnIndexOrThrow("json_data"))
                )
            )
        }
        cursor.close()
        return results
    }
}
