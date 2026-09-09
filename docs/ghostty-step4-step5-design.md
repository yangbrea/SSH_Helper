# Ghostty Step 4 / Step 5 设计

本文档是 `docs/ghostty-terminal-migration-plan.md` 的补充设计，用于指导 Step 4（Ghostty 核心 JNI 层）和 Step 5（Android Canvas/Skia 渲染器第一层）的落地。

## 1. 目标

- Step 4：在 JNI 侧建立受管的 `NativeTerminal`，不再把裸 `GhosttyTerminal` 当唯一 handle；提供 `write/reset/resize`、render state、批量行/单元格快照、WRITE_PTY 回传和生命周期保护。
- Step 5：基于 Step 4 的批量快照实现第一层 Canvas 渲染：背景、普通字符、颜色、样式、光标。
- 本阶段不做 scrollback UI、搜索、选择、IME、触摸；这些继续留在后续提交。

## 2. Step 4 设计

### 2.1 为什么需要 NativeTerminal 包装对象

裸 `GhosttyTerminal` 只是一个 opaque 指针。完整终端还需要同时管理：

- `GhosttyRenderState`
- 可复用的 `GhosttyRenderStateRowIterator`
- 可复用的 `GhosttyRenderStateRowCells`
- `vt_write` 期间产生的 `WRITE_PTY` 响应字节
- 标题/响铃/目录等事件标志
- generation / reset 隔离
- closed 状态和重复 close 保护

这些状态如果散落在 Kotlin 侧，会导致生命周期难以保证；如果每个 JNI 函数都临时创建 iterator，会产生大量跨 JNI 小对象。因此设计一个 C++ 侧 `NativeTerminal` 包装对象。

### 2.2 NativeTerminal 结构

```cpp
struct NativeTerminal {
    GhosttyTerminal terminal = nullptr;
    GhosttyRenderState render_state = nullptr;
    GhosttyRenderStateRowIterator row_iter = nullptr;
    GhosttyRenderStateRowCells row_cells = nullptr;

    // vt_write 期间由 WRITE_PTY 回调收集的待发送数据。
    std::vector<uint8_t> pending_pty_writes;

    // 本次 write/reset 后产生的事件位掩码。
    uint32_t pending_events = 0;

    // 每次 reset() 递增；Kotlin 用 generation 丢弃过期输出。
    uint64_t generation = 0;

    bool closed = false;
};
```

所有 `NativeTerminal*` 以 `jlong` 形式交给 Kotlin，Kotlin 只把它当作不透明 handle。`NativeTerminal` 的创建/销毁由 JNI 负责：

- `nativeCreateManaged` 分配 `NativeTerminal` 并创建 `GhosttyTerminal`、`GhosttyRenderState`。
- `nativeFreeManaged` 标记 closed、释放所有 Ghostty 对象、删除 C++ 对象。
- 重复 free 同一 handle 是未定义行为；Kotlin 侧由受管 wrapper 保证只 close 一次，或 close 后将 handle 置 0。

### 2.3 建议 JNI 方法

```kotlin
object GhosttyNative {
    external fun nativeCreateManaged(cols: Int, rows: Int): Long
    external fun nativeFreeManaged(handle: Long)

    external fun nativeVersion(): String

    // Step 4 核心
    external fun nativeReset(handle: Long)
    external fun nativeWrite(handle: Long, data: ByteArray)
    external fun nativeResize(
        handle: Long,
        cols: Int,
        rows: Int,
        cellWidthPx: Int,
        cellHeightPx: Int,
    )

    // 渲染快照：写入直接 ByteBuffer，返回行数/状态
    external fun nativeRenderSnapshot(
        handle: Long,
        buffer: ByteBuffer,
    ): Int

    // 查询/副作用回传
    external fun nativeDrainPtyWrites(handle: Long): ByteArray
    external fun nativeDrainEventFlags(handle: Long): Int
    external fun nativeGetTitle(handle: Long): String?
    external fun nativeGetPwd(handle: Long): String?
}
```

设计约束：

- 每个 handle 同一时间只能被一个线程调用。
- `nativeWrite` 内部先清空 `pending_pty_writes`，再调用 `ghostty_terminal_vt_write`；回调产生的查询响应进入 `pending_pty_writes`。
- `nativeWrite` 不直接返回 `ByteArray`，统一由 `nativeDrainPtyWrites` 取走，避免每次 write 都分配 JNI 返回数组。
- `nativeReset` 递增 `generation`，并清空旧的 render state。
- `nativeFreeManaged` 必须保证 safe：已经 free 的 handle 不应被再次使用。

### 2.4 Render snapshot 设计（批量跨 JNI）

渲染快照必须批量编码进一个 `ByteBuffer`，避免 Canvas 每画一个 cell 都调用一次 JNI。

建议使用 expo-libghostty 已验证的“二进制行记录”思路，但字段按本项目需要精简：

```text
Header:
  int32 version
  int32 dirty_kind        // 0 none / 1 partial / 2 full
  int32 cols
  int32 rows
  int32 default_bg_argb
  int32 default_fg_argb
  int32 cursor_x          // -1 表示不可见
  int32 cursor_y
  int32 cursor_style
  int32 cursor_visible
  int32 cursor_blinking
  int32 row_record_count
  int32 generation

每条 row record:
  int32 row_index
  int32 selection_start_x // -1 表示无
  int32 selection_end_x
  int32 cell_count
  int32 text_byte_length
  cell[...]
  text_utf8[...]
```

每个 cell：

```text
int32 fg_argb
int32 bg_argb
uint16 style_flags   // bold/italic/faint/inverse/underline/strike/wide/wide_tail...
uint16 text_offset   // 指向该 row 的 text blob
uint16 text_length
uint16 pad
```

原则：

- 文本一次性按 row 拼接为 UTF-8，避免每个 cell 分配字符串。
- dirty row 只输出变化的行；full 输出用于 reset/首次快照。
- Kotlin 侧只读取 `ByteBuffer`，不回调 JNI。

### 2.5 线程与生命周期

```text
SSH output coroutine
        │ ByteArray
        ▼
NativeTerminalManager (单线程 executor / main)
        │
        ├── ghostty_terminal_vt_write
        ├── pending_pty_writes  -> 回写 JSch
        └── render_state 更新
                │
                ▼
         不可变 RenderSnapshot
                │
                ▼
       Android main thread invalidate()
```

`NativeTerminalManager` 持有：

- `handle: Long`
- `generation: Long`
- `closed: Boolean`
- 用于生命周期安全的互斥或单线程调度

Kotlin 侧不允许直接保存裸 `GhosttyNative` handle 后到处传。未来 `GhosttyTerminalFrontend` 内部持有 manager，UI 只调用前端接口。

### 2.6 Step 4 提交拆分

```text
feat(terminal-native): add managed ghostty engine lifecycle
feat(terminal-native): add vt write reset and resize
feat(terminal-native): expose immutable render snapshots
test(terminal-native): verify headless vt rendering
```

每个提交保持可编译、可测试、可回退。

### 2.7 Step 4 测试设计

由于 `libghostty-vt.a` 是 Android ABI，JVM 单测无法直接加载；headless 验证放在 instrumentation 测试：

- `GhosttyEngineLifecycleTest`
  - create/free
  - free 后 handle 不再使用
  - reset 后 generation 递增

- `GhosttyEngineVtWriteTest`
  - 写入 `hello\r\n` 后 snapshot 首行包含 `hello`
  - alternate screen 进入/退出
  - 256 色 / True Color 的颜色字段正确
  - 宽字符占用两列

- `GhosttyEngineResizeTest`
  - resize 后 cols/rows 正确
  - 主屏幕 reflow、alternate screen 不 reflow

- `GhosttyEngineRenderSnapshotTest`
  - dirty row 数量正确
  - ByteBuffer 布局可被 Kotlin 解码
  - 批量编码不依赖每 cell JNI

## 3. Step 5 第一层渲染设计

### 3.1 组件划分

```text
GhosttyTerminalView (Android View / Compose AndroidView)
├── NativeTerminalManager          // Step 4 提供
├── RenderSnapshotDecoder          // 解析 ByteBuffer 到 Kotlin 网格
├── TerminalRenderer               // Canvas/Skia 绘制
├── TerminalCellMetrics            // 字体/行高/列宽计算
├── TerminalCursorRenderer         // 光标样式/闪烁
└── (后续) InputConnection / Gesture / Selection
```

### 3.2 渲染流程

```text
Choreographer frame
        │
        ▼
nativeRenderSnapshot(handle, buffer)
        │
        ▼
RenderSnapshotDecoder.decode(buffer)
        │
        ▼
TerminalRenderer.draw(canvas)
   - 背景铺底
   - 对 dirty rows 绘制 cell
   - 覆盖光标
        │
        ▼
invalidate dirty rects
```

避免：

- 不逐 cell 调用 JNI；
- 不在 Compose 中为每个字符创建 Text composable；
- 不每次全量重绘所有历史行。

### 3.3 字体与网格

第一层只保证等宽字体正确：

- 使用 `Typeface.MONOSPACE` 或项目后续选择的等宽字体。
- 以 `Paint.measureText("M")` 计算列宽。
- 以 `Paint.fontMetrics` 计算行高和 baseline。
- 行列数变化时调用 `nativeResize(cols, rows, cellWidthPx, cellHeightPx)`。
- Ghostty 的 cell width 是网格事实来源，Android 字体只负责 glyph 绘制。

### 3.4 样式映射

根据 snapshot 的 style flags 设置 Paint：

```text
bold          -> FakeBold / bold typeface
italic        -> italic typeface
faint         -> alpha 降低
inverse       -> fg/bg 交换
underline     -> drawLine
strikethrough -> drawLine
invisible     -> 不绘制前景
wide          -> 占两列，第二列 wide_tail 不绘制
```

第一层支持 16/256/TrueColor 的背景和前景即可。

### 3.5 光标

第一层支持：

- block / underline / bar；
- 可见/隐藏；
- 闪烁（如果 Ghostty snapshot 报告 blinking）。

光标作为覆盖层绘制，不清空整行位图。

### 3.6 Step 5 第一层验收

- 普通 shell 输出可读；
- vim/tmux/htop 的 alternate screen 基本可显示；
- 256 色与 TrueColor 不串色；
- 中文/宽字符列宽与 Ghostty 一致；
- 快速连续输出不出现明显闪烁；
- 旋转和 IME 弹出导致 resize 后不丢状态。

scrollback、搜索、选择、IME、触摸、链接、Kitty 图片继续在后续提交实现。

## 4. 当前建议顺序

```text
提交 Step 3 修复
    ↓
设备/模拟器 JNI 冒烟
    ↓
Step 4：NativeTerminal 生命周期 + write/reset/resize
    ↓
Step 4：RenderSnapshot 批量快照
    ↓
Step 5：Canvas 第一层（背景/字符/颜色/样式/光标）
```
