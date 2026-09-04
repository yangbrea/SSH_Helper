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
- 单 owner event-loop runtime、cancellable command queue。
- non-blocking TCP connect、HTTP CONNECT、SOCKS5 CONNECT、统一 transport 选择。
- 现代算法策略 helper。

### libssh2 实际连接 POC
- `Libssh2Session` RAII：init/session lifecycle。
- blocking handshake、password auth、hostKey()、execCommand()。
- AsyncSSH E2E host test 已跑通：
  `TCP -> SSH handshake -> password auth -> host key read -> exec -> output`.
- JNI `NativeSshBridge.nativeConnectExec()` 已暴露给 Kotlin 编译。

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
- native 非阻塞 event-loop 完整接入 libssh2（当前 POC 为 blocking）。
- host-key 持久化/确认流程 native 化。
- private key / keyboard-interactive auth。
- shell/PTY、exec 的 Kotlin `SshSession` 接入。
- SFTP、forward、jump 的 native API。
- `Libssh2SshSession` 生产实现与默认切换。
- 删除 JSch 及清理文档/notices。
- 真机/模拟器 release 门禁。

## 当前 Git 检查点
（最新提交随进度更新；里程碑建议后续打 tag `ssh-native-step-NN`。）
