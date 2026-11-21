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
| 输入法侧 | 实现 `onCreateInlineSuggestionsRequest()` 和 `onInlineSuggestionsResponse()`，并显示建议 | 在**搜狗进程**里 hook 这两个方法，把建议 inflate 后像 Gboard 一样显示在**键盘内部**顶部工具栏的位置（`ImeHooks.java` / `InlineController.java`） |

几个实现细节：

- **不碰搜狗的生命周期类名**：先 hook `InputMethodService.onCreate()` 拿到搜狗真正的服务子类（`SogouIME`），再沿继承链找到每个方法“实际生效”的声明去 hook。
- **原生显示（搜狗 20.10.0 已适配，`SogouNative.java`）**：通过反编译确认，搜狗键盘顶部一行的结构是
  ```
  com.sogou.core.ui.view.Toolbar (FrameLayout)
    └─ IMEInputCandidateViewContainer   绘制皮肤里的工具栏背景
         └─ ImeInputCandidateRootView   搜狗自研的虚拟组件框架
              ├─ NewIMEFunctionCandidateView   空闲时的工具栏图标（组件）
              └─ NewCandidateView              打字时的候选词（组件）
  ```
  建议栏作为**透明** View 加进 `Toolbar`；有建议时跳过工具栏图标组件和右侧“∨”按钮组件（`l1`）的绘制（`N1`/`L0`），
  皮肤背景照常由搜狗绘制。样式模仿搜狗原生的剪贴板栏：chip 完全透明，文字颜色和字号直接读皮肤里候选词的样式
  （`k.m0("CandidateWordView").x0()`，与剪贴板栏取法相同），图标染成文字色，条目之间用细竖线分隔，右侧是细线 ×。
  文字前的图标（如 Bitwarden 的地球图标）不显示；只有图标的条目（Bitwarden 的“打开密码库”盾牌，被标记为不可着色）保留。
- **回退方案**：`x1` / `N1` / `L0` 是混淆名，换搜狗版本可能失效；任何一步找不到时自动退回通用方案——不透明建议栏覆盖在键盘顶部。
- **不会让页面跳动**：两种方案都在键盘内部显示，不改变键盘高度、不修改 `contentTopInsets`。
- **样式**：Bitwarden 要求请求里带 `androidx.autofill` 的 v1 样式 Bundle，否则会回退到下拉框（`InlineStyles.java`）。
  系统渲染建议用的 Surface 是透明格式，所以半透明 chip 能透出皮肤。
- 右侧的 × 可以在当前输入框内关闭建议，恢复搜狗原本的工具栏。× 和滚动区按搜狗右侧“∨”按钮的位置对齐（皮肤里的分隔线就在它左边）。

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
| `ime: native Sogou toolbar integration ready` | 原生显示可用（没有这行、而是 `unavailable, falling back to overlay` 说明搜狗版本不匹配，使用回退方案） |
| `ime: InputMethodService implementation = ...` | 已注入搜狗进程，并找到其输入法服务类 |
| `ime: strip attached inside Sogou Toolbar` | 建议栏已放进搜狗工具栏 |
| `ime: onCreateInlineSuggestionsRequest -> ...` | 系统向搜狗索要了内联请求（没有这行说明系统侧没生效，或自动填充服务未触发） |
| `ime: onInlineSuggestionsResponse, count=N` | 收到 Bitwarden 的建议 |

常见问题：

- **完全没有 `onCreateInlineSuggestionsRequest`**：确认勾选了“系统框架”并重启；确认 Bitwarden 是当前自动填充服务且开启了内联自动填充。
- **收到响应但 `count=0`**：Bitwarden 没有匹配的条目，或者它没能识别样式（看 Bitwarden 是否改为弹出下拉框）。
- **建议文字颜色/字号和皮肤不搭**：日志 `ime: toolbar WxH skin=color=#... size=...px` 是从皮肤读到的值（读不到时 `skin=null`，退回按亮度选黑/白字），可以在 `InlineStyles.Palette.fromSkin()` 里调整。
- **建议栏点不动**：搜狗的触摸区域计算比较特殊，可以在 `InlineController.adjustInsets()` 里调整。
- 部分国产 ROM 精简了系统的 `InlineSuggestionRenderService`（ExtServices），这种情况下系统无法渲染内联建议，与本模块无关。
- 包名前缀 `com.sohu.inputmethod.sogou` 覆盖了官方版和小米版、vivo 版等渠道包；其他渠道包需在 LSPosed 里手动勾选作用域。

## 目录

```
app/src/main/java/io/github/l1uz3/sogouautofill/
├── MainHook.java          入口，按进程分发
├── SystemHooks.java       system_server：伪装 supportsInlineSuggestions
├── ImeHooks.java          搜狗进程：定位服务类并 hook 相关方法
├── SogouNative.java       搜狗 20.10.0 工具栏原生集成（版本相关）
├── InlineController.java  构造请求、在键盘内显示建议栏
├── InlineStyles.java      androidx.autofill v1 样式
└── L.java                 日志
```
