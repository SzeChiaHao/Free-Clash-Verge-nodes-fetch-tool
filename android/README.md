# Walls · 节点管家（Android）

免费节点「抓取 → 真实隧道测速 → 按速度排序 → 本地订阅」的手机版。
和 Windows 版 `tools/` 同一套思路，区别是手机上没有 mihomo 内核，
所以**测速用的协议客户端是自己实现的**。

- 包名 `com.szech.walls`，`minSdk 26`，`targetSdk 34`，Kotlin + 原生 View（无 XML 布局，UI 全在代码里）。
- 抓取、测速、订阅服务全部在本机完成，订阅服务只监听 `127.0.0.1`，不对外开端口。

## 一、代码结构

```
core/       通用工具：日志、base64、URL 编码、HKDF
model/      节点模型（内部就是 Clash 的 proxy 字典）+ JSON 持久化
parse/      分享链接解析（ss / ssr / vmess / vless / trojan / hy2 / tuic）
net/        网络层：抓取器、隧道协议、TLS/WebSocket 流、HTTP 探测
pipeline/   流水线：DefaultSources 默认节点源、Pipeline 三阶段、Prober 测速、Runner 调度
export/     订阅导出：Clash YAML / SIP008 / base64 / 分享链接 / 报告
server/     本地订阅 HTTP 服务（SubServer）+ 前台服务（SubService）+ 首页 HTML
store/      SharedPreferences 设置与结果仓库
work/       WorkManager 每日自动更新
```

## 二、测速是怎么做的（重点）

手机上没有 mihomo，`Prober` 就自己把隧道建起来，再借它去访问一个已知 URL，量出真实握手延迟和下载速度。
分层是这样的，每一层都是 `ProxyStream`，可以自由组合：

```
TCP socket
   └─ [TLS]                TlsStream（SNI、ALPN http/1.1、可忽略证书校验）
        └─ [WebSocket]     WsStream（RFC 6455 握手 + 分片收发 + ping/pong）
             └─ 代理协议头  vless / trojan 把自己的请求头写在这里
                  └─ 真实数据（比如一个 HTTP(S) 请求）
```

拿到 `Prober` 的延迟/速度后，`Pipeline` 按速度降序排序、按阈值过滤、给节点名加速度前缀，
最后交给 `export/` 和 `server/` 输出成各种订阅。

### 协议支持矩阵

| 协议 / 传输 | 真实测速 | 说明 |
|---|---|---|
| ss（AEAD：aes-gcm / chacha20-ietf-poly1305，流式：aes-cfb/ctr，none） | ✅ | 纯 Kotlin 实现，见 `Shadowsocks.kt` |
| trojan（tcp / ws，TLS） | ✅ | 请求头 = hex(SHA224(password)) + CRLF + 命令 + SOCKS5 地址 + CRLF |
| vless（tcp / ws，TLS） | ✅ | 请求头 version 0；ATYP 用自己的编号（1/2/3），和 SOCKS5（1/3/4）不一样 |
| vmess | ❌ | 还没实现（见「待办」） |
| vless + reality、vless + xtls-rprx-vision | ❌ | 需要伪造 TLS 指纹 / 改 TLS 记录层，平台 TLS 做不到 |
| xhttp / gRPC 传输 | ❌ | 需要 HTTP/2 帧与 gRPC 封装 |
| hysteria2 / tuic | ❌ | 走 QUIC，Android 平台没有公开的 QUIC API |
| ssr / ss2022（blake3） | ❌ | 算法实现量大，收益低 |

实测数据（2026-09-22，一次真实抓取）：某次抓到的 1200 个节点里，
vless ws+tls 395 个、vless tcp+tls 131 个、vmess 若干、ss 17 个。
也就是说只测 ss 的话，真实测速只覆盖约 2% 的节点池；加上 vless / trojan 之后能覆盖到 60% 以上。

不能真实测速的协议仍然会做 TCP 延迟测试，只是默认被「只保留可实测协议的节点」这个开关过滤掉。

## 三、测试

### 1. 离线线格式测试（默认就跑）

```bash
./gradlew testDebugUnitTest
```

`WireFormatTest` 把最容易写错、又最难排查的东西钉死：WebSocket 握手与分片（掩码、长度字段、ping/pong）、
vless / trojan 请求头的字节序、ATYP 编号差异、UUID 解析。
这些地方只要错一个字节，表现就是「所有节点都测不通」。

### 2. 真机实测（需要自己准备语料）

免费节点会过期，所以这组测试默认跳过。要跑的话先准备一个 TSV 语料，一行一个节点：

```
# type  server  port  secret  network  tls  sni  host  path
vless   1.2.3.4 443   <uuid>  ws       1    example.com  example.com  /
trojan  5.6.7.8 443   <pass>  ws       -    example.com               /path
```

`tls` 列：`1` 开、`0` 关、`-` 表示原配置里没有这个字段（按协议默认来）。

然后：

```bash
./gradlew testDebugUnitTest -Dwalls.liveCorpus=/path/to/corpus.tsv
```

**语料怎么来**：先用 `tools/` 抓一批节点，再拿 mihomo（Clash Verge 自带的内核）筛出「确实能跑通」的那些，
把它们当标准答案。自研客户端能跑通同样的一批，才说明协议实现是对的。
这一步很关键 —— 光看代码是看不出握手指令有没有写对的。

## 四、构建

```bash
./gradlew assembleRelease        # 产物在 app/build/outputs/apk/release/
```

签名信息从 `local.properties` 读（该文件不入库，模板见 `local.properties.example`）；
没配 keystore 时 release 自动退回 debug 签名，仍可构建。

## 五、App 内自动更新

更新信息**不查 GitHub API**，而是读仓库里的 `version.json`——因为 jsDelivr / gh-proxy 这类镜像能拿仓库文件，
却不一定能代理 `api.github.com`，在国内网络下这条路更稳。

```json
{
  "versionName": "1.3",
  "versionCode": 4,
  "apk": "https://github.com/.../releases/download/v1.3/Walls-1.3-vc4.apk",
  "size": 4796274,
  "sha256": "…",
  "notes": "这一版改了啥",
  "publishedAt": "2026-09-22 20:08"
}
```

取文件的顺序是：直连 raw → gh-proxy / ghproxy.net（实时透传）→ jsDelivr（有缓存，可能旧好几个小时，只当兜底）。
下载安装包同理，直连失败自动加代理前缀重试，下完核对 `sha256` 再装。

触发条件就一个：**`version.json` 里的 `versionCode` 比本机大**（比不出来时退回比版本号）。
所以平时发代码 commit 不会打扰用户，只有真出包时才提示。

坑记录：PowerShell 的 `Set-Content -Encoding UTF8` 会写 BOM，`JSONObject` 遇到 BOM 直接抛异常。
发布脚本已改成写不带 BOM 的 UTF-8，`Updater.parse` 也顺手清一下 BOM 兜底。

## 五、已知问题与待办

- **vmess 还没实现**。它在免费节点里占比很高（一次真实抓取里 136 个），
  需要按 AEAD 规范做 KDF、AuthID、请求头加密与分块读写。
- **自动更新最后一步依赖系统安装器**：调起安装器没问题，但「允许安装未知应用」这个开关只能用户在系统设置里点头，
  不打开的话 App 会引导过去（`ACTION_MANAGE_UNKNOWN_APP_SOURCES`）。
- **trojan 只有线上验证缺口**：协议实现和线格式测试都完成了，但手上那批样本节点里
  唯一的 trojan 节点前端已经挂掉（返回 403），没法做端到端确认。
- reality / vision / xhttp / gRPC / QUIC 系协议测不了，只能在报告里标「仅 TCP 延迟」。
- 测速用的信任策略：节点配了 `skip-cert-verify` 或首轮失败时，会再用「忽略证书校验」试一次。
  这只用于**测速**，应用本身不做代理转发，不会拿这种方式承载真实流量。
