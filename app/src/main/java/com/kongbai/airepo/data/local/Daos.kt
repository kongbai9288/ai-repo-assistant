package com.kongbai.airepo.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun conversations(): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(c: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY createdAt ASC")
    fun messages(id: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(m: MessageEntity)

    @Update
    suspend fun updateMessage(m: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun message(id: String): MessageEntity?

    @Query("DELETE FROM messages WHERE conversationId = :id")
    suspend fun clearMessages(id: String)
}
