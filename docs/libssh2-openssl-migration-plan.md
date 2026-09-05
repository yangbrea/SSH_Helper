# libssh2 + OpenSSL 完整替换 JSch 迁移计划

## 1. 文档目的

本文定义 SSH Helper 将全部 JSch 实现迁移到 `libssh2 + OpenSSL` 的分步方案。目标是在保持现有产品能力和数据兼容性的前提下，建立可维护、可测试、可回退的 Android native SSH 后端，并在验证完成后彻底移除 JSch。

本计划不使用一次性“大爆炸”替换。迁移期间保留 JSch 和 libssh2 两套后端，通过仅供开发和测试使用的后端选择开关逐步验证；新后端满足全部验收条件后，先切换默认值，再在独立提交中删除 JSch。

本文不包含工时估算。

## 2. 已确认的迁移决策

- 最终目标是移除项目中全部 JSch 依赖、类型、实现、测试命名、文档描述和第三方声明。
- 不需要兼容只支持 SHA-1 或其他过时算法的老旧 SSH 服务器。
- 禁止重新启用 `ssh-rsa` SHA-1 签名、DSA、SHA-1 KEX/MAC、CBC、3DES、Blowfish 和 RC4 作为兼容回退。
- 保留现代 RSA：`rsa-sha2-256` 和 `rsa-sha2-512` 不属于上述禁用范围。
- 迁移期间采用双后端并存策略，最终版本只保留 libssh2 后端。
- 继续支持当前两个 ABI：`arm64-v8a`、`x86_64`；本次迁移不扩展 ABI。
- Android 最低 API 保持 26，NDK 保持项目已锁定的 `29.0.14206865`。
- OpenSSL 仅作为 libssh2 的密码学后端；最终 native 库静态链接 `libcrypto.a`，不打包独立 `libssl.so` 或 `libcrypto.so`。
- Bouncy Castle 暂时保留，因为项目仍使用它生成 Ed25519 密钥；它不属于 JSch 清理范围。
- 现有数据库、凭据保险库、主机配置和 known-host 数据格式应保持兼容，不以 SSH 后端迁移为由修改用户数据格式。

## 3. 当前已有基础

### 3.1 Android native 构建基础

- 项目已经启用 Android NDK 和 CMake。
- 当前 NDK 版本为 `29.0.14206865`。
- 当前 ABI 为 `arm64-v8a` 和 `x86_64`。
- 已有 `libghostty-vt` 静态库构建、版本锁定、JNI 封装、native handle registry、host-native 测试和 APK 打包检查经验。
- `app/src/main/cpp/CMakeLists.txt` 已经处理 native shared library 和 Android 15+ 16 KB page alignment。
- `.github/workflows/android.yml` 已经包含 native 前置构建、JVM 测试、lint、APK 构建和 JNI 打包检查。

### 3.2 已下载并在本机编译的依赖

本机 `app/build/ssh-native/` 中已经存在以下基础：

- OpenSSL `3.5.8` 源码。
- 针对两个 ABI 编译的 `libcrypto.a`。
- libssh2 源码，当前检出 commit：
  `8e181e7a8e2ded832be63d2e0749d8ff8dc26cd6`。
- 针对两个 ABI 编译的 `libssh2.a`。

这些内容位于被 Git 忽略的 `app/build/` 下，只能证明本机工具链可工作，不能视为可复现构建。迁移必须把下载、校验、编译和产物验证固化为受版本控制的脚本与 lock 文件。

### 3.3 Kotlin 抽象基础

- `SshSession` 已抽象连接状态、输出、终端通道、主机密钥确认、命令执行、写入、resize 和断开。
- `SftpClient` 已抽象文件列表、属性、文件系统统计、修改、传输、续传和流式读取。
- `SftpCapableSession` 和 `PortForwardCapableSession` 已把扩展能力与基础会话分开。
- `SshSessionFactory` 已允许向 `SessionManager` 注入不同实现。
- UI、终端前端、会话复用、传输管理和大部分业务逻辑无需直接依赖具体 SSH 库。

### 3.4 已有功能和回归测试基础

现有 JSch 后端已经覆盖：

- 密码和 keyboard-interactive 认证。
- 内存私钥和私钥口令。
- known-host 首次确认和主机密钥变化阻断。
- 交互 Shell、PTY、resize。
- 带 PTY 的持久会话创建和附加。
- 独立 exec、stdout/stderr、退出码、超时和输出上限。
- SFTP 浏览、属性、statvfs、上传、下载、续传、流式预览和取消。
- HTTP、SOCKS5 连接代理。
- 单层跳板机。
- 本地、远程、动态端口转发。
- keepalive、意外断线分类和诊断日志。
- 锁屏、前台服务、凭据租约和自动重连相关策略。

已有集成测试应作为行为规范，而不是在迁移时直接删除。测试可以先改造成后端无关 contract test，再让两个后端运行相同用例。

## 4. 非目标

- 不在本次迁移中增加多层跳板链；保持当前单层 jump host 能力。
- 不新增 SSH agent、FIDO/SK、GSSAPI、X11 forwarding 或 agent forwarding。
- 不引入 FIPS 模式；如果未来需要，必须单独设计、构建和验证。
- 不恢复或默认启用弱算法来兼容老服务器。
- 不把 SSH 私钥写入临时文件；继续以内存形式传递。
- 不因 native 化而改变数据库 schema 或凭据保险库格式。
- 不把 OpenSSL、libssh2 的预编译二进制提交到 Git。
- 不将 SSH runtime 与 Ghostty 终端 runtime 合并成同一个逻辑组件。

## 5. 目标架构

```text
UI / SessionManager / Documents / TransferManager
                    │
                    ▼
     SshSession / SftpClient / Forward interfaces
                    │
                    ▼
             Libssh2SshSession
                    │
                    ▼
             NativeSshBridge (JNI)
                    │
                    ▼
           libsshhelper_ssh.so
                    │
        ┌───────────┴───────────┐
        ▼                       ▼
  Native SSH runtime       Native handle registry
  - event loop             - opaque Long IDs
  - command queue          - generation validation
  - socket polling         - idempotent close
  - channel scheduler
        │
        ▼
  libssh2.a + libcrypto.a
```

### 5.1 独立 shared library

新增 `libsshhelper_ssh.so`，与 `libsshhelper_terminal.so` 分离：

- SSH 构建失败不应改变终端核心的源代码组织。
- SSH 后端可以在不创建 Ghostty terminal 的情况下单独测试。
- 两个组件可以独立升级、诊断和审计。
- 两个 shared library 都必须满足 16 KB page alignment。

### 5.2 单 session 单 owner 原则

同一个 `LIBSSH2_SESSION` 的所有 API 调用必须由同一个 native runtime 串行驱动。不得让 Kotlin 的 shell、exec、SFTP、转发协程直接并发调用 libssh2。

每条最终 SSH transport 由一个 owner event loop 管理：

- Kotlin/JNI 向线程安全 command queue 提交请求。
- event loop 使用 non-blocking socket 和 `poll` 驱动 libssh2。
- `LIBSSH2_ERROR_EAGAIN` 是等待状态，不是业务错误。
- event loop 根据 `libssh2_session_block_directions()` 决定等待读、写或两者。
- shell、SFTP、exec、forward channel 在同一 event loop 中公平调度。
- 长文件传输不得饿死终端输入、输出或 keepalive。
- JNI 调用不得在等待网络时长期持有 JVM monitor 或 Kotlin mutex。

### 5.3 控制面和数据面

- Kotlin 保留业务状态、Flow、生命周期、UI 提示、Room 和 Android Service 控制。
- native 层负责 socket、SSH 协议状态机、channel、SFTP handle、数据泵和超时。
- 终端输出、SFTP 数据和转发数据必须批量传递，禁止逐字节 JNI 调用。
- SAF `InputStream`/`OutputStream` 保留在 Kotlin；以固定大小 ByteArray 分块与 native SFTP handle 交换数据。

## 6. Git 版本管理总则

迁移的首要要求是任何 Step 都必须可独立审查、回退和二分定位。

### 6.1 开始实施前

当前工作树已有与 Ghostty、UI、数据库等相关的未提交修改。开始迁移前必须由用户决定这些修改的归属；自动化实施不得擅自提交、stash、丢弃或改写这些内容。

允许的准备方式只有以下两类，由用户明确选择：

1. 先把现有工作整理并提交，再从确定的 commit 创建 `feat/libssh2-openssl`。
2. 先把现有工作安全保存，再基于确定的 HEAD 创建独立 Git worktree 和迁移分支。

在用户确认前，不执行 `git stash`、`git commit`、`git rebase`、`git reset` 或 worktree 创建。

### 6.2 提交纪律

- 每个 Step 至少对应一个独立提交；构建系统、功能实现、测试迁移、默认切换和删除 JSch 不混在同一提交。
- 不使用 `git add .`；只显式 stage 本 Step 的文件。
- 每次提交前记录 `git status --short`，确认没有混入用户的其他修改。
- 每个提交必须通过该 Step 定义的最小测试集。
- 依赖版本升级必须单独提交，不和业务代码修改混合。
- lock 文件每次只改变有意升级的字段，并同时更新 hash、测试记录和 notices。
- 不在共享分支上强制 push，不改写用户已有提交历史。
- 不提交 `app/build/ssh-native/`、下载压缩包、`.a`、中间对象或生成的 OpenSSL 配置。
- 对关键里程碑创建轻量 tag 或记录 commit SHA，建议命名为 `ssh-native-step-NN`。
- 遇到回归先使用 `git bisect` 定位，不通过大范围回滚掩盖问题。

### 6.3 建议的提交序列

```text
docs(ssh): add libssh2 OpenSSL migration plan
build(ssh-native): add pinned dependency build pipeline
test(ssh): extract backend contract test suite
feat(ssh-native): add JNI library and lifecycle smoke test
feat(ssh-native): add serialized nonblocking session runtime
feat(ssh-native): add direct transport and host key verification
feat(ssh-native): add password and private key authentication
feat(ssh-native): add shell pty and exec channels
feat(ssh-native): add keepalive diagnostics and disconnect mapping
feat(ssh-native): add SFTP operations and streaming
feat(ssh-native): add proxy transports
feat(ssh-native): add local remote and dynamic forwarding
feat(ssh-native): add jump host routing
test(ssh-native): complete parity and stress coverage
refactor(ssh): switch default backend to libssh2
refactor(ssh): remove JSch backend and dependency
docs(ssh): update notices and architecture after cutover
```

## 7. 分步迁移计划

## Step 0：保护现有工作并建立迁移基线

### 目标

在不损坏当前未提交工作的前提下，为迁移建立明确、可恢复的 Git 起点。

### 工作项

- 由用户检查当前 `git status --short` 和当前分支。
- 由用户决定现有未提交修改是提交、保留在原 worktree，还是另行保存。
- 从用户确认的基线 commit 创建 `feat/libssh2-openssl`。
- 保存基线 commit SHA 到迁移记录。
- 在分支说明中记录：本次迁移不兼容旧 SHA-1 SSH 服务端。
- 在开始每个后续 Step 前要求工作树只包含该 Step 的预期修改。

### 验收条件

- 基线 commit 明确且可以重新 checkout。
- 迁移分支与现有开发分支职责清晰。
- 用户的未提交修改没有被自动 stash、覆盖或提交。
- 当前测试状态被记录；已有失败与迁移新增失败能够区分。

### Git 检查点

本 Step 主要是用户控制的仓库操作，不把无关修改打包进迁移提交。

---

## Step 1：固化依赖版本和可复现构建

### 目标

让全新 clone 和 CI 不依赖本机 `app/build/ssh-native/`，能够从明确来源重建相同的 OpenSSL 和 libssh2 静态库。

### 新增或修改

- 新增 `toolchains/ssh-native.lock`，至少包含：

  ```text
  openssl_version=3.5.8
  openssl_sha256=a8f84a39918ec6415ce765d9b429d313ba97b8143169c172e734b9514464f5b2
  libssh2_commit=8e181e7a8e2ded832be63d2e0749d8ff8dc26cd6
  android_ndk_version=29.0.14206865
  android_api=26
  abis=arm64-v8a,x86_64
  crypto_backend=openssl
  legacy_algorithms=false
  ```

- 新增 `scripts/build-libssh2-android.sh`：
  - 从 lock 文件读取版本，不在脚本中重复硬编码。
  - 下载源代码时只允许 HTTPS 官方地址。
  - 校验 OpenSSL SHA-256。
  - libssh2 必须 checkout 精确 commit 并验证 HEAD。
  - 分 ABI 在独立 build directory 中构建。
  - OpenSSL 使用 `no-shared no-tests no-apps no-docs no-module`。
  - libssh2 使用 OpenSSL crypto backend、static-only、tests off、zlib off。
  - 输出写入 `app/build/ssh-native/<ABI>/` 或其他明确的 ignored 目录。
  - 构建完成后生成 `SHA256SUMS` 和构建元数据。
  - 支持重复运行；不得依赖调用者残留的 CMake cache。
- 新增 `scripts/verify-ssh-native-packaging.sh`：
  - 检查两个 ABI。
  - 检查库架构。
  - 检查不包含 host 架构对象。
  - 检查最终 `.so` 的 16 KB alignment。
  - 检查 APK 内不出现独立 `libcrypto.so`、`libssl.so`、`libssh2.so`。
- 更新 `.github/workflows/android.yml`，在 Gradle native 配置前运行依赖构建。
- 更新 `.gitignore`，明确忽略所有下载和 native build 产物，但不忽略 lock、脚本和许可证文本。
- 把 libssh2 BSD 和 OpenSSL Apache-2.0 notices 纳入 `THIRD_PARTY_NOTICES.md`。

### 不确定性门禁

当前 libssh2 使用精确的 `1.11.2_DEV` commit，而非正式 release。实施时可以继续用于开发验证，但在发布门禁前必须重新检查是否已有包含所需安全修复的正式 release：

- 若已有正式版本，先向用户报告版本、变更和测试结果，再由用户确认升级。
- 若仍无正式版本，必须向用户确认是继续发布已锁定 commit，还是等待正式 release。
- 不得静默把 lock 更新到新的 master HEAD。

### 验收条件

- 删除本机生成目录后，脚本仍可从零构建两个 ABI。
- 连续两次构建的输入版本一致；可解释的情况下产物 hash 一致。
- CI 使用 lock 指定的 NDK、OpenSSL 和 libssh2。
- Git 中没有 `.a`、`.so`、压缩包或第三方完整 build tree。

### Git 检查点

`build(ssh-native): add pinned dependency build pipeline`

---

## Step 2：建立后端无关的 contract test

### 目标

先冻结现有 JSch 行为，再写 native 实现，避免迁移过程中凭感觉改变语义。

### 工作项

- 把现有 JSch 集成测试中的连接场景抽成后端无关 fixture。
- contract test 通过 `SshSessionFactory` 创建待测后端。
- JSch 后端先运行全部 contract test，确认测试本身没有改变行为。
- 为以下行为定义明确断言：
  - 连接状态顺序。
  - host-key UNKNOWN、MATCH、CHANGED。
  - 密码错误只尝试一次。
  - 私钥错误和口令错误的用户可见错误分类。
  - shell 输出、写入、EOF、exit status。
  - PTY resize。
  - exec stdout/stderr、超时、输出上限。
  - 无 shell 的 headless transport。
  - SFTP 全部接口和错误映射。
  - 流式读取关闭后不阻塞、不泄漏、不破坏后续读取。
  - 三类转发注册、注销、重复 close 和会话断开清理。
  - HTTP/SOCKS5 代理成功、认证失败和连接失败。
  - jump/target 两跳 host key 独立验证。
  - keepalive 和意外断线状态。
- 纯业务测试继续使用 fake，不要求加载 native 库。

### 测试组织

- `SshBackendContract`：所有后端共享。
- `JschBackendContractTest`：迁移期保留。
- `Libssh2BackendContractTest`：native 后端接入后启用。
- Android/JNI 无关的状态机测试保留为 JVM unit test。
- native event loop、buffer 和 handle registry 使用 host C++ test。
- 真实协议兼容使用本地 OpenSSH 测试服务或现有 Apache SSHD fixture；测试环境必须明确记录算法配置。

### 验收条件

- JSch 后端通过抽取后的 contract test。
- contract test 不通过类名判断后端。
- 每个当前生产能力至少有一条成功测试和一条关键失败测试。

### Git 检查点

`test(ssh): extract backend contract test suite`

---

## Step 3：新增独立 native SSH target 和最小 JNI 桥

### 目标

生成可由 Android 加载的 `libsshhelper_ssh.so`，但暂不连接真实 SSH 服务。

### 新增或修改

- 在 CMake 中新增 `sshhelper_ssh` shared target。
- 链接 ABI 对应的 `libssh2.a`、`libcrypto.a`、Android `log` 和必要系统库。
- 不把 SSH 源码加入 `sshhelper_terminal` target。
- 新增：
  - `app/src/main/cpp/ssh/ssh_jni.cpp`
  - `app/src/main/cpp/ssh/ssh_runtime.*`
  - `app/src/main/cpp/ssh/ssh_error.*`
  - `app/src/main/java/com/yang136/sshhelper/ssh/native/NativeSshBridge.kt`
- JNI 初始接口仅包含：
  - `nativeVersion()`：返回 libssh2/OpenSSL 编译版本。
  - `nativeCapabilities()`：返回编译时能力和算法策略。
  - `nativeCreate()` / `nativeClose()`：验证 handle 生命周期。
- 使用 opaque `Long` registry ID，不向 Kotlin 暴露 native pointer。
- close 必须幂等；无效 handle 返回稳定错误，不允许 use-after-free。
- 所有 C++ 异常在 JNI 边界内捕获。
- 所有 native 线程明确 attach/detach JVM，或避免从 native 线程直接调用 JVM。

### 验收条件

- 两个 ABI 均生成 `libsshhelper_ssh.so`。
- Android instrumentation smoke test 可以加载并读取两个版本字符串。
- 重复 close、close(0)、错误 handle 不崩溃。
- APK 中只有项目自己的 SSH shared library，不单独携带依赖 `.so`。
- `readelf` 验证 16 KB alignment。

### Git 检查点

`feat(ssh-native): add JNI library and lifecycle smoke test`

---

## Step 4：实现 native session runtime 和事件循环

详细设计见 `docs/libssh2-step4-runtime-design.md`。实现与验收以该文档定义的
continuation、EAGAIN、poll、deadline、取消、关闭、公平调度和背压 contract 为准。

### 目标

建立后续所有功能共同依赖的串行、非阻塞、安全关闭 runtime。

### 状态模型

建议 native 状态至少包含：

```text
Created
Resolving
ConnectingSocket
ProxyHandshake
SshHandshake
AwaitingHostKeyDecision
Authenticating
Ready
Closing
Closed
Failed
```

channel 单独维护 `Opening / Active / EOF / Closing / Closed / Failed`。

### 工作项

- 每条 transport 创建单 owner event-loop thread。
- command queue 支持 connect、auth、open channel、read/write、resize、close、cancel。
- 使用 pipe 或 eventfd 唤醒 poll，禁止固定间隔 busy loop。
- 所有 libssh2 调用封装统一的 `EAGAIN` 驱动逻辑。
- 所有 request 带 request ID，Kotlin cancellation 可取消尚未完成的请求。
- close 优先打断 socket wait，再释放 channel、SFTP、listener、session、socket。
- 规定资源释放顺序并用 RAII 执行。
- event loop 实现公平调度，避免一个 SFTP handle 占满循环。
- 输出 queue 有上限和背压策略；不得无限积累 native 内存。
- 定义 error domain：system errno、proxy、SSH handshake、host key、auth、channel、SFTP、timeout、cancelled、internal。
- 不把秘密写入日志或错误文本。

### 验收条件

- host-native 测试覆盖 queue、EAGAIN、超时、取消、并发 close 和重复 close。
- ThreadSanitizer/AddressSanitizer 可用于 host build。
- event loop 空闲时不 busy-spin。
- connect 进行中 close 能及时退出。
- Kotlin coroutine 取消不会遗留 native request 或线程。

### Git 检查点

`feat(ssh-native): add serialized nonblocking session runtime`

---

## Step 5：实现 TCP、DNS 和连接代理

### 目标

在认证前完成可取消、可超时的 transport 建立，并保持当前代理语义。

### 工作项

- 实现 IPv4/IPv6 地址解析和 Happy Eyeballs 或受控顺序连接策略。
- TCP connect 使用 non-blocking socket，超时和取消由 event loop 管理。
- 设置必要的 socket options，并明确谁拥有 fd。
- HTTP proxy：
  - 实现 CONNECT。
  - 保留当前 username/password 能力。
  - 限制响应头大小。
  - 不记录 Proxy-Authorization。
- SOCKS5 proxy：
  - 支持 no-auth 和 username/password。
  - 保持远端 DNS 解析语义。
  - 校验所有长度和响应字段。
- 代理握手完成后把同一 socket 交给 libssh2 handshake。
- 诊断事件区分 DNS、TCP 和 PROXY 阶段。

### 验收条件

- 直连、HTTP、SOCKS5 成功路径通过。
- DNS 失败、连接拒绝、超时、代理认证失败均有稳定中文错误。
- 取消连接不会等待完整 timeout。
- 日志中没有代理密码或认证 header。

### Git 检查点

`feat(ssh-native): add direct and proxied socket transports`

---

## Step 6：实现 SSH 握手、现代算法策略和主机密钥确认

### 目标

完成服务端身份验证，并明确拒绝弱算法和未知/变化的 host key。

### 现代算法基线

最终精确列表应通过 `libssh2_session_supported_algs()` 与测试服务器共同验证，原则如下：

- KEX：优先 Curve25519、现代 ECDH、SHA-2 DH groups。
- Host key：Ed25519、ECDSA、RSA-SHA2。
- Cipher：ChaCha20-Poly1305、AES-GCM、AES-CTR。
- MAC：SHA-2，优先 encrypt-then-MAC；AEAD cipher 不额外使用传统 MAC。
- 明确禁用：
  - `ssh-rsa` SHA-1 签名。
  - `ssh-dss`。
  - `diffie-hellman-group1-sha1`。
  - `diffie-hellman-group14-sha1`。
  - SHA-1 group-exchange。
  - `hmac-sha1*`。
  - CBC、3DES、Blowfish、CAST、RC4。

不得在握手失败时自动降级到弱算法。错误应明确说明服务端没有双方共同支持的现代算法。

### 主机密钥流程

1. 完成 SSH transport handshake，但尚未发送用户认证凭据。
2. 使用 `libssh2_session_hostkey()` 取得完整 key blob 和类型。
3. 计算与现有数据库一致的 SHA-256 指纹和 Base64 key blob。
4. 与 `KnownHostDao` 中 hostname + port 记录比较。
5. MATCH：继续认证。
6. UNKNOWN：native runtime 进入 `AwaitingHostKeyDecision`，Kotlin 发布现有 `HostKeyRequest`。
7. CHANGED：阻断认证；保持当前必须显式忘记旧 key 的安全策略。
8. 用户确认 UNKNOWN 后，由 Kotlin 写入数据库，再通知 native 继续。
9. 用户拒绝、取消或超时后关闭 transport。

### 验收条件

- 未经 host-key 决策不会发送密码或私钥认证请求。
- target 和 jump 的 key 分开存储、展示和验证。
- key 比较使用 constant-time 比较或等效安全实现。
- 首次确认、匹配、变化、拒绝、超时全部通过 contract test。
- 仅提供弱算法的测试服务器连接失败且不会降级。

### Git 检查点

`feat(ssh-native): add modern handshake and host key verification`

---

## Step 7：实现密码、keyboard-interactive 和内存私钥认证

### 目标

覆盖当前两类 `Credential`，不产生磁盘私钥副本。

### 密码认证

- 先读取服务端公布的认证方法。
- 优先普通 password；服务端不支持时才尝试 keyboard-interactive。
- 每次用户连接动作最多提交一次密码，避免触发 PAM 锁定。
- keyboard-interactive 只实现当前产品已有的“用同一密码回答密码类 prompt”语义。
- 遇到 OTP、多因素或不可识别 prompt 时明确失败，不猜测回答。

### 私钥认证

- 使用 `libssh2_userauth_publickey_frommemory()`。
- 私钥、公钥和 passphrase 全部以内存 buffer 传递。
- 支持当前导入格式，并至少验证：
  - 项目生成的未加密 OpenSSH Ed25519 私钥。
  - 带 passphrase 的 OpenSSH 私钥。
  - 常见 RSA/ECDSA PEM 或 OpenSSH 私钥，具体以当前产品实际允许导入的格式为准。
- JNI 转换后立即清理临时密码和私钥副本。
- Kotlin 现有 `CharArray`/`ByteArray` 生命周期保持明确，使用结束后清零可控副本。
- 错误区分认证拒绝、私钥格式错误和 passphrase 错误，但不泄露底层敏感信息。

### 验收条件

- 正确和错误密码测试通过，错误密码只产生一次认证尝试。
- 项目 `KeyGenerator.generateEd25519()` 产物可直接认证。
- 私钥不写入 cache、files、tmp 或日志。
- auth 成功后清理不再需要的凭据副本。
- cancellation 和 close 能打断 auth。

### Git 检查点

`feat(ssh-native): add password and memory key authentication`

---

## Step 8：实现 Shell、PTY、持久会话和 exec

### 目标

让 libssh2 后端通过终端和远端命令 contract test。

### Shell/PTY

- 打开 session channel。
- 请求 `xterm-256color` PTY，使用当前 columns/rows。
- 请求 shell。
- 输出以批量 byte chunks 发布到现有 `Flow<ByteArray>`。
- 写入处理 partial write 和 EAGAIN，不假设一次写完。
- resize 使用 PTY size request。
- EOF、close、exit status 映射到现有 `TerminalChannelState`。
- terminal channel 关闭后 transport 仍保持可用，以支持重新打开终端和 SFTP。

### 持久会话

- 继续使用现有 `MultiplexerRegistry` 生成命令。
- 通过带 PTY 的 exec channel 创建或附加 tmux/zellij 会话。
- 保持普通 shell fallback 行为。

### Exec

- 分别读取 stdout 和 stderr。
- 合并执行总输出上限，达到上限后关闭 channel。
- deadline 到达后返回当前约定的 timeout exit code。
- 读取远端 exit status；未提供时保持现有兼容行为。
- command 内容不写入敏感诊断日志。

### 验收条件

- 交互输入、Unicode 输出、大块输出和快速开关 terminal 正常。
- resize 在高频调用下不会破坏 channel。
- exec stdout/stderr、退出码、超时、输出上限与现有行为一致。
- Shell channel 结束不误判整个 transport 已断开。
- 反复 open/close terminal 不泄漏 channel。

### Git 检查点

`feat(ssh-native): add shell pty and exec channels`

---

## Step 9：实现 keepalive、断线检测、错误映射和诊断

### 目标

保持现有用户可见状态和诊断能力，不把 libssh2 数字错误码直接暴露给 UI。

### 工作项

- 实现现有 5 秒 keepalive 间隔和失败判定语义，或以 contract test 证明调整后的语义等价。
- keepalive 由 event loop 驱动，不创建绕过 owner 的并发线程。
- 注册 disconnect/debug/trace 能力时进行脱敏和采样，release 默认不启用高噪声 packet trace。
- 将错误映射到现有阶段：TCP、PROXY、SSH_VERSION、KEX、HOST_KEY、AUTH、CHANNEL、SFTP、DISCONNECT。
- 将断线映射到现有 `DisconnectCause`。
- 限制底层错误 message 长度；移除控制字符、凭据和 key 内容。
- headless transport 必须能感知远端断开，不依赖 shell reader。
- Android 网络切换、锁屏、进程 service 生命周期保持现有上层策略。

### 验收条件

- 主动断开、远端 EOF、socket reset、keepalive timeout、读写失败分类正确。
- headless SFTP/forward session 可检测 transport 断开。
- 诊断 trace 在成功、失败、取消、用户关闭时只结束一次。
- 日志脱敏测试覆盖密码、私钥、代理凭据和控制字符。

### Git 检查点

`feat(ssh-native): add keepalive diagnostics and disconnect mapping`

---

## Step 10：完整迁移 SFTP

### 目标

提供 `SftpClient` 的完整 native 实现，并保留流式预览和取消语义。

### native handle 设计

- `NativeSftpClient` 持有逻辑 SFTP client handle，不持有裸 pointer。
- 每个 open file、directory 或 stream 使用独立 opaque handle。
- 所有 handle 最终仍由所属 session event loop 驱动。
- client close 会关闭其所有 child handles，但不关闭共享 SSH transport。
- transport close 会使所有 SFTP 请求以连接已断开结束。

### 接口映射

- `home()`：通过 realpath `.` 或等价协议能力实现。
- `realPath()`：SFTP REALPATH。
- `list()`：OPENDIR/READDIR，过滤 `.`、`..`。
- `stat()`：STAT/LSTAT。
- `fileSystem()`：statvfs extension；不支持时映射为“服务器不支持”。
- `mkdir()`、`rename()`、`delete()`、`chmod()`、`chown()`、`chgrp()`。
- `symlink()`、`readlink()`。
- rename 优先 POSIX rename extension，必要时使用标准 rename，并保持错误语义。
- recursive delete 逻辑可以继续留在 Kotlin，但底层单步调用必须原子、可取消。

### 上传和下载

- Kotlin 保持 SAF stream 所有权。
- download：native 打开远端 read handle；Kotlin 分块读取 native，再写 OutputStream。
- upload：Kotlin 分块读取 InputStream，再写 native remote handle。
- 每个 chunk 正确处理 partial read/write 和 EAGAIN。
- offset 使用 64 位 seek，覆盖大文件。
- progress 回调按节流策略发布，不按 SSH packet 回调 Room/UI。
- 用户取消时关闭 remote handle，解除 Kotlin stream 等待。

### `openRead()`

- 返回保持当前 `RemoteRead(size, InputStream)` 合约的 Kotlin wrapper。
- wrapper 内部使用有界 buffer；不得无限缓存整个远端文件。
- close 必须非阻塞，并向 event loop 提交 abort。
- read 中的后台失败必须在下一次 read 或 EOF 前传播。
- seek/重新打开不得复用仍处于 abort 中的 handle。

### 错误映射

至少覆盖：

- no such file。
- permission denied。
- connection lost/no connection。
- unsupported operation。
- generic SFTP protocol failure。
- cancelled。

### 验收条件

- `SftpClient` 所有方法通过 contract test。
- 大文件 offset 使用超过 2 GiB 的模拟或稀疏文件测试。
- shell 活跃时并行传输不会明显饿死终端。
- 并发多个独立 SFTP client 不会并发触碰同一 libssh2 session。
- 关闭预览流后能立即进行下一次读取。
- 反复取消传输不泄漏 fd、handle、thread 或 native memory。

### Git 检查点

`feat(ssh-native): add SFTP operations and streaming`

---

## Step 11：完整迁移本地、远程和动态转发

### 目标

替换 JSch 内建 forwarding 和当前直接依赖 JSch channel 的 `Socks5Server`。

### 架构决策

- Kotlin 保留规则、持久化、Service 生命周期、状态和 UI。
- forwarding socket 和 SSH channel 数据面进入 native runtime。
- 每条 forwarding connection 是 event loop 管理的双向 pump。
- 所有注册和 handle close 保持幂等。

### 本地转发 `-L`

- native 在请求 bind address/listen port 上监听。
- 接受本地连接后打开 `direct-tcpip` channel。
- 目标 hostname 在 SSH 服务端侧解析，保持 SSH local-forward 语义。
- listen port 为 0 时返回实际端口。
- close 停止 accept 并关闭全部 child connection。

### 动态转发 `-D`

- native 实现 SOCKS5 CONNECT。
- 只允许 loopback bind，保持现有安全限制。
- 只支持 TCP CONNECT；拒绝 UDP ASSOCIATE 和 BIND。
- no-auth 行为保持现状，不扩大到局域网公开代理。
- 保持最大并发连接数 64 或由同一常量统一定义。

### 远程转发 `-R`

- 使用 libssh2 remote forward listener。
- 接受 forwarded channel 后，从 Android 设备连接 `targetHost:targetPort`。
- native event loop 双向泵送 forwarded channel 与本地 target socket。
- 处理服务端分配实际端口的情况；若当前产品 UI 不允许 0，仍应在底层正确建模。
- close 取消 listener 并关闭已接受连接。

### 数据泵要求

- 两个方向都使用有界 buffer。
- 支持 half-close 和 EOF 传播。
- partial write/EAGAIN 不丢数据、不重复数据。
- 慢消费者产生背压，不无限增大队列。
- 每条连接独立失败，不应自动关闭整个 SSH session；transport 错误除外。
- session close 时 listener 和 child connection 全部释放。

### 验收条件

- 三类转发成功路径通过现有 integration test 等价用例。
- bind `127.0.0.1`、`0.0.0.0` 和端口 0 行为按规则验证。
- 重复关闭 handle 不抛异常、不崩溃。
- session 先断开、随后 handle close 仍安全。
- 并发上限和拒绝策略有效。
- 大流量和慢连接测试无死锁、无无限缓存。

### Git 检查点

`feat(ssh-native): add local remote and dynamic forwarding`

---

## Step 12：迁移单层跳板机

### 目标

在不创建明文中间 TCP 暴露的情况下，让 target SSH session 通过 jump session 的 `direct-tcpip` channel 建立。

### 推荐实现

- jump session 和 target session 由同一路由 runtime 协调，但各自保持独立 `LIBSSH2_SESSION`、host key、auth 和诊断 hop。
- 先连接、验证并认证 jump。
- 在 jump 上打开到 target hostname/port 的 `direct-tcpip` channel。
- target session 使用 libssh2 自定义 send/recv callback，把 transport I/O 映射到该 jump channel。
- target callback 返回 EAGAIN，由统一 event loop 同时推进 jump transport 和 target handshake。
- 不从 target callback 重入同一个 libssh2 session。
- target 成功后，业务 shell/SFTP/forward 都运行在 target session。
- target close 后释放 tunnel channel；完整 route close 时先 target、再 tunnel、最后 jump。

### 关键风险测试

- target banner 在 tunnel 建立初期立即发送，不得丢失。
- jump 和 target 同时 EAGAIN 时 event loop 不死锁。
- jump host key 和 target host key 分两次正确提示。
- jump 成功但 target 失败时完整清理 target/tunnel，同时不泄漏 jump。
- target 正常工作时 jump keepalive 和 transport read 仍被推进。
- jump 断线能正确归因，并让 target 与所有 channel 结束。
- jump profile 自己的 HTTP/SOCKS5 proxy 生效；target 在 jump 后不重复套用设备侧 proxy。

### 不确定性门禁

如果 custom send/recv callback 在 Android/libssh2 当前 commit 下无法稳定驱动嵌套 session，应暂停本 Step 并向用户报告 POC 结果，再选择：

1. 使用 native `socketpair` + 受控 tunnel pump 隔离 target session。
2. 调整 route event loop 结构。

不得未经用户确认直接删除 jump 功能或改成明文临时监听端口。

### 验收条件

- 现有 jump integration test 的全部场景通过 native 后端。
- 两跳独立使用密码或私钥组合。
- 两跳独立 host-key 变化均能阻断。
- target shell、SFTP 和 forwarding 都能经 jump 工作。
- 反复 connect/disconnect 无线程、channel、fd 泄漏。

### Git 检查点

`feat(ssh-native): add jump host routing`

---

## Step 13：接入 Kotlin 消费方并消除具体类型泄漏

### 目标

让所有生产调用方只依赖抽象接口，并能通过开发配置选择 JSch 或 libssh2。

### 工作项

- 新增 `Libssh2SshSession`，实现：
  - `SshSession`
  - `SftpCapableSession`
  - `PortForwardCapableSession`
- 保留现有 Flow 和 StateFlow 合约。
- `DefaultSessionManager` 继续通过 `SshSessionFactory` 注入后端。
- 把 `DocumentsBackend` 中的 `JschSshSession` 具体类型替换为接口组合或新的 backend-neutral holder。
- 把 `Socks5Server` 的 JSch 类型依赖删除；若数据面已 native 化则删除旧实现。
- 开发后端开关只允许：
  - debug build。
  - instrumentation/contract tests。
  - 明确的内部诊断入口。
- 不把双后端选择作为正式用户设置长期保留。
- session 恢复、重连、转发 Service 和 Documents provider 分别验证。

### 验收条件

- 生产业务包不 import `com.jcraft.jsch.*`，仅 JSch 迁移适配层和过渡测试允许存在。
- 同一组 contract test 能分别运行两个后端。
- 用户数据库和已保存凭据无需迁移即可连接。
- 已有 UI 不需要判断当前后端。

### Git 检查点

`refactor(ssh): route consumers through backend-neutral interfaces`

---

## Step 14：稳定性、安全性和性能验收

### 目标

在切换默认后端前证明 native 后端不仅“能连”，还适合长时间运行和异常环境。

### 稳定性矩阵

- 重复连接/断开。
- 连接中取消和关闭。
- host-key prompt 中关闭。
- auth 中网络断开。
- shell 持续输出同时上传/下载。
- shell + 多 SFTP client + forwarding 并行。
- 屏幕关闭、Doze、前后台切换。
- Wi-Fi/移动网络切换。
- 服务端重启、强制断开、半开连接。
- 本地存储 stream 提前关闭。
- 远端文件传输中取消。
- forwarding listener 与 session 同时关闭。
- jump 任一跳断开。
- App process 正常退出和系统杀进程后的恢复。

### native 安全检查

- host build 运行 ASan、UBSan；可用时运行 TSan。
- JNI 数组长度、字符串长度和 integer narrowing 全部做边界检查。
- channel/SFTP/path 长度设置合理上限。
- 所有网络输入视为不可信。
- 所有 handle 查找验证类型和 owner generation。
- 所有秘密 buffer 明确清零。
- 禁止日志输出密码、私钥、passphrase、代理凭据和完整认证 payload。
- OpenSSL legacy provider 不启用。
- 弱算法测试必须失败。

### 性能检查

- 记录 shell 输入到回显延迟。
- 对比大文件上传/下载吞吐。
- 验证并行 SFTP 时 shell 不饥饿。
- 记录空闲 session CPU 和 wakeup。
- 记录 native heap、线程数、fd 和 channel 数。
- JNI chunk 大小通过测试确定，并记录选择依据。
- APK size 增量单独记录，不用删除安全能力换体积。

### 验收条件

- contract、native unit、host integration、JVM unit、Android instrumentation 全部通过。
- 无已知 crash、use-after-free、double-free、死锁或无限等待。
- 关键并发场景反复运行稳定。
- 性能不低于产品可接受基线；若有显著退化必须先报告用户。
- 弱算法服务器无法连接，错误信息清晰。

### Git 检查点

`test(ssh-native): complete parity stress and security coverage`

---

## Step 15：切换默认后端

### 目标

把 libssh2 设为默认，但仍保留一个短暂、可回退的 JSch 提交边界。

### 工作项

- 修改默认 `SshSessionFactory` 为 `Libssh2SshSession`。
- JSch 后端仅保留给开发回归，不再作为普通运行路径。
- 运行完整 CI 和发布构建。
- 验证升级安装后已有 host、known-host、凭据、转发规则和 Documents 配置正常。
- 记录切换 commit SHA。
- 如发生回归，只需 revert 默认切换提交，不回滚此前已验证的 native 实现提交。

### 验收条件

- 默认 APK 所有 SSH 功能均走 libssh2。
- JSch contract test 仍可作为最后的行为对照运行。
- 默认切换是单独提交，可一键 revert。

### Git 检查点

`refactor(ssh): switch default backend to libssh2`

---

## Step 16：删除 JSch 和所有过渡代码

### 目标

完成“全部替换”，不留下运行时或测试时 JSch 依赖。

### 删除或重命名

- 删除 Gradle 中 `com.github.mwiede:jsch` dependency。
- 删除 `JschSshSession`。
- 删除 `JschSftpClient`，保留公共 `SftpClient`、模型和路径工具。
- 删除 `JschDiagnosticLogger`，由 native error/trace adapter 取代。
- 删除或重写依赖 JSch 的 `Socks5Server`。
- 删除 JSch 专用 proxy/jump/channel 类型。
- 将所有 `Jsch*IntegrationTest` 重命名为 libssh2 或 backend-neutral 名称。
- 将 `KeyGeneratorTest` 的“JSch 可加载”断言替换为 native libssh2 可认证/解析断言。
- 更新代码注释中关于 JSch deadlock、flush workaround、port watcher 的描述。
- 更新 README、设置页第三方组件列表和 Ghostty 迁移文档中的 JSch 描述。
- 从 `THIRD_PARTY_NOTICES.md` 删除 JSch，并保留 libssh2/OpenSSL notice。
- 删除 debug backend selector 和双后端专用 wiring。

### 静态清理门禁

以下搜索必须无生产结果；历史迁移文档若保留 JSch 字样，应明确标注为历史背景：

```bash
rg -n -i 'com\.jcraft\.jsch|mwiede|JschSshSession|JschSftpClient|JschDiagnostic' \
  app README.md THIRD_PARTY_NOTICES.md docs
```

Gradle dependency tree 必须不再包含 JSch：

```bash
./gradlew :app:dependencies | rg -i 'jsch|mwiede'
```

### 验收条件

- 编译和运行时 classpath 均无 JSch。
- 所有产品能力由 libssh2 后端通过。
- APK/Dex 中无 `com/jcraft/jsch`。
- README、设置页和 notices 与实际组件一致。
- 删除 JSch 是独立提交；若仅删除步骤失败，可以 revert，而不影响默认后端验证提交。

### Git 检查点

`refactor(ssh): remove JSch backend and dependency`

---

## Step 17：发布门禁和后续维护

### 发布前必须完成

- 重新检查 OpenSSL 3.5 LTS 最新安全 patch。
- 重新检查 libssh2 是否发布正式新版本及其安全公告。
- 对任何依赖升级单独提交并运行完整测试矩阵。
- 检查两个 ABI 的 symbols、alignment、依赖和体积。
- 生成或更新第三方许可证和版本清单。
- 确认 release 构建不启用高噪声 trace。
- 确认 release 构建不包含弱算法兼容开关。
- 确认 release APK 无未预期 native shared dependency。
- 在真实 arm64 设备完成 shell、SFTP、proxy、jump、forward 冒烟测试。
- 在 x86_64 emulator 完成 JNI 和基本连接测试。

### 持续维护

- OpenSSL 与 libssh2 版本由 lock 文件统一管理。
- 定期检查安全公告，但升级不跟随浮动 master。
- 每次升级创建独立分支和独立提交。
- 先更新一个依赖，验证后再更新另一个，避免难以定位兼容问题。
- 保留一个现代 OpenSSH 测试服务配置作为 CI fixture。
- 新增 SSH 功能必须通过 event loop command，不得绕过 owner session 原则。
- 新增 JNI handle 类型必须使用 registry，不得直接暴露 pointer。
- 新增算法必须有安全理由和互操作测试；弱算法不得以“临时兼容”方式重新加入。

### Git 检查点

`docs(ssh): finalize native SSH release and maintenance policy`

## 8. CI 最终流水线

建议最终 CI 顺序：

1. Checkout 仓库和受控 submodule。
2. 安装 lock 指定 NDK、CMake 和其他工具。
3. 校验 OpenSSL/libssh2 source identity。
4. 构建两个 ABI 的 OpenSSL/libssh2 静态库。
5. 运行 host-native unit test、ASan/UBSan test。
6. 运行 SSH backend contract/integration test。
7. 运行 JVM unit test。
8. 运行 Android lint。
9. 构建 debug、release 和 androidTest APK。
10. 检查 `libsshhelper_ssh.so` 两个 ABI、16 KB alignment 和动态依赖。
11. 检查 APK/Dex 不包含 JSch。
12. 检查第三方 notices 和 lock 信息一致。

任一步失败都阻止默认后端切换和 JSch 删除。

## 9. 完整验收清单

### 构建与版本

- [ ] 依赖来源、版本、commit 和 SHA-256 被 lock。
- [ ] 全新 clone 可重建两个 ABI。
- [ ] Git 不包含生成的第三方二进制。
- [ ] APK native 库满足 16 KB alignment。
- [ ] CI 不依赖开发机已有缓存才能成功。

### 安全

- [ ] 未确认 host key 前不发送认证凭据。
- [ ] changed host key 默认阻断。
- [ ] 密码认证单次尝试。
- [ ] 私钥只存在于受控内存。
- [ ] 日志和异常不泄露秘密。
- [ ] SHA-1、DSA、CBC、3DES、RC4 等弱算法无法协商。
- [ ] OpenSSL legacy provider 未启用。

### 功能

- [ ] 密码认证。
- [ ] keyboard-interactive 密码场景。
- [ ] 内存私钥和 passphrase。
- [ ] Shell、PTY、resize。
- [ ] 持久会话创建、附加、fallback。
- [ ] Exec、stdout/stderr、退出码、超时、输出上限。
- [ ] SFTP 全接口、续传、流式预览、取消。
- [ ] HTTP/SOCKS5 连接代理。
- [ ] 单层 jump host。
- [ ] 本地、远程、动态转发。
- [ ] keepalive、断线检测和自动重连上层策略。
- [ ] Documents provider 和后台传输。

### 稳定性

- [ ] 同一 session 无并发 libssh2 调用。
- [ ] EAGAIN 不被当成失败。
- [ ] shell/SFTP/forward 公平调度。
- [ ] close/cancel 幂等且可打断等待。
- [ ] 无线程、fd、channel、handle、native memory 泄漏。
- [ ] jump 双 session 不死锁、不重入。
- [ ] 长时间运行和网络切换测试通过。

### 清理

- [ ] Gradle 无 JSch dependency。
- [ ] 源码无 `com.jcraft.jsch` import。
- [ ] 类名、测试名和注释无现役 JSch 描述。
- [ ] APK/Dex 无 JSch。
- [ ] README、设置页和 notices 已更新。
- [ ] 临时双后端开关已删除。

## 10. 必须暂停并询问用户的情况

实施过程中出现以下情况时，不得自行改变目标，应暂停并提供证据，请用户决定：

- 正式发布时 libssh2 仍只有包含已知问题的旧 release，需要在 dev commit、补丁集和等待新 release 之间选择。
- 当前 libssh2 commit 与 OpenSSL 3.5 LTS 出现无法通过上游支持范围解释的兼容问题。
- jump host 自定义 transport callback 无法稳定工作，需要切换到 `socketpair` 架构。
- 现代算法策略导致用户实际服务器无法连接，而解决方案要求恢复弱算法。
- native 后端无法保持当前数据库或凭据格式，需要数据迁移。
- SFTP、forward 或 shell 并发模型要求改变现有用户可见行为。
- APK 体积、性能、功耗或稳定性出现显著退化，需要在能力和成本之间取舍。
- 测试只能通过降低 host-key、认证、秘密清理或弱算法安全要求来修复。
- 需要新增 ABI、FIPS、SSH agent、多因素认证或多层 jump 等范围外能力。
- 实施文件与用户当前未提交修改发生重叠，且无法安全合并。

暂停报告至少应包含：复现步骤、日志脱敏摘要、受影响功能、已尝试方案、可选路径、每条路径的兼容和安全影响。未得到用户选择前，不扩大迁移范围。
