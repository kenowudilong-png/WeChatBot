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
        // 查找聊天消息列表 (ListView)
        val listViews = rootNode.findAccessibilityNodeInfosByViewId("") // 通过类名查找
        val allNodes = ArrayList<AccessibilityNodeInfo>()
        rootNode.findByClassName(allNodes, "android.widget.ListView")

        val listView = allNodes.firstOrNull() ?: return

        // 尝试获取房间标题
        val roomName = getRoomTitle(rootNode) ?: "unknown"
        val isGroup = roomName.contains("(") || roomName.contains("（")

        // 遍历 ListView 子节点
        for (i in 0 until listView.childCount) {
            val itemNode = listView.getChild(i) ?: continue
            val textNodes = ArrayList<AccessibilityNodeInfo>()
            itemNode.findByClassName(textNodes, "android.widget.TextView")

            if (textNodes.size >= 2) {
                // 通常: [0] = 发送者, [1] = 消息内容 (具体顺序需实测)
                val sender = textNodes[0].text?.toString() ?: continue
                val content = textNodes[1].text?.toString() ?: continue

                if (content.isNotBlank() && sender.isNotBlank()) {
                    addMessage(sender, content, roomName, app, isGroup)
                }
            } else if (textNodes.size == 1) {
                // 单聊时可能只有消息内容
                val content = textNodes[0].text?.toString() ?: continue
                if (content.isNotBlank()) {
                    addMessage("", content, roomName, app, isGroup)
                }
            }
        }
    }

    /**
     * 递归查找指定 className 的节点
     */
    private fun AccessibilityNodeInfo.findByClassName(
        result: ArrayList<AccessibilityNodeInfo>,
        className: String,
        maxDepth: Int = 10,
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
     */
    private fun getRoomTitle(rootNode: AccessibilityNodeInfo): String? {
        // 查找 ListView，其前面的兄弟节点通常包含标题
        val allNodes = ArrayList<AccessibilityNodeInfo>()
        rootNode.findByClassName(allNodes, "android.widget.ListView")
        val listView = allNodes.firstOrNull() ?: return null

        val parent = listView.parent?.parent ?: return null

        // 遍历 parent 的子节点，找到 ListView 之前的节点
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            val textNodes = ArrayList<AccessibilityNodeInfo>()
            child.findByClassName(textNodes, "android.widget.TextView")
            if (textNodes.isNotEmpty()) {
                val title = textNodes.firstOrNull()?.text?.toString()
                if (!title.isNullOrBlank()) return title
            }
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
