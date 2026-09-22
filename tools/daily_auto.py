#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一体化免费节点管家：抓取 -> 测速 -> 按速度排序 -> 生成订阅 -> 同步 Clash Verge

与旧的 fetch_nodes.py / node_quality.py 的关系：
  - 复用这两个脚本里的"节点解析"与"独立 mihomo 内核测速"能力，
    但不走它们各自的 main()，而是重新编排成一条流水线。
  - 关键改进：
      1) 抓取源自动多镜像兜底（raw.githubusercontent 直连 -> jsDelivr -> gh-proxy）
      2) 用 url-test 组批量并发测延迟（比旧版逐个 select 快一个数量级）
      3) 对存活节点测真实下载速度，按速度降序排序
      4) 自动写入 Clash Verge 的本地订阅（profiles.yaml + profiles/{uid}.yaml）
      5) 内置防重复运行 guard（默认 6 小时内不重复跑）

用法:
  python daily_auto.py                 # 正常完整流程
  python daily_auto.py --force         # 忽略 guard 强制跑
  python daily_auto.py --quick         # 只测延迟不测下载速度（快速）
  python daily_auto.py --skip-sync     # 只生成文件,不写 Clash Verge

依赖: requests, pyyaml, 以及 Clash Verge 自带的 verge-mihomo.exe
"""

import argparse
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.parse
from datetime import datetime

import requests
import yaml

# 复用同目录下已有脚本的解析与测速框架
import fetch_nodes as FN
import node_quality as NQ
import export_formats as EF

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

GOOGLE_204 = NQ.GOOGLE_204
CF_SPEED = NQ.CF_SPEED

# ============ 可自行增删的额外节点源 ============
# 与 fetch_nodes.SOURCES 合并使用。每行一个订阅，末尾 #type=list 表示页面里含多个订阅链接。
EXTRA_SOURCES = [
    # "https://raw.githubusercontent.com/xxx/yyy/main/zzz.yaml",
]
# ===============================================

# 聚合页种子：专门收集订阅链接的页面（README 等），脚本会展开它们、发现新订阅源
SEED_PAGES = [
    "https://raw.githubusercontent.com/ermaozi/get_subscribe/main/README.md",
]
# 动态源状态文件
DISCOVERED_FILE = r"D:\Walls\tools\state\discovered_sources.txt"
HEALTH_FILE = r"D:\Walls\tools\state\source_health.json"
DEAD_SOURCE_THRESHOLD = 5  # 连续失败 N 次就淘汰一个动态源
DISCOVER_MAX_CANDIDATES = 20  # 每次最多验证多少个候选链接

DEFAULT_MIHOMO = r"D:\Clash Verge\verge-mihomo.exe"
DEFAULT_OUT = r"D:\Walls\tools\output"
DEFAULT_STATE = r"D:\Walls\tools\state\last_run.txt"
VERGE_PROFILE_UID = "AutoBestNodes"
VERGE_PROFILE_NAME = "auto_best_nodes.yml"


def mirrors(url):
    """把一个订阅链接展开成多个候选（原链接 + 各镜像），按优先级排序。"""
    out = [url]
    m = re.match(
        r"https://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)", url
    )
    if m:
        user, repo, branch, path = m.groups()
        out.append(f"https://cdn.jsdelivr.net/gh/{user}/{repo}@{branch}/{path}")
        out.append(f"https://gh-proxy.com/{url}")
    return out


def _extract_candidate_urls(text):
    """从聚合页文本里提取候选订阅链接，过滤掉明显的客户端/社交/图片链接。"""
    out = []
    for u in FN.HTTP_RE.findall(text):
        u = u.rstrip(".,;)]}")
        low = u.lower()
        if any(s in low for s in (
            ".png", ".jpg", ".jpeg", ".svg", ".gif", ".ico", ".webp",
            "shields.io", "badge", "apps.apple.com", "play.google.com",
            "t.me/", "telegram", "twitter.com", "x.com/",
            "github.com/", "/releases", "/wiki/", "apkpure",
            "creativecommons", "wikipedia.org",
        )):
            continue
        out.append(u)
    return list(dict.fromkeys(out))


def _load_discovered():
    if os.path.exists(DISCOVERED_FILE):
        try:
            with open(DISCOVERED_FILE, "r", encoding="utf-8") as f:
                return [ln.strip() for ln in f if ln.strip() and not ln.startswith("#")]
        except OSError:
            pass
    return []


def _save_discovered(urls):
    try:
        os.makedirs(os.path.dirname(DISCOVERED_FILE), exist_ok=True)
        with open(DISCOVERED_FILE, "w", encoding="utf-8") as f:
            f.write("\n".join(urls) + "\n")
    except OSError:
        pass


def _load_health():
    try:
        with open(HEALTH_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def _save_health(h):
    try:
        os.makedirs(os.path.dirname(HEALTH_FILE), exist_ok=True)
        with open(HEALTH_FILE, "w", encoding="utf-8") as f:
            json.dump(h, f, ensure_ascii=False, indent=2)
    except OSError:
        pass


def _port_open(port):
    try:
        s = socket.create_connection(("127.0.0.1", port), timeout=1.0)
        s.close()
        return True
    except OSError:
        return False


def _get_local_proxy():
    """读 local_proxy.conf，若代理端口确实在监听则返回地址，否则 None。"""
    for path in (r"D:\Walls\NoMoreWalls\local_proxy.conf", r"D:\Walls\tools\local_proxy.conf"):
        if not os.path.exists(path):
            continue
        try:
            v = open(path, "r", encoding="utf-8").read().strip()
        except OSError:
            continue
        if not v or v.upper() == "NONE":
            continue
        m = re.search(r"(\d+)", v)
        if m and _port_open(int(m.group(1))):
            return v
    return None


def discover_new_sources(timeout):
    """从聚合页种子展开并验证新的订阅源，返回新发现的源 URL 列表。"""
    t = min(timeout, 10)
    session = requests.Session()
    session.trust_env = False
    session.headers.update({"User-Agent": FN.UA})
    proxy = _get_local_proxy()

    def fetch(url):
        for cand in mirrors(url):
            try:
                r = session.get(cand, timeout=t)
                if r.status_code == 200 and r.text:
                    return r.text
            except requests.RequestException:
                continue
        if proxy:
            try:
                r = session.get(url, timeout=t, proxies={"http": proxy, "https": proxy})
                if r.status_code == 200 and r.text:
                    return r.text
            except requests.RequestException:
                pass
        return None

    known = {s.split("#")[0].strip() for s in list(FN.SOURCES) + list(EXTRA_SOURCES)}
    known.update(_load_discovered())

    candidates = []
    for page in SEED_PAGES:
        text = fetch(page)
        if not text:
            print(f"聚合页种子不可达: {page}")
            continue
        for u in _extract_candidate_urls(text):
            if u not in known and u not in candidates:
                candidates.append(u)
        print(f"聚合页 {page} -> 提取 {len(candidates)} 个候选订阅链接")

    valid = []
    for u in candidates[:DISCOVER_MAX_CANDIDATES]:
        text = fetch(u)
        if not text:
            continue
        uris, proxies = FN.parse_content(text)
        if uris or proxies:
            valid.append(u)
            print(f"  发现新订阅源: {u} ({len(uris) + len(proxies)} 节点)")
    return valid


# 各协议在 mihomo 里必须齐备的字段；缺一个它就会拒绝整个配置
REQUIRED_FIELDS = {
    "ss": ("cipher", "password"),
    "ssr": ("cipher", "password", "protocol", "obfs"),
    "vmess": ("uuid",),
    "vless": ("uuid",),
    "trojan": ("password",),
    "hysteria2": ("password",),
    "hysteria": ("auth-str",),
    "tuic": ("password",),
    "anytls": ("password",),
    "http": (),
    "socks5": (),
}


def proxy_field_error(p):
    """返回「缺哪个字段」的说明；没问题返回 None。"""
    t = (p.get("type") or "").lower()
    if t not in REQUIRED_FIELDS:
        return f"不认识的协议 {t or '?'}"
    for f in REQUIRED_FIELDS[t]:
        v = p.get(f)
        if v is None or (isinstance(v, str) and not v.strip()):
            return f"缺字段 {f}"
    if t == "hysteria" and not (p.get("auth-str") or p.get("auth_str")):
        return "缺字段 auth-str"
    return None


def _bad_proxy_index(msg):
    """从 mihomo 的报错里取出坏节点的下标：proxy 424: '' has unset fields: password"""
    m = re.search(r"proxy (\d+):", msg or "")
    return int(m.group(1)) if m else None


def _parse_floors(spec):
    """把 "ss=80,vmess=60" 解析成 dict。"""
    out = {}
    for part in (spec or "").split(","):
        part = part.strip()
        if not part or "=" not in part:
            continue
        k, v = part.split("=", 1)
        try:
            out[k.strip().lower()] = int(v)
        except ValueError:
            continue
    return out


DEFAULT_MIN_PER_TYPE = "ss=80,vmess=80,vless=60,trojan=40,hysteria2=40,tuic=20,anytls=20"


def _clean_proxy_fields(p):
    """去掉值为 None / 'None' / 'null' / 空串的垃圾字段，避免污染 Clash 配置。"""
    out = {}
    for k, v in p.items():
        if v is None:
            continue
        if isinstance(v, str) and v.strip().lower() in ("", "none", "null"):
            continue
        out[k] = v
    return out


def fetch_all(max_nodes, timeout, min_per_type=DEFAULT_MIN_PER_TYPE):
    """抓取全部来源（含动态发现的源）并解析合并，返回 (proxies, ok_urls, fail_urls)。"""
    session = requests.Session()
    session.trust_env = False  # 不走系统代理/环境变量代理，避免被坏代理卡死
    session.headers.update({"User-Agent": FN.UA})
    proxy = _get_local_proxy()

    def fetch(url):
        for cand in mirrors(url):
            try:
                r = session.get(cand, timeout=timeout)
                if r.status_code == 200 and r.text:
                    return r.text
            except requests.RequestException:
                continue
        if proxy:
            try:
                r = session.get(url, timeout=timeout, proxies={"http": proxy, "https": proxy})
                if r.status_code == 200 and r.text:
                    return r.text
            except requests.RequestException:
                pass
        return None

    # 静态源 + 动态发现的源（剔除连续失败过多的死源），并去重
    health = _load_health()
    discovered = [d for d in _load_discovered()
                  if health.get(d, {}).get("fail", 0) < DEAD_SOURCE_THRESHOLD]
    sources = list(FN.SOURCES) + list(EXTRA_SOURCES) + discovered
    seen_src = set()
    uniq = []
    for s in sources:
        if s not in seen_src:
            seen_src.add(s)
            uniq.append(s)
    sources = uniq

    all_uris, all_proxies = [], []
    groups = []  # 每个源一组，供轮转挑选
    ok_urls, fail_urls = [], []
    for i, src in enumerate(sources):
        url = src.split("#")[0].strip()
        is_list = "#type=list" in src
        text = fetch(url)
        if text is None:
            fail_urls.append(url)
            print(f"[{i:02d}] FAIL   {url}")
            continue
        ok_urls.append(url)
        if is_list:
            merged = FN.expand_list_page(text, fetch)
            uris = [u for u in merged if isinstance(u, str)]
            proxies = [p for p in merged if isinstance(p, dict)]
        else:
            uris, proxies = FN.parse_content(text)
        all_uris.extend(uris)
        all_proxies.extend(proxies)
        # 每个源单独留一组，后面轮转挑选用
        groups.append(list(proxies) + [FN.uri_to_proxy(u) for u in uris])
        print(f"[{i:02d}] {len(uris) + len(proxies):>5d}  {url}")

    # 去重 + 过滤内网/假节点 + 轮转挑选
    #
    # 注意这里不能用「拼成一个大会表再截前 max_nodes 条」的写法：
    # 那样前面几个大源会把名额吃光，后面源里的节点（比如 ShadowsocksAggregator
    # 里 4000 多个中的 1800 个 ss）一个都进不来。改成每个源轮流取一条，
    # 名额就摊平了，ss / vmess 这些小众协议才有机会被测到。
    clean, seen = [], set()
    dropped_field = {}

    def _accept(p):
        if not isinstance(p, dict) or not p.get("server"):
            return None
        if FN.is_decoy(p.get("server")):
            return None
        bad = proxy_field_error(p)
        if bad:
            dropped_field[bad.split()[1] if " " in bad else bad] = \
                dropped_field.get(bad.split()[1] if " " in bad else bad, 0) + 1
            return None
        key = (
            p.get("type"),
            p.get("server"),
            p.get("port"),
            str(p.get("uuid") or p.get("password") or "")[:12],
        )
        if key in seen:
            return None
        seen.add(key)
        return _clean_proxy_fields(p)

    cursors = [0] * len(groups)
    while len(clean) < max_nodes:
        progressed = False
        for gi, g in enumerate(groups):
            while cursors[gi] < len(g):
                item = _accept(g[cursors[gi]])
                cursors[gi] += 1
                if item is None:
                    continue
                clean.append(item)
                progressed = True
                break
            if len(clean) >= max_nodes:
                break
        if not progressed:
            break
    type_stat = {}
    for p in clean:
        type_stat[p.get("type")] = type_stat.get(p.get("type"), 0) + 1

    # ===== 协议保底 =====
    # 只按源轮转还不够：一个源内部 ss 可能排得很靠后。这里对「能转成客户端订阅」
    # 的协议做上浮，保证池子里每种协议都够测，不然用 Shadowsocks / v2rayN 的人
    # 永远拿到空文件。
    floors = _parse_floors(min_per_type)
    extra_budget = 300
    for t, floor in floors.items():
        need = floor - type_stat.get(t, 0)
        if need <= 0:
            continue
        got = 0
        for gi in range(len(groups)):
            g = groups[gi]
            while cursors[gi] < len(g) and got < need and extra_budget > 0:
                item = _accept(g[cursors[gi]])
                cursors[gi] += 1
                if item is None or item.get("type") != t:
                    continue
                clean.append(item)
                got += 1
                extra_budget -= 1
            if got >= need or extra_budget <= 0:
                break
        if got:
            type_stat[t] = type_stat.get(t, 0) + got

    if dropped_field:
        print("字段不全被丢弃：" + "、".join(f"{k}×{v}" for k, v in
                                      sorted(dropped_field.items(), key=lambda kv: -kv[1])))
    print("候选协议分布：" + "、".join(f"{k}×{v}" for k, v in
                                 sorted(type_stat.items(), key=lambda kv: -kv[1])))
    print(f"进入测速的候选：{len(clean)} 个（含协议保底补入的）")

    # 节点名去重
    names = set()
    for p in clean:
        n = p.get("name") or f"{p.get('server')}:{p.get('port')}"
        base, idx = n, 2
        while n in names:
            n = f"{base} #{idx}"
            idx += 1
        names.add(n)
        p["name"] = n
    return clean, ok_urls, fail_urls


def build_test_config(proxies, names, mixed_port, api_port, secret):
    return {
        "mixed-port": mixed_port,
        "allow-lan": False,
        "mode": "rule",
        "log-level": "error",
        "ipv6": False,
        "unified-delay": True,
        "external-controller": f"127.0.0.1:{api_port}",
        "secret": secret,
        "dns": {"enable": False},
        "proxies": proxies,
        "proxy-groups": [
            {
                "name": "TEST",
                "type": "url-test",
                "url": GOOGLE_204,
                "interval": 300,
                "tolerance": 50,
                "proxies": names,
            }
        ],
        "rules": ["MATCH,TEST"],
    }


def batch_delay(runner, group, url, timeout_ms):
    """用 url-test 组批量并发测延迟，返回 {name: delay_ms}，只含存活节点。"""
    q = urllib.parse.quote(url, safe="")
    try:
        r = runner.api(
            "GET",
            f"/group/{group}/delay?url={q}&timeout={timeout_ms}",
            timeout=max(30, timeout_ms * len(str(group)) // 1000 + 60),
        )
        if r.status_code == 200:
            data = r.json()
            if isinstance(data, dict):
                out = {}
                for k, v in data.items():
                    if isinstance(v, (int, float)) and v > 0:
                        out[str(k)] = int(v)
                return out
    except requests.RequestException:
        pass
    return {}


def fallback_delay(runner, names, timeout_ms, limit=200):
    """兜底：逐个测延迟（当批量 API 异常时使用），只测前 limit 个。"""
    out = {}
    for name in names[:limit]:
        d = runner.delay(name, timeout_ms)
        if d:
            out[name] = d
    return out


def speedtest(pool, args):
    """起独立 mihomo 内核，对候选节点批量测延迟 + 真实下载速度。"""
    names = [p["name"] for p in pool]
    mixed = NQ.free_port()
    api = NQ.free_port()
    secret = secrets.token_hex(8)
    tmp = tempfile.mkdtemp(prefix="autonodes-")
    cfg_path = os.path.join(tmp, "test.yaml")
    with open(cfg_path, "w", encoding="utf-8") as f:
        yaml.safe_dump(
            build_test_config(pool, names, mixed, api, secret),
            f,
            allow_unicode=True,
            sort_keys=False,
        )

    runner = NQ.MihomoRunner(args.mihomo, tmp, mixed, api, secret)
    results = []
    alive_count = 0
    try:
        # 安全网：mihomo 只要碰到一个不合法的节点就会整个拒绝启动。
        # 抓取阶段已经按协议校验过必填字段了，这里再兜一层 ——
        # 从它的报错里读出坏节点的下标，剔掉重试。
        for _ in range(8):
            try:
                runner.start(cfg_path)
                break
            except RuntimeError as e:
                idx = _bad_proxy_index(str(e))
                if idx is None or not (0 <= idx < len(pool)):
                    raise
                bad = pool.pop(idx)
                names = [p["name"] for p in pool]
                print(f"  mihomo 拒绝第 {idx} 个节点（{bad.get('name')} / {bad.get('type')}），剔除后重试")
                with open(cfg_path, "w", encoding="utf-8") as f:
                    yaml.safe_dump(
                        build_test_config(pool, names, mixed, api, secret),
                        f, allow_unicode=True, sort_keys=False,
                    )
        else:
            raise RuntimeError("mihomo 连续拒绝配置，放弃本次运行")
        session = NQ.http_session(mixed)

        # 阶段 A：批量延迟
        print("批量测延迟中 ...")
        delays = batch_delay(runner, "TEST", GOOGLE_204, args.max_delay)
        if not delays:
            print("批量延迟 API 无结果，回退逐个测（仅前 200 个）")
            delays = fallback_delay(runner, names, args.max_delay)
        alive = sorted(delays.items(), key=lambda kv: kv[1])
        alive_count = len(alive)
        print(f"延迟存活 {alive_count}/{len(names)}")

        if args.quick:
            results = [
                {"name": n, "delay": d, "speed": None} for n, d in alive[: args.top * 4]
            ]
        else:
            # 候选：延迟 <= speed_max_delay 的存活节点，按延迟升序取前 speed_top
            cand = [(n, d) for n, d in alive if d <= args.speed_max_delay]
            if not cand:
                cand = alive  # 全部都很慢时兜底
            cand = cand[: args.speed_top]
            print(f"开始对 {len(cand)} 个节点测下载速度 ...")
            for i, (name, d) in enumerate(cand, 1):
                runner.select(name)
                time.sleep(0.3)
                sp = NQ.measure_speed(session, CF_SPEED, 3 * 1024 * 1024, 12)
                results.append({"name": name, "delay": d, "speed": sp})
                if i % 10 == 0:
                    ok = sum(1 for r in results if r["speed"] and r["speed"] >= args.min_speed)
                    print(f"  速度测试 {i}/{len(cand)} ... 达标 {ok}")
    finally:
        runner.stop()
        shutil.rmtree(tmp, ignore_errors=True)
    return results, alive_count


def sync_to_verge(best_yml_path):
    """把生成的订阅写入 Clash Verge 的本地订阅，返回是否成功。"""
    appdata = os.environ.get("APPDATA", "")
    base = os.path.join(appdata, "io.github.clash-verge-rev.clash-verge-rev")
    profiles_yaml = os.path.join(base, "profiles.yaml")
    if not os.path.exists(profiles_yaml):
        return False

    try:
        shutil.copy2(profiles_yaml, profiles_yaml + ".bak")
        with open(profiles_yaml, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f) or {}
        items = data.setdefault("items", [])
        found = None
        for it in items:
            if isinstance(it, dict) and it.get("uid") == VERGE_PROFILE_UID:
                found = it
                break
        if found is None:
            found = {
                "uid": VERGE_PROFILE_UID,
                "type": "local",
                "name": VERGE_PROFILE_NAME,
                "file": VERGE_PROFILE_UID + ".yaml",
                "desc": "每日自动测速排序",
                "option": {"allow_auto_update": True},
            }
            items.append(found)
        found["updated"] = int(time.time())

        target = os.path.join(base, "profiles", VERGE_PROFILE_UID + ".yaml")
        shutil.copy2(best_yml_path, target)

        with open(profiles_yaml, "w", encoding="utf-8") as f:
            yaml.safe_dump(data, f, allow_unicode=True, sort_keys=False)
        return True
    except Exception as e:  # noqa: BLE001
        print(f"同步 Clash Verge 失败（不影响结果文件）: {e}")
        return False


def should_run(state_file, min_hours):
    # 运行锁：3 小时内有人跑过/正在跑则跳过，防止每日任务与开机自启并发
    lock_path = state_file + ".lock"
    if os.path.exists(lock_path):
        try:
            if time.time() - os.path.getmtime(lock_path) < 3 * 3600:
                return False
        except OSError:
            pass
    if os.path.exists(state_file):
        try:
            last = float(open(state_file, "r", encoding="utf-8").read().strip())
            if time.time() - last < min_hours * 3600:
                return False
        except (OSError, ValueError):
            pass
    return True


def main():
    ap = argparse.ArgumentParser(description="一体化免费节点管家")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--mihomo", default=DEFAULT_MIHOMO)
    ap.add_argument("--state-file", default=DEFAULT_STATE)
    ap.add_argument("--max-nodes", type=int, default=600, help="进入测速的候选节点上限")
    ap.add_argument("--max-delay", type=int, default=3000, help="延迟阈值 ms，超过视为死节点")
    ap.add_argument("--speed-top", type=int, default=150, help="做下载测速的节点数量")
    ap.add_argument("--speed-max-delay", type=int, default=1500, help="参与下载测速的延迟上限 ms")
    ap.add_argument("--min-speed", type=float, default=0.2, help="合格最低下载速度 MB/s")
    ap.add_argument("--top", type=int, default=40, help="最终保留节点数量")
    ap.add_argument(
        "--min-per-type",
        default=DEFAULT_MIN_PER_TYPE,
        help="候选池里每种协议至少留几个，例如 ss=80,vmess=80",
    )
    ap.add_argument(
        "--keep-per-type",
        default="ss=6,vmess=5,vless=5,trojan=4,hysteria2=3,tuic=2,anytls=2",
        help="最终名单里每种协议至少保留几条（从存活池按速度补），保证各客户端都有得用",
    )
    ap.add_argument(
        "--exclude-types",
        default="",
        help="排除某些协议，逗号分隔，例如 http,socks5（这类节点转不成分享链接，会挤掉别人）",
    )
    ap.add_argument("--timeout", type=int, default=20, help="抓取单源超时秒")
    ap.add_argument("--min-interval-hours", type=float, default=6.0, help="两次完整运行的最小间隔小时")
    ap.add_argument("--quick", action="store_true", help="只测延迟不测下载速度")
    ap.add_argument("--force", action="store_true", help="忽略 guard 强制运行")
    ap.add_argument("--skip-sync", action="store_true", help="不写 Clash Verge")
    ap.add_argument("--no-label", action="store_true", help="不给节点名加实测速度前缀")
    args = ap.parse_args()

    if not os.path.exists(args.mihomo):
        print(f"找不到 mihomo 内核: {args.mihomo}")
        return 2

    if not args.force and not should_run(args.state_file, args.min_interval_hours):
        print(
            f"距上次成功运行不足 {args.min_interval_hours} 小时，跳过"
            "（如需强制请加 --force）"
        )
        return 0

    os.makedirs(args.out, exist_ok=True)
    os.makedirs(os.path.dirname(args.state_file), exist_ok=True)

    # 标记正在运行（should_run 里的 3 小时锁据此拦截并发实例）
    try:
        with open(args.state_file + ".lock", "w", encoding="utf-8") as f:
            f.write(str(os.getpid()))
    except OSError:
        pass

    # ===== 阶段 0：从聚合页发现新订阅源 =====
    new_sources = discover_new_sources(args.timeout)
    discovered = _load_discovered()
    for u in new_sources:
        if u not in discovered:
            discovered.append(u)
    _save_discovered(discovered)
    if new_sources:
        print(f"本轮新发现 {len(new_sources)} 个订阅源")

    # ===== 阶段 1：抓取 =====
    print(f"== 阶段 1/3：抓取节点（{datetime.now():%Y-%m-%d %H:%M:%S}）==")
    pool, ok_urls, fail_urls = fetch_all(args.max_nodes, args.timeout, args.min_per_type)
    if not pool:
        print("抓取得到 0 节点，保留旧订阅，退出")
        return 1
    print(f"抓取去重过滤后 {len(pool)} 个节点")

    # 更新源健康度并淘汰连续失败过多的动态源
    health = _load_health()
    for u in ok_urls:
        h = health.setdefault(u, {"ok": 0, "fail": 0})
        h["ok"] += 1
        h["fail"] = 0
    for u in fail_urls:
        h = health.setdefault(u, {"ok": 0, "fail": 0})
        h["fail"] += 1
    _save_health(health)
    alive = [d for d in discovered if health.get(d, {}).get("fail", 0) < DEAD_SOURCE_THRESHOLD]
    if len(alive) != len(discovered):
        _save_discovered(alive)
        print(f"已淘汰 {len(discovered) - len(alive)} 个连续失败的死源")

    # ===== 阶段 2：测速 =====
    print("== 阶段 2/3：测速 ==")
    results, alive_count = speedtest(pool, args)
    if not results:
        print("测速后 0 存活节点，保留旧订阅，退出")
        return 1

    # ===== 阶段 3：排序 + 输出 + 同步 =====
    print("== 阶段 3/3：排序 + 输出 ==")
    if args.quick:
        results.sort(key=lambda r: r["delay"])
        tested_all = list(results)  # 截断前的全部存活节点，导出其它格式时按协议补用
        results = results[: args.top]
        qualified_count = len(results)
    else:
        # 有真实测速数据的按下载速度降序、延迟升序；测速失败的剔除
        with_speed = [r for r in results if r["speed"] is not None]
        with_speed.sort(key=lambda r: (-r["speed"], r["delay"]))
        qualified = [r for r in with_speed if r["speed"] >= args.min_speed]
        qualified_count = len(qualified)
        # 优先只保留达标的；一个都不达标时才用相对最好的一批兜底（避免空订阅）
        results = qualified if qualified else with_speed
        tested_all = list(results)
        results = results[: args.top]

    by_name = {p["name"]: p for p in pool}
    # 速度/延迟按名字查（用截断前的 tested_all，配额补进来的节点也要能查到）
    stat_by_name = {r["name"]: r for r in tested_all}
    speed_map = {r["name"]: r["speed"] for r in tested_all}
    ordered = []
    for r in results:
        if r["name"] in by_name:
            ordered.append(by_name[r["name"]])

    # ===== 协议配额 =====
    # 光按速度排，前几名常常全是 http/socks5（它们没法转成 ss:// 或 vmess:// 分享链接），
    # 结果用 Shadowsocks / v2rayN 的人拿到的是空文件。这里给每种协议保底几条。
    quota = _parse_floors(args.keep_per_type)
    picked = {p.get("name") for p in ordered}
    quota_added = {}
    for t, need in quota.items():
        have = sum(1 for p in ordered if (p.get("type") or "").lower() == t)
        if have >= need:
            continue
        for r in tested_all:
            if have >= need:
                break
            nm = r.get("name")
            if nm in picked:
                continue
            p = by_name.get(nm)
            if not p or (p.get("type") or "").lower() != t:
                continue
            picked.add(nm)
            ordered.append(p)
            have += 1
        quota_added[t] = have
    if any(quota_added.values()):
        print("协议配额补入：" + "、".join(
            f"{t}×{v}" for t, v in sorted(quota_added.items()) if v))

    if not ordered:
        print("排序后 0 节点，保留旧订阅，退出")
        return 1

    if args.exclude_types:
        bad = {t.strip().lower() for t in args.exclude_types.split(",") if t.strip()}
        before = len(ordered)
        ordered = [p for p in ordered if (p.get("type") or "").lower() not in bad]
        print(f"按 --exclude-types 过滤掉 {before - len(ordered)} 个 {sorted(bad)} 节点")

    # 可选：给节点名加实测速度前缀，便于在 Clash 里一眼看出快慢
    final_proxies = []
    for p in ordered:
        p = dict(p)
        sp = speed_map.get(p["name"])
        if not args.no_label and not args.quick and sp is not None:
            p["name"] = f"{sp:.1f}MB/s {p['name']}"
        final_proxies.append(p)

    gnames = [p["name"] for p in final_proxies]
    cfg = {
        "proxies": final_proxies,
        "proxy-groups": [
            {
                "name": "🚀 自动选择",
                "type": "url-test",
                "url": "https://www.gstatic.com/generate_204",
                "interval": 300,
                "tolerance": 50,
                "proxies": gnames,
            },
            {"name": "🐟 手动选择(按速度排序)", "type": "select", "proxies": gnames},
        ],
        "rules": ["MATCH,🚀 自动选择"],
    }
    best_path = os.path.join(args.out, "best_nodes.yml")
    with open(best_path, "w", encoding="utf-8") as f:
        yaml.safe_dump(cfg, f, allow_unicode=True, sort_keys=False)
    print(f"已生成 {best_path}（{len(ordered)} 个节点，按速度降序）")

    # 顺手导出各种客户端认识的格式：Shadowsocks(sip008 / ss 链接)、v2rayN(通用订阅)、sing-box…
    # 失败不影响主流程，best_nodes.yml 已经落盘了
    try:
        # 传两份：final_proxies 是入选的；labeled_pool 是全部测过且存活的，
        # 让 ss / v2ray 这些格式能从更大范围里按协议挑，不被 http 类节点挤空。
        labeled_pool = []
        for r in tested_all:
            p = by_name.get(r['name'])
            if not p:
                continue
            q = dict(p)
            sp = r.get('speed')
            if not args.no_label and not args.quick and sp is not None:
                q['name'] = f"{sp:.1f}MB/s {q['name']}"
            labeled_pool.append(q)
        EF.write_all(args.out, final_proxies, pool=labeled_pool)
    except Exception as e:  # noqa: BLE001
        print(f"导出其它客户端格式失败（不影响主流程）: {e}")

    # 报告
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    speed_line = (
        "- 测速模式: 仅延迟（quick）"
        if args.quick
        else f"- 下载达标(≥{args.min_speed}MB/s): {qualified_count}"
    )
    report_md = [
        f"# 节点测速报告（{now}）",
        "",
        f"- 候选节点: {len(pool)}",
        f"- 延迟存活: {alive_count}",
        speed_line,
        f"- 入选: {len(ordered)}（按下载速度降序）",
        "",
        "| 排名 | 节点 | 延迟(ms) | 下载速度(MB/s) |",
        "|---|---|---|---|",
    ]
    for i, p in enumerate(final_proxies, 1):
        orig = p["name"]
        # 名字里可能已经带了速度前缀，查表时要去掉
        if "MB/s " in orig and orig.split("MB/s ", 1)[1] in stat_by_name:
            orig = orig.split("MB/s ", 1)[1]
        st = stat_by_name.get(orig, {}) or {}
        sp = f"{st.get('speed'):.2f}" if st.get("speed") is not None else "-"
        report_md.append(f"| {i} | {p['name']} | {st.get('delay', '-')} | {sp} |")
    with open(os.path.join(args.out, "report.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(report_md) + "\n")

    synced = False
    if not args.skip_sync:
        synced = sync_to_verge(best_path)
        if synced:
            print(f"已同步到 Clash Verge 订阅「{VERGE_PROFILE_NAME}」")

    with open(args.state_file, "w", encoding="utf-8") as f:
        f.write(str(time.time()))

    print(f"测速池：{len(tested_all)} 个存活节点（入选前 {args.top} 个给 Clash，其余按协议补给其它客户端）")

    print("输出目录里的文件：")
    for fn, desc in (
        ("best_nodes.yml", "Clash / mihomo / Clash Verge / FlClash"),
        ("sip008.json", "Shadowsocks 官方 JSON 订阅"),
        ("ss-base64.txt / ss-plain.txt", "ss:// 订阅 / 明文"),
        ("v2ray-base64.txt / all-links.txt", "v2rayN / v2rayNG / Shadowrocket"),
        ("singbox-outbounds.json", "sing-box outbounds 片段"),
        ("report.md", "测速报告"),
    ):
        print(f"  {fn:32s} {desc}")
    print(
        f"\n完成({now})：入选 {len(ordered)} 个节点，按速度降序。"
        f"{'已同步 Clash Verge。' if synced else '未同步（可手动导入 best_nodes.yml）。'}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
