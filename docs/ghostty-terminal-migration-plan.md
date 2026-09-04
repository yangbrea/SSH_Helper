# Ghostty 终端迁移计划

> 2026-09 Beta 状态：Ghostty 继续作为可选实验后端，xterm.js 仍为默认回退。
> 已补齐句柄安全、scrollback 上限、完整调色板、搜索高亮与大小写搜索、
> 安全粘贴、远程剪贴板确认、HTTP(S) 链接、焦点/尺寸/配色协议、终端事件、
> IME 预编辑和触摸鼠标策略。JVM、宿主机测试和无设备构建会实际执行；
> Android 仪器测试源码只编译，尚未在模拟器或真机运行，因此默认切换、性能结论
> 和设备兼容性签字仍是后续独立发布门槛。Kitty 图形协议与内置 Nerd Font 延期。

### 2026-09 本轮交付清单

- [x] 线程安全 opaque handle、幂等释放、10,000 行/16 MiB scrollback 上限。
- [x] xterm-256color、尺寸/单元格/配色查询、焦点 1004、终端事件与剪贴板策略。
- [x] 48 KiB 串行写入、统一用户输入、约 4 ms 搜索分片与 generation 取消。
- [x] 快照 v4、256 色/下划线色/闪烁/软换行/搜索标记及 Canvas 交互补齐。
- [x] JVM 与宿主原生测试实际执行；Android 仪器测试已编写并仅验证编译打包。
- [x] Java 17、NDK 29.0.14206865、Zig 0.16.0 的无设备 CI 与双 ABI ELF 校验。
- [ ] 真机/模拟器行为、性能基线和设备兼容性验证（未来默认切换的发布门槛）。
- [ ] Kitty 图形协议、Ghostty 默认切换、xterm/WebView 移除（不在本轮范围）。

## 1. 目标与结论

本项目计划使用 Ghostty 的终端核心完全取代当前的 xterm.js 终端前端，同时保留现有 JSch SSH 传输、会话管理和 Jetpack Compose 应用结构。

迁移采用渐进式双后端方案：先让 xterm 与 Ghostty 并存，在 Ghostty 达到功能和稳定性要求后再移除 WebView、JavaScript Bridge、xterm.js 和对应的 npm 构建链。禁止一次性替换，任何阶段都必须保持项目可编译、可测试、可回退。

Android 端采用以下架构：

```text
JSch / SessionManager
        │ SSH PTY 字节流
        ▼
TerminalFrontend 抽象
        │
        ├── XtermTerminalFrontend（迁移期间保留）
        │       └── WebView + xterm.js
        │
        └── GhosttyTerminalFrontend
                ├── Kotlin 生命周期与背压控制
                ├── JNI Bridge
                ├── libghostty-vt
                └── Android View + Canvas/Skia 渲染与输入
```

Ghostty 的 `libghostty-vt` 负责 VT/ANSI 解析、终端状态、scrollback、搜索、选择、按键和鼠标编码以及 render state；Android 端自行负责字体、像素绘制、IME、触摸交互和 Compose 集成。

## 2. 当前项目基线

计划制定时的项目基线：

- 主项目分支：`main`
- 主项目提交：`a73092d`
- 主项目版本标签：`v1.9.0`
- `compileSdk`：36
- `minSdk`：26
- `targetSdk`：36
- Java/Kotlin JVM target：17
- Android Gradle Plugin：9.2.0
- 当前终端：WebView + xterm.js 6.0.0
- 当前 SSH 实现：JSch 2.28.0
- 当前 PTY 类型：`xterm-256color`

已验证的 Ghostty 构建基线：

```text
ghostty_commit=31bdcd5a79639bbac97c1a94e0f41d0f5ff84ca2
ghostty_library_version=0.1.0-dev
zig_version=0.16.0
android_ndk_version=29.0.14206865
simd=true
abis=arm64-v8a,x86_64
```

已完成的 arm64 编译产物检查：

- `libghostty-vt.a` 已生成，包含公开 C API 符号。
- `libghostty-vt.so.0.1.0` 为 ELF64 AArch64 动态库。
- 所有 ELF `LOAD` 段均为 `0x4000` 对齐，满足 16 KB 页要求。
- C API 头文件已生成，包含 terminal、render、search、selection、key、mouse、paste 等接口。
- 动态库的 `-Dstrip=true` 当前没有移除调试段；最终 Android JNI 动态库必须额外执行 strip 和对齐检查。

## 3. Git 版本管理原则

Git 管理是本次迁移的硬性要求，而不是收尾工作。

### 3.1 分支策略

禁止直接在 `main` 上实施迁移。使用集成分支：

```bash
git switch -c feat/ghostty-terminal
```

如果单阶段改动较大，可从集成分支继续创建短期主题分支：

```text
feat/ghostty-build
feat/ghostty-core
feat/ghostty-renderer
feat/ghostty-input
```

主题分支完成且通过对应门禁后，再合并回 `feat/ghostty-terminal`。全部迁移验证完成后才能合并到 `main`。

### 3.2 当前工作树保护

计划制定时，工作树已有以下未跟踪内容：

```text
docs/reverse-ssh-tunnel-guide.md
docs/tunnel-tutorial.md
ghostty/
output/
tmp/
```

这些内容不得被迁移提交意外带入。两份文档由其原任务单独决定是否提交；`output/` 和 `tmp/` 应纳入忽略规则。迁移过程中禁止无检查地执行：

```bash
git add .
git commit -am "ghostty migration"
```

必须显式暂存本次修改并在提交前审查：

```bash
git add <明确的文件路径>
git diff --cached
git status --short
git commit -m "<类型>(terminal): <单一目的>"
```

### 3.3 Ghostty 源码和版本锁定

不得将整个 Ghostty 源码树作为普通文件复制进主项目历史。推荐把当前 `ghostty/` 注册为 Git submodule，并锁定到已经验证的提交：

```text
31bdcd5a79639bbac97c1a94e0f41d0f5ff84ca2
```

主项目应保存一个显式锁定文件，例如 `toolchains/ghostty.lock`，至少记录：

```text
ghostty_commit=31bdcd5a79639bbac97c1a94e0f41d0f5ff84ca2
zig_version=0.16.0
ndk_version=29.0.14206865
simd=true
abis=arm64-v8a,x86_64
```

Ghostty API 尚未稳定，任何升级都必须使用独立分支和独立提交。一次升级只改变一个 Ghostty SHA，同时更新锁定文件、产物哈希和兼容性测试结果。

### 3.4 生成物策略

下列内容不得进入普通 Git 历史：

```text
.zig-cache/
zig-out/
app/build/ghostty/
*.a
构建过程中生成的 *.so
```

默认由本地脚本和 CI 根据锁定文件重新构建。若以后需要分发预编译库，使用带 SHA-256 校验的 Release artifact 或 Git LFS，不直接把多 ABI 大型二进制塞入常规提交。

### 3.5 提交质量

- 每个提交必须保持项目可编译。
- 核心行为提交必须同时添加或更新测试。
- 不得在同一提交里同时新增 Ghostty 和删除 xterm。
- 构建链、接口抽象、核心引擎、渲染、输入、清理必须分开提交。
- 依赖升级不得与功能修改混在一起。
- 历史必须可以使用 `git bisect` 定位回归。
- 合并前必须审查 `git diff main...feat/ghostty-terminal`。
- xterm 至少保留到 Ghostty 通过完整功能对等验证和一个 Beta 周期。

建议的主要提交序列：

```text
chore(terminal): establish ghostty migration baseline
build(terminal): add pinned libghostty-vt toolchain
test(terminal): add native bridge smoke coverage
refactor(terminal): introduce terminal frontend abstraction
feat(terminal-core): add ghostty terminal lifecycle and vt input
feat(terminal-core): expose render state and resize through jni
feat(terminal-core): add search selection and input encoders
feat(terminal): add native ghostty canvas renderer
feat(terminal-input): add ime and hardware keyboard support
feat(terminal-input): add scrolling selection and link handling
refactor(terminal): make ghostty the default backend
chore(terminal): remove xterm web assets and npm build
chore(deps): remove unused androidx webkit dependency
docs: update third-party notices for libghostty
```

## 4. 分阶段实施计划

## Step 1：整理 Git 工作树并建立迁移基线

### 工作内容

1. 明确处理现有未跟踪文档，避免与迁移提交混合。
2. 将 `output/`、`tmp/` 和其他生成目录加入 `.gitignore`。
3. 创建 `feat/ghostty-terminal` 分支。
4. 在不改动行为的前提下执行当前 xterm 基线测试。
5. 记录当前终端关键界面截图、性能和行为，用于迁移对照。
6. 创建 Ghostty 依赖锁定文件。
7. 将 Ghostty 作为固定 SHA 的 submodule 管理，不提交其构建缓存。

### 基线测试

```bash
./scripts/build-debug.sh
```

至少记录以下行为：

- 连接并显示普通 shell。
- 多会话切换。
- 中文输入法输入。
- 物理键盘、Ctrl 和扩展键。
- 横竖屏切换和键盘弹出时的 resize。
- scrollback、搜索、选择和复制。
- 快速连续输出时的背压提示。
- vim、tmux、htop、less 等全屏程序。

### Git 交付点

```text
chore(terminal): establish ghostty migration baseline
```

### 验收条件

- 当前 xterm 后端行为不变。
- 构建和测试通过。
- 工作树中的用户文件没有被误提交。
- Ghostty、Zig 和 NDK 版本均被明确锁定。
- 清理构建输出后可以从零重新生成 libghostty-vt。

## Step 2：建立可复现的 Android Native 构建链

### 工作内容

新增统一构建脚本：

```text
scripts/build-libghostty-android.sh
```

脚本负责：

1. 校验 Zig 精确版本为 0.16.0。
2. 校验 NDK 精确版本为 29.0.14206865。
3. 校验 Ghostty submodule SHA。
4. 分别构建 `arm64-v8a` 和 `x86_64`。
5. 使用 `-Doptimize=ReleaseFast -Dsimd=true`。
6. 将头文件和静态库放到忽略的构建目录。
7. 输出 SHA-256。
8. 构建结束后验证目标架构、公开符号和 16 KB 页对齐。

建议的输出布局：

```text
app/build/ghostty/
├── include/ghostty/
├── arm64-v8a/libghostty-vt.a
└── x86_64/libghostty-vt.a
```

新增 JNI 工程：

```text
app/src/main/cpp/
├── CMakeLists.txt
└── ghostty_terminal_jni.cpp
```

Gradle 固定 NDK 和 ABI：

```kotlin
android {
    ndkVersion = "29.0.14206865"

    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
}
```

第一版 JNI 只实现 `create`、`free` 和版本查询，暂时不连接 UI。使用静态 `libghostty-vt.a` 生成单一 Android 动态库：

```text
libsshhelper_terminal.so
```

不要直接把 `libghostty-vt.so.0.1.0` 作为最终 Android 依赖，以避免 SONAME、加载顺序和重复动态库管理问题。

### Git 交付点

```text
build(terminal): add pinned libghostty-vt toolchain
test(terminal): add native bridge smoke coverage
```

### 验收条件

- arm64 真机可以加载 JNI 库。
- x86_64 模拟器可以加载 JNI 库。
- 最终 `.so` 为 16 KB 对齐。
- Release `.so` 已 strip。
- Native handle 可以重复创建和释放，无崩溃、无明显泄漏。
- 执行构建后 `git status --short` 不出现生成物。

## Step 3：抽象终端前端并引入双后端

### 工作内容

当前 `TerminalController` 直接依赖 WebView 和 `window.sshTerminal`。先抽象与具体实现无关的接口：

```kotlin
interface TerminalFrontend {
    suspend fun write(bytes: ByteArray)
    suspend fun reset()
    fun resize(columns: Int, rows: Int)
    fun paste(text: String)
    fun search(query: String, backwards: Boolean, caseSensitive: Boolean)
    fun enterSelectionMode()
    fun selectAll()
    fun copySelection()
    fun clearSelection()
    fun setAppearance(...)
    fun close()
}
```

实现：

```text
XtermTerminalFrontend
GhosttyTerminalFrontend
```

增加仅用于开发和灰度的后端选择：

```text
terminal_backend=xterm|ghostty
```

这一阶段默认后端必须仍是 xterm。`SessionManager`、JSch 和终端屏幕上层 UI 不应知道底层使用 WebView 还是 Ghostty。

### Git 交付点

```text
refactor(terminal): introduce terminal frontend abstraction
```

### 验收条件

- 默认 xterm 行为与迁移前一致。
- 上层会话和 UI 通过统一接口操作终端。
- 切换后端不需要改变 JSch 或 SessionManager。
- 此提交可以单独回退，不影响 Native 构建层。

## Step 4：实现 Ghostty 核心 JNI 层

### 实现顺序

1. Terminal `create/free/reset`。
2. `vtWrite(ByteArray)`。
3. `resize(cols, rows, widthPx, heightPx)`。
4. render state 和 dirty rows。
5. key encoder、鼠标编码和 bracketed paste。
6. viewport 与 scrollback。
7. 增量搜索。
8. 选择手势、选择格式化和复制文本。
9. OSC 标题、响铃、工作目录和剪贴板回调。
10. 终端查询响应通过 `WRITE_PTY` 回传 JSch。

建议线程模型：

```text
JSch 输出协程
    │
    ▼
有界 Channel / 批处理队列
    │
    ▼
每个会话单一 Ghostty Engine 执行上下文
    │
    ├── libghostty-vt 状态修改
    └── 生成不可变 render snapshot
             │
             ▼
       Android 主线程 invalidate()
```

同一个 native terminal handle 不得被多个线程并发访问。Ghostty 回调不能重入调用同一个 terminal 的 VT write。所有 handle 都必须在会话关闭、页面销毁和连接异常时可靠释放。

### 背压要求

保留当前终端的有界输出队列和渲染延迟提示。JNI 不再需要 Base64，但仍需：

- 合并小块 SSH 输出。
- 限制单批字节数。
- 丢弃过期 generation 的输出。
- 在 UI 渲染落后时阻止无限积压。
- session reset 后不允许旧数据污染新屏幕。

### Git 交付点

此阶段必须拆成多个提交，禁止形成一个巨型 JNI 提交。

### 验收条件

- Headless 测试可将 ANSI/VT 字节转换成预期网格。
- resize、alternate screen 和 scrollback 行为正确。
- 搜索和选择涵盖历史缓冲区。
- 键盘编码遵守终端模式。
- JNI 错误不会导致 Kotlin 侧悬挂等待。
- ASan/内存检查或反复生命周期测试无明显泄漏。

## Step 5：实现 Android 原生 Canvas/Skia 渲染器

推荐实现自定义 Android `View`，通过 Compose `AndroidView` 嵌入，而不是将每个终端字符实现为 Compose 节点。

建议结构：

```text
GhosttyTerminalView
├── TerminalRenderer
├── TerminalInputConnection
├── TerminalGestureController
├── TerminalSelectionController
└── TerminalFontResolver
```

### 第一层渲染范围

- 背景和普通字符。
- 16 色、256 色和 True Color。
- 粗体、斜体、淡色、反色。
- 单/双/波浪/点状/虚线下划线。
- 删除线和上划线。
- 宽字符、组合字符和 grapheme。
- 光标形状、可见性和闪烁。
- dirty-row 局部刷新。
- 字体变化和 View 尺寸变化引起的网格 resize。

### 第二层渲染范围

- scrollback 与惯性滚动。
- 搜索匹配高亮。
- 选择区域和选择颜色。
- OSC 8 与 HTTP/HTTPS 链接。
- CJK、emoji 和系统字体 fallback。
- Nerd Font 私有区字符。
- Kitty 图片协议，作为后续增强，不阻塞第一轮替换。

第一版不追求移植 Ghostty 桌面的 OpenGL/Metal 渲染器。先使用 Canvas/Skia 达成功能对等，再依据基准数据决定是否投入 OpenGL/Vulkan。

### Git 交付点

```text
feat(terminal): add native ghostty canvas renderer
```

复杂功能按字体、光标、滚动、选择分别提交。

### 验收条件

- 普通 shell、vim、tmux、htop、less 可正确显示。
- 快速滚动无明显撕裂或残影。
- 横竖屏 resize 不破坏缓冲区。
- CJK 宽度与 Ghostty 网格一致。
- 连续输出时不阻塞主线程。

## Step 6：实现 IME、键盘和触摸功能对等

这是迁移风险最高的阶段。当前 xterm 前端已经专门处理键盘闪烁、点击光标才呼出输入法、滚动时保持 IME 和触摸选择，迁移时必须逐项建立测试。

### 输入功能

- Android `InputConnection`。
- 中文拼音输入和 composing text。
- `commitText`、`deleteSurroundingText` 和退格。
- 英文软键盘。
- 物理键盘 press/repeat/release。
- Ctrl、Alt、Shift 和扩展键。
- Kitty keyboard protocol。
- bracketed paste 和不安全粘贴确认。
- 输入焦点、键盘弹出与隐藏。

### 触摸功能

- 单击光标单元格请求键盘。
- 滚动和 fling。
- 长按进入选择。
- 跨行选择和自动滚动。
- 全选、复制、取消选择。
- 点击 HTTP/HTTPS 或 OSC 8 链接。
- 支持终端鼠标报告模式。

### PTY 类型

迁移期间继续使用：

```text
xterm-256color
```

不要因为底层改用 Ghostty 就立即改为 `xterm-ghostty`。后者要求远程系统具有相应 terminfo。只有建立 terminfo 部署和兼容方案后才能单独评估该变更。

### Git 交付点

IME、物理键盘、触摸选择、滚动、链接和鼠标协议分别提交，便于独立回退和 bisect。

### 验收条件

- 中文输入不丢字、不重复、不破坏组合状态。
- 键盘显示/隐藏无明显闪烁。
- 横屏输入和 resize 稳定。
- 物理键盘和扩展键行为与 xterm 一致。
- 选择、复制、搜索和链接达到当前功能水平。

## Step 7：跨平台验证与灰度切换

### 设备矩阵

```text
ABI:
  - arm64-v8a 真机
  - x86_64 模拟器

Android:
  - API 26（最低版本）
  - API 35/36
  - Android 15+ 16 KB 页环境

输入:
  - 中文 IME
  - 英文 IME
  - 物理键盘
  - 竖屏和横屏
```

### 功能矩阵

- bash/zsh 常规交互。
- vim、nano、tmux、screen。
- htop、less、man。
- 256 色和 True Color。
- alternate screen。
- CJK、emoji、组合字符和宽字符。
- 10 MB 连续输出。
- 多次快速 resize。
- 多会话反复切换。
- 网络断开、重连和会话 reset。
- scrollback、搜索、选择、复制和链接。

### 性能指标

至少比较：

- 首次终端显示时间。
- 1 MB 和 10 MB 输出处理时间。
- 主线程卡顿和掉帧。
- 峰值内存与稳定状态内存。
- 多会话内存增长。
- APK/AAB 体积变化。
- 旋转和 IME resize 延迟。

### 灰度顺序

1. Debug 构建允许手动选择 Ghostty。
2. 内部测试默认 Ghostty，仍可切回 xterm。
3. Beta 版本默认 Ghostty，保留用户回退入口。
4. 至少经历一个完整 Beta 周期。
5. 没有阻塞性回归后，才进入 xterm 清理阶段。

### Git 发布点

```bash
git tag -a v2.0.0-ghostty-beta -m "Ghostty terminal backend beta"
```

若 Beta 失败，切回后端配置即可；除非 native 构建本身导致全局问题，否则不应回退整个迁移历史。

## Step 8：移除 xterm 和 WebView

只有满足以下条件才能删除 xterm：

- Ghostty 已成为默认后端。
- 功能矩阵全部通过。
- Beta 周期未发现阻塞问题。
- xterm 回退开关没有继续保留的产品需求。
- arm64 和 x86_64 Release 包均通过 native 检查。

### 删除范围

```text
terminal-web/
app/src/main/assets/terminal/
@xterm/xterm
@xterm/addon-fit
@xterm/addon-search
@xterm/addon-web-links
esbuild
TerminalBridge JavaScript 接口
WebViewAssetLoader 终端代码
```

若项目其他功能没有使用 `androidx.webkit`，同时移除：

```kotlin
implementation("androidx.webkit:webkit:1.14.0")
```

更新：

- `README.md`
- `THIRD_PARTY_NOTICES.md`
- 构建说明
- Native 依赖和许可证说明
- 发布说明

### Git 交付点

删除和默认切换必须分成不同提交：

```text
refactor(terminal): make ghostty the default backend
chore(terminal): remove xterm web assets and npm build
chore(deps): remove unused androidx webkit dependency
docs: update terminal architecture and third-party notices
```

最终正式版本单独打标签，不移动或覆盖已有标签。

## 5. CI 门禁

迁移分支的 CI 至少包含：

1. 验证 Ghostty submodule SHA 与锁定文件一致。
2. 验证 Zig 和 NDK 版本。
3. 构建 arm64-v8a 和 x86_64 静态库。
4. 构建 Debug APK。
5. 构建 Release APK/AAB。
6. 检查最终 APK 中的 ABI。
7. 检查每个 `.so` 的 ELF 架构、依赖和 16 KB 对齐。
8. 检查 Release `.so` 不含不必要的 debug sections。
9. 运行 Kotlin 单元测试。
10. 编译 JNI 生命周期和 VT instrumentation tests 的测试 APK，但无设备 CI 不执行它们。
11. 运行不依赖 Android UI 的宿主原生入口，覆盖句柄、搜索 generation、粘贴结果和快照边界。
12. 确认构建结束没有产生应提交但未提交的锁定文件变化。

模拟器和真机 instrumentation smoke test 保留为默认后端切换前的独立发布门槛，
不属于本轮本地或 CI 验证，也不得据此宣称设备兼容性已经通过。

CI 缓存只用于加速 Zig 和 Gradle 构建，不能作为唯一依赖来源。删除缓存后仍必须能够根据锁定版本完整重建。

## 6. 风险与应对

### Ghostty C API 变动

应对：固定 commit；所有升级使用独立分支；JNI 之外的 Kotlin 层不直接依赖 Ghostty C 数据结构。

### Android 渲染性能不及预期

应对：dirty-row 渲染、行级缓存、批量 JNI snapshot；先测量再决定是否引入 OpenGL/Vulkan。

### CJK、emoji 和字体宽度不一致

应对：以 Ghostty 的 cell width 为网格事实来源；Android 字体只负责 glyph 绘制；建立 CJK/emoji golden screenshots。

### IME 回归

应对：自定义 InputConnection；保留 xterm 回退；在 API 26 和最新 API 上分别验证中文 composing 流程。

### JNI 生命周期崩溃

应对：opaque handle 封装、单线程访问、显式 close、重复 close 安全、会话 generation 隔离和 instrumentation 压力测试。

### APK 体积增长

应对：只发布 arm64-v8a 和 x86_64；使用静态链接和最终 strip；字体按需裁剪；通过 AAB 按 ABI 分发。

### 远程 terminfo 不兼容

应对：初期继续声明 `xterm-256color`；`xterm-ghostty` 作为单独项目评估，不能与渲染器切换绑定。

## 7. 推荐后续安排

### 第一轮：构建基础

完成 Step 1 和 Step 2：

- 整理 Git 状态。
- 注册 Ghostty submodule。
- 固定依赖版本。
- 构建双 ABI 静态库。
- 创建 JNI 冒烟接口。

此轮不修改现有终端 UI。

### 第二轮：双后端与核心

完成 Step 3 和 Step 4：

- 抽象 TerminalFrontend。
- 保持 xterm 为默认实现。
- 完成 Ghostty headless 状态机和 JNI 测试。

此轮目标是在没有图形终端的情况下证明 Ghostty 核心正确。

### 第三轮：渲染和输入

完成 Step 5 和 Step 6：

- 原生 Canvas/Skia 渲染。
- IME、键盘、触摸、选择、搜索和链接。
- 与 xterm 逐项对照。

### 第四轮：灰度和清理

完成 Step 7 和 Step 8：

- 跨设备验证。
- 发布 Beta。
- 观察一个完整版本周期。
- 删除 xterm 和 WebView。
- 更新许可证与文档并发布正式版本。

## 8. 完成定义

只有同时满足下列条件，本迁移才算完成：

- xterm.js、终端 WebView 和 JavaScript Bridge 已从生产代码删除。
- Ghostty 在 arm64-v8a 和 x86_64 上通过 Release 构建与运行验证。
- API 26、最新 API 和 16 KB 页环境通过验证。
- 当前搜索、选择、复制、主题、多会话、IME、扩展键和 resize 功能没有退化。
- 连续输出和多会话性能不低于迁移前可接受基线。
- Ghostty、Zig、NDK 和构建参数均可追溯并锁定。
- CI 可从干净环境完整重建所有 Native 产物。
- 所有第三方许可证和 notices 已更新。
- Git 历史保持可审查、可回退、可 bisect。
- 正式版本已创建不可变 Git tag。
