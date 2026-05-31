package com.aetheria.cipher.context

import androidx.room.*

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String = "default"
)

@Entity(tableName = "preferences")
data class PreferenceEntity(
    @PrimaryKey val key: String,
    val value: String,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggerType: String,
    val triggerValue: String,
    val actionJson: String,
    val enabled: Boolean = true
)

@Entity(tableName = "location_memory")
data class LocationMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val lastVisit: Long,
    val notes: String = ""
)

@Entity(tableName = "contact_memory")
data class ContactMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactId: String,
    val name: String,
    val lastCalled: Long? = null,
    val relationshipNotes: String = ""
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getSession(sessionId: String): List<ConversationEntity>

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 100): List<ConversationEntity>

    @Insert
    fun insert(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE sessionId = :sessionId")
    fun clearSession(sessionId: String)
}

@Dao
interface PreferenceDao {
    @Query("SELECT value FROM preferences WHERE key = :key")
    fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun set(preference: PreferenceEntity)
}

@Database(
    entities = [
        ConversationEntity::class,
        PreferenceEntity::class,
        RoutineEntity::class,
        LocationMemoryEntity::class,
        ContactMemoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class CipherDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun preferenceDao(): PreferenceDao
}
