# Sogou Inline Autofill

LSPosed 模块，让**搜狗输入法**支持 Android 11+ 的**键盘内联自动填充**：Bitwarden 等密码管理器的账号建议直接显示在搜狗工具栏里。

> 仅供个人学习研究使用。原生显示针对搜狗 20.10.0 (2550) 适配，其他版本会自动退回通用显示方式。

## 功能

- 建议显示在搜狗自己的工具栏里，背景是当前皮肤，文字颜色和字号跟随皮肤。
- 打拼音时自动让位给候选词；右侧 × 可在当前输入框内关闭建议。
- 名称太长时截短名称，保证用户名可见。
- 建议出现/消失不改变键盘高度，页面不跳动。

## 使用

1. Android 11+，已 root 并安装 LSPosed。
2. 从 [Releases](../../releases) 下载 APK 安装，启用模块，作用域勾选 **系统框架** 和 **搜狗输入法**，然后**重启手机**。
3. Bitwarden：设置 → 自动填充 → 开启自动填充服务和 **使用内联自动填充**。

## 排查

LSPosed → 日志 → 模块日志，搜索 `[SogouInlineAutofill]`：

- 没有 `system_server: ...`：没勾选“系统框架”或没重启。
- 有 `unavailable, falling back to overlay`：搜狗版本不匹配，使用通用显示方式。
- 没有 `ime: onCreateInlineSuggestionsRequest`：检查 Bitwarden 是否为当前自动填充服务并开启了内联自动填充。
- 部分 ROM 精简了系统的内联建议渲染服务（ExtServices），这种情况无法使用。

## 构建与发版

- 推送到 `main` 或 PR 时自动构建，APK 在 Actions 的 Artifacts 里；本地构建：`./gradlew assembleDebug`（Android SDK 35、JDK 17+）。
- 在网页上发布 `v*` 标签的 Release，会自动构建并附加 APK，说明取自 `RELEASE_NOTES.md`。
- 固定签名：在仓库 Secrets 里配置 `SIGNING_KEYSTORE_BASE64`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`，未配置时使用 debug 签名。
- 清理旧构建记录：Actions → **Clean up old runs** → Run workflow。
