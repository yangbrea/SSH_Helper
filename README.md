# SSH Helper

一个简体中文的 Android SSH 客户端。第一版支持主机管理、密码/私钥认证、主机指纹校验、Keystore 加密凭据和单会话交互终端。

## 构建

项目固定使用 Gradle Wrapper、AGP 和 Kotlin 版本，避免不同机器上的全局 Gradle 造成依赖重复解析：

```bash
./scripts/build-debug.sh
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

常用参数：

```bash
# 完全离线构建
./scripts/build-debug.sh --offline

# 清理后构建
./scripts/build-debug.sh --clean

# 仅构建 APK，不运行测试
./scripts/build-debug.sh --skip-tests
```

## 安装到实体机

默认安装到 `192.168.2.35:43631`，覆盖旧版本并冷启动应用：

```bash
./scripts/install-debug.sh
```

也可以指定其他 ADB 地址或 USB 设备序列号：

```bash
./scripts/install-debug.sh 192.168.2.35:43631
./scripts/install-debug.sh --device DEVICE_SERIAL
```

一条命令完成测试、构建、安装和启动：

```bash
./scripts/build-and-install.sh
```

脚本支持通过 `SSH_HELPER_ADB_TARGET` 指定默认设备，通过 `SSH_HELPER_APK_PATH` 指定 APK。编译脚本始终使用项目 Wrapper 和标准 Gradle 缓存，不会创建额外的 `GRADLE_USER_HOME`。

第一次执行 Wrapper 时，如果 `~/.gradle/wrapper/dists` 中还没有 Gradle 9.6.1，Wrapper 会下载一次对应的发行包。这个下载发生在 Gradle 启动之前，所以即使传入 `--offline` 也不会被阻止；后续构建会复用该文件，不会重复下载，也不会建立第二套依赖缓存。

如果 Maven 元数据曾被错误地缓存为“不存在”，先停止旧 daemon，再仅刷新依赖：

```bash
./gradlew --stop
./gradlew --refresh-dependencies help
```

不要为普通构建指定临时 `-g`/`GRADLE_USER_HOME`，否则 Gradle 会建立另一套完整缓存。项目对 Google Maven 加了 Android/AndroidX 内容过滤，其他依赖直接走 Maven Central，避免每个依赖都在错误仓库中等待超时。

本项目还在 `pluginManagement` 中将 Android Application 插件直接映射到 `com.android.tools.build:gradle`，从而绕过部分环境中会被长期错误缓存的 AGP plugin-marker HEAD 查询。

## 使用局域网主机测试 SSH

手机可以直接把同一局域网内的开发主机作为 SSH 服务器。本机当前无线网地址可用以下命令确认：

```bash
ip -brief address
```

在 Arch Linux 上首次生成主机密钥并临时启动 OpenSSH 服务：

```bash
sudo ssh-keygen -A
sudo systemctl start sshd
```

确认需要开机自动启动后，再执行 `sudo systemctl enable sshd`。

确认服务监听和手机侧可达：

```bash
ss -lnt | grep ':22 '
adb -s 192.168.2.35:43631 shell nc -z -w 3 192.168.2.47 22
```

然后在 SSH Helper 中填写主机地址 `192.168.2.47`、端口 `22`、当前 Linux 用户名和对应密码。也可以改用专门的测试账号及私钥，避免使用日常账号。

## 终端资源

xterm.js 资源离线打包在 APK 中。修改 `terminal-web/src/index.js` 后执行：

```bash
cd terminal-web
npm install
npm run build
```

## 安全说明

- 密码、私钥和私钥口令通过 Android Keystore 的 AES-256-GCM 密钥加密。
- 未知主机必须确认 SHA-256 指纹。
- 已信任主机的密钥发生变化时会阻止连接。
- 本版不提供本机 Shell、SFTP、端口转发或后台会话保活。
