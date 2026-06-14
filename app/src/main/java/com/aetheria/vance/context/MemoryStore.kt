package com.aetheria.vance.context

import android.content.Context
import android.util.Log
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
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
    suspend fun getBySession(sessionId: String): List<ConversationEntity>

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<ConversationEntity>

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Insert
    suspend fun insertAll(vararg conversations: ConversationEntity)

    @Query("DELETE FROM conversations WHERE timestamp < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long): Int

    @Query("DELETE FROM conversations WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String)

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun getCount(): Int
}

@Dao
interface PreferenceDao {
    @Query("SELECT * FROM preferences WHERE `key` = :key")
    suspend fun getByKey(key: String): PreferenceEntity?

    @Query("SELECT value FROM preferences WHERE `key` = :key")
    suspend fun getValue(key: String): String?

    @Query("SELECT * FROM preferences")
    suspend fun getAll(): List<PreferenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preference: PreferenceEntity)

    @Query("DELETE FROM preferences WHERE `key` = :key")
    suspend fun deleteByKey(key: String)
}

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines WHERE enabled = 1")
    suspend fun getEnabled(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE triggerType = :type AND enabled = 1")
    suspend fun getByTriggerType(type: String): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE triggerType = :type AND triggerValue = :value AND enabled = 1")
    suspend fun getByTrigger(type: String, value: String): List<RoutineEntity>

    @Insert
    suspend fun insert(routine: RoutineEntity): Long

    @Insert
    suspend fun insertAll(vararg routines: RoutineEntity)

    @Update
    suspend fun update(routine: RoutineEntity)

    @Query("UPDATE routines SET lastRun = :timestamp WHERE id = :id")
    suspend fun updateLastRun(id: Long, timestamp: Long)

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface LocationMemoryDao {
    @Query("SELECT * FROM location_memory ORDER BY lastVisit DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<LocationMemoryEntity>

    @Query("SELECT * FROM location_memory WHERE label LIKE :query ORDER BY lastVisit DESC")
    suspend fun searchByLabel(query: String): List<LocationMemoryEntity>

    @Insert
    suspend fun insert(location: LocationMemoryEntity): Long

    @Query("SELECT * FROM location_memory WHERE lat BETWEEN :minLat AND :maxLat AND lng BETWEEN :minLng AND :maxLng ORDER BY lastVisit DESC")
    suspend fun getNearby(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): List<LocationMemoryEntity>

    @Query("DELETE FROM location_memory WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ContactMemoryDao {
    @Query("SELECT * FROM contact_memory WHERE contactId = :contactId")
    suspend fun getByContactId(contactId: String): ContactMemoryEntity?

    @Query("SELECT * FROM contact_memory ORDER BY lastContacted DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<ContactMemoryEntity>

    @Query("SELECT * FROM contact_memory WHERE lastContacted < :timestamp ORDER BY lastContacted ASC")
    suspend fun getNotContactedSince(timestamp: Long): List<ContactMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactMemoryEntity)

    @Query("UPDATE contact_memory SET contactCount = contactCount + 1, lastContacted = :timestamp WHERE contactId = :contactId")
    suspend fun incrementContactCount(contactId: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM contact_memory WHERE contactId = :contactId")
    suspend fun deleteByContactId(contactId: String)
}

// ── Memory (Reinforcement Learning) ───────────────────────────────

@Entity(tableName = "memory",
    indices = [Index("reinforcement_score")]
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val prompt: String,
    val response: String,
    @ColumnInfo(name = "reinforcement_score") val reinforcementScore: Int = 0,
    @ColumnInfo(name = "source") val source: String = "user",  // "user" | "web" | "curriculum"
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String = "default"
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory WHERE reinforcement_score != 0 ORDER BY abs(reinforcement_score) DESC LIMIT :limit")
    suspend fun getTrainingSamples(limit: Int): List<MemoryEntity>

    @Query("UPDATE memory SET reinforcement_score = :score WHERE id = :id")
    suspend fun updateReinforcementScore(id: Long, score: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntity): Long

    @Query("SELECT * FROM memory ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MemoryEntity>

    @Query("SELECT COUNT(*) FROM memory WHERE reinforcement_score > 0")
    suspend fun getPositiveCount(): Int

    @Query("SELECT COUNT(*) FROM memory WHERE reinforcement_score < 0")
    suspend fun getNegativeCount(): Int
}

// ── LoRA Checkpoints ──────────────────────────────────────────────

@Entity(tableName = "lora_checkpoints")
data class LoraCheckpointEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val filename: String,
    val timestamp: Long = System.currentTimeMillis(),
    val validationLoss: Double,
    @ColumnInfo(name = "is_active") val isActive: Boolean = false
)

@Dao
interface LoraCheckpointDao {
    @Query("SELECT * FROM lora_checkpoints ORDER BY timestamp DESC")
    suspend fun getAllCheckpoints(): List<LoraCheckpointEntity>

    @Query("SELECT * FROM lora_checkpoints WHERE is_active = 1 LIMIT 1")
    suspend fun getActiveCheckpoint(): LoraCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(checkpoint: LoraCheckpointEntity): Long

    @Query("UPDATE lora_checkpoints SET is_active = 0")
    suspend fun deactivateAll(): Int

    @Query("UPDATE lora_checkpoints SET is_active = 1 WHERE id = :id")
    suspend fun activateCheckpoint(id: Int): Int

    @Query("DELETE FROM lora_checkpoints WHERE id = :id")
    suspend fun deleteCheckpoint(id: Int): Int
}

// ── Web Knowledge (PR #3) ──────────────────────────────────────────

@Entity(tableName = "web_knowledge",
    indices = [Index("cosine_score"), Index("timestamp")]
)
data class WebKnowledgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val url: String,
    val content: String,
    @ColumnInfo(name = "embedding") val embeddingBlob: ByteArray,
    @ColumnInfo(name = "cosine_score") val cosineScore: Float,
    val timestamp: Long = System.currentTimeMillis(),
    val keywords: String
)

@Dao
interface WebKnowledgeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WebKnowledgeEntity): Long

    @Query("SELECT * FROM web_knowledge ORDER BY cosine_score DESC LIMIT :limit")
    suspend fun getTopCurriculum(limit: Int): List<WebKnowledgeEntity>

    @Query("DELETE FROM web_knowledge WHERE timestamp < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM web_knowledge")
    suspend fun getCount(): Int
}

// ── Wake Word Samples (PR #7) ──────────────────────────────────────

@Entity(tableName = "wake_samples",
    indices = [Index("label"), Index("timestamp")]
)
data class WakeSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "pcm_bytes") val pcmBytes: ByteArray,
    @ColumnInfo(name = "mfcc_bytes") val mfccBytes: ByteArray,
    val label: Int,  // 0=noise, 1=partial, 2=wake
    @ColumnInfo(name = "confirmed_by_user") val confirmedByUser: Boolean,
    val confidence: Float,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface WakeSampleDao {
    @Query("SELECT * FROM wake_samples ORDER BY timestamp DESC")
    suspend fun getAll(): List<WakeSampleEntity>

    @Query("SELECT COUNT(*) FROM wake_samples WHERE label = :label")
    suspend fun countByLabel(label: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sample: WakeSampleEntity): Long

    @Query("DELETE FROM wake_samples WHERE id IN " +
           "(SELECT id FROM wake_samples ORDER BY timestamp ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int): Int

    @Query("SELECT COUNT(*) FROM wake_samples")
    suspend fun total(): Int

    @Query("UPDATE wake_samples SET label = :label, confirmed_by_user = 1 WHERE id = :id")
    suspend fun relabelSample(id: Int, label: Int): Int

    @Query("UPDATE wake_samples SET confirmed_by_user = 1 WHERE id = :id")
    suspend fun markConfirmed(id: Int): Int
}

// ── Skills ─────────────────────────────────────────────────────────

@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val triggerPatterns: String,
    val responseTemplate: String,
    val actionType: String,
    val confidenceScore: Float = 1.0f,
    val useCount: Int = 0,
    val lastUsed: Long = 0L,
    val autoLearned: Boolean = false,
    val approved: Boolean = true
)

@Dao
interface SkillDao {
    @Query("SELECT * FROM skills")
    suspend fun getAllSkills(): List<SkillEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(vararg skills: SkillEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(skills: List<SkillEntity>)

    @Query("SELECT * FROM skills WHERE approved = 1")
    suspend fun getApprovedSkills(): List<SkillEntity>

    @Query("UPDATE skills SET useCount = useCount + 1, lastUsed = :now WHERE id = :id")
    suspend fun incrementUseCount(id: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun deleteById(id: String)
}

// ── Memory Embeddings (RAG) ───────────────────────────────────────

@Entity(tableName = "memory_embeddings")
data class MemoryEmbeddingEntity(
    @PrimaryKey val id: String,
    val conversationId: Long,
    val embeddingBlob: ByteArray,
    val inputText: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface MemoryEmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbedding(e: MemoryEmbeddingEntity)

    @Query("SELECT * FROM memory_embeddings")
    suspend fun getAllEmbeddings(): List<MemoryEmbeddingEntity>

    @Query("SELECT COUNT(*) FROM memory_embeddings")
    suspend fun getCount(): Int
}

// ── Database ─────────────────────────────────────────────────────

@Database(
    entities = [
        ConversationEntity::class,
        PreferenceEntity::class,
        RoutineEntity::class,
        LocationMemoryEntity::class,
        ContactMemoryEntity::class,
        SkillEntity::class,
        MemoryEmbeddingEntity::class,
        MemoryEntity::class,
        LoraCheckpointEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class CipherDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun preferenceDao(): PreferenceDao
    abstract fun routineDao(): RoutineDao
    abstract fun locationMemoryDao(): LocationMemoryDao
    abstract fun contactMemoryDao(): ContactMemoryDao
    abstract fun skillDao(): SkillDao
    abstract fun embeddingDao(): MemoryEmbeddingDao
    abstract fun memoryDao(): MemoryDao
    abstract fun loraCheckpointDao(): LoraCheckpointDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS memory (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        prompt TEXT NOT NULL,
                        response TEXT NOT NULL,
                        reinforcement_score INTEGER NOT NULL DEFAULT 0,
                        source TEXT NOT NULL DEFAULT 'user',
                        timestamp INTEGER NOT NULL DEFAULT (strftime('%s', 'now') * 1000),
                        sessionId TEXT NOT NULL DEFAULT 'default'
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_reinforcement_score ON memory(reinforcement_score)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS lora_checkpoints (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        filename TEXT NOT NULL,
                        timestamp INTEGER NOT NULL DEFAULT (strftime('%s', 'now') * 1000),
                        validationLoss REAL NOT NULL,
                        is_active INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }
    }
}

// ── MemoryStore — high-level API ─────────────────────────────────

class MemoryStore(context: Context) {

    val db: CipherDatabase = Room.databaseBuilder(
        context.applicationContext,
        CipherDatabase::class.java,
        "cipher_memory.db"
    )
        .addMigrations(CipherDatabase.MIGRATION_3_4)
        .fallbackToDestructiveMigration()
        .build()

    val conversations get() = db.conversationDao()
    val preferences get() = db.preferenceDao()
    val routines get() = db.routineDao()
    val locations get() = db.locationMemoryDao()
    val contacts get() = db.contactMemoryDao()

    // ── Conversation helpers (Fix 2: all DAO calls on IO dispatcher) ──

    suspend fun saveExchange(sessionId: String, userMessage: String, cipherResponse: String, actionType: String? = null) {
        withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val savedId = conversations.insert(ConversationEntity(role = "user", content = userMessage, timestamp = now, sessionId = sessionId))
                conversations.insert(ConversationEntity(role = "cipher", content = cipherResponse, timestamp = now + 1, sessionId = sessionId, actionType = actionType))

                // Fire-and-forget embedding for RAG
                try {
                    val text = "User: $userMessage → Vance: $cipherResponse"
                    val embedding = com.aetheria.vance.brain.MemoryEmbedder.embed(text)
                    val blob = com.aetheria.vance.brain.floatsToBytes(embedding)
                    embeddings.insertEmbedding(
                        MemoryEmbeddingEntity(
                            id = java.util.UUID.randomUUID().toString(),
                            conversationId = savedId,
                            embeddingBlob = blob,
                            inputText = text,
                            timestamp = now
                        )
                    )
                } catch (e: Exception) {
                    Log.w("MemoryStore", "Embedding failed silently: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e("MemoryStore", "saveExchange failed", e)
            }
        }
    }

    suspend fun getSessionHistory(sessionId: String): List<ConversationEntity> = withContext(Dispatchers.IO) {
        conversations.getBySession(sessionId)
    }

    suspend fun getRecentConversations(limit: Int): List<ConversationEntity> = withContext(Dispatchers.IO) {
        conversations.getRecent(limit)
    }

    suspend fun clearHistory(sessionId: String) = withContext(Dispatchers.IO) {
        conversations.clearSession(sessionId)
    }

    suspend fun pruneOldConversations(olderThan: Long) = withContext(Dispatchers.IO) {
        conversations.deleteOlderThan(olderThan)
    }

    // ── Preference helpers ─────────────────────────────────────────

    suspend fun getPreference(key: String): String? = withContext(Dispatchers.IO) {
        preferences.getValue(key)
    }

    suspend fun setPreference(key: String, value: String) = withContext(Dispatchers.IO) {
        preferences.upsert(PreferenceEntity(key = key, value = value))
    }

    suspend fun getAllPreferences(): List<PreferenceEntity> = withContext(Dispatchers.IO) {
        preferences.getAll()
    }

    // ── Routine helpers ────────────────────────────────────────────

    suspend fun getEnabledRoutines(): List<RoutineEntity> = withContext(Dispatchers.IO) {
        routines.getEnabled()
    }

    suspend fun getRoutinesByTrigger(type: String): List<RoutineEntity> = withContext(Dispatchers.IO) {
        routines.getByTriggerType(type)
    }

    suspend fun addRoutine(triggerType: String, triggerValue: String, actionJson: String, label: String): Long = withContext(Dispatchers.IO) {
        routines.insert(RoutineEntity(
            triggerType = triggerType,
            triggerValue = triggerValue,
            actionJson = actionJson,
            label = label
        ))
    }

    suspend fun markRoutineRun(routineId: Long) = withContext(Dispatchers.IO) {
        routines.updateLastRun(routineId, System.currentTimeMillis())
    }

    suspend fun deleteRoutine(routineId: Long) = withContext(Dispatchers.IO) {
        routines.deleteById(routineId)
    }

    // ── Location helpers ───────────────────────────────────────────

    suspend fun rememberLocation(lat: Double, lng: Double, label: String, notes: String? = null): Long = withContext(Dispatchers.IO) {
        locations.insert(LocationMemoryEntity(lat = lat, lng = lng, label = label, notes = notes))
    }

    suspend fun getRecentLocations(limit: Int = 10): List<LocationMemoryEntity> = withContext(Dispatchers.IO) {
        locations.getRecent(limit)
    }

    suspend fun searchLocations(query: String): List<LocationMemoryEntity> = withContext(Dispatchers.IO) {
        locations.searchByLabel("%$query%")
    }

    // ── Contact helpers ────────────────────────────────────────────

    suspend fun rememberContact(contactId: String, name: String, notes: String? = null) = withContext(Dispatchers.IO) {
        contacts.upsert(ContactMemoryEntity(contactId = contactId, name = name, relationshipNotes = notes))
    }

    suspend fun recordContact(contactId: String) = withContext(Dispatchers.IO) {
        contacts.incrementContactCount(contactId)
    }

    suspend fun getContact(contactId: String): ContactMemoryEntity? = withContext(Dispatchers.IO) {
        contacts.getByContactId(contactId)
    }

    suspend fun getForgottenContacts(daysAgo: Long): List<ContactMemoryEntity> = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - (daysAgo * 86400000L)
        contacts.getNotContactedSince(cutoff)
    }

    // ── Skill helpers ───────────────────────────────────────────────

    suspend fun getAllSkills(): List<SkillEntity>? = withContext(Dispatchers.IO) {
        try {
            db.skillDao().getAllSkills()
        } catch (e: Exception) {
            Log.e("MemoryStore", "getAllSkills failed", e)
            null
        }
    }

    suspend fun insertSkills(skills: List<SkillEntity>) = withContext(Dispatchers.IO) {
        try {
            db.skillDao().insertAll(skills)
        } catch (e: Exception) {
            Log.e("MemoryStore", "insertSkills failed", e)
        }
    }

    // ── Memory Embedding helpers (RAG) ──────────────────────────────

    val embeddings get() = db.embeddingDao()

    // ── Memory (Reinforcement) helpers ──────────────────────────────

    val memoryDao get() = db.memoryDao()
    val loraCheckpointDao get() = db.loraCheckpointDao()

    // ── RAG backfill (Section 5) ─────────────────────────────────────

    suspend fun backfillEmbeddings() = withContext(Dispatchers.IO) {
        try {
            val count = embeddings.getCount()
            if (count > 0) {
                Log.d("MemoryStore", "Embeddings already populated ($count), skipping backfill")
                return@withContext
            }
            val allConversations = conversations.getRecent(100)
            val pairs = allConversations.filter { it.role == "user" }.mapNotNull { userMsg ->
                val cipherMsg = allConversations.find {
                    it.role == "cipher" && it.timestamp > userMsg.timestamp && it.sessionId == userMsg.sessionId
                }
                if (cipherMsg != null) Pair(userMsg, cipherMsg) else null
            }
            Log.i("MemoryStore", "Backfilling ${pairs.size} conversation pairs")
            for ((userMsg, cipherMsg) in pairs) {
                try {
                    val text = "User: ${userMsg.content} → Vance: ${cipherMsg.content}"
                    val embedding = com.aetheria.vance.brain.MemoryEmbedder.embed(text)
                    val blob = com.aetheria.vance.brain.floatsToBytes(embedding)
                    embeddings.insertEmbedding(
                        MemoryEmbeddingEntity(
                            id = java.util.UUID.randomUUID().toString(),
                            conversationId = userMsg.id,
                            embeddingBlob = blob,
                            inputText = text,
                            timestamp = userMsg.timestamp
                        )
                    )
                } catch (e: Exception) {
                    Log.w("MemoryStore", "Backfill embedding failed for pair: ${e.message}")
                }
            }
            Log.i("MemoryStore", "Backfill complete: ${embeddings.getCount()} embeddings")
        } catch (e: Exception) {
            Log.e("MemoryStore", "Backfill failed", e)
        }
    }
}
