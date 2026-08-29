# 局域网设备发现

发现页默认使用 SSH 模式，也可在本次页面会话中切换为通用设备模式。切换模式会清空当前结果，不写入设置或数据库。

## 扫描范围

- 仅主动枚举 IPv4 私有地址、CGNAT 和 Link-local 网段，最多 1024 个地址；更大的当前子网默认缩小到本机 `/24`。
- SSH 模式扫描用户指定端口，通过 SSH Banner 和 `_ssh._tcp` / `_sftp-ssh._tcp` mDNS 广播确认候选。
- 通用模式第一阶段扫描 `22,80,443,445,554,9100` 和最多 4 个额外端口；只对已由 TCP、mDNS 或 SSDP 发现的地址补扫 `21,23,53,139,631,1883,3389,8000,8080,8443`。
- 通用 mDNS 覆盖 SSH、HTTP(S)、工作站、打印、SMB、AirPlay、Google Cast、HomeKit 和 RTSP 等常见类型。SSDP 向标准 IPv4 组播地址发送两次 `M-SEARCH`，响应窗口为 2.5 秒。
- ARP 是尽力增强能力：通用模式可以显示 ARP-only 记录，但会明确标记在线状态未确认；SSH 模式不会把它显示为 SSH 主机。

## 设备详情与安全边界

UPnP 设备描述只在用户打开详情时读取，并仅在当前扫描会话缓存。读取要求 `LOCATION` 为 HTTP 字面 IPv4，且必须与 SSDP 响应来源一致；请求绑定所选网络，禁止重定向，连接和读取超时均为 1.5 秒，响应上限 64 KiB，并拒绝 DOCTYPE。

“打开 Web”只根据设备 IP 和已发现的 Web 端口构造根 URL。应用会先显示完整 URL 并要求确认，再交给外部浏览器。网络返回的跳转或 SSDP `LOCATION` 不会直接作为浏览器目标。

设备分类采用保守规则：打印协议、UPnP 网关/媒体类型、工作站/RDP、HomeKit/MQTT 等强证据才参与分类；单个 HTTP 或 SMB 开放端口不会被强判为路由器或 NAS。MAC 地址不用于唯一身份或安全判断。

## 平台限制

Android 新版本可能禁止普通应用读取 `/proc/net/arp`，所以 MAC/OUI 缺失是正常情况。静默设备如果不响应所选 TCP 端口、mDNS、SSDP，且系统又不允许读取 ARP，则无法保证被发现。首版不主动发送 HTTP、RTSP 或 SMB 指纹探针，也不包含 ICMP、WSD、NetBIOS、IPv6、raw/root ARP、全端口、后台扫描或历史持久化。
