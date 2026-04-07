package com.example.wechatbot

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.ven.assists.AssistsCore
import com.ven.assists.AssistsCore.click
import com.ven.assists.AssistsCore.setNodeText
import com.ven.assists.AssistsCore.txt
import com.ven.assists.service.AssistsService
import kotlinx.coroutines.*

/**
 * 消息发送器
 * 通过无障碍服务操控企微/微信发送消息
 */
object MessageSender {

    private const val WEWORK_PACKAGE = "com.tencent.wework"
    private const val WECHAT_PACKAGE = "com.tencent.mm"

    var onLog: ((String) -> Unit)? = null

    // 发送队列锁（确保同时只有一个发送操作）
    private val sendLock = Object()

    /**
     * 发送消息到指定联系人/群
     * @param to 联系人或群名称
     * @param content 消息内容
     * @return 是否发送成功
     */
    fun sendMessage(to: String, content: String): Boolean {
        synchronized(sendLock) {
            return try {
                onLog?.invoke("准备发送消息给: $to")

                val rootNode = AssistsService.instance?.rootInActiveWindow
                if (rootNode == null) {
                    onLog?.invoke("错误: 无障碍服务未连接")
                    return false
                }

                val packageName = rootNode.packageName?.toString() ?: ""

                // 如果当前不在目标聊天界面，需要先进入
                if (!isInChatRoom(rootNode, to)) {
                    if (!navigateToChat(rootNode, to)) {
                        onLog?.invoke("错误: 无法进入聊天 $to")
                        return false
                    }
                    // 等待页面加载
                    Thread.sleep(1000)
                }

                // 重新获取 rootNode
                val currentRoot = AssistsService.instance?.rootInActiveWindow ?: return false

                // 发送消息
                val result = sendInCurrentChat(currentRoot, content)
                if (result) {
                    onLog?.invoke("消息发送成功: $to <- $content")
                } else {
                    onLog?.invoke("消息发送失败: $to")
                }
                result
            } catch (e: Exception) {
                onLog?.invoke("发送异常: ${e.message}")
                false
            }
        }
    }

    /**
     * 在当前聊天界面发送消息
     */
    private fun sendInCurrentChat(rootNode: AccessibilityNodeInfo, content: String): Boolean {
        // 查找输入框 (EditText)
        val editText = findNodeByClass(rootNode, "android.widget.EditText")
        if (editText == null) {
            // 可能是语音模式，尝试切换到文字模式
            val voiceButton = findNodeByText(rootNode, "按住 说话")
                ?: findNodeByText(rootNode, "按住说话")
            if (voiceButton != null) {
                // 点击语音按钮前面的控件切换到文字模式
                val prevNode = findPrevSibling(voiceButton)
                prevNode?.click()
                Thread.sleep(500)
                // 重新查找
                val newRoot = AssistsService.instance?.rootInActiveWindow ?: return false
                return sendInCurrentChat(newRoot, content)
            }
            onLog?.invoke("错误: 未找到输入框")
            return false
        }

        // 设置文本
        editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        Thread.sleep(100)
        if (!editText.setNodeText(content)) {
            onLog?.invoke("错误: 设置文本失败")
            return false
        }
        Thread.sleep(300)

        // 查找发送按钮
        val newRoot = AssistsService.instance?.rootInActiveWindow ?: return false
        val sendButton = findSendButton(newRoot)
        if (sendButton == null) {
            onLog?.invoke("错误: 未找到发送按钮")
            return false
        }

        // 点击发送
        sendButton.click()
        Thread.sleep(500)
        return true
    }

    /**
     * 查找发送按钮
     * 参考 WorkTool 逻辑：查找文本为"发送"的可点击 Button
     */
    private fun findSendButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val buttons = ArrayList<AccessibilityNodeInfo>()
        findAllByClass(rootNode, "android.widget.Button", buttons)
        return buttons.firstOrNull { it.text?.toString() == "发送" }
            ?: buttons.firstOrNull { it.contentDescription?.toString() == "发送" }
    }

    /**
     * 判断当前是否在目标聊天房间
     */
    private fun isInChatRoom(rootNode: AccessibilityNodeInfo, targetName: String): Boolean {
        // 查找 ListView (聊天消息列表存在说明在聊天界面)
        val listView = findNodeByClass(rootNode, "android.widget.ListView") ?: return false

        // 检查标题是否匹配
        val parent = listView.parent?.parent ?: return false
        val textNodes = ArrayList<AccessibilityNodeInfo>()
        findAllByClass(parent, "android.widget.TextView", textNodes, maxDepth = 3)

        return textNodes.any {
            val text = it.text?.toString() ?: ""
            text.contains(targetName) || targetName.contains(text.replace(Regex("\\(\\d+\\)$"), ""))
        }
    }

    /**
     * 导航到指定聊天
     * 通过搜索功能进入聊天
     */
    private fun navigateToChat(rootNode: AccessibilityNodeInfo, targetName: String): Boolean {
        // 先尝试在消息列表中直接查找
        val listViews = ArrayList<AccessibilityNodeInfo>()
        findAllByClass(rootNode, "android.widget.ListView", listViews)
        findAllByClass(rootNode, "androidx.recyclerview.widget.RecyclerView", listViews)

        for (listView in listViews) {
            for (i in 0 until listView.childCount) {
                val item = listView.getChild(i) ?: continue
                val textNodes = ArrayList<AccessibilityNodeInfo>()
                findAllByClass(item, "android.widget.TextView", textNodes)
                val matched = textNodes.any { it.text?.toString()?.contains(targetName) == true }
                if (matched) {
                    item.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    // 向上查找可点击的父节点
                    var clickable = item
                    while (!clickable.isClickable && clickable.parent != null) {
                        clickable = clickable.parent
                    }
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Thread.sleep(1000)
                    return true
                }
            }
        }

        onLog?.invoke("未在列表中找到 $targetName，尝试搜索...")
        // TODO: 实现搜索功能进入聊天
        return false
    }

    /**
     * 递归查找指定 className 的第一个节点
     */
    private fun findNodeByClass(
        node: AccessibilityNodeInfo,
        className: String,
        maxDepth: Int = 15,
        currentDepth: Int = 0
    ): AccessibilityNodeInfo? {
        if (currentDepth > maxDepth) return null
        if (node.className?.toString() == className) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByClass(child, className, maxDepth, currentDepth + 1)
            if (found != null) return found
        }
        return null
    }

    /**
     * 递归查找包含指定文本的节点
     */
    private fun findNodeByText(
        node: AccessibilityNodeInfo,
        text: String,
        maxDepth: Int = 15,
        currentDepth: Int = 0
    ): AccessibilityNodeInfo? {
        if (currentDepth > maxDepth) return null
        if (node.text?.toString() == text) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByText(child, text, maxDepth, currentDepth + 1)
            if (found != null) return found
        }
        return null
    }

    /**
     * 递归查找所有指定 className 的节点
     */
    private fun findAllByClass(
        node: AccessibilityNodeInfo,
        className: String,
        result: ArrayList<AccessibilityNodeInfo>,
        maxDepth: Int = 15,
        currentDepth: Int = 0
    ) {
        if (currentDepth > maxDepth) return
        if (node.className?.toString() == className) {
            result.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAllByClass(child, className, result, maxDepth, currentDepth + 1)
        }
    }

    /**
     * 查找前一个兄弟节点
     */
    private fun findPrevSibling(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val parent = node.parent ?: return null
        for (i in 0 until parent.childCount) {
            if (parent.getChild(i) == node && i > 0) {
                return parent.getChild(i - 1)
            }
        }
        return null
    }
}
