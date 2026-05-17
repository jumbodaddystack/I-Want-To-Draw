package com.aichat.sandbox.data.local

import androidx.room.*
import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.Message
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    fun getAllChats(): Flow<List<Chat>>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getChatById(chatId: String): Chat?

    @Query("SELECT * FROM chats WHERE id = :chatId")
    fun getChatByIdFlow(chatId: String): Flow<Chat?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: Chat)

    @Update
    suspend fun updateChat(chat: Chat)

    @Delete
    suspend fun deleteChat(chat: Chat)

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteChatById(chatId: String)

    /**
     * Pin / unpin a note for [chatId] (sub-phase 4.4). `null` clears the pin.
     * Touches only `pinnedNoteId` so the rest of the chat row is untouched —
     * pinning is metadata, not an edit to the conversation itself.
     */
    @Query("UPDATE chats SET pinnedNoteId = :noteId WHERE id = :chatId")
    suspend fun updatePinnedNoteId(chatId: String, noteId: String?)

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC")
    fun getMessagesForChat(chatId: String): Flow<List<Message>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message)

    @Delete
    suspend fun deleteMessage(message: Message)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun clearChatHistory(chatId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId")
    suspend fun getMessageCount(chatId: String): Int

    // Message editing (1.2)
    @Query("UPDATE messages SET content = :content WHERE id = :messageId")
    suspend fun updateMessageContent(messageId: String, content: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId AND createdAt >= :timestamp")
    suspend fun deleteMessagesFrom(chatId: String, timestamp: Long)

    // Full-text search (1.5)
    @Query("""
        SELECT m.* FROM messages m
        JOIN messages_fts ON messages_fts.rowid = m.rowid
        WHERE messages_fts MATCH :query
        ORDER BY m.createdAt DESC
    """)
    suspend fun searchMessages(query: String): List<Message>

    @Query("""
        SELECT DISTINCT c.* FROM chats c
        INNER JOIN messages m ON m.chatId = c.id
        INNER JOIN messages_fts ON messages_fts.rowid = m.rowid
        WHERE messages_fts MATCH :query
        ORDER BY c.updatedAt DESC
    """)
    suspend fun searchChats(query: String): List<Chat>

    @Query("""
        SELECT snippet(messages_fts, '', '', '...', -1, 40) FROM messages_fts
        WHERE messages_fts.rowid = :messageRowId AND messages_fts MATCH :query
    """)
    suspend fun getSnippet(messageRowId: Long, query: String): String?

    @Query("SELECT rowid FROM messages WHERE id = :messageId")
    suspend fun getMessageRowId(messageId: String): Long?
}
