package by.brest103.assistant.data

import androidx.room.*

@Entity(tableName = "patients")
data class Patient(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fio: String,
    val birthDate: String,
    val gender: String = "",
    val address: String = ""
)

@Entity(
    tableName = "cards",
    foreignKeys = [
        ForeignKey(
            entity = Patient::class,
            parentColumns = ["id"],
            childColumns = ["patientId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("patientId")]
)
data class CardRecord(
    @PrimaryKey(autoGenerate = true) val cardId: Long = 0,
    val patientId: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val completenessScore: Int, // от 0 до 100
    val rawJsonData: String     // Сохраняет JSON со всеми полями карты
)
