package com.aetheria.vance.context

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── Entities ─────────────────────────────────────────────────────

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String = "default",
    val actionType: String? = null
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
    val label: String,
    val enabled: Boolean = true,
    val lastRun: Long? = null
)

@Entity(tableName = "location_memory")
data class LocationMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val label: String,
    val lastVisit: Long = System.currentTimeMillis(),
    val notes: String? = null
)

@Entity(tableName = "contact_memory")
data class ContactMemoryEntity(
    @PrimaryKey val contactId: String,
    val name: String,
    val lastContacted: Long = System.currentTimeMillis(),
    val contactCount: Int = 0,
    val relationshipNotes: String? = null
)

// ── DAOs ─────────────────────────────────────────────────────────

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getBySession(sessionId: String): List<ConversationEntity>

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 100): List<ConversationEntity>

    @Insert
    fun insert(conversation: ConversationEntity): Long

    @Insert
    fun insertAll(vararg conversations: ConversationEntity)

    @Query("DELETE FROM conversations WHERE timestamp < :timestamp")
    fun deleteOlderThan(timestamp: Long): Int

    @Query("DELETE FROM conversations WHERE sessionId = :sessionId")
    fun clearSession(sessionId: String)

    @Query("SELECT COUNT(*) FROM conversations")
    fun getCount(): Int
}

@Dao
interface PreferenceDao {
    @Query("SELECT * FROM preferences WHERE `key` = :key")
    fun getByKey(key: String): PreferenceEntity?

    @Query("SELECT value FROM preferences WHERE `key` = :key")
    fun getValue(key: String): String?

    @Query("SELECT * FROM preferences")
    fun getAll(): List<PreferenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(preference: PreferenceEntity)

    @Query("DELETE FROM preferences WHERE `key` = :key")
    fun deleteByKey(key: String)
}

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines WHERE enabled = 1")
    fun getEnabled(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE triggerType = :type AND enabled = 1")
    fun getByTriggerType(type: String): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE triggerType = :type AND triggerValue = :value AND enabled = 1")
    fun getByTrigger(type: String, value: String): List<RoutineEntity>

    @Insert
    fun insert(routine: RoutineEntity): Long

    @Insert
    fun insertAll(vararg routines: RoutineEntity)

    @Update
    fun update(routine: RoutineEntity)

    @Query("UPDATE routines SET lastRun = :timestamp WHERE id = :id")
    fun updateLastRun(id: Long, timestamp: Long)

    @Query("DELETE FROM routines WHERE id = :id")
    fun deleteById(id: Long)
}

@Dao
interface LocationMemoryDao {
    @Query("SELECT * FROM location_memory ORDER BY lastVisit DESC LIMIT :limit")
    fun getRecent(limit: Int = 20): List<LocationMemoryEntity>

    @Query("SELECT * FROM location_memory WHERE label LIKE :query ORDER BY lastVisit DESC")
    fun searchByLabel(query: String): List<LocationMemoryEntity>

    @Insert
    fun insert(location: LocationMemoryEntity): Long

    @Query("SELECT * FROM location_memory WHERE lat BETWEEN :minLat AND :maxLat AND lng BETWEEN :minLng AND :maxLng ORDER BY lastVisit DESC")
    fun getNearby(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): List<LocationMemoryEntity>

    @Query("DELETE FROM location_memory WHERE id = :id")
    fun deleteById(id: Long)
}

@Dao
interface ContactMemoryDao {
    @Query("SELECT * FROM contact_memory WHERE contactId = :contactId")
    fun getByContactId(contactId: String): ContactMemoryEntity?

    @Query("SELECT * FROM contact_memory ORDER BY lastContacted DESC LIMIT :limit")
    fun getRecent(limit: Int = 20): List<ContactMemoryEntity>

    @Query("SELECT * FROM contact_memory WHERE lastContacted < :timestamp ORDER BY lastContacted ASC")
    fun getNotContactedSince(timestamp: Long): List<ContactMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(contact: ContactMemoryEntity)

    @Query("UPDATE contact_memory SET contactCount = contactCount + 1, lastContacted = :timestamp WHERE contactId = :contactId")
    fun incrementContactCount(contactId: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM contact_memory WHERE contactId = :contactId")
    fun deleteByContactId(contactId: String)
}

// ── Database ─────────────────────────────────────────────────────

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
    abstract fun routineDao(): RoutineDao
    abstract fun locationMemoryDao(): LocationMemoryDao
    abstract fun contactMemoryDao(): ContactMemoryDao
}

// ── MemoryStore — high-level API ─────────────────────────────────

class MemoryStore(context: Context) {

    val db: CipherDatabase = Room.databaseBuilder(
        context.applicationContext,
        CipherDatabase::class.java,
        "cipher_memory.db"
    )
        .fallbackToDestructiveMigration()
        .build()

    val conversations get() = db.conversationDao()
    val preferences get() = db.preferenceDao()
    val routines get() = db.routineDao()
    val locations get() = db.locationMemoryDao()
    val contacts get() = db.contactMemoryDao()

    // ── Conversation helpers ───────────────────────────────────────

    fun saveExchange(sessionId: String, userMessage: String, cipherResponse: String, actionType: String? = null) {
        val now = System.currentTimeMillis()
        conversations.insert(ConversationEntity(role = "user", content = userMessage, timestamp = now, sessionId = sessionId))
        conversations.insert(ConversationEntity(role = "cipher", content = cipherResponse, timestamp = now + 1, sessionId = sessionId, actionType = actionType))
    }

    fun getSessionHistory(sessionId: String) = conversations.getBySession(sessionId)

    fun getRecentExchanges(limit: Int = 20) = conversations.getRecent(limit)

    suspend fun getRecentConversations(limit: Int): List<ConversationEntity> =
        withContext(Dispatchers.IO) {
            conversations.getRecent(limit)
        }

    fun clearHistory(sessionId: String) = conversations.clearSession(sessionId)

    fun pruneOldConversations(olderThan: Long) = conversations.deleteOlderThan(olderThan)

    // ── Preference helpers ─────────────────────────────────────────

    fun getPreference(key: String): String? = preferences.getValue(key)

    fun setPreference(key: String, value: String) {
        preferences.upsert(PreferenceEntity(key = key, value = value))
    }

    fun getAllPreferences() = preferences.getAll()

    // ── Routine helpers ────────────────────────────────────────────

    fun getEnabledRoutines() = routines.getEnabled()

    fun getRoutinesByTrigger(type: String) = routines.getByTriggerType(type)

    fun addRoutine(triggerType: String, triggerValue: String, actionJson: String, label: String): Long {
        return routines.insert(RoutineEntity(
            triggerType = triggerType,
            triggerValue = triggerValue,
            actionJson = actionJson,
            label = label
        ))
    }

    fun markRoutineRun(routineId: Long) = routines.updateLastRun(routineId, System.currentTimeMillis())

    fun deleteRoutine(routineId: Long) = routines.deleteById(routineId)

    // ── Location helpers ───────────────────────────────────────────

    fun rememberLocation(lat: Double, lng: Double, label: String, notes: String? = null): Long {
        return locations.insert(LocationMemoryEntity(lat = lat, lng = lng, label = label, notes = notes))
    }

    fun getRecentLocations(limit: Int = 10) = locations.getRecent(limit)

    fun searchLocations(query: String) = locations.searchByLabel("%$query%")

    // ── Contact helpers ────────────────────────────────────────────

    fun rememberContact(contactId: String, name: String, notes: String? = null) {
        contacts.upsert(ContactMemoryEntity(contactId = contactId, name = name, relationshipNotes = notes))
    }

    fun recordContact(contactId: String) = contacts.incrementContactCount(contactId)

    fun getContact(contactId: String) = contacts.getByContactId(contactId)

    fun getForgottenContacts(daysAgo: Long): List<ContactMemoryEntity> {
        val cutoff = System.currentTimeMillis() - (daysAgo * 86400000L)
        return contacts.getNotContactedSince(cutoff)
    }
}
