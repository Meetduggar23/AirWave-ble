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
    var unread: Int = 0
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
}

@Database(
    entities = [ContactEntity::class, ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
}
