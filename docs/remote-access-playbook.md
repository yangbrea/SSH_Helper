# 内网穿透 + 远程使用 DSH CLI 运维手册

> 场景:家庭主机(NAT 后,无公网 IP)通过**反向 SSH 隧道**接入一台有公网 IP 的阿里云 ECS,
> 使手机可从公网 SSH 进入家庭主机,并远程使用本机的 DSH(DeepSeek Harness)CLI / Web GUI。
>
> 最后验证时间:2026-08-23。命令均在本会话实测通过。

## 0. 拓扑与原理(先想清楚,别配错)

```
手机(SSH Helper) ──SSH──▶ 云服务器 8.155.173.255:22022 ──反向隧道──▶ 本机(Arch) sshd:22
                                                        ▲
                             家庭主机(本机)主动出站建立 -R 22022:localhost:22
```

- **为什么用反向隧道而不是跳板机**:目标机(家庭主机)完全在 NAT 后,云服务器**够不着它**。
  SSH Helper 的"跳板机"要求云服务器可达目标机的 IP:22,此场景不适用。
  必须由目标机**主动出站**连云服务器,把本地 22 暴露到云服务器的 22022。
- **关键配置三件套**:云服务器 `GatewayPorts yes`(否则反向端口只绑回环)+ 云服务器防火墙放行 + 阿里云**安全组**放行。三层缺一不可。

## 1. 云服务器配置(阿里云 ECS)

| 项目 | 值 |
|---|---|
| 地域 | cn-heyuan(华南2·河源) |
| 实例 ID | i-f8zccvhjotwe9y4up5rc |
| 公网 IP | 8.155.173.255 |
| SSH 用户 | root |
| 系统 | CentOS 7(x86_64) |

配置方式:阿里云 Workbench CLI(见第 4 节)或控制台远程连接执行。

### 1.1 开启 GatewayPorts(反向端口可绑公网)

```bash
cp /etc/ssh/sshd_config /etc/ssh/sshd_config.bak.$(date +%s)
if grep -qE '^GatewayPorts' /etc/ssh/sshd_config; then
  sed -i 's/^GatewayPorts.*/GatewayPorts yes/' /etc/ssh/sshd_config
else
  echo 'GatewayPorts yes' >> /etc/ssh/sshd_config
fi
sshd -t && systemctl restart sshd        # 校验合法再重启
ss -lnt | grep ':22 '                     # 确认 sshd 恢复监听
```

> 踩坑:默认 `GatewayPorts no`,反向隧道端口只绑 `127.0.0.1`,手机从公网永远连不上。
> 验证方法:云服务器上 `ss -lnt | grep 22022` 应显示 `*:22022` 而非 `127.0.0.1:22022`。

### 1.2 firewalld 放行隧道端口(示例 22022)

```bash
firewall-cmd --permanent --add-port=22022/tcp
firewall-cmd --reload
firewall-cmd --list-ports                     # 确认 22022/tcp 在列
```

### 1.3 阿里云安全组放行(控制台操作,Workbench CLI 查不到)

ECS 实例 → 安全组 → 入方向规则:
- 协议 `TCP`、端口 `22022`、源 `0.0.0.0/0`(或限定你的手机/家庭出口 IP,更安全)
- 确认 `22` 已放行(目标机的反向隧道要连进来)

> 验证安全组是否放行(从外网测,区分两种情况):
> - `timeout 8 bash -c 'cat < /dev/null > /dev/tcp/8.155.173.255/22022'` → **连接被拒绝** = 数据已到达服务器,只是无监听(放行生效 ✓)
> - 超时 / 无法连接 = 安全组或防火墙仍在拦截 ✗

### 1.4 授权目标机公钥登录(服务器侧)

```bash
# 把目标机公钥(~/.ssh/id_rsa.pub 内容)追加到 root:
cp /root/.ssh/authorized_keys /root/.ssh/authorized_keys.bak.$(date +%s)
echo '目标机公钥整行' >> /root/.ssh/authorized_keys
chmod 600 /root/.ssh/authorized_keys && chmod 700 /root/.ssh
```

> 注意:服务器 `authorized_keys` 可能已存在但为空(0 字节),用追加(`>>`)不要覆盖。

## 2. 本机配置(家庭主机,目标机)

| 项目 | 值 |
|---|---|
| 系统 | Arch Linux |
| 用户 | Yang136 |
| SSH | sshd 监听 22,密钥 ~/.ssh/id_rsa |
| 隧道端口 | 22022 → localhost:22 |

### 2.1 sshd 启动 + 开机自启(必须 enable!)

```bash
sudo systemctl enable --now sshd        # 立即启动 + 开机自启
systemctl is-active sshd                 # active
ss -lnt | grep ':22 '                    # 监听 0.0.0.0:22
```

> 踩坑(本次真实发生):sshd 是 `disabled`,机器重启后 22 不监听,
> 隧道 `-R 22022:localhost:22` 虽然还活着,但连 22022 会被拒连。必须 `enable`。
> `sudo` 无交互终端时会失败(sudo: 需要密码),**需要你自己在终端执行**。

### 2.2 本机公钥自授权(手机才能用密钥登进本机)

```bash
mkdir -p ~/.ssh && chmod 700 ~/.ssh
cat ~/.ssh/id_rsa.pub >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
```

> 踩坑:只把公钥加到云服务器还不够——经隧道连回的是**本机 sshd**,本机也要认自己的公钥。

### 2.3 反向隧道 systemd 用户服务(常驻保活,等效 autossh)

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

启用与开机自启(linger 让用户服务无需登录也自启):

```bash
systemctl --user daemon-reload
systemctl --user enable --now ssh-tunnel
loginctl enable-linger Yang136
```

常用维护:

```bash
systemctl --user status ssh-tunnel                      # 状态
journalctl --user -u ssh-tunnel -f                      # 日志
systemctl --user restart ssh-tunnel                     # 重启隧道
```

> 说明:用 `ssh -N -R` + `Restart=always` 保活,无需安装 autossh(Arch 默认无 autossh)。
> 端口占用/转发失败时 `ExitOnForwardFailure=yes` 会让进程退出、由 systemd 10 秒后重试。

## 3. 端到端验证(每次配置后照做)

```bash
# ① 本机 sshd 在跑
systemctl is-active sshd
# ② 云服务器 22022 已绑公网(Workbench 或 SSH 上去看)
ss -lnt | grep 22022          # 期望 *:22022
# ③ 从公网经隧道连回本机(任意有外网的机器)
ssh -p 22022 -i ~/.ssh/id_rsa -o StrictHostKeyChecking=accept-new Yang136@8.155.173.255 \
  'echo TUNNEL_OK; hostname; whoami'
```

## 4. 手机使用(SSH Helper)

新建主机:
- 名称:随意(如"家里 Arch")
- 地址:`8.155.173.255`、端口:`22022`
- 用户名:`Yang136`
- 认证:私钥(导入本机 `~/.ssh/id_rsa`),或密码(本机 sshd 密码认证仍开)

连上后:终端、SFTP 可用;可选加 `-L` 转发规则(如 `127.0.0.1:3080 → 127.0.0.1:3080`)把本机 DSH Web GUI 暴露给手机浏览器。

## 5. 远程使用 DSH CLI

登录本机后:

```bash
export PATH="$HOME/.npm-global/bin:$PATH"   # 若 dsh 不在 PATH(建议写入 ~/.bashrc)
dsh --profile headless "你的任务"            # 单次任务,打印结果即退出(适合 SSH)
```

- 多轮 / 可视化:用 SSH Helper `-L 3080` 转发,手机浏览器开 `http://127.0.0.1:3080`(前提:本机 `dsh web` 在跑,即当前这个 Web GUI)。
- 通过 SSH 启动的 DSH 是独立实例,与网页会话互不干扰,共享 `~/.dsh` 配置。

## 6. 踩坑清单(下次直接对照)

| 症状 | 原因 | 修复 |
|---|---|---|
| 连 22022 超时 | 安全组或 firewalld 没放行 | 控制台放行 + `firewall-cmd --add-port`,再从外网验证(拒连=通) |
| 连 22022 拒连,但服务器无监听 | 隧道没建立 | `systemctl --user restart ssh-tunnel`;看 `journalctl --user -u ssh-tunnel` |
| 隧道建立了但手机连不上 | `GatewayPorts no`(端口只绑回环) | 云服务器改 `GatewayPorts yes` 并重启 sshd |
| 机器重启后隧道失效 | sshd 没 `enable`(本机),或 linger 没开 | `sudo systemctl enable --now sshd`;`loginctl enable-linger Yang136` |
| 密钥登录被拒 Permission denied | 公钥没加对地方 | 本机 `authorized_keys`(自授权)+ 云服务器 root `authorized_keys` 都要有 |
| `sudo` 报"需要密码" | 无交互终端 | 自己在终端执行,别指望 agent 代跑 |
| Workbench CLI 报认证失败 | AccessKey 失效/权限不足 | 检查 `~/.workbench/config.json`(600),RAM 策略需含 ecs-workbench:LoginECSInstance 等 |

## 7. 安全提醒

- 本会话曾把阿里云 AccessKey 明文贴出:**尽快在 RAM 控制台轮换**,并只用最小权限子账号(策略见 Workbench skill)。
- 本机 22 经公网可达且密码认证开启:SSH Helper 里建议只用密钥认证;如需要可在本机 sshd 关闭密码登录。
- 22022 源地址建议收敛到手机/家庭出口 IP,别长期 `0.0.0.0/0`。
