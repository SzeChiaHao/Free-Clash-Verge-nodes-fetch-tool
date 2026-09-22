#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把测速排序后的节点导出成各种客户端都能吃的格式。

输入是 Clash 风格的 proxy 字典（daily_auto.py 里那套），输出到 --out 目录：

  best_nodes.yml         mihomo / Clash Verge / Clash Meta for Android / FlClash / NekoBox
  sip008.json            Shadowsocks 官方 JSON 订阅（shadowsocks-android / ss-windows / sing-box）
  ss-base64.txt          base64 的 ss:// 列表（shadowsocks-android 的「订阅」、Shadowrocket）
  ss-plain.txt           明文 ss:// 一行一个（复制粘贴就能导入）
  gui-config.json        旧版 shadowsocks 客户端的「导入配置」
  v2ray-base64.txt       base64 的通用订阅（v2rayN / v2rayNG / Shadowrocket / NekoBox）
  all-links.txt          明文分享链接（ss / vmess / vless / trojan / hy2 / tuic）
  singbox-outbounds.json sing-box 的 outbounds 片段（粘进自己的配置里，见文件内说明）
  report.md              人类可读的测速报告（由调用方生成，这里只在独立运行时补一份）

单独用：
  python export_formats.py --in output/best_nodes.yml --out output
"""

import argparse
import base64
import json
import os
import urllib.parse
import uuid
from collections import Counter

try:
    import yaml
except ImportError:  # pragma: no cover
    yaml = None

# v2ray 系分享链接能表达的协议；http / socks5 没有标准的分享链接格式
LINK_TYPES = ("ss", "vmess", "vless", "trojan", "hysteria2", "hysteria", "tuic", "anytls")
SS_TYPES = ("ss",)


# ----------------------------------------------------------------- 小工具

def _b64(s):
    if isinstance(s, str):
        s = s.encode("utf-8")
    return base64.b64encode(s).decode("ascii")


def _quote(s):
    return urllib.parse.quote(str(s if s is not None else ""), safe="")


def _int(v, default=0):
    try:
        return int(v)
    except (TypeError, ValueError):
        return default


def _name(p, fallback=""):
    n = str(p.get("name") or "").strip()
    if not n:
        n = f"{p.get('server')}:{p.get('port')}"
    return n or fallback


def _ws_opts(p):
    ws = p.get("ws-opts") or {}
    path = ws.get("path") or "/"
    host = (ws.get("headers") or {}).get("Host") or ""
    return path, host


# ------------------------------------------------------------ ss 分享链接

def ss_uri(p):
    """SIP002 格式：ss://base64(method:password)@host:port/?plugin=...#name"""
    if p.get("type") != "ss":
        return None
    method = p.get("cipher") or p.get("method") or ""
    password = p.get("password", "")
    if not method or not p.get("server") or not p.get("port"):
        return None
    uri = "ss://{}@{}:{}".format(
        _b64("{}:{}".format(method, password)), p["server"], _int(p["port"])
    )
    plugin = p.get("plugin")
    opts = p.get("plugin-opts") or {}
    parts = []
    if plugin == "obfs":
        parts.append("obfs-local")
        parts.append("obfs={}".format(opts.get("mode", "http")))
        if opts.get("host"):
            parts.append("obfs-host={}".format(opts["host"]))
    elif plugin == "v2ray-plugin":
        parts.append("v2ray-plugin")
        parts.append("mode={}".format(opts.get("mode", "websocket")))
        if opts.get("host"):
            parts.append("host={}".format(opts["host"]))
        if opts.get("path"):
            parts.append("path={}".format(opts["path"]))
        if opts.get("tls"):
            parts.append("tls")
    if parts:
        uri += "/?plugin=" + _quote(";".join(parts))
    return uri + "#" + _quote(_name(p))


# --------------------------------------------------------- v2ray 系链接

def v2ray_uri(p):
    """vmess / vless / trojan / hysteria2 / tuic / anytls 的分享链接。"""
    t = p.get("type")
    server = p.get("server", "")
    port = _int(p.get("port"))
    if not server or not port:
        return None
    path, ws_host = _ws_opts(p)
    net = p.get("network", "tcp") or "tcp"
    sni = p.get("servername") or p.get("sni") or p.get("host") or ""
    insecure = bool(p.get("skip-cert-verify"))

    if t == "vmess":
        obj = {
            "v": "2",
            "ps": _name(p),
            "add": server,
            "port": str(port),
            "id": str(p.get("uuid", "")),
            "aid": str(_int(p.get("alterId", 0))),
            "scy": p.get("cipher") or "auto",
            "net": net,
            "type": "none",
            "host": ws_host or sni,
            "path": path,
            "tls": "tls" if p.get("tls") else "",
            "sni": sni,
        }
        return "vmess://" + _b64(json.dumps(obj, ensure_ascii=False))

    if t == "vless":
        q = ["type=" + net]
        if p.get("tls"):
            q.append("security=" + ("reality" if p.get("reality-opts") else "tls"))
        if sni:
            q.append("sni=" + _quote(sni))
        if p.get("flow"):
            q.append("flow=" + _quote(p["flow"]))
        ro = p.get("reality-opts") or {}
        if ro:
            q.append("pbk=" + _quote(ro.get("public-key", "")))
            q.append("sid=" + _quote(ro.get("short-id", "")))
        if net == "ws":
            q.append("path=" + _quote(path))
            if ws_host:
                q.append("host=" + _quote(ws_host))
        if net == "grpc":
            q.append("serviceName=" + _quote((p.get("grpc-opts") or {}).get("grpc-service-name", "")))
        if insecure:
            q.append("allowInsecure=1")
        return "vless://{}@{}:{}?{}#{}".format(p.get("uuid", ""), server, port, "&".join(q), _quote(_name(p)))

    if t == "trojan":
        q = ["security=" + ("tls" if p.get("tls") is not False else "none")]
        if sni:
            q.append("sni=" + _quote(sni))
        if net == "ws":
            q.append("type=ws")
            q.append("path=" + _quote(path))
            if ws_host:
                q.append("host=" + _quote(ws_host))
        if insecure:
            q.append("allowInsecure=1")
        return "trojan://{}@{}:{}?{}#{}".format(
            _quote(p.get("password", "")), server, port, "&".join(q), _quote(_name(p))
        )

    if t in ("hysteria2", "hysteria"):
        q = []
        if sni:
            q.append("sni=" + _quote(sni))
        if p.get("obfs"):
            q.append("obfs=" + _quote(p["obfs"]))
        if p.get("obfs-password"):
            q.append("obfs-password=" + _quote(p["obfs-password"]))
        if insecure:
            q.append("insecure=1")
        scheme = "hysteria2" if t == "hysteria2" else "hysteria"
        tail = ("?" + "&".join(q)) if q else ""
        return "{}://{}@{}:{}{}#{}".format(
            scheme, _quote(p.get("password", "")), server, port, tail, _quote(_name(p))
        )

    if t == "tuic":
        q = []
        if sni:
            q.append("sni=" + _quote(sni))
        if insecure:
            q.append("allow_insecure=1")
        tail = ("?" + "&".join(q)) if q else ""
        return "tuic://{}:{}@{}:{}{}#{}".format(
            p.get("uuid", ""), _quote(p.get("password", "")), server, port, tail, _quote(_name(p))
        )

    if t == "anytls":
        q = []
        if sni:
            q.append("sni=" + _quote(sni))
        if insecure:
            q.append("insecure=1")
        tail = ("?" + "&".join(q)) if q else ""
        return "anytls://{}@{}:{}{}#{}".format(
            _quote(p.get("password", "")), server, port, tail, _quote(_name(p))
        )

    return None


# ----------------------------------------------------------------- 各格式

def pick(proxies, types):
    return [p for p in proxies if p.get("type") in types]


# 各协议在导出时最多补多少条（池子本身已按速度降序）
PER_TYPE_KEEP = {
    "ss": 30,
    "vmess": 15,
    "vless": 15,
    "trojan": 15,
    "hysteria2": 10,
    "hysteria": 10,
    "tuic": 10,
    "anytls": 10,
}


def _by_type(pool, types, limit):
    """从池子里挑前 limit 个指定协议的节点，按 (协议,服务器,端口) 去重。"""
    out, seen = [], set()
    for p in pool:
        if p.get("type") not in types:
            continue
        key = (p.get("type"), p.get("server"), p.get("port"))
        if key in seen:
            continue
        seen.add(key)
        out.append(p)
        if len(out) >= limit:
            break
    return out


def links(proxies, types=LINK_TYPES):
    out = []
    for p in proxies:
        u = ss_uri(p) if p.get("type") == "ss" else v2ray_uri(p)
        if u:
            out.append(u)
    return out


def sip008(proxies, tag="Walls"):
    """Shadowsocks 官方 JSON 订阅格式。"""
    servers = []
    for p in pick(proxies, SS_TYPES):
        item = {
            "id": str(uuid.uuid3(uuid.NAMESPACE_URL, "{}:{}:{}".format(
                p.get("server"), p.get("port"), p.get("cipher") or p.get("method")))),
            "remarks": _name(p),
            "server": p.get("server"),
            "server_port": _int(p.get("port")),
            "method": p.get("cipher") or p.get("method") or "",
            "password": p.get("password", ""),
        }
        plugin = p.get("plugin")
        opts = p.get("plugin-opts") or {}
        if plugin == "obfs":
            parts = ["obfs-local", "obfs={}".format(opts.get("mode", "http"))]
            if opts.get("host"):
                parts.append("obfs-host={}".format(opts["host"]))
            item["plugin"] = ";".join(parts)
        elif plugin == "v2ray-plugin":
            parts = ["v2ray-plugin", "mode={}".format(opts.get("mode", "websocket"))]
            if opts.get("host"):
                parts.append("host={}".format(opts["host"]))
            if opts.get("path"):
                parts.append("path={}".format(opts["path"]))
            if opts.get("tls"):
                parts.append("tls")
            item["plugin"] = ";".join(parts)
        servers.append(item)
    return json.dumps({"version": 1, "servers": servers}, ensure_ascii=False, indent=2)


def gui_config(proxies):
    """老版 shadowsocks 客户端的 gui-config.json（就是个数组）。"""
    arr = []
    for p in pick(proxies, SS_TYPES):
        arr.append({
            "server": p.get("server"),
            "server_port": _int(p.get("port")),
            "password": p.get("password", ""),
            "method": p.get("cipher") or p.get("method") or "",
            "remarks": _name(p),
            "timeout": 300,
        })
    return json.dumps(arr, ensure_ascii=False, indent=2)


def singbox_outbounds(proxies, tag="🚀 自动选择"):
    """sing-box 的 outbounds 片段。

    只给 outbounds（外加一个 selector），不动 inbounds/route，
    这样不会跟你自己那份配置里的版本差异打架：把这几个对象粘进 outbounds 数组即可。
    """
    out = []
    names = []
    for p in proxies:
        t = p.get("type")
        server, port = p.get("server"), _int(p.get("port"))
        if not server or not port:
            continue
        o = {"tag": _name(p), "server": server, "server_port": port}
        tls = {}
        if p.get("tls"):
            tls = {"enabled": True}
            if p.get("servername") or p.get("sni"):
                tls["server_name"] = p.get("servername") or p.get("sni")
            if p.get("skip-cert-verify"):
                tls["insecure"] = True
        path, ws_host = _ws_opts(p)
        transport = None
        if (p.get("network") or "tcp") == "ws":
            transport = {"type": "ws", "path": path}
            if ws_host:
                transport["headers"] = {"Host": ws_host}

        if t == "ss":
            o.update({"type": "shadowsocks", "method": p.get("cipher") or p.get("method"),
                      "password": p.get("password", "")})
        elif t == "vmess":
            o.update({"type": "vmess", "uuid": p.get("uuid", ""), "security": p.get("cipher") or "auto",
                      "alter_id": _int(p.get("alterId", 0))})
        elif t == "vless":
            o.update({"type": "vless", "uuid": p.get("uuid", "")})
            if p.get("flow"):
                o["flow"] = p["flow"]
        elif t == "trojan":
            o.update({"type": "trojan", "password": p.get("password", "")})
            tls = tls or {"enabled": True}
        elif t in ("hysteria2", "hysteria"):
            o.update({"type": "hysteria2", "password": p.get("password", "")})
            tls = tls or {"enabled": True}
        else:
            continue

        if tls:
            o["tls"] = tls
        if transport:
            o["transport"] = transport
        out.append(o)
        names.append(o["tag"])

    if not names:
        return json.dumps([], ensure_ascii=False, indent=2)
    out.append({"type": "selector", "tag": tag, "outbounds": names, "default": names[0]})
    return json.dumps(out, ensure_ascii=False, indent=2)


# ----------------------------------------------------------------- 主入口

def report_md(proxies, title="节点清单"):
    """单独跑的时候补一份简单的清单报告（daily_auto.py 自己会生成带测速数据的版本）。"""
    lines = [f"# {title}", "", f"- 节点数: {len(proxies)}", "",
             "| 排名 | 节点 | 协议 | 地址 |", "|---|---|---|---|"]
    for i, p in enumerate(proxies, 1):
        lines.append("| {} | {} | {} | {}:{} |".format(
            i, p.get("name"), p.get("type"), p.get("server"), p.get("port")))
    return "\n".join(lines) + "\n"


def write_all(outdir, proxies, pool=None, meta=None):
    """把所有格式写到 outdir，返回写出去的文件名列表。

    proxies 是最终入选、按速度降序的那批（Clash 用户用这份）。
    pool 是「所有测过且存活」的更大池子。加 pool 是因为入选的前几名常常全是
    http/socks5 这类没法转成分享链接的协议，纯 Shadowsocks / v2rayN 用户会拿到空文件，
    所以这些格式会再从 pool 里按协议补一批。
    """
    meta = meta or {}
    os.makedirs(outdir, exist_ok=True)
    written = []

    def dump(name, text):
        with open(os.path.join(outdir, name), "w", encoding="utf-8") as f:
            f.write(text if text.endswith("\n") else text + "\n")
        written.append(name)

    pool = list(pool or proxies)

    # Shadowsocks 系：入选里有就先用，不够再从池子里补
    ss_nodes = _by_type(list(proxies) + pool, SS_TYPES, PER_TYPE_KEEP["ss"])

    # v2ray 系分享链接：先放入选的，再按协议补齐那些被 http 挤掉的
    v2, seen_uri = [], set()

    def add_link(p):
        u = ss_uri(p) if p.get("type") == "ss" else v2ray_uri(p)
        if u and u not in seen_uri:
            seen_uri.add(u)
            v2.append(u)

    for p in proxies:
        add_link(p)
    for t, limit in PER_TYPE_KEEP.items():
        for p in _by_type(pool, (t,), limit):
            add_link(p)

    # Shadowsocks 系
    dump("sip008.json", sip008(ss_nodes))
    dump("gui-config.json", gui_config(ss_nodes))
    ss_links = [u for u in (ss_uri(p) for p in ss_nodes) if u]
    dump("ss-plain.txt", "\n".join(ss_links))
    dump("ss-base64.txt", _b64("\n".join(ss_links)))

    # v2ray 系
    dump("all-links.txt", "\n".join(v2))
    dump("v2ray-base64.txt", _b64("\n".join(v2)))

    # sing-box
    dump("singbox-outbounds.json", singbox_outbounds(list(proxies) + pool))

    counts = Counter(p.get("type") for p in proxies)
    print("已导出客户端格式到 {}：{}".format(outdir, "、".join(written)))
    print("  入选协议分布：" + "、".join("{}×{}".format(k, v) for k, v in counts.most_common()))
    print("  实际写入：ss {} 条，v2ray 分享链接 {} 条".format(len(ss_links), len(v2)))
    if not ss_nodes:
        print("  提示：测速存活的节点里这次没有 Shadowsocks，sip008 / ss-* 只能是空的。")
        print("        （候选池里是有 ss 的，只是没测活；可多跑一次或放宽 --max-delay）")
    if not v2:
        print("  提示：池子里没有能转成分享链接的协议（vmess/vless/trojan/ss/hy2/tuic）。")
    return written


def main():
    ap = argparse.ArgumentParser(description="把 Clash 节点列表导出成各种客户端格式")
    ap.add_argument("--in", dest="src", default=r"D:\Walls\tools\output\best_nodes.yml")
    ap.add_argument("--out", dest="out", default=r"D:\Walls\tools\output")
    ap.add_argument("--top", type=int, default=0, help="只导出前 N 个（0 表示全部）")
    args = ap.parse_args()

    with open(args.src, "r", encoding="utf-8") as f:
        data = yaml.safe_load(f) or {}
    proxies = data.get("proxies", [])
    if args.top > 0:
        proxies = proxies[: args.top]
    write_all(args.out, proxies)
    with open(os.path.join(args.out, "report.md"), "w", encoding="utf-8") as f:
        f.write(report_md(proxies, f"节点清单（{os.path.basename(args.src)}）"))
    print("已重新生成 report.md")


if __name__ == "__main__":
    main()
