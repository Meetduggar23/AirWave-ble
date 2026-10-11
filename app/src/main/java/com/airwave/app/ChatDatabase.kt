package com.airwave.app

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val id: String,
    var name: String,
    var addresses: String = "",
    var lastSeen: Long = 0L
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val convId: String,
    var title: String = "",
    var isGroup: Boolean = false,
    var groupId: String? = null,
    var contactId: String? = null,
    var lastBleAddr: String? = null,
    var lastText: String = "",
    var lastTime: Long = 0L,
    var unread: Int = 0,
    // v4.1: disappearing messages TTL (0 = off)
    var disappearSecs: Int = 0
)

@Entity(
    tableName = "messages",
    indices = [Index("convId"), Index(value = ["convId", "time"])]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val convId: String,
    val sender: String,
    val text: String,
    val time: Long,
    val mine: Boolean,
    val delivered: Boolean = false,
    val failed: Boolean = false,
    val system: Boolean = false,
    val kind: Int = 0,
    val imagePath: String? = null,
    val caption: String = "",
    val voicePath: String? = null,
    val voiceDurationSec: Int = 0,
    // v4.1: file sharing
    val filePath: String? = null,
    val fileName: String = "",
    val fileSize: Long = 0L,
    val mime: String = "",
    // v4.1: read receipts
    val seen: Boolean = false,
    // v4.1: polls
    val pollId: String? = null,
    val pollData: String = "",
    val replyToSender: String? = null,
    val replyToText: String? = null
)

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts")
    suspend fun all(): List<ContactEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: ContactEntity)
    @Query("DELETE FROM contacts")
    suspend fun clear()
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY lastTime DESC")
    suspend fun all(): List<ConversationEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: ConversationEntity)
    @Query("SELECT * FROM conversations WHERE convId = :convId")
    suspend fun forId(convId: String): ConversationEntity?
    @Query("UPDATE conversations SET disappearSecs = :secs WHERE convId = :convId")
    suspend fun setDisappearing(convId: String, secs: Int)
    @Query("UPDATE conversations SET unread = 0 WHERE convId = :convId")
    suspend fun clearUnread(convId: String)
    @Query("DELETE FROM conversations")
    suspend fun clear()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE convId = :convId ORDER BY time ASC")
    suspend fun forConv(convId: String): List<MessageEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(m: MessageEntity)
    @Query("UPDATE messages SET delivered = 1 WHERE convId = :convId AND time = :time AND mine = 1")
    suspend fun markDelivered(convId: String, time: Long)
    @Query("DELETE FROM messages WHERE convId = :convId")
    suspend fun clearConv(convId: String)
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)
    @Query("DELETE FROM messages")
    suspend fun clear()
    // v4.1
    @Query("UPDATE messages SET seen = 1 WHERE convId = :convId AND time <= :time AND mine = 1")
    suspend fun markSeen(convId: String, time: Long)
    @Query("SELECT * FROM messages WHERE convId = :convId AND time < :before")
    suspend fun expired(convId: String, before: Long): List<MessageEntity>
    @Query("DELETE FROM messages WHERE convId = :convId AND time < :before")
    suspend fun deleteExpired(convId: String, before: Long)
}

@Database(
    entities = [ContactEntity::class, ConversationEntity::class, MessageEntity::class],
    version = 3,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN voicePath TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN voiceDurationSec INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN disappearSecs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN filePath TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN fileName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE messages ADD COLUMN fileSize INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN mime TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE messages ADD COLUMN seen INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN pollId TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN pollData TEXT NOT NULL DEFAULT ''")
    }
}
