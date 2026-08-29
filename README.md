# SSH Helper

一个简体中文的 Android SSH 客户端：主机管理、密码/私钥认证、应用内 SSH 密钥生成、HTTP/SOCKS5 连接代理、单层跳板机、Keystore 加密凭据、多会话交互终端、SFTP 文件管理、端口转发（-L/-R/-D）与可选的 AI 终端助手。

## 功能特性

- **主机管理** — 保存多台服务器配置，支持密码/私钥认证，应用内一键生成 ed25519 密钥对
- **局域网发现** — 可选 SSH 或通用设备模式；通过自适应 TCP、mDNS、SSDP/UPnP 发现设备，并尽力从 ARP 缓存补充 MAC/OUI 厂商信息
- **连接方式** — 直连、单层跳板机、HTTP/SOCKS5 代理，目标机与跳板机的主机密钥独立校验
- **多会话终端** — 同一台主机可运行多个会话；终端与文件系统共享同一条 SSH 连接；支持搜索、快捷命令、选择复制
- **文件管理** — 内置 SFTP 文件浏览器与传输队列；图片/音频可在线流式预览（图片经 Coil 流式加载，音频经 Media3/ExoPlayer 边下边播并带磁盘缓存）
- **端口转发** — 本地 / 远程 / 动态（SOCKS5）隧道，支持锁屏后自动重连
- **凭据安全** — 密码、私钥、私钥口令与代理密码经 Android Keystore（AES-256-GCM）加密，可启用生物识别保险库
- **外观主题** — 四套预设深浅主题，或从图片提取配色
- **数据备份** — 服务器配置与快捷指令可导出为 JSON，并支持增量导入
- **AI 助手（可选）** — 可接入兼容服务提供终端命令建议，命令执行前需要手动确认

## 构建

项目使用固定版本的 Gradle Wrapper，无需全局安装 Gradle：

```bash
./scripts/build-debug.sh         # 构建 Debug APK 并运行单元测试
./scripts/build-and-install.sh   # 测试、构建并安装到已连接的 Android 设备
```

## 文档

- [内网穿透完整教程](docs/tunnel-tutorial.md) —— 从零配置反向 SSH 隧道(NAT 后主机公网可达),含服务器端与目标机端全流程
- [内网穿透运维速查](docs/remote-access-playbook.md) —— 已部署环境的恢复步骤与踩坑清单,含远程使用 DSH CLI
- [局域网设备发现](docs/lan-discovery.md) —— 扫描模式、协议范围、安全边界与平台限制

Release 构建的签名凭据保存在本机 `~/.android`，不进入仓库。

## 安全说明

- 密码、私钥、私钥口令与代理密码均通过 Android Keystore 的 AES-256-GCM 密钥加密
- 未知主机必须确认 SHA-256 指纹；已信任主机密钥发生变化时会阻止连接
- AI 助手的 API Key 保存在本机 DataStore；终端上下文发送前请注意内容会离开设备，且每条命令都需要确认
- 动态 SOCKS5 代理只监听本机回环地址，不提供无认证的局域网公开代理
- 局域网扫描只允许私有、CGNAT 与 IPv4 Link-local 地址段，单次最多 1024 个地址；SSH 模式最多 16 个端口，通用模式最多添加 4 个第一阶段端口；扫描结果不会持久化
- SSH Banner 仅用于协议识别，不代表服务器身份可信；首次连接仍必须核对主机密钥指纹
- Android 新版本通常禁止普通应用读取 ARP 邻居表，MAC 缺失属于预期情况，不影响 TCP/mDNS/SSDP 扫描

## 第三方组件

[xterm.js](https://xtermjs.org/) · [JSch](http://www.jcraft.com/jsch/) · Bouncy Castle · CommonMark · OkHttp · Room · Jetpack Compose
