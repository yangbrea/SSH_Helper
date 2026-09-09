# libssh2 Step 4：Native Session Runtime 与事件循环设计

本文档细化 `docs/libssh2-openssl-migration-plan.md` 的 Step 4，作为后续
TCP、SSH 握手、认证、channel、SFTP、forward 和 jump host 实现共同遵守的
runtime contract。

本文只完成设计，不把当前 blocking POC 直接改造成生产实现。实施时必须保持
每个提交可编译、可测试、可回退。

## 1. 目标与边界

Step 4 交付一个与具体 SSH 功能解耦的运行时，保证：

- 每个最终 SSH transport 只有一个 owner event-loop thread。
- 同一个 `LIBSSH2_SESSION` 的全部调用只发生在 owner thread，并严格串行。
- libssh2 永久工作在 non-blocking mode；`LIBSSH2_ERROR_EAGAIN` 被建模为
  continuation 的等待状态。
- command、超时、取消、完成和 close 都有确定且可测试的语义。
- event loop 由 wake fd、socket readiness 和最近 deadline 驱动，空闲时不轮询。
- request、输入、输出和事件队列有明确上限及背压。
- 关闭能立即打断 `poll()`，并按依赖关系释放资源。
- JNI 不暴露 native pointer，不从 owner thread 直接回调 JVM。

本 Step 不实现真实 DNS/TCP/proxy、host-key UI、认证、shell、SFTP、forward 或
jump host；这些功能从 Step 5 起以 `Operation` 的形式接入这里定义的调度器。

## 2. 当前基础与需要替换的行为

现有 `ssh_runtime.*` 已具备：

- 每个 `SshNativeSession` 一个线程；
- mutex 保护的生产者队列；
- non-blocking pipe 唤醒 `poll()`；
- 基础 request ID 和 queued cancellation；
- 幂等 `shutdown()`。

它仍是 Step 3 骨架，不能作为最终 Step 4 runtime，原因如下：

- `std::function<void()>` 只能“一次运行到结束”，不能在 EAGAIN 后保存状态并恢复；
- `poll()` 只监听 wake pipe，不能监听 transport fd 或 deadline；
- request 开始执行前就从 `pending_` 删除，无法取消 active request；
- shutdown 会执行所有已排队任务，而迁移计划要求 close 优先取消和清理；
- task 抛异常会越过 owner loop，可能终止线程；
- 没有 exactly-once completion、状态转换、超时、公平调度、背压和资源图。

现有 `BlockingSshConnection` 与 `Libssh2Session` 继续作为 POC/E2E 基线，直到
对应能力迁入 non-blocking runtime；Step 4 不在同一个提交中删除它们。

## 3. 已核对的 libssh2 API 约束

设计依据本机锁定源码
`app/build/ssh-native/sources/libssh2/` 中的 header、API 文档和 non-blocking
示例。实施时至少遵守以下约束：

1. 创建 session 后立即调用：

   ```cpp
   libssh2_session_set_blocking(session, 0);
   ```

   该设置会同时影响此 session 后续关联的 channel。

2. 返回 `LIBSSH2_ERROR_EAGAIN` 不是失败。必须立即调用
   `libssh2_session_block_directions()`，把
   `LIBSSH2_SESSION_BLOCK_INBOUND/OUTBOUND` 转换为 `POLLIN/POLLOUT`，等待
   readiness 后再次调用同一个 continuation。

3. 返回指针的 API（例如 `libssh2_channel_open_session()`、
   `libssh2_sftp_init()`）用 `nullptr` 表示“未得到对象”。必须用
   `libssh2_session_last_errno()` 区分 EAGAIN 和真实失败。

4. `libssh2_channel_read_ex()` 返回 `0` 只表示本次没有 payload，不能单独当作
   EOF；EOF 由 `libssh2_channel_eof()` 或 channel 状态确认。

5. `libssh2_channel_write_ex()` 可能 partial write。若返回 EAGAIN，下次必须继续
   使用原 buffer 中同一未完成片段；在该调用完成前不得换成不同 pointer/length。
   因此 outbound 数据必须归 operation 所有，不能引用 JNI 临时内存。

6. `libssh2_channel_close()`、`libssh2_channel_free()`、
   `libssh2_sftp_shutdown()`、`libssh2_session_disconnect_ex()` 和
   `libssh2_session_free()` 都可能返回 EAGAIN，清理也必须是 continuation。

7. `libssh2_session_free()` 会尝试清理遗留 channel 和 listener，但 runtime 仍需
   显式按资源依赖顺序关闭，以保留逐资源完成事件、错误归因和有界 graceful close。

8. 不使用已废弃的 `libssh2_poll()`；event loop 使用 Android/Linux 的
   `poll()`，libssh2 只提供 block-direction 信息。

相关本地文档：

- `docs/libssh2_session_set_blocking.md`
- `docs/libssh2_session_block_directions.md`
- `docs/libssh2_session_handshake.md`
- `docs/libssh2_channel_read_ex.md`
- `docs/libssh2_channel_write_ex.md`
- `docs/libssh2_channel_close.md`
- `docs/libssh2_channel_free.md`
- `docs/libssh2_sftp_shutdown.md`
- `docs/libssh2_session_disconnect_ex.md`
- `docs/libssh2_session_free.md`

其中 `docs/` 均相对于
`app/build/ssh-native/sources/libssh2/`。实现还应参考同目录
`example/ssh2_exec.c` 和 `example/sftp_*_nonblock.c`，但不能复制示例中的
busy retry。

## 4. 不可破坏的 runtime invariant

以下规则应写成断言或测试，而不只作为注释：

- **单 owner**：除构造/提交/cancel/close/waitEvent 外，runtime 内部可变状态只由
  owner thread 访问。
- **单调用门**：任何时刻最多一个线程进入任意 libssh2 API；生产者和 JNI 线程
  永远不直接调用 libssh2。
- **非阻塞**：owner thread 上不存在可能无限等待网络的 blocking libssh2 调用。
- **不重入**：libssh2 callback 只复制/读取必要数据，不从 callback 再调用同一个
  session。
- **exactly once**：每个被接受的 request 最终恰好产生一个 terminal completion：
  success、failure、timeout 或 cancelled。
- **有界内存**：command、outstanding request、输入、输出和 event queue 都有上限。
- **拥有参数**：可能跨 EAGAIN 的字符串、buffer、凭据和 callback context 均由
  operation/session 拥有。
- **close 优先**：close request 不排在普通 command 后面；它直接设置 stop flag 并
  唤醒 event loop。
- **无秘密诊断**：错误和 trace 不记录 password、private key、passphrase、代理认证
  header 或 channel payload。

Debug/host-test build 在每次调用 libssh2 前验证当前线程是 owner thread。

## 5. 组件划分

```text
JNI / Kotlin producers
        │ submit / cancel / close
        ▼
SshNativeSession                    // 线程安全 front door、handle 对象
├── CommandQueue                    // MPSC、有界、只存拥有型 payload
├── CancellationIndex               // mutex 保护，仅保存 ID -> cancellation flag
├── CompletionQueue                 // 有界；Kotlin 通过 waitEvent/drain 读取
├── WakeupFd                        // eventfd；host fallback 为 pipe2
└── EventLoopCore                   // 仅 owner thread 使用
    ├── SessionStateMachine
    ├── RequestTable
    ├── OperationScheduler          // continuation + round-robin
    ├── PollSet                     // wake、transport、后续 listener/child fd
    ├── DeadlineQueue               // CLOCK_MONOTONIC
    ├── ResourceGraph               // session/channel/SFTP/listener/socket
    └── Libssh2Facade               // EAGAIN、错误、owner 断言
```

建议文件边界：

```text
ssh_runtime.*            public front door、thread、主循环
ssh_operation.*          Operation/StepResult/IoInterest
ssh_request.*            request/completion/cancellation 类型
ssh_poll.*               WakeupFd、pollfd 构建和 readiness
ssh_resource.*           owner-only 资源及关闭顺序
ssh_error.*              稳定 error domain 和脱敏错误
```

不要求一次提交拆出所有文件，但不得继续把真实 SSH 操作塞进不可暂停的
`std::function<void()>`。

## 6. Session 与 channel 状态模型

### 6.1 Session 状态

```cpp
enum class SessionState {
    kCreated,
    kResolving,
    kConnectingSocket,
    kProxyHandshake,
    kSshHandshake,
    kAwaitingHostKeyDecision,
    kAuthenticating,
    kReady,
    kClosing,
    kClosed,
    kFailed,
};
```

主成功路径：

```text
Created -> Resolving -> ConnectingSocket -> [ProxyHandshake]
        -> SshHandshake -> AwaitingHostKeyDecision
        -> Authenticating -> Ready
```

关闭路径：

```text
任意非终态 -> Closing -> Closed
```

失败路径：

```text
任意工作态 -> Failed
Failed --close--> Closed
```

进入 `Failed` 时立即取消其他 request 并清理网络资源，但保留稳定错误快照和 event
queue，直到 Kotlin 读取或 handle 被 close。`Closed` 后只允许读取最终状态；所有新
submit 都返回 rejected。

`AwaitingHostKeyDecision` 只接受 host-key decision、cancel 和 close；不得抢先排队
认证从而绕过安全门。

### 6.2 Channel 状态

```cpp
enum class ChannelState {
    kOpening,
    kActive,
    kEofReceived,
    kClosing,
    kClosed,
    kFailed,
};
```

PTY request、shell/exec startup 是 `Opening` 的子阶段，不额外制造可被其他线程
观察到的半初始化 channel。只有完整成功后才发布 `Active` handle。

## 7. Request 与 Operation 模型

### 7.1 Request envelope

```cpp
using RequestId = uint64_t;

struct RequestEnvelope {
    RequestId id;
    CommandKind kind;
    Priority priority;
    MonoTime deadline;
    std::unique_ptr<Operation> operation;
    std::shared_ptr<CancellationState> cancellation;
};
```

- ID `0` 永远表示提交失败。
- request ID 单调分配；回绕时跳过 `0` 和 `RequestTable` 中仍存在的 ID。
- `deadline` 是绝对 monotonic time；“无超时”使用显式 sentinel，不使用巨大整数。
- payload 在 submit 时完成复制/移动。JNI array/string 返回后不得留下 borrowed pointer。
- outstanding request 上限默认为 `1024`；超限同步返回 `queue_full`，不生成 ID。

线程安全 front door 中的 `CancellationIndex` 只保存 request ID 到共享 cancellation
flag 的映射，不保存 operation 或 libssh2 状态。这样 `cancel(id)` 可以同步区分 pending
与 unknown/already-completed，同时不破坏 owner-only `RequestTable`。owner 发布 terminal
completion 后再从该 index 删除 ID。

### 7.2 Continuation 接口

```cpp
enum class StepKind {
    kMadeProgress,
    kWaitIo,
    kWaitTimer,
    kCompleted,
    kFailed,
};

struct StepResult {
    StepKind kind;
    IoInterest interest;        // WaitIo 时有效
    MonoTime wake_at;           // WaitTimer 时有效
    size_t bytes_progressed;
    SshError error;             // Failed 时有效
};

class Operation {
public:
    virtual ~Operation() = default;
    virtual StepResult step(LoopContext&, const ReadySet&) = 0;
    virtual CancelScope cancelScope() const = 0;
};
```

一次 `step()` 最多执行一个可能返回 EAGAIN 的 libssh2 API 调用，或一小段纯 CPU
工作；不能在内部 `while (rc == EAGAIN)`。

operation 必须保存自己的 phase 和调用参数。例如 channel write 保存：

```text
owned buffer + base offset + current pointer + remaining length
```

partial write 只推进 offset；EAGAIN 不改变 pointer/remaining，readiness 到来后重试。

### 7.3 Completion

```cpp
struct CompletionEvent {
    RequestId request_id;
    CompletionKind kind;        // success/failure/timeout/cancelled
    SessionState session_state;
    ResultPayload payload;
    SshError error;
};
```

terminal completion 入队和从 `RequestTable` 删除必须在 owner thread 的同一逻辑步骤
完成。`completed` 标志作为第二道防线，防止 cancel、timeout 和 socket failure 对同一
request 重复完成。

## 8. EAGAIN 统一适配

`Libssh2Facade` 不隐藏具体 API phase，但统一解释三类返回值：

```text
int API:      0 -> complete；EAGAIN -> wait；其他负值 -> failure
ssize_t API: >0 -> progress；EAGAIN -> wait；其他负值 -> failure
pointer API: non-null -> complete；null + last_errno==EAGAIN -> wait；否则 failure
```

每次收到 EAGAIN，必须在任何其他 libssh2 调用前捕获：

```cpp
const int directions = libssh2_session_block_directions(session);
```

并转换为：

```text
INBOUND  -> POLLIN
OUTBOUND -> POLLOUT
两者     -> POLLIN | POLLOUT
```

每个 suspended operation 保存自己在 EAGAIN 当下捕获的 interest；scheduler 构建
poll set 时取并集。socket readiness 到来后，只唤醒 interest 匹配的 operation。

若 EAGAIN 连续返回空 direction：

1. 允许一次立即重试，以容忍状态刚发生变化；
2. 第二次仍为空时记录不含秘密的 invariant diagnostic；
3. 将该 operation 以 `internal/eagain_without_direction` 失败。

这样不会把始终 writable 的 socket 当作 fallback 而形成 busy-spin。

## 9. Event loop 算法

主循环使用以下顺序：

```text
while not terminal:
    1. 检查 close_requested；若为 true，立即进入 Closing
    2. drain wake fd
    3. 从 CommandQueue 最多接收 64 条 command
    4. 处理 cancellation 和已到期 deadline
    5. 按优先级 + round-robin 推进 runnable operation
       - 每个 operation 每轮最多一个 step
       - 全局每轮最多 64 次 libssh2 call
       - 全局每轮最多搬运 256 KiB payload
    6. 发布 terminal completion 和状态事件
    7. 若仍有 runnable work，直接开始下一轮
    8. 计算所有 WaitIo interest 与最近 deadline
    9. poll(wake fd + transport/listener/child fds, timeout)
   10. 把 revents 转为 ReadySet；错误/hangup 优先于普通 I/O
```

`poll()` timeout：

- 有已到期 timer 或 runnable work：`0`；
- 有未来 deadline：向上取整到毫秒，并限制到 `INT_MAX`；
- 没有 timer：`-1`；
- `EINTR`：重新计算 deadline 后再 poll，不能沿用旧 timeout。

`WakeupFd` 在 Android/Linux 首选 `eventfd(EFD_NONBLOCK | EFD_CLOEXEC)`；host
兼容层可用 `pipe2(O_NONBLOCK | O_CLOEXEC)`。写入返回 EAGAIN 表示已有唤醒未消费，
不是失败。

## 10. 公平调度与背压

调度采用加权 round-robin，而不是“把一个 command 一次做完”：

```text
P0 close/cancel/transport failure    立即处理，不参与普通配额
P1 interactive shell/control        weight 4
P2 exec/forward                     weight 2
P3 bulk SFTP                        weight 1
```

无论优先级如何，等待超过一轮的 operation 都保留在轮转队列中，P3 不允许永久饥饿。
一次 operation visit 最多执行一个 libssh2 call；读取到连续数据也必须受 byte budget
限制后让出 owner。

默认水位集中定义在 `RuntimeLimits`，host test 可注入更小值：

```text
max queued commands          1024
max completion obligations   1024  // active + completed-but-not-drained
control/completion slots      1040  // 1024 completion + 16 reserved state slots
per-channel inbound high     1 MiB
per-channel inbound low      512 KiB
per-channel outbound high    1 MiB
per-channel outbound low     512 KiB
per-session inbound high     4 MiB
per-session inbound low      2 MiB
per-session outbound high    4 MiB
per-session outbound low     2 MiB
```

- inbound 达到 high watermark 后，暂停该 channel 的 `libssh2_channel_read*()`；Kotlin
  drain 到 low watermark 后通过 wake fd 恢复。
- 不能因为一个 channel 背压而停止整个 SSH socket 的协议推进；其他 channel、控制包
  和 keepalive 仍可调度。
- outbound submit 超过 high watermark 时返回 `would_block/queue_full`，Kotlin 等待
  writable-capacity event 后重试，不能静默丢弃或无限复制。
- completion/control event 不得丢弃。submit admission 使用
  `active + completed-but-not-drained < 1024`，保证所有已接收 request 都有 terminal
  completion 槽位；额外 16 个槽位保留给 `Failed/Closing/Closed` 等 session 事件。
  高频状态更新合并为“每种状态只保留最新一条”，不能耗尽保留槽位。

## 11. 取消、超时与关闭语义

### 11.1 Cancel

- queued request：owner 从 runnable queue 移除，发布一次 `cancelled`。
- active、尚未进入 libssh2 的 request：下一安全点停止，发布 `cancelled`。
- 已 EAGAIN 的 channel-scoped request：撤销 readiness interest，进入对应 channel 的
  close continuation；不必关闭整个 session。
- DNS、connect、proxy、SSH handshake 或 auth 等 transport-scoped request 被取消时，
  当前 transport 不再可复用，session 进入 Closing。
- cancel 未知或已完成 ID 返回 `not_found`；重复 cancel 不产生第二个 completion。

cancel 只是设置 flag、入队 control command 并 wake，不在调用线程释放 libssh2 资源。

### 11.2 Timeout

- 所有 timeout 使用 `CLOCK_MONOTONIC` 的绝对 deadline。
- deadline 到达与 readiness 同轮发生时，先检查 deadline；已经超时的 operation 不再
  发起新的协议调用。
- timeout 的清理 scope 与 cancel 相同，但 completion kind/error domain 为 timeout。
- 若 socket fatal error 已先被 owner 观察并完成 request，不再改写为 timeout。

### 11.3 Close

`shutdown()` 的语义改为“停止并取消”，不再 drain 普通 command：

1. 原子设置 `accepting_commands=false` 和 `close_requested=true`；
2. 写 wake fd，立即打断无限期 `poll()`；
3. owner 发布所有 queued/active request 的 cancelled completion；
4. 停止 listener/accept，禁止产生新 child resource；
5. 以依赖顺序推进有界 graceful close：

   ```text
   SFTP file/dir handles
   shell / exec / forward child channels
   SFTP sessions
   remote forward listeners
   libssh2_session_disconnect_ex
   libssh2_session_free
   transport socket
   wake/poll resources
   ```

6. graceful close 默认预算为 `500 ms`，测试可注入；各 close/free 的 EAGAIN 仍通过
   poll 推进；
7. 预算耗尽时先关闭 transport fd，使网络等待失效，再完成本地
   `libssh2_session_free()` 清理；任意非 EAGAIN 返回后 session pointer 立即置空，绝不
   二次 free；
8. 发布 `Closed`，唤醒等待 completion 的 Kotlin 线程，然后 owner thread 退出。

外部线程调用 `shutdown()` 可以 join。owner thread 内触发 close 只能启动关闭流程，
不得 join 自己。析构发生在外部持有者释放最后一个 `shared_ptr` 后。

## 12. 资源所有权

`EventLoopCore` 是所有网络/SSH 资源的唯一 owner：

```text
RouteRuntime
└── Transport
    ├── fd
    └── LIBSSH2_SESSION
        ├── Channels
        ├── SftpSessions -> SftpHandles
        └── RemoteForwardListeners -> AcceptedChannels
```

- registry handle 只指向 `SshNativeSession`，不为每个 libssh2 pointer 单独暴露裸地址。
- channel/SFTP/listener 对 Kotlin 暴露的 ID 使用 session-local generation ID；关闭
  session 会使全部 child ID 失效。
- RAII destructor 是异常兜底，不承担需要网络往返的正常 close；正常路径由 owner
  continuation 显式执行。
- socket 只有一个 owner。交给 session runtime 后，旧 transport builder 必须把 fd
  置为无效，防止双重 close。
- Step 12 的 jump target 可以增加自定义 transport readiness provider，但不能破坏
  `Operation`、owner 和 completion contract。

## 13. 错误模型与诊断

扩展 `ErrorDomain`：

```cpp
enum class ErrorDomain {
    kNone,
    kInvalidHandle,
    kSystem,
    kDns,
    kProxy,
    kSshHandshake,
    kHostKey,
    kAuth,
    kChannel,
    kSftp,
    kTimeout,
    kCancelled,
    kInternal,
};
```

稳定错误对象包含：

```text
domain, stable_code, request_id, session_state, operation_kind,
libssh2_code(optional), errno(optional), sanitized_message
```

调用 `libssh2_session_last_error(..., want_buf=0)` 后立即复制 message；不跨下一次
libssh2 调用保留 borrowed pointer。用户可见分类依赖 domain/stable_code，不解析英文
message。日志只记录长度、计数、状态、code、deadline 和 fd readiness，不记录 payload
或凭据。

所有 `Operation::step()` 异常在主循环边界捕获并转换为 `internal` failure；异常不能
逃出 owner thread。

## 14. JNI / Kotlin 完成通道

Step 4 采用 pull completion，避免 native owner thread attach JVM 和持有 Kotlin
对象：

```kotlin
external fun nativeCancel(handle: Long, requestId: Long): Boolean
external fun nativeAwaitEvent(handle: Long, timeoutMillis: Long): NativeSshEvent?
external fun nativeClose(handle: Long)
```

后续各功能增加 `nativeSubmitXxx(...): Long`，只负责复制参数并返回 request ID。
内部 submit 返回 `SubmitResult{id, error}`；JNI 对 `queue_full/closed/invalid_state` 使用
带稳定 code 的同步异常，成功时保证 ID 非 0。`0` 只作为 JNI 异常路径的防御性返回值，
Kotlin 不把它当成已接受的 request。

Kotlin 每个 session 启动一个 `Dispatchers.IO` collector 调用 `nativeAwaitEvent()`；该
函数等待 native condition variable，不持有 Java monitor，也不等待网络。coroutine
取消时调用 `nativeCancel()` 并 wake；session scope 结束时调用 `nativeClose()`。

`nativeClose()` 可能等待 owner 完成最长 graceful-close 预算，因此也必须从 IO
dispatcher 调用。registry 先移除 handle，阻止新 JNI 请求；已取得的
`shared_ptr<SshNativeSession>` 仍安全存活到当前 JNI 调用结束。

大数据不塞进 control completion。Step 8/10/11 使用单独的有界 chunk queue/direct
buffer drain API，但仍沿用本设计的水位和 wake 机制。

## 15. 可测试性接口

Step 4 必须允许 host test 注入：

- `Clock`：推进虚拟 monotonic time；
- `Poller`：返回脚本化 readiness/EINTR/error；
- `Wakeup`：统计 wake/drain；
- `Libssh2Driver`：脚本化 success/EAGAIN/partial/error 和 block directions；
- `RuntimeLimits`：使用很小水位触发边界。

生产实现使用 system clock、`poll()` 和真实 libssh2。测试不能依赖 `sleep()` 猜测时序；
只有“close 能打断真实 poll”测试允许用短的、带上限的 wall-clock 断言。

## 16. Step 4 测试矩阵

### Queue / owner

- 多生产者提交，所有 operation 只在一个 owner thread 执行。
- 两个 session 使用不同 owner thread，状态和 request ID 不串扰。
- queue/outstanding 上限返回稳定 `queue_full`。
- operation 抛异常只失败该 request，不终止 event loop。

### EAGAIN / poll

- int、pointer、ssize 三类 API 的 EAGAIN 都转换为正确 interest。
- INBOUND、OUTBOUND、两者分别生成正确 poll mask。
- 无 readiness 时不重复调用 continuation、不 busy-spin。
- readiness 到达后从原 phase 恢复。
- partial write + EAGAIN 保持原未完成 buffer pointer/length。
- pointer API 的 null + 非 EAGAIN 映射为 failure。
- 连续空 block direction 触发 invariant failure，而非 POLLOUT 自旋。

### Timeout / cancel

- queued cancel、active cancel、EAGAIN cancel 都 exactly once。
- timeout 从 fake clock 精确触发，不依赖 wall clock。
- cancel/timeout/readiness 同时到达时结果遵守既定优先级。
- transport-scoped cancel 关闭 session；channel-scoped cancel 不误伤其他 channel。

### Close / lifecycle

- close 立即打断无限期 poll。
- close 不执行尚未开始的普通 command。
- 并发 close、重复 close、close(0)、未知 handle 均安全。
- close 顺序符合资源依赖；每个可能 EAGAIN 的 close/free 可恢复。
- graceful budget 到期后强制关闭 fd，无 thread/fd/native-memory 泄漏。
- owner 内部失败触发 close 时不 self-join。

### Fairness / backpressure

- 持续 bulk operation 下 interactive operation 仍在有限轮次内执行。
- 单 operation 每轮 call/byte budget 生效。
- inbound 达到 high watermark 后暂停，低于 low watermark 后恢复。
- outbound 超限不丢数据、不无限分配。
- completion event 在 data queue 饱和时仍可交付。

### Sanitizer

- host Debug build 运行 ASan/UBSan。
- queue/cancel/close 并发套件运行 TSan；TSan 与 ASan 分开执行。
- 至少执行 1,000 次 create/close 和 10,000 次 submit/cancel 压力循环。

## 17. 实施拆分

建议 Step 4 使用以下独立提交：

```text
refactor(ssh-native): model runtime requests and continuations
feat(ssh-native): poll transport readiness and monotonic deadlines
feat(ssh-native): add cancellation completion and bounded shutdown
feat(ssh-native): add fair scheduling and queue backpressure
test(ssh-native): stress serialized nonblocking runtime
```

实施顺序：

1. 先增加 fake `Clock/Poller/Libssh2Driver` 和新的 request/completion 类型。
2. 把现有 `std::function<void()>` 测试迁到一次完成的 `Operation` adapter。
3. 接入 continuation、deadline 与 socket poll，但仍只跑 fake driver。
4. 修改 shutdown：普通队列改为取消，加入有界 close state machine。
5. 加入公平性和水位测试。
6. 最后用一个真实 socketpair/libssh2 handshake harness 验证 wake + EAGAIN 驱动；真实
   DNS/TCP 建连仍留给 Step 5。

## 18. Step 4 完成定义

只有同时满足以下条件，迁移进度才能把 Step 4 标为“实现完成”：

- runtime 不再以不可暂停的 `std::function<void()>` 作为生产 operation 模型；
- fake driver 证明 EAGAIN 后不会 busy retry，socket readiness 后可恢复；
- request completion exactly once，取消和 timeout 不遗留 request；
- close 能从无限期 poll 中及时退出，且不执行未开始的普通 command；
- 队列和数据缓冲存在可触发、可恢复的背压；
- 公平调度测试证明 bulk operation 不饿死 interactive operation；
- ASan/UBSan/TSan 目标可运行并通过 Step 4 测试集；
- Android `nativeCreate/nativeClose` smoke test 继续通过；
- blocking POC 的现有 E2E 测试继续通过，证明本 Step 未破坏已建立的协议基线。

设计文档完成不等于 Step 4 实现完成；在上述门禁全部通过前，迁移状态仍应记录为
“Step 4 设计完成，non-blocking runtime 实现未完成”。

## 19. 实施结果

Step 4 已按本文 contract 实现：production `Operation` continuation、poll/readiness、
deadline、active/queued cancellation、exactly-once completion、weighted scheduler、背压、
有界资源关闭、libssh2 返回值适配及 JNI/Kotlin pull-event 边界均已落地。真实
DNS/TCP/proxy/SSH 业务 operation 仍按边界留给 Step 5 及后续步骤。

完成时已通过 runtime/libssh2 adapter host tests、ASan/UBSan、TSan、双 ABI Android
Debug 构建，以及保留的 handshake、host-key、keyboard-interactive 和 proxy E2E 基线。
