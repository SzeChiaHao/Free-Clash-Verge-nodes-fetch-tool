# NodeKeeper · 免费节点抓取测速管家

抓取公开免费节点 → 实测延迟与下载速度 → 排序过滤 → 导出**各种客户端**的订阅：
Clash / Clash Verge / mihomo、sing-box、v2rayN / v2rayNG、Shadowrocket、Shadowsocks（sip008 / ss 链接）。
同一套逻辑有两个前端：Windows 上的 Python 命令行流水线，以及 Android 手机上的「节点管家」App。

> 免费节点随时失效、质量参差，且有安全风险（可能记录流量）。请勿通过免费节点登录重要账号、
> 访问明文网站或进行挖矿。本项目仅用于学习交流与访问合法内容。

---

## 一、目录结构

```
tools/      Windows 端：Python 抓取 + mihomo 内核测速 + 导出多客户端订阅 + 同步 Clash Verge
android/    Android 端：同款逻辑的 Kotlin 实现，抓取/测速/本地订阅服务一体化 App
```

## 二、Windows 端（`tools/`）

一条全自动流水线：**抓取 → 测速 → 按速度排序 → 过滤垃圾节点 → 导出多客户端订阅 → 同步进 Clash Verge**。

| 文件 | 作用 |
|---|---|
| `daily_auto.py` | 核心脚本。抓取 + 独立 mihomo 内核测速 + 按下载速度降序排序 + 导出多客户端订阅 + 同步 Clash Verge |
| `daily_auto.ps1` | 包装脚本：调用 `daily_auto.py`，写 UTF-8 日志并自动清理旧日志 |
| `setup_auto.ps1` | 一键安装自动任务（每日 08:00 + 开机登录自启），无需管理员权限 |
| `fetch_nodes.py` | 节点源清单与解析（被 `daily_auto.py` 复用） |
| `node_quality.py` | mihomo 测速框架（延迟 / 下载速度） |
| `update_nomorewalls.ps1`、`scrape_nodes.ps1` | 旧版脚本，保留备用 |

快速开始：

```powershell
pip install requests pyyaml
powershell -ExecutionPolicy Bypass -File tools\daily_auto.ps1
```

更多参数、节点源增删（含聚合页自动发现）、自动任务安装与卸载，详见 **[tools/README.md](tools/README.md)**。

### 一次运行导出 8 种订阅文件（不同客户端各拿各的）

| 客户端 | 拿哪个文件 |
|---|---|
| Clash Verge、mihomo、FlClash、Clash Meta for Android、NekoBox | `best_nodes.yml` |
| sing-box | `singbox-outbounds.json`（`outbounds` 片段，粘进自己的配置） |
| v2rayN、v2rayNG、Shadowrocket | `v2ray-base64.txt`（通用 base64 订阅） |
| Shadowsocks 系：shadowsocks-android / ss-windows / Shadowrocket | `sip008.json`、`ss-base64.txt`、`ss-plain.txt`、`gui-config.json` |
| 手动挑节点 | `all-links.txt`（明文分享链接） |

文件全部落在 `tools/output/`，细节见 **[tools/README.md](tools/README.md)** 第四节。

## 三、Android 端（`android/`）

「节点管家」App：把上面那套流水线搬到手机上——内置默认节点源、后台定时抓取与测速、
在手机本地起一个订阅 HTTP 服务，直接把订阅地址喂给 Clash / Clash Verge / 其它客户端。

- 包名 `com.szech.walls`，`minSdk 26`，`targetSdk 34`，Kotlin + 原生 View。
- 主要模块：`pipeline/`（默认节点源与流水线）、`net/`（抓取与自研隧道客户端）、
  `parse/`（URI 解析）、`server/`（本地订阅服务）、`export/`（订阅格式导出）、`work/`（定时任务）。
- 手机上没有 mihomo 内核，所以**测速用的协议客户端是自己实现的**：
  Shadowsocks（AEAD / 流式）、Trojan（tcp / ws + TLS）、VLESS（tcp / ws + TLS）。
  做法是拿一批「mihomo 能跑通」的节点当标准答案，做端到端比对，详见 **[android/README.md](android/README.md)**。

发布新版本（App 会自动提示更新）：

```powershell
powershell -ExecutionPolicy Bypass -File D:\Walls\build-apk.ps1 -Release -Notes "这一版改了啥"
```

这条命令会把版本号 +1、编译、传成 GitHub Release 资产、更新仓库里的 `version.json` 并推送。
手机上的 App 就是盯着 `version.json` 的 `versionCode` —— 它变大了就提示升级，所以平时发代码 commit 不会打扰用户。

构建（Windows 一键）：

```powershell
powershell -ExecutionPolicy Bypass -File D:\Walls\build-apk.ps1              # 版本 +1 并编译
powershell -ExecutionPolicy Bypass -File D:\Walls\build-apk.ps1 -NoBump      # 只重编，不动版本号
powershell -ExecutionPolicy Bypass -File D:\Walls\build-apk.ps1 -Version 2.0 # 指定版本名
```

脚本会自动把 `versionCode` 加一（覆盖安装要求新包的 versionCode 更大），编译完把成品复制成 `%USERPROFILE%\Downloads\Walls-<版本>.apk`，直接传手机装即可。

手动构建：

```bash
cd android
./gradlew assembleRelease          # Windows: gradlew.bat assembleRelease
```

签名信息从 `android/local.properties` 读取（该文件不入库，模板见 `android/local.properties.example`）：

```properties
sdk.dir=D\:\\Android\\Sdk
walls.storeFile=walls-release.jks
walls.storePassword=******
walls.keyAlias=wallskey
walls.keyPassword=******
```

不配置 keystore 时 `release` 自动退回默认 debug 签名，仍可正常构建。

## 四、免责声明

本仓库只做「公开链接的收集与测速」，不提供任何节点服务器，也不对第三方节点的可用性、
合法性、安全性作任何担保。使用者需自行承担使用风险，并遵守所在地法律法规。
