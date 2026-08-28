# 内网穿透完整教程:反向 SSH 隧道(NAT 后主机公网可达)

> 场景:家里的电脑(或任意 NAT 后的主机)没有公网 IP,你想在**任何地方**用手机/笔记本 SSH 进它,
> 还能用它的本地服务(如本机跑着的 DSH CLI / Web 服务)。
>
> 本文从零开始,覆盖**服务器端**和**目标机端**完整配置,命令区分通用写法与本项目实际值
> (云服务器 8.155.173.255,目标机 Arch Linux,用户 Yang136,隧道端口 22022)。
> 速查/踩坑见 `remote-access-playbook.md`。

---

## 一、原理:先看懂再动手

```
手机(任意网络) ──SSH──▶ 云服务器:22022 ──反向隧道──▶ 目标机(家庭主机):22
```

- **问题**:目标机在 NAT 后,公网无法主动连它;云服务器也"够不着"它。
- **解法**:让目标机**主动出站**连云服务器(它出网总是可以的),在云服务器上开一个端口(22022),
  把"连到 22022 的流量"顺着这条已建立的连接送回目标机的 22。
- **关键三件套**(缺一不可):
  1. 云服务器 sshd 开 `GatewayPorts yes` —— 否则反向端口只绑 127.0.0.1,公网连不上;
  2. 云服务器防火墙放行 22022(firewalld/ufw);
  3. 云厂商**安全组**放行 22022(阿里云在控制台,和安全组是两层,都要放)。
- **保活**:隧道是一次长连接,断线要自动重连 → 用 autossh 或 systemd `Restart=always`。

---

## 二、前置准备

| 项 | 说明 |
|---|---|
| 云服务器 | 有公网 IP,能跑 sshd(本文:阿里云 ECS,8.155.173.255,CentOS 7,root) |
| 目标机 | NAT 后,能出网(本文:Arch Linux,用户 Yang136,已有 ~/.ssh/id_rsa) |
| 隧道端口 | 自选,避开常用端口(本文用 22022) |

---

## 三、服务器端(云服务器)配置

以下命令在云服务器上执行(SSH 登录、控制台远程连接或阿里云 Workbench CLI 均可)。

### 3.1 确认 sshd 在跑

```bash
# Debian/Ubuntu
sudo systemctl enable --now ssh
# CentOS/RHEL
sudo systemctl enable --now sshd
# Arch
sudo systemctl enable --now sshd

ss -lnt | grep ':22 '    # 应显示监听
```

### 3.2 开启 GatewayPorts

```bash
cp /etc/ssh/sshd_config /etc/ssh/sshd_config.bak.$(date +%s)   # 先备份
if grep -qE '^GatewayPorts' /etc/ssh/sshd_config; then
  sed -i 's/^GatewayPorts.*/GatewayPorts yes/' /etc/ssh/sshd_config
else
  echo 'GatewayPorts yes' >> /etc/ssh/sshd_config
fi
sshd -t && sudo systemctl restart sshd     # 配置校验通过才重启
```

### 3.3 防火墙放行隧道端口

**firewalld(CentOS/RHEL)**:
```bash
sudo firewall-cmd --permanent --add-port=22022/tcp
sudo firewall-cmd --reload
firewall-cmd --list-ports        # 确认 22022/tcp 在列
```

**ufw(Ubuntu/Debian)**:
```bash
sudo ufw allow 22022/tcp
sudo ufw status
```

### 3.4 云厂商安全组放行(阿里云控制台)

ECS 实例 → 安全组 → 入方向规则 → 添加:
- 协议 `TCP`、端口 `22022`、源 `0.0.0.0/0`(建议收敛到你的手机/家庭出口 IP)
- 确认 `22` 已放行(目标机的隧道要连进来)

> **如何判断安全组放没放行(重要)**:隧道还没建时,从外网测端口:
> ```bash
> timeout 8 bash -c 'cat < /dev/null > /dev/tcp/8.155.173.255/22022'
> ```
> - **"连接被拒绝"** = 数据已到达服务器(放行生效 ✓,只是没进程监听);
> - **超时/无法连接** = 安全组或防火墙还在拦 ✗。

### 3.5 授权目标机公钥(让目标机能免密登录)

把目标机的公钥内容(`~/.ssh/id_rsa.pub`)整行追加:

```bash
cp /root/.ssh/authorized_keys /root/.ssh/authorized_keys.bak.$(date +%s)
echo 'ssh-rsa AAAA…目标机公钥整行…' >> /root/.ssh/authorized_keys
chmod 600 /root/.ssh/authorized_keys && chmod 700 /root/.ssh
```

> 注意:用 `>>` 追加,不要覆盖(文件可能已存在,甚至为空)。

---

## 四、目标机端(家庭主机)配置

以下命令在**目标机(家庭那台)**执行。

### 4.1 启动本机 sshd 并开机自启

目标机自己要能被隧道连进来,所以本机 22 必须常开:

```bash
# Arch
sudo systemctl enable --now sshd
# Ubuntu/Debian
sudo systemctl enable --now ssh
systemctl is-active sshd && ss -lnt | grep ':22 '
```

> 必须 `enable`(开机自启),否则机器重启后 22 不监听,隧道虽然活着但连不进。

### 4.2 生成密钥并"自授权"

```bash
ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519 -N ""    # 已有密钥可跳过
mkdir -p ~/.ssh && chmod 700 ~/.ssh
cat ~/.ssh/id_ed25519.pub >> ~/.ssh/authorized_keys  # 本机认自己的公钥
chmod 600 ~/.ssh/authorized_keys
```

> 这一步常被漏掉:经隧道连回的是**目标机自己的 sshd**,它也要认识你的公钥。

### 4.3 手动测试隧道(一次性)

```bash
ssh -N -T -R 22022:localhost:22 \
  -o ServerAliveInterval=30 -o ServerAliveCountMax=3 \
  -o ExitOnForwardFailure=yes root@8.155.173.255
```

保持这个命令运行,另开一个终端(或换台设备)验证:
```bash
ssh -p 22022 Yang136@8.155.173.255 'echo TUNNEL_OK'
```
看到 `TUNNEL_OK` 就说明全链路通了,`Ctrl+C` 结束测试,进入保活配置。

### 4.4 保活方案 A:autossh(标准做法)

```bash
# Ubuntu/Debian
sudo apt install autossh
# Arch
sudo pacman -S autossh
```

启动命令:
```bash
autossh -M 0 -N -R 22022:localhost:22 \
  -o ServerAliveInterval=30 -o ServerAliveCountMax=3 \
  -o ExitOnForwardFailure=yes root@8.155.173.255
```

配成 systemd 服务(开机自启):
`/etc/systemd/system/ssh-tunnel.service`:
```ini
[Unit]
Description=SSH reverse tunnel
After=network-online.target
Wants=network-online.target

[Service]
User=Yang136
ExecStart=/usr/bin/autossh -M 0 -N -R 22022:localhost:22 -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o ExitOnForwardFailure=yes root@8.155.173.255
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```
```bash
sudo systemctl enable --now ssh-tunnel
```

### 4.5 保活方案 B:ssh -N -R + systemd(本项目在用,零额外依赖)

如果不想装 autossh,直接让 systemd 帮我们重连(效果等同)。

文件 `~/.config/systemd/user/ssh-tunnel.service`:
```ini
[Unit]
Description=SSH reverse tunnel: 8.155.173.255:22022 -> localhost:22
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/bin/ssh -N -T -R 22022:localhost:22 -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o ExitOnForwardFailure=yes root@8.155.173.255
Restart=always
RestartSec=10

[Install]
WantedBy=default.target
```

启用并允许"无需登录"也自启(linger):
```bash
systemctl --user daemon-reload
systemctl --user enable --now ssh-tunnel
loginctl enable-linger Yang136
```

维护:
```bash
systemctl --user status ssh-tunnel
journalctl --user -u ssh-tunnel -f
systemctl --user restart ssh-tunnel
```

---

## 五、端到端验证(每次配完照做)

```bash
# ① 目标机 sshd 在跑
systemctl is-active sshd
# ② 云服务器上 22022 已绑公网(在云服务器上执行)
ss -lnt | grep 22022          # 期望 *:22022,而不是 127.0.0.1:22022
# ③ 从公网经隧道连回目标机(任意有外网的机器)
ssh -p 22022 -i ~/.ssh/id_ed25519 Yang136@8.155.173.255 'echo TUNNEL_OK; hostname'
```

---

## 六、手机使用(SSH Helper)

新建主机:
- 地址 `8.155.173.255`、端口 `22022`、用户名 `Yang136`
- 认证:私钥(导入目标机的 `~/.ssh/id_ed25519` / `id_rsa`),或密码

连上后终端、SFTP 全可用。想让手机浏览器用目标机的 Web 服务(如 DSH GUI:3080),
加一条本地转发规则:`-L 127.0.0.1:3080 → 127.0.0.1:3080`,手机开 `http://127.0.0.1:3080`。

远程用 DSH CLI(SSH 连上目标机后):
```bash
export PATH="$HOME/.npm-global/bin:$PATH"     # 若 dsh 不在 PATH,建议写入 ~/.bashrc
dsh --profile headless "你的任务"              # 单次任务,打印即退
```

---

## 七、安全加固与常见问题

**安全**:
- 私钥/密钥别明文发给别人;AccessKey 泄露过就轮换;
- 22022 源地址收敛(别长期 0.0.0.0/0);
- 目标机 sshd 可关密码登录、只留密钥(`PasswordAuthentication no`),改前确认公钥已配好。

**常见问题对照**:

| 症状 | 原因 | 处理 |
|---|---|---|
| 连 22022 超时 | 安全组/防火墙没放行 | 三层检查:安全组 + firewalld/ufw + GatewayPorts |
| 拒连且服务器无监听 | 隧道没起来 | `systemctl --user restart ssh-tunnel`;看日志 |
| 隧道在但手机连不上 | GatewayPorts no | 服务器改 `GatewayPorts yes` 重启 sshd |
| 重启机器后失效 | 目标机 sshd 没 enable / linger 没开 | `sudo systemctl enable --now sshd`;`loginctl enable-linger <用户>` |
| 密钥登录被拒 | 公钥没加对地方 | 目标机自授权 + 云服务器 root authorized_keys 都要有 |
| `sudo` 报需要密码 | 无交互终端 | 自己在终端执行 |
