package com.example.wechatbot

import android.view.accessibility.AccessibilityNodeInfo
import com.ven.assists.AssistsCore
import com.ven.assists.AssistsCore.click
import com.ven.assists.AssistsCore.setNodeText
import com.ven.assists.service.AssistsService

/**
 * 消息发送器
 * 通过无障碍服务操控企微/微信发送消息
 */
object MessageSender {

    var onLog: ((String) -> Unit)? = null
    // 记录上一次发送的错误详情
    var lastError: String = ""
        private set

    private val sendLock = Object()

    /**
     * 发送消息 — 要求手机已停留在聊天界面
     */
    fun sendMessage(to: String, content: String): Boolean {
        synchronized(sendLock) {
            return try {
                lastError = ""
                onLog?.invoke("准备发送消息给: $to")

                val rootNode = AssistsService.instance?.rootInActiveWindow
                if (rootNode == null) {
                    lastError = "无障碍服务未连接 (rootNode is null)"
                    onLog?.invoke("错误: $lastError")
                    return false
                }

                val pkg = rootNode.packageName?.toString() ?: "unknown"
                onLog?.invoke("当前包名: $pkg")

                // 直接在当前界面尝试发送
                val result = sendInCurrentChat(rootNode, content)
                if (result) {
                    onLog?.invoke("消息发送成功: $to <- $content")
                } else {
                    onLog?.invoke("消息发送失败: $lastError")
                }
                result
            } catch (e: Exception) {
                lastError = "异常: ${e.message}"
                onLog?.invoke("发送异常: ${e.message}")
                false
            }
        }
    }

    /**
     * 在当前聊天界面发送消息
     */
    private fun sendInCurrentChat(rootNode: AccessibilityNodeInfo, content: String): Boolean {
        // Step 1: 查找输入框
        // 使用系统 API findAccessibilityNodeInfosByViewId（无深度限制，最可靠）
        var editText: AccessibilityNodeInfo? = null

        // 微信输入框的 ScrollView 容器 id
        val wechatScrollView = rootNode.findAccessibilityNodeInfosByViewId("com.tencent.mm:id/o4q")
        if (!wechatScrollView.isNullOrEmpty()) {
            // ScrollView 内部的 EditText
            editText = findNodeByClass(wechatScrollView[0], "android.widget.EditText", maxDepth = 5)
            if (editText == null) {
                // 尝试直接点击 ScrollView 激活输入框，再重新获取
                wechatScrollView[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(500)
                val newRoot = AssistsService.instance?.rootInActiveWindow ?: return false
                val sv = newRoot.findAccessibilityNodeInfosByViewId("com.tencent.mm:id/o4q")
                if (!sv.isNullOrEmpty()) {
                    editText = findNodeByClass(sv[0], "android.widget.EditText", maxDepth = 5)
                }
            }
        }

        // 兜底：全局搜索 EditText（深度加大到 30）
        if (editText == null) {
            editText = findNodeByClass(rootNode, "android.widget.EditText", maxDepth = 30)
        }

        // 企微 resource-id 兜底
        if (editText == null) {
            editText = findNodeById(rootNode, "com.tencent.wework:id/et_sendmsg")
        }

        if (editText == null) {
            // 可能处于语音输入模式 — 点击"切换到按住说话"按钮切换到键盘
            val voiceSwitchBtn = findNodeByDesc(rootNode, "切换到按住说话")
            if (voiceSwitchBtn != null) {
                onLog?.invoke("检测到语音模式，切换到键盘...")
                voiceSwitchBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(800)
                val newRoot = AssistsService.instance?.rootInActiveWindow ?: return false
                return sendInCurrentChat(newRoot, content)
            }

            lastError = "未找到输入框 (EditText)"
            return false
        }

        onLog?.invoke("找到输入框: class=${editText.className}, id=${editText.viewIdResourceName}")

        // Step 2: 聚焦并设置文本
        editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        Thread.sleep(200)

        val setText = editText.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, content)
            }
        )
        if (!setText) {
            // 备选方案: 使用粘贴
            onLog?.invoke("ACTION_SET_TEXT 失败，尝试粘贴方式")
            val clipboard = AssistsService.instance?.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("text", content))
                editText.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                Thread.sleep(300)
            } else {
                lastError = "设置文本失败 (SET_TEXT 和 PASTE 都失败)"
                return false
            }
        }

        Thread.sleep(300)

        // Step 3: 查找并点击发送按钮
        val freshRoot = AssistsService.instance?.rootInActiveWindow ?: return false
        val sendButton = findSendButton(freshRoot)
        if (sendButton == null) {
            lastError = "未找到发送按钮"
            return false
        }

        onLog?.invoke("找到发送按钮: text=${sendButton.text}, class=${sendButton.className}")
        sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(500)
        return true
    }

    /**
     * 查找发送按钮 — 多种策略
     * 注意：微信中发送按钮只有在输入框有文字后才会出现
     */
    private fun findSendButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 收集所有节点（深度 30）
        val allNodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(rootNode, allNodes, maxDepth = 30)

        // 策略1: 查找文本为"发送"的 Button
        val btn = allNodes.firstOrNull {
            it.className?.toString() == "android.widget.Button" && it.text?.toString() == "发送"
        }
        if (btn != null) return btn

        // 策略2: 查找 contentDescription 为"发送"的可点击节点
        val descBtn = allNodes.firstOrNull {
            it.contentDescription?.toString() == "发送" && it.isClickable
        }
        if (descBtn != null) return descBtn

        // 策略3: 查找文本为"发送"的任意可点击节点
        val textBtn = allNodes.firstOrNull {
            it.text?.toString() == "发送" && it.isClickable
        }
        if (textBtn != null) return textBtn

        // 策略4: 查找文本为"Send"的节点（英文版微信）
        val sendBtn = allNodes.firstOrNull {
            it.text?.toString()?.equals("Send", ignoreCase = true) == true && it.isClickable
        }
        if (sendBtn != null) return sendBtn

        // 策略5: 查找 resource-id 包含 send 的按钮
        val idBtn = allNodes.firstOrNull {
            val id = it.viewIdResourceName ?: ""
            (id.contains("send") || id.contains("btn_send")) && it.isClickable
        }
        return idBtn
    }

    private fun findNodeByClass(node: AccessibilityNodeInfo, className: String, maxDepth: Int = 30, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > maxDepth) return null
        if (node.className?.toString() == className) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findNodeByClass(it, className, maxDepth, depth + 1) }
            if (found != null) return found
        }
        return null
    }

    private fun findNodeById(node: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        val results = node.findAccessibilityNodeInfosByViewId(id)
        return results?.firstOrNull()
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String, maxDepth: Int = 30, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > maxDepth) return null
        if (node.text?.toString() == text) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findNodeByText(it, text, maxDepth, depth + 1) }
            if (found != null) return found
        }
        return null
    }

    private fun findAllByClass(node: AccessibilityNodeInfo, className: String, result: ArrayList<AccessibilityNodeInfo>, maxDepth: Int = 30, depth: Int = 0) {
        if (depth > maxDepth) return
        if (node.className?.toString() == className) result.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findAllByClass(it, className, result, maxDepth, depth + 1) }
        }
    }

    private fun collectAllNodes(node: AccessibilityNodeInfo, result: ArrayList<AccessibilityNodeInfo>, maxDepth: Int = 30, depth: Int = 0) {
        if (depth > maxDepth || result.size > 1000) return
        result.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectAllNodes(it, result, maxDepth, depth + 1) }
        }
    }

    private fun findPrevSibling(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val parent = node.parent ?: return null
        for (i in 0 until parent.childCount) {
            if (parent.getChild(i) == node && i > 0) return parent.getChild(i - 1)
        }
        return null
    }

    private fun findNodeByDesc(node: AccessibilityNodeInfo, desc: String, maxDepth: Int = 30, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > maxDepth) return null
        if (node.contentDescription?.toString()?.contains(desc) == true) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findNodeByDesc(it, desc, maxDepth, depth + 1) }
            if (found != null) return found
        }
        return null
    }
}
