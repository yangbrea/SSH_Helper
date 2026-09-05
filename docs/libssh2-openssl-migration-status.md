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
- Step 5 起把 DNS/TCP/proxy 和真实 libssh2 handshake/auth 等功能实现为新的 runtime
  `Operation`；当前实际连接路径仍使用保留的 blocking POC。
- host-key 确认流程仍为 blocking direct POC，尚未覆盖 jump、proxy 和 event-loop 状态机。
- keyboard-interactive 仍只覆盖单密码 prompt；OTP/多因素拒绝逻辑与错误分类待 contract 级验证。
- shell/PTY、exec 的 Kotlin `SshSession` 接入。
- SFTP、forward、jump 的 native API。
- `Libssh2SshSession` 生产实现与默认切换。
- 删除 JSch 及清理文档/notices。
- 真机/模拟器 release 门禁。

## 当前 Git 检查点
- `a31c001 feat(ssh-native): support keyboard-interactive password fallback`
- `d7ddc59 feat(ssh-native): gate direct auth behind host key verification`
（里程碑建议后续打 tag `ssh-native-step-NN`。）
