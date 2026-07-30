package com.flowbot.agent.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "display_name") val displayName: String,
    val type: String,
    val verified: Boolean,
    @ColumnInfo(name = "discovered_at") val discoveredAt: Long,
)

@Entity(
    tableName = "observations",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversation_id"],
        onDelete = ForeignKey.SET_NULL,
    )],
    indices = [Index(value = ["viewport_hash", "captured_at"]), Index("conversation_id")],
)
data class ObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long? = null,
    @ColumnInfo(name = "group_name_hint") val groupNameHint: String,
    @ColumnInfo(name = "ocr_text") val ocrText: String,
    @ColumnInfo(name = "viewport_hash") val viewportHash: String,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    @ColumnInfo(name = "parse_confidence") val parseConfidence: Float,
)

@Entity(
    tableName = "message_candidates",
    foreignKeys = [ForeignKey(
        entity = ObservationEntity::class,
        parentColumns = ["id"],
        childColumns = ["observation_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("observation_id"), Index("fingerprint")],
)
data class MessageCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "observation_id") val observationId: Long,
    val sender: String,
    val content: String,
    @ColumnInfo(name = "timestamp_text") val timestampText: String,
    @ColumnInfo(name = "group_name_hint") val groupNameHint: String,
    val confidence: Float,
    val fingerprint: String,
    val kind: String = CandidateKind.TEXT,
)

data class CandidateDigest(
    val sender: String,
    val content: String,
    val timestampText: String,
    val groupNameHint: String,
    val confidence: Float,
    val firstCapturedAt: Long,
    val lastCapturedAt: Long,
    val seenCount: Int,
)

@Entity(
    tableName = "confirmed_messages",
    foreignKeys = [
        ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MessageCandidateEntity::class, parentColumns = ["id"], childColumns = ["candidate_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("conversation_id"), Index(value = ["candidate_id"], unique = true)],
)
data class ConfirmedMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    @ColumnInfo(name = "candidate_id") val candidateId: Long,
    val sender: String,
    val content: String,
    @ColumnInfo(name = "visible_time") val visibleTime: String?,
    @ColumnInfo(name = "collected_at") val collectedAt: Long,
    @ColumnInfo(name = "dedup_fingerprint") val dedupFingerprint: String,
    val confidence: Float,
)

@Entity(tableName = "collection_events", indices = [Index("trace_id"), Index("created_at")])
data class CollectionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "trace_id") val traceId: String,
    val stage: String,
    val outcome: String,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
    val detail: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
interface CollectionDao {
    @Insert
    fun insertObservation(observation: ObservationEntity): Long

    @Query("SELECT id FROM observations WHERE viewport_hash = :viewportHash AND captured_at >= :after ORDER BY captured_at DESC LIMIT 1")
    fun findRecentObservation(viewportHash: String, after: Long): Long?

    @Insert
    fun insertCandidates(candidates: List<MessageCandidateEntity>)

    @Insert
    fun insertEvent(event: CollectionEventEntity): Long

    @Query("SELECT COUNT(*) FROM observations")
    fun observationCount(): Int

    @Query("SELECT COUNT(*) FROM message_candidates")
    fun candidateCount(): Int

    @Query("SELECT * FROM message_candidates ORDER BY id DESC LIMIT :limit")
    fun recentCandidates(limit: Int = 50): List<MessageCandidateEntity>

    @Query("""
        SELECT * FROM message_candidates
        WHERE kind = :textKind AND (
            content LIKE '%QQ音乐%'
            OR lower(content) LIKE '%' || char(10) || 'pdf%'
            OR lower(content) LIKE '%' || char(10) || 'mp3%'
            OR ((content LIKE '%KB%' COLLATE NOCASE OR content LIKE '%MB%' COLLATE NOCASE)
                AND content LIKE '%未下载%')
        )
    """)
    fun textCandidatesWithMediaMarkers(
        textKind: String = CandidateKind.TEXT,
    ): List<MessageCandidateEntity>

    @Query("UPDATE message_candidates SET kind = :unsupportedKind WHERE id = :id AND kind = :textKind")
    fun markCandidateUnsupported(
        id: Long,
        textKind: String = CandidateKind.TEXT,
        unsupportedKind: String = CandidateKind.UNSUPPORTED_MEDIA,
    ): Int

    @Query("""
        UPDATE message_candidates AS current
        SET kind = :unsupportedKind
        WHERE kind = :textKind AND length(content) <= 80
          AND (SELECT kind FROM message_candidates
               WHERE observation_id = current.observation_id AND id < current.id
               ORDER BY id DESC LIMIT 1) = :unsupportedKind
          AND (SELECT kind FROM message_candidates
               WHERE observation_id = current.observation_id AND id > current.id
               ORDER BY id ASC LIMIT 1) = :unsupportedKind
    """)
    fun markSandwichedMediaTitles(
        textKind: String = CandidateKind.TEXT,
        unsupportedKind: String = CandidateKind.UNSUPPORTED_MEDIA,
    ): Int

    @Query("""
        SELECT c.sender, c.content, c.timestamp_text AS timestampText, c.group_name_hint AS groupNameHint,
            MAX(c.confidence) AS confidence, MIN(o.captured_at) AS firstCapturedAt,
            MAX(o.captured_at) AS lastCapturedAt, COUNT(*) AS seenCount
        FROM message_candidates c
        INNER JOIN observations o ON o.id = c.observation_id
        WHERE c.kind = :kind AND o.captured_at >= :after
          AND c.content NOT LIKE '%QQ音乐%'
          AND lower(c.content) NOT LIKE '%' || char(10) || 'pdf%'
          AND lower(c.content) NOT LIKE '%' || char(10) || 'mp3%'
          AND NOT ((c.content LIKE '%KB%' COLLATE NOCASE OR c.content LIKE '%MB%' COLLATE NOCASE)
                   AND c.content LIKE '%未下载%')
        GROUP BY c.fingerprint
        ORDER BY lastCapturedAt DESC
        LIMIT :limit
    """)
    fun recentCandidateDigests(after: Long, kind: String = CandidateKind.TEXT, limit: Int = 50): List<CandidateDigest>

    @Query("""
        SELECT COUNT(DISTINCT c.fingerprint)
        FROM message_candidates c
        INNER JOIN observations o ON o.id = c.observation_id
        WHERE c.kind = :kind AND o.captured_at >= :after
          AND c.content NOT LIKE '%QQ音乐%'
          AND lower(c.content) NOT LIKE '%' || char(10) || 'pdf%'
          AND lower(c.content) NOT LIKE '%' || char(10) || 'mp3%'
          AND NOT ((c.content LIKE '%KB%' COLLATE NOCASE OR c.content LIKE '%MB%' COLLATE NOCASE)
                   AND c.content LIKE '%未下载%')
    """)
    fun candidateDigestCount(after: Long, kind: String = CandidateKind.TEXT): Int

    @Query("SELECT * FROM collection_events ORDER BY id DESC LIMIT 1")
    fun latestEvent(): CollectionEventEntity?
}

@Database(
    entities = [
        ConversationEntity::class,
        ObservationEntity::class,
        MessageCandidateEntity::class,
        ConfirmedMessageEntity::class,
        CollectionEventEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class MessageDatabase : RoomDatabase() {
    abstract fun collectionDao(): CollectionDao

    companion object {
        @Volatile
        private var instance: MessageDatabase? = null

        fun getInstance(context: Context): MessageDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MessageDatabase::class.java,
                "flowbot_messages.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `conversations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `display_name` TEXT NOT NULL, `type` TEXT NOT NULL, `verified` INTEGER NOT NULL, `discovered_at` INTEGER NOT NULL)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `observations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `conversation_id` INTEGER, `group_name_hint` TEXT NOT NULL, `ocr_text` TEXT NOT NULL, `viewport_hash` TEXT NOT NULL, `captured_at` INTEGER NOT NULL, `parse_confidence` REAL NOT NULL, FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_observations_viewport_hash_captured_at` ON `observations` (`viewport_hash`, `captured_at`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_observations_conversation_id` ON `observations` (`conversation_id`)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `message_candidates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `observation_id` INTEGER NOT NULL, `sender` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp_text` TEXT NOT NULL, `group_name_hint` TEXT NOT NULL, `confidence` REAL NOT NULL, `fingerprint` TEXT NOT NULL, FOREIGN KEY(`observation_id`) REFERENCES `observations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_message_candidates_observation_id` ON `message_candidates` (`observation_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_message_candidates_fingerprint` ON `message_candidates` (`fingerprint`)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `confirmed_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `conversation_id` INTEGER NOT NULL, `candidate_id` INTEGER NOT NULL, `sender` TEXT NOT NULL, `content` TEXT NOT NULL, `visible_time` TEXT, `collected_at` INTEGER NOT NULL, `dedup_fingerprint` TEXT NOT NULL, `confidence` REAL NOT NULL, FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`candidate_id`) REFERENCES `message_candidates`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_confirmed_messages_conversation_id` ON `confirmed_messages` (`conversation_id`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_confirmed_messages_candidate_id` ON `confirmed_messages` (`candidate_id`)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `collection_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trace_id` TEXT NOT NULL, `stage` TEXT NOT NULL, `outcome` TEXT NOT NULL, `error_code` TEXT, `detail` TEXT NOT NULL, `created_at` INTEGER NOT NULL)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_events_trace_id` ON `collection_events` (`trace_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_events_created_at` ON `collection_events` (`created_at`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `message_candidates` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'TEXT'")
            }
        }
    }
}
