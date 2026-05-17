package com.aichat.sandbox.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class Chat(
    @PrimaryKey
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "New chat",
    val model: String = ChatSettings.Defaults.MODEL,
    val systemMessage: String = "",
    val temperature: Float = ChatSettings.Defaults.TEMPERATURE,
    val topP: Float = ChatSettings.Defaults.TOP_P,
    val maxTokens: Int = ChatSettings.Defaults.MAX_TOKENS,
    val presencePenalty: Float = ChatSettings.Defaults.PRESENCE_PENALTY,
    val frequencyPenalty: Float = ChatSettings.Defaults.FREQUENCY_PENALTY,
    val totalTokens: Int = 0,
    val totalCost: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // Sub-phase 4.4: a single note can be pinned to a chat as context. On every
    // send, the chat layer re-renders the pinned note's current state and
    // attaches it transparently to the API call (image for vision models, OCR
    // text otherwise). `null` means no pin.
    val pinnedNoteId: String? = null,
)
