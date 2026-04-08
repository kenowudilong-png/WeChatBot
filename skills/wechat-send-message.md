---
skill_id: wechat-send-message
trigger_keywords: [发送, send, 消息, message, EditText, 输入框, 发送按钮]
priority: 1
depends_on: [wechat-node-debug]
---

# 微信消息发送

## 触发条件
当任务涉及修改或调试消息发送逻辑时加载。

## 执行步骤
1. **定位输入框**（优先级从高到低）：
   - 通过 `findAccessibilityNodeInfosByViewId("com.tencent.mm:id/o4q")` 找到 ScrollView 容器
   - 在 ScrollView 内搜索 `EditText`（maxDepth=5 即可）
   - 如果找不到，点击 ScrollView 激活后重试
   - 兜底：全局搜索 EditText（maxDepth=30）
2. **处理语音模式**：
   - 如果找不到 EditText，检查是否有 desc="切换到按住说话" 的 ImageButton
   - 点击该按钮切换到键盘模式，等待 800ms 后重试
3. **输入文本**：
   - `ACTION_FOCUS` → `ACTION_SET_TEXT`
   - 如果 SET_TEXT 失败，使用剪贴板 `ACTION_PASTE`
4. **点击发送**：
   - 输入文本后刷新 rootNode（发送按钮此时才会出现）
   - 查找 text="发送" 或 desc="发送" 的可点击节点

## 边界情况
- 微信输入框非标准 EditText，可能是 WebView 或自定义控件
- 发送按钮只在输入框有文字时才显示（之前是"更多功能"按钮）
- 群聊可能有 @提醒弹窗遮挡

## 验证标准
- [ ] curl /send 返回 `{"code":0,"data":{"success":true}}`
- [ ] 微信聊天界面实际显示了发送的消息

## 常见错误
- ❌ 在设置文本后不刷新 rootNode → ✅ 必须重新获取 rootInActiveWindow
- ❌ 搜索深度太浅 → ✅ 至少 30 层
- ❌ 只查找 Button 类型的发送按钮 → ✅ 也要查找任意可点击节点
