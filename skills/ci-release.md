---
skill_id: ci-release
trigger_keywords: [CI, CD, build, release, 构建, 发布, 签名, keystore, APK]
priority: 2
depends_on: []
---

# CI/CD 构建与发布

## 触发条件
当任务涉及修改构建流程、签名配置、版本发布时加载。

## 执行步骤
1. **修改代码后**：
   - 递增 `app/build.gradle` 中的 `versionCode`
   - 更新 `versionName`（语义化版本）
2. **提交并推送到 main 分支**：
   - GitHub Actions 自动触发 `.github/workflows/build.yml`
   - 构建 debug APK → 上传 artifact → 创建 Release（tag: latest）
3. **签名配置**：
   - 当前：CI 中用 keytool 生成临时 keystore（每次不同 ⚠️）
   - TODO：应将 keystore base64 编码存入 GitHub Secrets，CI 中解码使用
4. **验证构建**：
   - 检查 GitHub Actions 运行状态：`gh run list --limit 3`
   - 等待完成：`gh run watch <run-id> --exit-status`

## 边界情况
- keytool 即使参数相同，每次生成的密钥材料不同 → 签名不一致 → 覆盖安装失败
- Release 使用固定 tag "latest"，每次先删后建

## 验证标准
- [ ] GitHub Actions 构建成功（绿色）
- [ ] Release 页面有最新 APK
- [ ] 手机上能通过应用内更新下载并安装（签名一致）

## 常见错误
- ❌ 忘记递增 versionCode → ✅ 每次修改代码后必须递增
- ❌ 依赖 keytool 临时生成 keystore → ✅ 应持久化到 GitHub Secrets
- ❌ 缺少 `permissions: contents: write` → ✅ workflow job 级别添加
