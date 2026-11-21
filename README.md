# Sogou Inline Autofill

一个 LSPosed（Xposed）模块，让**搜狗输入法**支持 Android 11+ 的**键盘内联自动填充**（Inline Suggestions），
这样 Bitwarden 等密码管理器的账号建议可以直接显示在键盘上方，而不是弹出下拉框。

> 仅供个人学习研究使用。开发时针对的搜狗版本是 20.10.0 (2550)，但模块只 hook Android 框架类，
> 不依赖搜狗内部的类名，理论上其他版本也能用。

## 原理

Android 11 引入了内联自动填充：自动填充服务（Bitwarden）把建议交给系统渲染，再由**输入法**把渲染好的
`InlineContentView` 放到自己的界面里。要走通这条链路需要两件事，搜狗都没有做：

| 环节 | 正常要求 | 本模块的做法 |
| --- | --- | --- |
| 系统侧 | 输入法在 `input-method` XML 里声明 `android:supportsInlineSuggestions="true"`，否则 `InputMethodManagerService` 不会向输入法索要请求 | 在 **system_server** 里把搜狗的 `InputMethodInfo.mInlineSuggestionsEnabled` 改为 `true`，并 hook `isInlineSuggestionsEnabled()` 兜底（`SystemHooks.java`） |
| 输入法侧 | 实现 `onCreateInlineSuggestionsRequest()` 和 `onInlineSuggestionsResponse()`，并显示建议 | 在**搜狗进程**里 hook 这两个方法，把建议 inflate 后放进插在键盘上方的一条横向建议栏（`ImeHooks.java` / `InlineController.java`） |

几个实现细节：

- **不碰搜狗的类名**：先 hook `InputMethodService.onCreate()` 拿到搜狗真正的服务子类，再沿继承链找到每个方法“实际生效”的声明去 hook。
  这样搜狗即使重写了 `onComputeInsets()` 且不调用 `super`，hook 也有效。
- **建议栏位置**：插在输入法窗口里 `android:id/inputArea`（承载搜狗键盘的 FrameLayout）前面，不修改搜狗自己的 View 层级。
- **Insets 修正**：在搜狗算完 `Insets` 之后把建议栏区域加进可见/可触摸区域，否则建议栏点不动、或者会挡住输入框。
- **样式**：Bitwarden 要求请求里带 `androidx.autofill` 的 v1 样式 Bundle，否则会回退到下拉框，所以依赖了 `androidx.autofill:autofill`（`InlineStyles.java`）。
  建议条目的外观（背景色、字号等）只能通过这个样式影响，跟随系统深色模式。

## 使用

1. 环境：Android 11 及以上、已 root、已安装 LSPosed。
2. 安装 APK，在 LSPosed 中启用模块，作用域勾选：
   - **系统框架**（必须，否则系统不会向搜狗发起内联请求）
   - **搜狗输入法**
3. **重启手机**（`InputMethodInfo` 在开机时解析并缓存，只重启搜狗不够）。
4. Bitwarden：设置 → 自动填充 → 打开“自动填充服务”，并打开 **“使用内联自动填充”**。
5. 打开任意 App 的登录框，用搜狗输入，键盘上方应出现 Bitwarden 的账号建议。

## 构建

推送到 GitHub 后 Actions 会自动构建，产物在 workflow 的 Artifacts 里。本地构建：

```bash
./gradlew assembleDebug   # 需要 Android SDK (compileSdk 35) 和 JDK 17+
```

## 排查

LSPosed 管理器 → 日志，搜索 `[SogouInlineAutofill]`：

| 日志 | 说明 |
| --- | --- |
| `system_server: hooked InputMethodInfo constructors` | 系统框架作用域生效 |
| `system_server: forced supportsInlineSuggestions=true for ...` | 搜狗的输入法信息已被修改 |
| `ime: InputMethodService implementation = ...` | 已注入搜狗进程，并找到其输入法服务类 |
| `ime: onCreateInlineSuggestionsRequest -> ...` | 系统向搜狗索要了内联请求（没有这行说明系统侧没生效，或自动填充服务未触发） |
| `ime: onInlineSuggestionsResponse, count=N` | 收到 Bitwarden 的建议 |

常见问题：

- **完全没有 `onCreateInlineSuggestionsRequest`**：确认勾选了“系统框架”并重启；确认 Bitwarden 是当前自动填充服务且开启了内联自动填充。
- **收到响应但 `count=0`**：Bitwarden 没有匹配的条目，或者它没能识别样式（看 Bitwarden 是否改为弹出下拉框）。
- **建议栏显示但点不动 / 被遮挡**：搜狗的 `onComputeInsets` 行为比较特殊，可以在 `InlineController.adjustInsets()` 里调整。
- 部分国产 ROM 精简了系统的 `InlineSuggestionRenderService`（ExtServices），这种情况下系统无法渲染内联建议，与本模块无关。
- 包名前缀 `com.sohu.inputmethod.sogou` 覆盖了官方版和小米版、vivo 版等渠道包；其他渠道包需在 LSPosed 里手动勾选作用域。

## 目录

```
app/src/main/java/io/github/l1uz3/sogouautofill/
├── MainHook.java          入口，按进程分发
├── SystemHooks.java       system_server：伪装 supportsInlineSuggestions
├── ImeHooks.java          搜狗进程：定位服务类并 hook 相关方法
├── InlineController.java  构造请求、显示建议栏、修正 Insets
├── InlineStyles.java      androidx.autofill v1 样式
└── L.java                 日志
```
