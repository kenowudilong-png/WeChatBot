package com.example.wechatbot.model

data class ChatMessage(
    val id: String,
    val sender: String,
    val content: String,
    val roomName: String,
    val timestamp: Long,
    val isGroup: Boolean = false,
    val app: String = "" // "wework" or "wechat"
)
