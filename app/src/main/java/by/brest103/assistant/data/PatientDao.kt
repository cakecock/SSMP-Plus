package by.brest103.assistant.data

import androidx.room.*

@Dao
interface PatientDao {
    @Query("SELECT * FROM patients WHERE fio LIKE :fio LIMIT 1")
    suspend fun findPatientByFio(fio: String): Patient?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPatient(patient: Patient): Long

    @Insert
    suspend fun insertCard(card: CardRecord): Long

    @Query("SELECT * FROM cards WHERE patientId = :patientId ORDER BY timestamp DESC LIMIT 2")
    suspend fun getRecentCards(patientId: Long): List<CardRecord>

    // Метод выбора наилучшей карточки:
    suspend fun getBestCardForAutofill(patientId: Long): CardRecord? {
        val recent = getRecentCards(patientId)
        if (recent.isEmpty()) return null
        if (recent.size == 1) return recent[0]

        val latest = recent[0]
        val previous = recent[1]

        // Если последняя заполнена слабо (< 40%), а предпоследняя подробная (>= 60%) — берем предпоследнюю
        return if (latest.completenessScore < 40 && previous.completenessScore >= 60) {
            previous
        } else {
            latest
        }
    }
}
