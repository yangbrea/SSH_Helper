# 反向 SSH 隧道安全配置教程

> 适用场景：一台 Linux 目标机位于 NAT 后、没有可直接访问的公网地址，但可以主动连接一台公网 VPS。你希望使用 SSH Helper 从手机访问目标机的终端、SFTP 或本地服务。
>
> 本文最后审校于 2026-09-01。所有地址、用户名和密钥均为占位符，不包含实际环境信息。

## 先选架构

本文提供两种方式：

1. **首选：VPS 回环监听 + SSH Helper 跳板机**
   - 反向端口只监听 VPS 的 `127.0.0.1:22022`。
   - 手机先登录 VPS，再由 SSH Helper 通过跳板通道访问该回环端口。
   - 不需要向公网开放 22022，也不需要启用 `GatewayPorts`。
2. **备选：公网直连 VPS:22022**
   - 反向端口监听 VPS 的公网接口。
   - 必须同时配置 sshd、主机防火墙和云安全组，并限制允许访问的来源。
   - 只应在客户端不能使用跳板机或存在明确公网直连需求时采用。

首选方案的拓扑：

```text
手机上的 SSH Helper
        │
        │ SSH 登录 VPS（跳板）
        ▼
公网 VPS ── 127.0.0.1:22022
        ▲
        │ 目标机主动建立 -R 127.0.0.1:22022:127.0.0.1:22
        │
NAT 后的目标机 sshd:22
```

## 占位符

| 占位符 | 含义 |
|---|---|
| `<VPS_HOST>` | VPS 的域名或公网 IP |
| `<VPS_SSH_PORT>` | VPS 自身的 SSH 端口，通常是 22 |
| `<VPS_USER>` | 手机正常登录 VPS 的账户 |
| `<TARGET_USER>` | 目标机上的日常账户 |
| `<CLIENT_CIDR>` | 公网直连方案允许访问 22022 的来源 IP/CIDR |
| `tunnel` | VPS 上仅用于建立反向转发的专用账户 |
| `22022` | VPS 上的反向监听端口，可换成未占用的 1024-65535 端口 |

## 密钥和账户规划

不要让同一把私钥承担所有角色：

| 用途 | 私钥保存位置 | 公钥安装位置 |
|---|---|---|
| 目标机建立反向隧道 | 目标机 `~/.ssh/id_ed25519_reverse_tunnel` | VPS 的 `tunnel` 账户 |
| 手机登录 VPS 跳板 | SSH Helper 保险库 | VPS 的 `<VPS_USER>` 账户 |
| 手机登录最终目标 | SSH Helper 保险库中的另一把密钥 | 目标机的 `<TARGET_USER>` 账户 |

公钥可以分发，私钥不能。不要把目标机私钥复制到手机，也不要把任何私钥粘贴到命令、聊天或工单中。

## 一、准备目标机

### 1. 启动目标机 sshd

Arch Linux：

```bash
sudo systemctl enable --now sshd
systemctl is-active sshd
```

Ubuntu / Debian：

```bash
sudo systemctl enable --now ssh
systemctl is-active ssh
```

Fedora / RHEL 系：

```bash
sudo systemctl enable --now sshd
systemctl is-active sshd
```

检查监听并记录目标机主机指纹：

```bash
ss -lnt | grep ':22 '
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```

以后通过反向端口连接时，SSH 握手仍由目标机 sshd 完成，所以客户端应看到这枚目标机指纹。

### 2. 生成专用隧道密钥

在目标机执行：

```bash
install -d -m 700 ~/.ssh
ssh-keygen -t ed25519 -a 64 \
  -f ~/.ssh/id_ed25519_reverse_tunnel \
  -C 'reverse-tunnel-to-vps'
chmod 600 ~/.ssh/id_ed25519_reverse_tunnel
```

无人值守服务如果不使用 `ssh-agent`，通常需要让这把专用密钥不带口令。该密钥只能通过 VPS 端的最小权限配置使用，不应复用于日常登录。

### 3. 安装手机到目标机的公钥

在 SSH Helper 中单独生成或导入一把用于登录目标机的 ed25519 密钥，然后把其公钥整行追加到目标机：

```bash
install -d -m 700 ~/.ssh
printf '%s\n' '<PHONE_TO_TARGET_PUBLIC_KEY>' >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
```

## 二、准备 VPS

VPS 应运行仍受支持的操作系统和现代 OpenSSH。CentOS Linux 7 已于 2024-06-30 结束生命周期，不应继续作为新部署的公网 SSH 跳板基础。

### 1. 创建专用隧道账户

```bash
sudo useradd --create-home --shell /bin/bash tunnel
sudo passwd tunnel
sudo install -d -m 700 -o tunnel -g tunnel /home/tunnel/.ssh
sudo install -m 600 -o tunnel -g tunnel /dev/null \
  /home/tunnel/.ssh/authorized_keys
```

为账户设置一个强随机密码，是为了避免某些系统把锁定账户整体拒绝。下一节会强制该账户只接受公钥，密码不用于远程登录。

把目标机专用隧道公钥安装到 VPS：

```bash
cat ~/.ssh/id_ed25519_reverse_tunnel.pub
```

复制输出的公钥整行，然后在 VPS 上执行：

```bash
printf '%s\n' '<REVERSE_TUNNEL_PUBLIC_KEY>' | \
  sudo tee -a /home/tunnel/.ssh/authorized_keys >/dev/null
sudo chown tunnel:tunnel /home/tunnel/.ssh/authorized_keys
sudo chmod 600 /home/tunnel/.ssh/authorized_keys
```

### 2. 限制 tunnel 账户

创建 `/etc/ssh/sshd_config.d/60-reverse-tunnel.conf`：

```sshconfig
Match User tunnel
    AuthenticationMethods publickey
    PasswordAuthentication no
    AllowTcpForwarding remote
    PermitListen 127.0.0.1:22022
    PermitTTY no
    X11Forwarding no
    AllowAgentForwarding no
```

如果系统不读取 `sshd_config.d`，先检查 `/etc/ssh/sshd_config` 中的 `Include` 配置，再把等价内容放到实际生效的配置文件中。

校验并查看最终生效值：

```bash
sudo sshd -t
sudo sshd -T \
  -C user=tunnel,host="$(hostname)",addr=127.0.0.1 | \
  grep -E 'authenticationmethods|passwordauthentication|allowtcpforwarding|permitlisten|permittty'
```

只有 `sshd -t` 成功后才能重载：

```bash
sudo systemctl reload sshd 2>/dev/null || sudo systemctl reload ssh
```

如果 OpenSSH 太旧而不识别 `PermitListen`，不要删除限制后继续，应先升级系统或 OpenSSH。

### 3. 准备手机登录 VPS 的账户

把 SSH Helper 中“手机到 VPS”密钥的公钥安装到 `<VPS_USER>` 的 `authorized_keys`。该账户需要允许客户端创建 `direct-tcpip` 通道；如果 VPS 全局禁用了 TCP 转发，可对该账户显式允许本地转发：

```sshconfig
Match User <VPS_USER>
    AllowTcpForwarding local
```

修改后同样先运行 `sudo sshd -t`，再重载 sshd。

## 三、手动建立首选隧道

### 1. 带外核对 VPS 主机指纹

先在 VPS 控制台执行：

```bash
sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```

再从目标机首次连接 VPS：

```bash
ssh -p <VPS_SSH_PORT> \
  -i ~/.ssh/id_ed25519_reverse_tunnel \
  -o IdentitiesOnly=yes \
  tunnel@<VPS_HOST>
```

逐字比较指纹，确认一致后才接受。不要只因为域名或 IP 看起来正确就信任主机密钥。

### 2. 前台启动一次性隧道

```bash
ssh -p <VPS_SSH_PORT> \
  -i ~/.ssh/id_ed25519_reverse_tunnel \
  -o IdentitiesOnly=yes \
  -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=30 \
  -o ServerAliveCountMax=3 \
  -N -T \
  -R 127.0.0.1:22022:127.0.0.1:22 \
  tunnel@<VPS_HOST>
```

命令保持运行且没有输出是正常现象。

另开终端登录 VPS 检查：

```bash
ss -lnt | grep ':22022 '
nc -vz 127.0.0.1 22022
```

预期监听地址是 `127.0.0.1:22022`，而不是 `0.0.0.0:22022`。

通过反向端口读取目标主机指纹：

```bash
ssh-keyscan -p 22022 127.0.0.1 2>/dev/null | ssh-keygen -lf -
```

该指纹必须与目标机本地记录的 sshd 指纹一致。`ssh-keyscan` 只用于读取和比较，不能替代带外核验。

## 四、使用 systemd 常驻保活

在目标机创建 `~/.config/systemd/user/reverse-ssh-tunnel.service`：

```ini
[Unit]
Description=Reverse SSH tunnel to VPS loopback port 22022
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/bin/ssh -p <VPS_SSH_PORT> -i %h/.ssh/id_ed25519_reverse_tunnel -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o ConnectTimeout=15 -N -T -R 127.0.0.1:22022:127.0.0.1:22 tunnel@<VPS_HOST>
Restart=always
RestartSec=10

[Install]
WantedBy=default.target
```

`ExecStart` 必须保持一行。启动服务前，应已通过上一节的交互连接核对并保存 VPS 主机密钥。

启用服务，并允许用户服务在未登录时运行：

```bash
systemctl --user daemon-reload
systemctl --user enable --now reverse-ssh-tunnel.service
sudo loginctl enable-linger <TARGET_USER>
```

检查状态和日志：

```bash
systemctl --user status reverse-ssh-tunnel.service
journalctl --user -u reverse-ssh-tunnel.service -n 80 --no-pager
```

保活参数的作用：

- `ServerAliveInterval=30` 和 `ServerAliveCountMax=3`：连接约 90 秒无响应后退出。
- `ExitOnForwardFailure=yes`：远端监听创建失败时立即退出，避免“进程存在但隧道不可用”。
- `Restart=always`：网络恢复、VPS 重启或连接中断后由 systemd 重建进程；手动 `stop` 不会触发重启。
- `network-online.target` 只影响启动阶段，运行中的网络变化仍靠 SSH 退出和 systemd 重试恢复。

## 五、配置 SSH Helper

### 1. 新建 VPS 跳板主机

| 字段 | 填写内容 |
|---|---|
| 名称 | 例如“公网 VPS” |
| 地址 | `<VPS_HOST>` |
| 端口 | `<VPS_SSH_PORT>` |
| 用户名 | `<VPS_USER>`，不要使用专门建立 `-R` 的 `tunnel` 账户 |
| 认证 | 手机到 VPS 的独立私钥 |
| 跳板机 | 关闭；跳板主机本身必须直连 |

### 2. 新建最终目标主机

| 字段 | 填写内容 |
|---|---|
| 名称 | 例如“家中 Linux（反向隧道）” |
| 地址 | `127.0.0.1` |
| 端口 | `22022` |
| 用户名 | `<TARGET_USER>` |
| 认证 | 手机到目标机的独立私钥 |
| 通过跳板机连接 | 开启，并选择“公网 VPS” |

首次连接需要分别核对两枚指纹：

1. 跳板机确认框应显示 VPS 的主机指纹。
2. 目标机确认框应显示目标机 sshd 的主机指纹。

地址 `127.0.0.1` 是从 VPS 的视角解释的，不是手机自身的回环地址。

### 3. 访问目标机上的 Web 服务

连接最终目标主机后，可在 SSH Helper 添加本地转发：

| 字段 | 值 |
|---|---|
| 类型 | 本地 `-L` |
| 监听地址 | `127.0.0.1` |
| 监听端口 | `3080` |
| 目标主机 | `127.0.0.1` |
| 目标端口 | `3080` |

规则运行后，在手机浏览器打开 `http://127.0.0.1:3080`。

## 六、备选：公网直连 VPS:22022

只有明确需要时才使用本节方案。移动网络出口 IP 经常变化时，首选的“回环 + 跳板”通常更安全也更稳定。

### 1. 修改 VPS sshd

在任何 `Match` 块之前设置全局选项：

```sshconfig
GatewayPorts clientspecified
```

把 `tunnel` 用户的限制改为：

```sshconfig
Match User tunnel
    AuthenticationMethods publickey
    PasswordAuthentication no
    AllowTcpForwarding remote
    PermitListen 0.0.0.0:22022
    PermitTTY no
    X11Forwarding no
    AllowAgentForwarding no
```

校验并重载：

```bash
sudo sshd -t
sudo systemctl reload sshd 2>/dev/null || sudo systemctl reload ssh
```

不要使用 `GatewayPorts yes`。`clientspecified` 允许每条隧道显式选择监听地址，范围更可控。

### 2. 修改目标机隧道参数

把手动命令或 systemd 的 `-R` 参数替换为：

```text
-R 0.0.0.0:22022:127.0.0.1:22
```

### 3. 限制公网入口

必须同时配置：

- VPS 主机防火墙：只允许 `<CLIENT_CIDR>` 访问 TCP 22022。
- 云安全组：添加 TCP 22022 入方向规则，源为 `<CLIENT_CIDR>`。
- VPS 自身 SSH 入口：仍需允许目标机出口和手机管理来源访问。

ufw 示例：

```bash
sudo ufw allow from <CLIENT_CIDR> to any port 22022 proto tcp
sudo ufw status numbered
```

firewalld 示例：

```bash
sudo firewall-cmd --permanent --add-rich-rule=\
'rule family="ipv4" source address="<CLIENT_CIDR>" port port="22022" protocol="tcp" accept'
sudo firewall-cmd --reload
sudo firewall-cmd --list-rich-rules
```

不要把 `0.0.0.0/0` 当作长期默认来源。

### 4. SSH Helper 直连字段

| 字段 | 填写内容 |
|---|---|
| 地址 | `<VPS_HOST>` |
| 端口 | `22022` |
| 用户名 | `<TARGET_USER>` |
| 认证 | 手机到目标机的密钥 |
| 跳板机 | 关闭 |

虽然连接地址是 VPS，SSH 握手由目标机 sshd 完成，因此首次出现的应是目标机指纹，不是 VPS 指纹。

## 七、端到端验收

完成配置后逐项检查：

1. 目标机 sshd 为 `active`，且 22 端口正在监听。
2. 目标机 systemd 用户服务为 `active`，日志没有主机密钥、认证或转发创建错误。
3. VPS 的 22022 监听地址符合所选架构：首选为 `127.0.0.1`，公网备选为 `0.0.0.0` 或指定公网地址。
4. 通过反向端口读取到的主机指纹等于目标机指纹。
5. SSH Helper 能分别验证 VPS 跳板和最终目标，并可打开终端、列出 SFTP 目录。
6. 重启目标机和 VPS 后，隧道能自动恢复；断网再联网后也能恢复。

## 八、常见问题

| 症状 | 优先检查 | 处理 |
|---|---|---|
| 服务反复重启 | 日志中的 host key 或 permission denied | 核对 `known_hosts`、密钥权限、VPS `authorized_keys` 和 `Match` 配置 |
| `remote port forwarding failed` | 端口占用或 `PermitListen` 不匹配 | 在 VPS 用 `ss` 查端口；确保 `-R` 监听地址与 `PermitListen` 完全一致 |
| VPS 回环端口存在但目标登录失败 | 目标机 sshd 或目标账户授权 | 检查目标机 22、手机公钥、用户名和认证日志 |
| 跳板方案无法连接 `127.0.0.1` | 最终目标是否选择 VPS 跳板 | 确认最终目标为 `127.0.0.1:22022`，跳板主机可直连且允许 local forwarding |
| 公网端口超时 | 云安全组、主机防火墙、监听地址 | 先在 VPS 查 `ss`，再查两层规则，最后从允许来源外部探测 |
| 公网端口被拒绝 | 无监听或中间设备主动 reject | 不能据此断言安全组已放行，仍需逐层检查 |
| 主机密钥发生变化 | 机器是否重装、端口是否指向其他主机 | 暂停连接，从控制台或本机重新取指纹，查明原因后再更新信任 |

## 九、安全检查清单

- VPS 和目标机都运行受支持、持续更新的系统与 OpenSSH。
- `tunnel` 账户只允许公钥认证、远程转发和指定的 `PermitListen`。
- 三种用途的密钥互不复用，私钥权限为 600 或存入系统安全存储。
- 优先采用回环监听 + 跳板机，不向公网开放 22022。
- 公网直连时限制来源 CIDR，并定期复核云安全组和主机防火墙。
- 保存并核对 VPS 与目标机的 SHA-256 主机指纹，任何变化都先调查。
- 定期检查 systemd 日志和失败认证；不再需要时立即停止服务并删除入口规则。

## 十、相较旧教程的关键修正

- “反向隧道场景不适用跳板机”不正确：普通跳板无法直接访问 NAT 后的私网地址，但可以访问反向隧道在 VPS 回环上建立的端口。
- `GatewayPorts` 不是建立反向隧道的必要条件，只是允许非回环监听的服务器策略。
- `GatewayPorts clientspecified` 比 `GatewayPorts yes` 更可控。
- 使用 VPS `root` 建立隧道权限过大，应使用专用账户和 `PermitListen`。
- “目标机公钥自授权”只有在客户端持有同一私钥时才有意义；把服务器私钥复制到手机会破坏密钥隔离。
- “连接被拒绝即证明安全组放行”并不可靠，拒绝也可能来自防火墙或其他中间设备。
- 直接用 `grep`/`sed` 修改 `sshd_config` 容易忽略缩进、`Include` 和实际生效顺序，应使用 drop-in、`sshd -t` 和 `sshd -T`。
- Ubuntu / Debian 常用服务名是 `ssh`，Arch、Fedora 和 RHEL 系通常是 `sshd`。
- `loginctl enable-linger` 通常需要管理员权限，应使用 `sudo loginctl`。

## 官方参考资料

- [OpenBSD sshd_config(5)：GatewayPorts、AllowTcpForwarding、PermitListen](https://man.openbsd.org/sshd_config)
- [OpenBSD sshd(8)：authorized_keys 限制选项](https://man.openbsd.org/sshd)
- [OpenBSD ssh_config(5)：ServerAlive 与远程转发参数](https://man.openbsd.org/ssh_config)
- [systemd loginctl：enable-linger](https://www.freedesktop.org/software/systemd/man/latest/loginctl.html)
- [systemd：Network Online](https://systemd.io/NETWORK_ONLINE/)
- [Alibaba Cloud ECS：Security group rules](https://help.aliyun.com/en/ecs/user-guide/security-group-rules)
- [Red Hat：CentOS Linux EOL](https://www.redhat.com/en/topics/linux/centos-linux-eol)
