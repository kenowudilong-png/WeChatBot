# Project Rules

## Tech Stack
- Language: Kotlin (Android)
- Framework: Assists v3.2.219 (AccessibilityService)
- HTTP Server: NanoHTTPD 2.3.1
- Build: Gradle 7.x + AGP 7.3.0
- CI/CD: GitHub Actions (auto build + release)
- Min SDK: 24, Target SDK: 34

## Hard Rules
- 永远不要直接修改 `AndroidManifest.xml` 中的无障碍服务声明（继承自 Assists 框架）
- 所有节点查找深度必须 >= 30（微信 UI 节点层级很深）
- 不要依赖微信的 resource-id 作为唯一查找方式（ID 随版本混淆变化），优先用控件类型 + contentDescription
- 提交前必须确认 `versionCode` 已递增
- CI 构建的 APK 必须使用统一签名（`wechatbot.jks`）
- 不允许在代码中硬编码 API 密钥或敏感信息
- 必须基于 TASK.md 执行，不允许凭空决策
- 每完成一步必须更新 STATE.md
- 不允许停止，直到 TASK.md 全部完成

## Architecture
- `MessageSender` 负责发送，`MessageMonitor` 负责监听，职责分离
- HTTP API 是唯一对外接口，所有操作通过 `/send`、`/messages` 等端点
- 无障碍服务通过 Assists 框架的 `SelectToSpeakService` 绕过微信节点混淆

## Testing
- Build: `./gradlew assembleDebug`
- 手动验证: `curl http://<phone-ip>:8080/status`
- 消息发送: `curl -X POST http://<phone-ip>:8080/send -H "Content-Type: application/json" -d '{"to":"<name>","content":"<msg>"}'`
- 节点调试: `curl http://<phone-ip>:8080/debug/nodes`

## Known WeChat Node Structure (v8.x)
- 消息列表: `RecyclerView` id=`com.tencent.mm:id/bp0`
- 输入框容器: `ScrollView` id=`com.tencent.mm:id/o4q`
- 语音切换: `ImageButton` desc="切换到按住说话" id=`com.tencent.mm:id/bpe`
- 表情按钮: `ImageButton` desc="表情" id=`com.tencent.mm:id/bqr`
- 更多功能: `ImageButton` desc="更多功能按钮，已折叠" id=`com.tencent.mm:id/bjz`
- 头像: `ImageView` desc="<name>头像" id=`com.tencent.mm:id/bk1`
- 时间戳: `TextView` id=`com.tencent.mm:id/br1`
