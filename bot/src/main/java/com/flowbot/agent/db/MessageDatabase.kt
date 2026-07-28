package com.flowbot.agent.db

import android.content.Context
import androidx.room.*

@Entity(tableName = "messages", indices = [Index("content_hash", unique = true)])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "group_name") val groupName: String,
    val sender: String,
    val content: String,
    @ColumnInfo(name = "timestamp_text") val timestampText: String,
    @ColumnInfo(name = "raw_text") val rawText: String,
    @ColumnInfo(name = "collected_at") val collectedAt: Long,
    @ColumnInfo(name = "content_hash") val contentHash: String,
)

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(messages: List<MessageEntity>): List<Long>

    @Query("SELECT * FROM messages ORDER BY collected_at DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages")
    fun count(): Int

    @Query("SELECT * FROM messages WHERE group_name = :group ORDER BY collected_at DESC")
    fun getByGroup(group: String): List<MessageEntity>

    @Query("SELECT DISTINCT group_name FROM messages")
    fun getAllGroups(): List<String>
}

@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class MessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: MessageDatabase? = null

        fun getInstance(context: Context): MessageDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MessageDatabase::class.java,
                    "flowbot_messages.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
