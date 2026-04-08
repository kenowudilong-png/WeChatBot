package com.example.wechatbot

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.wechatbot.model.ChatMessage
import com.ven.assists.AssistsCore
import com.ven.assists.AssistsCore.getChildren
import com.ven.assists.AssistsCore.getNodes
import com.ven.assists.AssistsCore.txt
import com.ven.assists.service.AssistsService
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 消息监听器
 * 监听企微/微信的无障碍事件，提取新消息
 */
object MessageMonitor {

    private const val MAX_MESSAGES = 500
    private const val WEWORK_PACKAGE = "com.tencent.wework"
    private const val WECHAT_PACKAGE = "com.tencent.mm"

    // 消息队列（线程安全）
    private val messages = ConcurrentLinkedDeque<ChatMessage>()

    // 已处理消息的 hash 集合（去重）
    private val processedHashes = LinkedHashSet<String>()

    // 日志回调
    var onLog: ((String) -> Unit)? = null

    /**
     * 获取所有消息
     */
    fun getMessages(): List<ChatMessage> = messages.toList()

    /**
     * 获取指定时间之后的消息
     */
    fun getMessagesSince(timestamp: Long): List<ChatMessage> {
        return messages.filter { it.timestamp > timestamp }
    }

    /**
     * 清空消息队列
     */
    fun clearMessages() {
        messages.clear()
    }

    /**
     * 消息数量
     */
    fun messageCount(): Int = messages.size

    /**
     * 处理无障碍事件，提取新消息
     */
    fun processEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        if (packageName != WEWORK_PACKAGE && packageName != WECHAT_PACKAGE) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // 通知栏消息
                handleNotification(event, packageName)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 界面变化时尝试读取聊天列表
                handleWindowChange(event, packageName)
            }
        }
    }

    /**
     * 处理通知栏消息
     */
    private fun handleNotification(event: AccessibilityEvent, packageName: String) {
        val text = event.text?.joinToString("") ?: return
        if (text.isBlank()) return

        val app = if (packageName == WEWORK_PACKAGE) "wework" else "wechat"

        // 通知格式通常是: "联系人: 消息内容" 或 "群名: 联系人: 消息内容"
        val parts = text.split(":", limit = 2)
        if (parts.size >= 2) {
            val sender = parts[0].trim()
            val content = parts[1].trim()
            addMessage(sender, content, sender, app, false)
        }
    }

    /**
     * 处理窗口内容变化 - 读取当前聊天列表
     */
    private fun handleWindowChange(event: AccessibilityEvent, packageName: String) {
        try {
            val rootNode = AssistsService.instance?.rootInActiveWindow ?: return
            val app = if (packageName == WEWORK_PACKAGE) "wework" else "wechat"
            readChatMessages(rootNode, app)
        } catch (e: Exception) {
            // 忽略读取错误
        }
    }

    /**
     * 从当前界面读取聊天消息列表
     */
    private fun readChatMessages(rootNode: AccessibilityNodeInfo, app: String) {
        // 尝试获取房间标题
        val roomName = getRoomTitle(rootNode) ?: "unknown"
        val isGroup = roomName.contains("(") || roomName.contains("（")

        // 微信使用 RecyclerView（id: com.tencent.mm:id/bp0）
        var messageList: AccessibilityNodeInfo? = null

        // 策略1: 通过 resource-id 查找微信消息列表
        val recyclerViews = rootNode.findAccessibilityNodeInfosByViewId("com.tencent.mm:id/bp0")
        if (!recyclerViews.isNullOrEmpty()) {
            messageList = recyclerViews[0]
        }

        // 策略2: 通过类名查找 RecyclerView
        if (messageList == null) {
            val rvNodes = ArrayList<AccessibilityNodeInfo>()
            rootNode.findByClassName(rvNodes, "androidx.recyclerview.widget.RecyclerView")
            messageList = rvNodes.firstOrNull()
        }

        // 策略3: 兼容企微 ListView
        if (messageList == null) {
            val lvNodes = ArrayList<AccessibilityNodeInfo>()
            rootNode.findByClassName(lvNodes, "android.widget.ListView")
            messageList = lvNodes.firstOrNull()
        }

        if (messageList == null) return

        // 遍历消息列表子节点
        for (i in 0 until messageList.childCount) {
            val itemNode = messageList.getChild(i) ?: continue
            extractMessageFromNode(itemNode, roomName, app, isGroup)
        }
    }

    /**
     * 从消息节点中提取发送者和内容
     */
    private fun extractMessageFromNode(
        itemNode: AccessibilityNodeInfo,
        roomName: String,
        app: String,
        isGroup: Boolean
    ) {
        val textNodes = ArrayList<AccessibilityNodeInfo>()
        itemNode.findByClassName(textNodes, "android.widget.TextView", maxDepth = 15)

        // 提取发送者（从头像的 contentDescription，如"大哥头像"）
        var sender = ""
        val avatarNodes = ArrayList<AccessibilityNodeInfo>()
        itemNode.findByClassName(avatarNodes, "android.widget.ImageView", maxDepth = 10)
        for (avatar in avatarNodes) {
            val desc = avatar.contentDescription?.toString()
            if (desc != null && desc.endsWith("头像")) {
                sender = desc.removeSuffix("头像")
                break
            }
        }

        // 提取消息文本（排除时间戳等）
        for (textNode in textNodes) {
            val text = textNode.text?.toString() ?: continue
            if (text.isBlank()) continue
            // 跳过时间戳（如 "09:11", "11:48"）
            if (text.matches(Regex("^\\d{1,2}:\\d{2}$"))) continue
            // 跳过很短的非消息文本
            if (text.length <= 1) continue

            addMessage(sender, text, roomName, app, isGroup)
        }
    }

    /**
     * 递归查找指定 className 的节点
     */
    private fun AccessibilityNodeInfo.findByClassName(
        result: ArrayList<AccessibilityNodeInfo>,
        className: String,
        maxDepth: Int = 25,
        currentDepth: Int = 0
    ) {
        if (currentDepth > maxDepth) return
        if (this.className?.toString() == className) {
            result.add(this)
            return
        }
        for (i in 0 until childCount) {
            getChild(i)?.findByClassName(result, className, maxDepth, currentDepth + 1)
        }
    }

    /**
     * 获取聊天房间标题
     * 从 ActionBar 区域提取标题文字
     */
    private fun getRoomTitle(rootNode: AccessibilityNodeInfo): String? {
        // 微信：标题在 actionbar 区域，尝试通过已知的 resource-id 查找
        val titleNodes = rootNode.findAccessibilityNodeInfosByViewId("com.tencent.mm:id/obp")
        if (!titleNodes.isNullOrEmpty()) {
            val text = titleNodes[0].text?.toString()
            if (!text.isNullOrBlank()) return text
            // 如果 obp 本身没有 text，搜索其子 TextView
            val childTexts = ArrayList<AccessibilityNodeInfo>()
            titleNodes[0].findByClassName(childTexts, "android.widget.TextView", maxDepth = 5)
            for (child in childTexts) {
                val t = child.text?.toString()
                if (!t.isNullOrBlank()) return t
            }
        }

        // 企微标题兼容
        val weworkTitle = rootNode.findAccessibilityNodeInfosByViewId("com.tencent.wework:id/title")
        if (!weworkTitle.isNullOrEmpty()) {
            return weworkTitle[0].text?.toString()
        }

        return null
    }

    /**
     * 添加消息到队列（去重）
     */
    private fun addMessage(sender: String, content: String, roomName: String, app: String, isGroup: Boolean) {
        val hash = md5("$sender:$content:$roomName")

        synchronized(processedHashes) {
            if (processedHashes.contains(hash)) return
            processedHashes.add(hash)

            // 限制 hash 集合大小
            if (processedHashes.size > MAX_MESSAGES * 2) {
                val iterator = processedHashes.iterator()
                repeat(MAX_MESSAGES) {
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
            }
        }

        val msg = ChatMessage(
            id = hash,
            sender = sender,
            content = content,
            roomName = roomName,
            timestamp = System.currentTimeMillis(),
            isGroup = isGroup,
            app = app
        )

        messages.addLast(msg)

        // 限制消息队列大小
        while (messages.size > MAX_MESSAGES) {
            messages.pollFirst()
        }

        onLog?.invoke("[${app}] ${roomName} | ${sender}: ${content}")
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
