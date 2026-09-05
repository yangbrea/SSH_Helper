# libssh2 + OpenSSL 迁移进度记录

Branch: `feat/libssh2-openssl`
Base: `2ea89ff`（commit current workspace checkpoint 后创建）

## 已完成

### 构建与依赖
- `toolchains/ssh-native.lock` 固定 OpenSSL 3.5.8 / libssh2 commit / NDK 29 / API 26 / 双 ABI。
- `scripts/build-libssh2-android.sh` 可复现构建双 ABI 静态库并生成 SHA256SUMS/BUILD_INFO。
- `scripts/verify-ssh-native-packaging.sh` 校验架构、16 KB alignment、无第三方 `.so`。
- CI 已接入 OpenSSL/libssh2 构建与 SSH host tests。

### Native runtime / transport
- `libsshhelper_ssh.so` 独立 shared library。
- Step 4 production runtime 已实现：每 transport 单 owner thread、`Operation::step()`
  continuation、wake fd + socket `poll()`、monotonic deadline、active/queued cancel、
  exactly-once completion 与有界 shutdown。
- 三档 weighted scheduler 防止 bulk 饿死 interactive；command/completion obligation 和
  `WatermarkedBuffer` 提供可恢复背压。
- 资源按 SFTP handle、channel、SFTP session、listener、libssh2 session、socket 的依赖
  顺序关闭；graceful close 超时后执行有界 force-close。
- `ssh_libssh2_nonblocking.*` 统一分类 int/count/pointer API 的 success、EAGAIN 与 failure，
  EAGAIN 后立即复制 `libssh2_session_block_directions()`，失败后立即复制 last-error。
- JNI/Kotlin 已提供 `nativeCancel()`、`nativeAwaitEvent()` 和不可变 `NativeSshEvent`，owner
  thread 不直接回调 JVM。
- non-blocking TCP connect、HTTP CONNECT、SOCKS5 CONNECT、统一 transport 选择。
- 现代算法策略 helper。
- Step 4 的 production runtime 设计已固化在
  `docs/libssh2-step4-runtime-design.md`：定义 continuation/EAGAIN、poll、deadline、
  exactly-once completion、取消、关闭、资源所有权、公平调度、背压及 sanitizer 门禁。
- Step 4 host tests、ASan/UBSan、TSan、双 ABI `assembleDebug` 和现有 SSH E2E 均通过。
- 已开始把真实 libssh2 调用迁入 runtime `Operation`：
  - `TcpConnectOperation`：non-blocking TCP connect（DNS 在 producer 线程，connect/poll 在 owner），host test 通过。
  - `Libssh2HandshakeOperation`：non-blocking handshake + host key 读取，E2E 通过。
  - `TcpHandshakeOperation`：从 host:port 直连并读取 host key（不认证），E2E 通过。
  - completion 已包含 `fingerprint` / `keyType` / `keyBase64` 三字段。
  - `Libssh2PasswordAuthOperation`：non-blocking password auth，E2E 通过。
  - `Libssh2PrivateKeyAuthOperation`：non-blocking in-memory private key auth，E2E 通过。
  - `Libssh2PasswordExecOperation`：non-blocking password auth + exec + stdout，E2E 通过。
  - `TcpPasswordExecOperation`：从 host:port 到 exec 的完整 direct runtime 路径（TCP connect + handshake + password auth + exec），E2E 通过。
  - `TcpPrivateKeyExecOperation`：从 host:port 到 exec 的完整 direct runtime 路径（TCP connect + handshake + in-memory private key auth + exec），E2E 通过。
  - direct runtime exec 已同时读取 stdout/stderr，E2E 覆盖 stderr-only 命令。
  - direct runtime exec 已支持 max_output_bytes 输出上限，超限返回 exit=125，E2E 通过。
  - `HttpProxyConnectOperation`：non-blocking HTTP CONNECT runtime operation，host test 通过。
  - `Socks5ProxyConnectOperation`：non-blocking SOCKS5 CONNECT runtime operation（no-auth/user-pass），host test 通过。

### libssh2 实际连接 POC
- `Libssh2Session` RAII：init/session lifecycle。
- blocking handshake、password auth、publicKeyAuth()、hostKey()、execCommand()。
- `BlockingSshConnection` 保持“已完成 handshake 但未认证”的连接，供 host-key 确认后再发送凭据。
- AsyncSSH E2E host test 已跑通：
  `TCP -> SSH handshake -> password auth -> host key read -> exec -> output`.
- host-key gate E2E 已跑通：先读取 type/fingerprint/keyBase64，通过后再 password auth + exec。
- JNI 暴露 `nativeOpenDirectHandshake()` + host-key 读取 + `nativeDirectPasswordExec()` / `nativeDirectPrivateKeyExec()`。

### host-key 确认流程（direct blocking POC）
- `Libssh2SshSession` 在认证前从 native 读取 host key，与 `KnownHostDao` 比较。
- UNKNOWN 发布 `HostKeyRequest`，用户接受后写库再认证；MATCH 不重复提示；CHANGED 阻断连接并保留请求。
- native host-key type 映射修正为真实 libssh2 常量（ssh-rsa、ecdsa-sha2-nistp384/521、ssh-ed25519）。

### 认证（blocking POC）
- password auth 和 in-memory private key auth 已有 E2E。
- keyboard-interactive fallback 已实现：先读服务器 auth 方法，仅当无 plain password 时用同一密码回答单个 keyboard-interactive prompt；E2E 已跑通。

### runtime Operation 当前覆盖（direct/proxy/exec）
- non-blocking TCP connect
- non-blocking HTTP CONNECT（含 Basic auth）
- non-blocking SOCKS5 CONNECT（no-auth / user-pass）
- direct runtime 完整链路：password / private key → handshake → exec
  - stdout/stderr/exit code
  - output limit → exit=125
  - JNI runtime exec 支持 deadline，超时映射 exit=124
  - direct runtime exec 支持 expected fingerprint：不匹配认证前失败（E2E），匹配可正常 exec（E2E）
  - direct runtime password exec 支持 keyboard-interactive fallback（kbdint-only E2E 通过）
- host tests 与 Android assembleDebug 均通过
- JNI 已暴露 `nativeRunTcpHandshake()` / `nativeRunDirectPasswordExec()` / `nativeRunDirectPrivateKeyExec()`（含 expected fingerprint、deadline）/ `nativeRunHttpProxyConnect()` / `nativeRunSocks5ProxyConnect()`，Kotlin 可直接调用 runtime 路径。
- `Libssh2SshSession` 的 host-key 探测已从 blocking direct-handshake POC 切换到 runtime `TcpHandshakeOperation`：
  - connect/首次确认前先 `runTcpHandshake()` 读取 fingerprint/keyType/keyBase64。
  - 确认/匹配后使用 `runDirectPasswordExec` / `runDirectPrivateKeyExec` 执行 `true` 验证认证。
  - Kotlin 新增 `parseRuntimeTcpHandshakePayload()` 纯函数解析，JVM unit test 覆盖。

### 后端无关 contract suite（JSch 侧）
- 已有 14 个共享 contract tests 通过：
  - direct connect + host-key UNKNOWN/MATCH/CHANGED
  - wrong password 单次尝试
  - headless exec stdout、timeout、output limit
  - shell transport stability
  - SFTP lifecycle
  - local / remote / dynamic forwarding
  - HTTP CONNECT / SOCKS5 proxy

## 尚未完成（按计划顺序）
- 把 HTTP/SOCKS5 proxy operation 与 SSH handshake/auth/exec 串成完整代理路径。
- runtime 内 UNKNOWN host-key 交互决策（首次确认状态机）。
- shell/PTY、持久会话。
- SFTP 全功能 native 化。
- 本地/远程/动态转发 native 化。
- jump host native 化。
- `Libssh2SshSession` 生产接入与默认切换。
- JSch 删除与文档/notices 清理。
- 稳定性/安全/性能验收与真机/模拟器 release 门禁。

## 当前 Git 检查点
- `72682d7 feat(ssh): route Libssh2SshSession host key probe through runtime`
- `835cf7c feat(ssh-native): expose TcpHandshakeOperation through JNI and NativeSshRuntime`
- `9b8fd0a feat(ssh-native): add TcpHandshakeOperation host key probe`
- `2dde306 docs(ssh): record runtime payload parser tests`
- `c9f4e95 test(ssh): extract and unit-test runtime exec payload parser`
- `d1e5801 docs(ssh): record runtime keyboard-interactive fallback`
- `7311e7c feat(ssh-native): add keyboard-interactive fallback to direct runtime password exec`
- `cf7aa5e docs(ssh): record NativeSshRuntime wrapper`
- `257931f refactor(ssh): add NativeSshRuntime handle wrapper`
- `0978b77 docs(ssh): record host key match E2E coverage`
- `c986e81 test(ssh-native): verify expected host key match allows runtime exec`
- `8abb172 feat(ssh): surface runtime host key mismatch as changed-key error in exec`
- `3031661 feat(ssh): route Libssh2SshSession exec through runtime JNI when host key known`
- `069efac docs(ssh): record Libssh2SshSession runtime exec routing`
- `22a1858 feat(ssh-native): expose expected host key fingerprint through JNI exec bridge`
- `896c8f9 feat(ssh-native): enforce expected host key in direct runtime exec`
- `6ef9dee feat(ssh-native): map runtime exec deadline to exit 124 in JNI`
- `c73e952 feat(ssh-native): expose proxy connect operations through JNI bridge`
- `140fd44 docs(ssh): record JNI direct runtime bridge`
- `d633bd1 feat(ssh-native): expose direct runtime exec through JNI bridge`
- `d6194dc feat(ssh-native): add nonblocking SOCKS5 CONNECT runtime operation`
- `ec12616 feat(ssh-native): add nonblocking HTTP CONNECT runtime operation`
（里程碑建议后续打 tag `ssh-native-step-NN`。）
