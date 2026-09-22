#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
独立免费节点抓取器（不依赖 NoMoreWalls 仓库）

从多个公开订阅源抓取免费节点，解析并合并为 Clash Meta 配置，
即使 NoMoreWalls 仓库因任何原因无法访问，本脚本仍可独立工作。

用法:
  python fetch_nodes.py
  python fetch_nodes.py --max-nodes 1000 --out D:\\Walls\\tools\\output

输出:
  my_nodes.yml      -- 可直接导入 Clash Verge 的订阅(Clash Meta 格式)
  my_nodes.txt      -- Base64 节点列表(通用订阅格式)
  sources_result.csv -- 各来源抓取统计

代理说明: 若存在 local_proxy.conf(内容形如 http://127.0.0.1:7897/)，
抓取请求会走该代理; 内容为 NONE 或文件不存在则直连。
"""

import argparse
import base64
import binascii
import csv
import ipaddress
import json
import os
import re
import sys
import urllib.parse
from datetime import datetime

import requests
import yaml

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")

# ============ 可自行增删的节点源 ============
# 每行一个订阅。URL 末尾可加 #type=list 表示该页面里包含多个订阅链接(自动展开)。
SOURCES = [
    # --- Clash / YAML 格式源 ---
    "https://raw.githubusercontent.com/ripaojiedian/freenode/main/clash",
    "https://raw.githubusercontent.com/ripaojiedian/freenode/main/sub",
    "https://raw.githubusercontent.com/zhangkaiitugithub/passcro/main/speednodes.yaml",
    "https://raw.githubusercontent.com/shaoyouvip/free/refs/heads/main/all.yaml",
    "https://raw.githubusercontent.com/anaer/Sub/main/clash.yaml",
    "https://raw.githubusercontent.com/learnhard-cn/free_proxy_ss/main/clash/clash.provider.yaml",
    # --- Base64 / 通用列表源 ---
    "https://raw.githubusercontent.com/Surfboardv2ray/TGParse/main/python/hy2",
    "https://raw.githubusercontent.com/Surfboardv2ray/TGParse/main/python/hysteria2",
    "https://raw.githubusercontent.com/freefq/free/master/v2",
    "https://raw.githubusercontent.com/aiboboxx/v2rayfree/main/v2",
    "https://raw.githubusercontent.com/Pawdroid/Free-servers/main/sub",
    "https://raw.githubusercontent.com/mfuu/v2ray/master/v2ray",
    # --- 订阅列表页(自动展开其中链接) ---
    "https://raw.githubusercontent.com/abshare/abshare.github.io/main/README.md#type=list",
]
# ===========================================

URI_RE = re.compile(
    r"(?:vmess|vless|trojan|ss|ssr|hy2|hysteria2|hysteria|tuic)://[^\s\"'<>\\]+"
)
HTTP_RE = re.compile(r"https?://[^\s\"'<>()\\]+")


def b64d(s):
    s = s.strip()
    s += "=" * (-len(s) % 4)
    try:
        return base64.urlsafe_b64decode(s).decode("utf-8", "replace")
    except (binascii.Error, ValueError):
        try:
            return base64.b64decode(s).decode("utf-8", "replace")
        except (binascii.Error, ValueError):
            return None


def b64e(s):
    return base64.b64encode(s.encode("utf-8")).decode("utf-8")


def is_decoy(server):
    s = (server or "").strip().lower()
    if not s or s in ("localhost", "127.0.0.1") or s.startswith("127."):
        return True
    try:
        ip = ipaddress.ip_address(s.split("%")[0])
        if (
            ip.is_private
            or ip.is_loopback
            or ip.is_link_local
            or ip.is_multicast
            or ip.is_reserved
            or ip.is_unspecified
        ):
            return True
    except ValueError:
        pass
    return False


def split_host_port(hostport):
    hp = hostport.split("#", 1)[0].strip()
    if ":" in hp:
        host, port = hp.rsplit(":", 1)
        try:
            return host.strip(), int(port)
        except ValueError:
            pass
    return hp, None


def parse_vmess(uri):
    raw = uri[len("vmess://"):]
    try:
        data = json.loads(b64d(raw) or raw)
    except (json.JSONDecodeError, ValueError):
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            return None
    host, port = data.get("add"), data.get("port")
    if not host or not port:
        return None
    name = data.get("ps") or f"{host}:{port}"
    p = {
        "name": name,
        "type": "vmess",
        "server": host,
        "port": int(port),
        "uuid": data.get("id"),
        "alterId": int(data.get("aid") or 0),
        "cipher": data.get("scy") or "auto",
    }
    net = data.get("net") or "tcp"
    if net != "tcp":
        p["network"] = net
    if net == "ws":
        opts = {"path": data.get("path") or "/"}
        if data.get("host"):
            opts["headers"] = {"Host": data["host"]}
        p["ws-opts"] = opts
    if data.get("tls") in ("tls", True, "1"):
        p["tls"] = True
        p["servername"] = data.get("sni") or data.get("host") or host
    return p


def parse_vless(uri):
    m = re.match(r"vless://([^@]+)@([^:/?#]+):(\d+)([^#]*)(?:#(.*))?$", uri)
    if not m:
        return None
    uuid, host, port, query, frag = m.groups()
    q = urllib.parse.parse_qs(query.lstrip("?"))
    p = {
        "name": urllib.parse.unquote(frag or "") or f"{host}:{port}",
        "type": "vless",
        "server": host,
        "port": int(port),
        "uuid": uuid,
        "cipher": "auto",
    }
    net = q.get("type", ["tcp"])[0]
    if net not in ("tcp", "none"):
        p["network"] = net
    security = q.get("security", [""])[0]
    if security in ("tls", "reality", "xtls"):
        p["tls"] = True
        p["servername"] = q.get("sni", [host])[0]
        if security == "reality":
            pk = q.get("pbk", [None])[0]
            if pk:
                p["reality-opts"] = {"public-key": pk, "short-id": q.get("sid", [""])[0]}
    if q.get("flow", [""])[0]:
        p["flow"] = q["flow"][0]
    if q.get("allowInsecure", ["0"])[0] in ("1", "true") or q.get("insecure", ["0"])[0] in ("1", "true"):
        p["skip-cert-verify"] = True
    if q.get("fp", [None])[0]:
        p["client-fingerprint"] = q["fp"][0]
    if net == "ws":
        opts = {"path": q.get("path", ["/"])[0]}
        hosth = q.get("host", [None])[0]
        if hosth:
            opts["headers"] = {"Host": hosth}
        p["ws-opts"] = opts
    elif net in ("grpc",):
        p["grpc-opts"] = {"grpc-service-name": q.get("serviceName", [""])[0]}
    return p


def parse_trojan(uri):
    m = re.match(r"trojan://([^@]*)@([^:/?#]+):(\d+)([^#]*)(?:#(.*))?$", uri)
    if not m:
        return None
    password, host, port, query, frag = m.groups()
    q = urllib.parse.parse_qs(query.lstrip("?"))
    p = {
        "name": urllib.parse.unquote(frag or "") or f"{host}:{port}",
        "type": "trojan",
        "server": host,
        "port": int(port),
        "password": password,
        "udp": True,
    }
    security = q.get("security", ["tls"])[0]
    if security in ("tls", "reality"):
        p["tls"] = True
        p["servername"] = q.get("sni", [host])[0]
    if q.get("allowInsecure", ["0"])[0] in ("1", "true"):
        p["skip-cert-verify"] = True
    if q.get("fp", [None])[0]:
        p["client-fingerprint"] = q["fp"][0]
    net = q.get("type", ["tcp"])[0]
    if net == "ws":
        p["network"] = "ws"
        opts = {"path": q.get("path", ["/"])[0]}
        hosth = q.get("host", [None])[0]
        if hosth:
            opts["headers"] = {"Host": hosth}
        p["ws-opts"] = opts
    elif net in ("grpc",):
        p["network"] = "grpc"
        p["grpc-opts"] = {"grpc-service-name": q.get("serviceName", [""])[0]}
    return p


def parse_ss(uri):
    raw = uri[len("ss://"):]
    frag = ""
    if "#" in raw:
        raw, frag = raw.split("#", 1)
    if "@" in raw:
        b64part, hostport = raw.split("@", 1)
        dec = b64d(b64part)
        if not dec or ":" not in dec:
            return None
        method, password = dec.split(":", 1)
        host, port = split_host_port(hostport)
    else:
        dec = b64d(raw)
        if not dec:
            return None
        parts = dec.split(":")
        if len(parts) < 4:
            return None
        method, password, host = parts[0], parts[1], parts[2]
        port = int(parts[3])
    if not host or not port:
        return None
    return {
        "name": urllib.parse.unquote(frag) or f"{host}:{port}",
        "type": "ss",
        "server": host,
        "port": port,
        "cipher": method,
        "password": password,
        "udp": True,
    }


def parse_ssr(uri):
    raw = uri[len("ssr://"):]
    dec = b64d(raw)
    if not dec:
        return None
    body, _, query = dec.partition("?")
    parts = body.split(":")
    if len(parts) < 6:
        return None
    server, port, protocol, method, obfs, passb64 = parts[:6]
    q = urllib.parse.parse_qs(query)
    p = {
        "name": b64d(q.get("remarks", [""])[0]) or f"{server}:{port}",
        "type": "ssr",
        "server": server,
        "port": int(port),
        "cipher": method,
        "password": b64d(passb64) or "",
        "protocol": protocol,
        "obfs": obfs,
        "udp": True,
    }
    if q.get("obfsparam", [""])[0]:
        p["obfs-param"] = b64d(q["obfsparam"][0]) or ""
    if q.get("protoparam", [""])[0]:
        p["protocol-param"] = b64d(q["protoparam"][0]) or ""
    return p


def parse_hysteria2(uri):
    m = re.match(r"(?:hysteria2|hy2)://([^@]*)@([^:/?#]+):(\d+)([^#]*)(?:#(.*))?$", uri)
    if not m:
        return None
    auth, host, port, query, frag = m.groups()
    q = urllib.parse.parse_qs(query.lstrip("?"))
    p = {
        "name": urllib.parse.unquote(frag or "") or f"{host}:{port}",
        "type": "hysteria2",
        "server": host,
        "port": int(port),
        "password": auth,
    }
    if q.get("sni", [None])[0]:
        p["sni"] = q["sni"][0]
    if q.get("insecure", ["0"])[0] in ("1", "true"):
        p["skip-cert-verify"] = True
    if q.get("obfs", [None])[0]:
        p["obfs"] = q["obfs"][0]
    if q.get("obfs-password", [None])[0]:
        p["obfs-password"] = q["obfs-password"][0]
    return p


def parse_hysteria(uri):
    m = re.match(r"hysteria://([^:/?#]+):(\d+)([^#]*)(?:#(.*))?$", uri)
    if not m:
        return None
    host, port, query, frag = m.groups()
    q = urllib.parse.parse_qs(query.lstrip("?"))
    p = {
        "name": urllib.parse.unquote(frag or "") or f"{host}:{port}",
        "type": "hysteria",
        "server": host,
        "port": int(port),
        "protocol": q.get("protocol", ["udp"])[0],
        "auth-str": q.get("auth", [""])[0],
        "up": q.get("up", ["100"])[0],
        "down": q.get("down", ["100"])[0],
        "udp": True,
    }
    if q.get("sni", [None])[0]:
        p["sni"] = q["sni"][0]
    if q.get("insecure", ["0"])[0] in ("1", "true"):
        p["skip-cert-verify"] = True
    return p


def parse_tuic(uri):
    m = re.match(r"tuic://([^@]+)@([^:/?#]+):(\d+)([^#]*)(?:#(.*))?$", uri)
    if not m:
        return None
    userinfo, host, port, query, frag = m.groups()
    uuid, _, password = userinfo.partition(":")
    q = urllib.parse.parse_qs(query.lstrip("?"))
    p = {
        "name": urllib.parse.unquote(frag or "") or f"{host}:{port}",
        "type": "tuic",
        "server": host,
        "port": int(port),
        "uuid": uuid,
        "password": password,
    }
    if q.get("congestion_control", [None])[0]:
        p["congestion-controller"] = q["congestion_control"][0]
    if q.get("udp_relay_mode", [None])[0]:
        p["udp-relay-mode"] = q["udp_relay_mode"][0]
    if q.get("alpn", [None])[0]:
        p["alpn"] = q["alpn"][0].split(",")
    if q.get("sni", [None])[0]:
        p["sni"] = q["sni"][0]
    if q.get("allow_insecure", ["0"])[0] in ("1", "true"):
        p["skip-cert-verify"] = True
    return p


def uri_to_proxy(uri):
    try:
        scheme = uri.split(":", 1)[0].lower()
        if scheme == "vmess":
            return parse_vmess(uri)
        if scheme == "vless":
            return parse_vless(uri)
        if scheme == "trojan":
            return parse_trojan(uri)
        if scheme == "ss":
            return parse_ss(uri)
        if scheme == "ssr":
            return parse_ssr(uri)
        if scheme in ("hy2", "hysteria2"):
            return parse_hysteria2(uri)
        if scheme == "hysteria":
            return parse_hysteria(uri)
        if scheme == "tuic":
            return parse_tuic(uri)
    except (ValueError, IndexError, KeyError, TypeError):
        return None
    return None


def extract_uris(text):
    return list(dict.fromkeys(URI_RE.findall(text)))


def parse_content(text):
    """返回 (uris, proxies)"""
    proxies = []
    try:
        data = yaml.safe_load(text)
        if isinstance(data, dict) and isinstance(data.get("proxies"), list):
            for p in data["proxies"]:
                if isinstance(p, dict) and p.get("server") and p.get("name"):
                    proxies.append(p)
            if proxies:
                return [], proxies
    except yaml.YAMLError:
        pass

    uris = extract_uris(text)
    if uris:
        return uris, []

    dec = b64d(text[:20000])
    if dec:
        uris = extract_uris(dec)
        if uris:
            return uris, []
    return [], []


def expand_list_page(text, fetch):
    """订阅列表页: 抓取页面里的每个订阅链接"""
    links = HTTP_RE.findall(text)
    out = []
    for link in links[:20]:
        link = link.rstrip(".,;)]}")
        if any(s in link for s in (".png", ".jpg", ".svg", ".css", ".js", "github.com/")):
            continue
        try:
            r = fetch(link)
            if r is None:
                continue
            u, p = parse_content(r)
            out.extend(u)
            out.extend(p)
        except Exception:  # noqa: BLE001
            continue
    return out


def load_proxy_setting():
    for path in (
        r"D:\Walls\NoMoreWalls\local_proxy.conf",
        r"D:\Walls\tools\local_proxy.conf",
    ):
        if os.path.exists(path):
            try:
                with open(path, "r", encoding="utf-8") as f:
                    v = f.read().strip()
                return None if v in ("", "NONE") else v
            except OSError:
                return None
    return None


def main():
    ap = argparse.ArgumentParser(description="独立免费节点抓取器")
    ap.add_argument("--max-nodes", type=int, default=1000)
    ap.add_argument("--out", default=r"D:\Walls\tools\output")
    ap.add_argument("--timeout", type=int, default=20)
    ap.add_argument("--sources-file", default=None, help="可选: 每行一个订阅链接的文本文件")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    proxy = load_proxy_setting()
    session = requests.Session()
    session.trust_env = False
    session.headers.update({"User-Agent": UA})
    if proxy:
        session.proxies = {"http": proxy, "https": proxy}

    def fetch(url):
        try:
            r = session.get(url, timeout=args.timeout)
            if r.status_code == 200:
                return r.text
        except requests.RequestException:
            pass
        return None

    sources = list(SOURCES)
    if args.sources_file and os.path.exists(args.sources_file):
        with open(args.sources_file, "r", encoding="utf-8") as f:
            sources = [ln.strip() for ln in f if ln.strip() and not ln.startswith("#")]

    all_uris = []
    all_proxies = []
    rows = []
    for i, src in enumerate(sources):
        url = src.split("#")[0].strip()
        is_list = "#type=list" in src
        text = fetch(url)
        if text is None:
            rows.append((i, url, "失败"))
            continue
        if is_list:
            merged = expand_list_page(text, fetch)
            uris = [u for u in merged if isinstance(u, str)]
            proxies = [p for p in merged if isinstance(p, dict)]
        else:
            uris, proxies = parse_content(text)
        all_uris.extend(uris)
        all_proxies.extend(proxies)
        rows.append((i, url, len(uris) + len(proxies)))
        print(f"[{i}] {url} -> {len(uris) + len(proxies)} 节点")

    # 合并 & 去重 & 过滤
    proxies = list(all_proxies)
    seen_keys = set()
    for u in all_uris:
        p = uri_to_proxy(u)
        if p:
            proxies.append(p)

    clean = []
    for p in proxies:
        if is_decoy(p.get("server")):
            continue
        key = (p.get("type"), p.get("server"), p.get("port"), str(p.get("uuid") or p.get("password") or "")[:12])
        if key in seen_keys:
            continue
        seen_keys.add(key)
        clean.append(p)

    clean = clean[: args.max_nodes]
    names = set()
    for p in clean:
        n = p["name"]
        base = n
        i = 2
        while n in names:
            n = f"{base} #{i}"
            i += 1
        names.add(n)
        p["name"] = n

    # 输出
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    yml_path = os.path.join(args.out, "my_nodes.yml")
    cfg = {
        "proxies": clean,
        "proxy-groups": [
            {
                "name": "🚀 自动选择",
                "type": "url-test",
                "url": "https://www.gstatic.com/generate_204",
                "interval": 300,
                "tolerance": 80,
                "proxies": [p["name"] for p in clean],
            },
            {"name": "🐟 手动选择", "type": "select", "proxies": [p["name"] for p in clean]},
        ],
        "rules": ["MATCH,🚀 自动选择"],
    }
    with open(yml_path, "w", encoding="utf-8") as f:
        yaml.safe_dump(cfg, f, allow_unicode=True, sort_keys=False)

    txt_path = os.path.join(args.out, "my_nodes.txt")
    with open(txt_path, "w", encoding="utf-8") as f:
        f.write(b64e("\n".join(all_uris)))

    csv_path = os.path.join(args.out, "sources_result.csv")
    with open(csv_path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["序号", "来源", "节点数"])
        w.writerows(rows)
        w.writerow(["总计", "", len(clean)])

    print(f"\n抓取完成({now}): 原始 {len(all_uris) + len(all_proxies)} -> 去重过滤后 {len(clean)} 个节点")
    print(f"Clash Meta 订阅: {yml_path}")
    print(f"Base64 列表:     {txt_path}")
    print(f"来源统计:        {csv_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
