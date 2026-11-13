package by.brest103.ssmpplus

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

data class SavedCard(
    val id: Long,
    val fio: String,
    val timestamp: Long,
    val score: Int,
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

    fun saveCard(fio: String, birthDate: String, score: Int, jsonData: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("fio", fio.trim().lowercase())
            put("birth_date", birthDate)
            put("timestamp", System.currentTimeMillis())
            put("score", score)
            put("json_data", jsonData)
        }
        db.insert("cards", null, values)
    }

    // Алгоритм выбора последней или предпоследней карты по заполненности
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

        // Если последняя заполнена слабо (< 40%), а предпоследняя подробная (>= 60%) — берем предпоследнюю
        return if (latest.first < 40 && previous.first >= 60) {
            previous.second
        } else {
            latest.second
        }
    }
}
