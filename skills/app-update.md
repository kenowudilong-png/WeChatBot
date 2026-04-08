---
skill_id: app-update
trigger_keywords: [更新, update, 下载, download, 镜像, mirror, 安装]
priority: 2
depends_on: [ci-release]
---

# 应用内自动更新

## 触发条件
当任务涉及修改更新检查、下载、安装逻辑时加载。

## 执行步骤
1. **检查更新**: 调用 GitHub API `releases/tags/latest` 获取最新 Release
2. **查找 APK**: 从 release.assets 中找到 `.apk` 文件
3. **镜像下载**: 依次尝试镜像前缀（HEAD 测试可用性 → 可用则下载）
   - `ghfast.top` → `ghproxy.cc` → `gh-proxy.com` → 直连
4. **下载到公共目录**: 使用 DownloadManager，目标为 `Environment.DIRECTORY_DOWNLOADS`（卸载 APP 不删除）
5. **安装 APK**: 优先用 DownloadManager 返回的 content URI；兜底用 FileProvider

## 边界情况
- 镜像可能随时失效，需要定期更新镜像列表
- Android 各版本安装 APK 权限不同（需要 REQUEST_INSTALL_PACKAGES）
- 签名不一致时无法覆盖安装（需用户先卸载旧版）

## 验证标准
- [ ] 点击"检查更新"按钮能显示最新版本信息
- [ ] APK 下载到公共 Downloads 目录
- [ ] 下载完成后自动弹出安装界面
- [ ] 卸载 APP 后 APK 文件仍在

## 常见错误
- ❌ 保存到 app-private 目录 → ✅ 用 getExternalStoragePublicDirectory
- ❌ 直连 GitHub 下载（中国太慢）→ ✅ 先尝试镜像加速
- ❌ 用 FileProvider 处理 DownloadManager 的文件 → ✅ 用 getUriForDownloadedFile
