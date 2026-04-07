package com.example.wechatbot

import com.example.wechatbot.model.ChatMessage
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

/**
 * 内嵌 HTTP API 服务
 * 提供 REST API 供外部桥接服务调用
 */
class HttpApiServer(port: Int = 8080) : NanoHTTPD(port) {

    private val gson = Gson()

    var onLog: ((String) -> Unit)? = null

    // AI 自动回复暂停状态
    var isPaused = false
        private set

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        onLog?.invoke("${method.name} $uri")

        return try {
            when {
                uri == "/status" && method == Method.GET -> handleStatus()
                uri == "/messages" && method == Method.GET -> handleGetMessages(session)
                uri == "/send" && method == Method.POST -> handleSend(session)
                uri == "/pause" && method == Method.POST -> handlePause(session)
                uri == "/clear" && method == Method.POST -> handleClear()
                else -> jsonResponse(404, mapOf("code" to 404, "msg" to "not found"))
            }
        } catch (e: Exception) {
            onLog?.invoke("API 错误: ${e.message}")
            jsonResponse(500, mapOf("code" to 500, "msg" to e.message))
        }
    }

    /**
     * GET /status - 获取服务状态
     */
    private fun handleStatus(): Response {
        val data = mapOf(
            "accessibility" to com.ven.assists.AssistsCore.isAccessibilityServiceEnabled(),
            "currentPackage" to com.ven.assists.AssistsCore.getPackageName(),
            "messageCount" to MessageMonitor.messageCount(),
            "paused" to isPaused,
            "timestamp" to System.currentTimeMillis()
        )
        return jsonResponse(200, mapOf("code" to 0, "data" to data, "msg" to "ok"))
    }

    /**
     * GET /messages?since=timestamp - 获取消息列表
     */
    private fun handleGetMessages(session: IHTTPSession): Response {
        val sinceParam = session.parms["since"]
        val messages = if (sinceParam != null) {
            val since = sinceParam.toLongOrNull() ?: 0L
            MessageMonitor.getMessagesSince(since)
        } else {
            MessageMonitor.getMessages()
        }

        val data = mapOf(
            "messages" to messages,
            "count" to messages.size,
            "timestamp" to System.currentTimeMillis()
        )
        return jsonResponse(200, mapOf("code" to 0, "data" to data, "msg" to "ok"))
    }

    /**
     * POST /send - 发送消息
     * Body: {"to": "联系人名", "content": "消息内容"}
     */
    private fun handleSend(session: IHTTPSession): Response {
        val body = readBody(session)
        val request = gson.fromJson(body, SendRequest::class.java)

        if (request.to.isNullOrBlank() || request.content.isNullOrBlank()) {
            return jsonResponse(400, mapOf("code" to 400, "msg" to "参数错误: to 和 content 不能为空"))
        }

        // 在后台线程执行发送操作
        val result = MessageSender.sendMessage(request.to, request.content)

        val data = mapOf("success" to result)
        return jsonResponse(200, mapOf(
            "code" to if (result) 0 else -1,
            "data" to data,
            "msg" to if (result) "ok" else "发送失败"
        ))
    }

    /**
     * POST /pause - 暂停/恢复 AI 自动回复
     * Body: {"paused": true/false}
     */
    private fun handlePause(session: IHTTPSession): Response {
        val body = readBody(session)
        val request = gson.fromJson(body, PauseRequest::class.java)

        isPaused = request.paused

        val data = mapOf("paused" to isPaused)
        return jsonResponse(200, mapOf("code" to 0, "data" to data, "msg" to "ok"))
    }

    /**
     * POST /clear - 清空消息队列
     */
    private fun handleClear(): Response {
        MessageMonitor.clearMessages()
        return jsonResponse(200, mapOf("code" to 0, "msg" to "ok"))
    }

    /**
     * 读取 POST body
     */
    private fun readBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength == 0) return "{}"

        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"] ?: "{}"
    }

    /**
     * 构造 JSON 响应
     */
    private fun jsonResponse(statusCode: Int, data: Any): Response {
        val json = gson.toJson(data)
        val status = when (statusCode) {
            200 -> Response.Status.OK
            400 -> Response.Status.BAD_REQUEST
            404 -> Response.Status.NOT_FOUND
            else -> Response.Status.INTERNAL_ERROR
        }
        return newFixedLengthResponse(status, "application/json; charset=utf-8", json)
    }

    // 请求数据类
    data class SendRequest(val to: String?, val content: String?)
    data class PauseRequest(val paused: Boolean = false)
}
