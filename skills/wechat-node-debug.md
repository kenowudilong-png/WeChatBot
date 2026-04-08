---
skill_id: wechat-node-debug
trigger_keywords: [节点, debug, nodes, 找不到, EditText, 控件, UI结构]
priority: 1
depends_on: []
---

# 微信节点调试

## 触发条件
当任务涉及以下场景时自动加载：
- 微信 UI 控件找不到（EditText、Button 等）
- 需要分析微信界面节点结构
- 发送/监听功能异常

## 执行步骤
1. 确保手机已打开微信并停留在目标界面（聊天窗口）
2. 执行 `curl http://<phone-ip>:8080/debug/nodes` 获取节点树
3. 分析返回的 JSON，重点关注：
   - `package` 字段：必须是 `com.tencent.mm`（微信）而非 `com.example.wechatbot`
   - 查找目标控件的 `class`、`id`、`desc`、`depth`
   - 注意 `childCount` > 0 但子节点未展示 = 深度被截断
4. 如果深度不够，增加 `HttpApiServer.kt` 中 `dumpNode` 的深度限制
5. 根据实际节点结构更新 `AGENTS.md` 中的 "Known WeChat Node Structure"

## 边界情况
- 如果 package 不是 com.tencent.mm，说明手机当前不在微信界面，需先切换
- 如果 nodeCount 为 0 或很少，可能无障碍服务未连接
- 微信版本更新后 resource-id 会变化，需要重新调试

## 验证标准
- [ ] debug/nodes 返回 package=com.tencent.mm
- [ ] 能找到目标控件（EditText、发送按钮等）
- [ ] 控件的 depth 在搜索深度范围内

## 常见错误
- ❌ 在 WeChatBot 自己的界面调试 → ✅ 必须先切到微信
- ❌ 深度限制太小看不到深层节点 → ✅ 深度至少设为 30
- ❌ 只依赖 resource-id → ✅ 优先用 class + contentDescription，id 作为辅助
