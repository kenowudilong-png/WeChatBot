# 当前项目任务

**目标**: 构建完整的微信 AI 自动回复机器人 APK，通过无障碍服务控制微信，支持消息监听、自动回复、人工接管

**完成标准**:
- `/send` API 能成功在微信聊天界面发送消息
- `/messages` API 能正确返回微信聊天消息
- Python 桥接服务能连接 AI API 实现自动回复
- 支持人工接管（暂停/恢复 AI 回复）
- 应用内自动更新正常工作

## 步骤

- [x] 1. 搭建 Android 项目基础结构（Gradle、依赖、Manifest）
- [x] 2. 实现无障碍服务（继承 Assists 框架 SelectToSpeakService）
- [x] 3. 实现 HTTP API 服务器（NanoHTTPD，/status /messages /send /pause）
- [x] 4. 实现消息监听 MessageMonitor（从无障碍事件提取新消息）
- [x] 5. 实现消息发送 MessageSender（查找输入框 → 输入 → 点击发送）
- [x] 6. CI/CD 自动构建 + GitHub Release
- [x] 7. 应用内自动更新（检查更新 → 镜像加速下载 → 安装）
- [x] 8. 根据微信实际节点树修复 MessageSender 和 MessageMonitor
- [ ] 9. 修复 CI 签名一致性（持久化 keystore 到 GitHub Secrets）
- [ ] 10. 实测消息发送功能（在微信聊天界面验证 /send）
- [ ] 11. 实测消息监听功能（验证 /messages 返回正确数据）
- [ ] 12. 构建 Python 桥接服务（连接手机 API ↔ AI API）
- [ ] 13. 实现人工接管检测（检测人类手动操作 → 自动暂停 AI）
- [ ] 14. 端到端联调测试
