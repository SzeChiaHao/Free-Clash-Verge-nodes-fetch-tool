#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NoMoreWalls 节点质量筛选器

用 Clash Verge 自带的 mihomo 内核（verge-mihomo.exe）独立加载一批候选节点，
逐个测试以下标准：
  1. 能打开 Google   (https://www.google.com/generate_204 返回 204)
  2. 能打开 YouTube  (https://www.youtube.com/ 返回 2xx/3xx)
  3. 能流畅播放 YouTube 视频《海峡中线》(尽力而为：搜索视频并测视频流速度)
  4. 能打开 Pornhub  (https://www.pornhub.com/ 返回 2xx/3xx)
  5. 能流畅播放 Pornhub 随机视频 (尽力而为：取随机视频并测视频流速度)

输出:
  best_nodes.yml   -- 筛选出的优质节点(Clash Meta 配置,可直接导入 Clash Verge)
  report.csv       -- 每个节点的详细测试结果
  report.md        -- 人类可读的总结报告

用法示例:
  python node_quality.py --source D:\\Walls\\NoMoreWalls\\list.meta.yml --top 12
  python node_quality.py --quick          # 只测连通性,不做速度/视频测试(快速迭代用)
"""

import argparse
import csv
import ipaddress
import json
import os
import re
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

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")

GOOGLE_204 = "https://www.google.com/generate_204"
YT_HOME = "https://www.youtube.com/"
PH_HOME = "https://www.pornhub.com/"
CF_SPEED = "https://speed.cloudflare.com/__down?bytes=5242880"
YT_QUERY = "海峡中线"
INNERTUBE_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"


def free_port():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def http_session(mixed_port):
    s = requests.Session()
    s.trust_env = False
    s.headers.update({"User-Agent": UA})
    s.proxies = {
        "http": f"http://127.0.0.1:{mixed_port}",
        "https": f"http://127.0.0.1:{mixed_port}",
    }
    return s


class MihomoRunner:
    def __init__(self, mihomo, workdir, mixed_port, api_port, secret):
        self.mihomo = mihomo
        self.workdir = workdir
        self.mixed_port = mixed_port
        self.api_port = api_port
        self.secret = secret
        self.proc = None

    def start(self, cfg_path):
        flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        self.proc = subprocess.Popen(
            [self.mihomo, "-d", self.workdir, "-f", cfg_path],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=flags,
        )
        deadline = time.time() + 25
        while time.time() < deadline:
            if self.proc.poll() is not None:
                raise RuntimeError(f"mihomo 启动失败,退出码 {self.proc.returncode}")
            try:
                r = requests.get(
                    f"http://127.0.0.1:{self.api_port}/version",
                    timeout=1,
                    headers={"Authorization": f"Bearer {self.secret}"},
                )
                if r.status_code == 200:
                    return
            except requests.RequestException:
                pass
            time.sleep(0.4)
        raise RuntimeError("mihomo 管理接口启动超时")

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=8)
            except subprocess.TimeoutExpired:
                self.proc.kill()

    def api(self, method, path, **kw):
        kw.setdefault("headers", {})
        kw["headers"].setdefault("Authorization", f"Bearer {self.secret}")
        kw.setdefault("timeout", 5)
        url = f"http://127.0.0.1:{self.api_port}{path}"
        return requests.request(method, url, **kw)

    def select(self, name):
        self.api("PUT", "/proxies/TEST", json={"name": name})

    def delay(self, name, timeout_ms):
        q = urllib.parse.quote(name, safe="")
        try:
            r = self.api(
                "GET",
                f"/proxies/{q}/delay?url={urllib.parse.quote(GOOGLE_204, safe='')}&timeout={timeout_ms}",
            )
            if r.status_code == 200:
                return r.json().get("delay")
            return None
        except requests.RequestException:
            return None


def http_ok(session, url, timeout, expect=(200, 204, 301, 302, 303, 307, 308)):
    try:
        r = session.get(url, timeout=timeout, allow_redirects=True)
        return r.status_code in expect
    except requests.RequestException:
        return False


def measure_speed(session, url, max_bytes, cap_sec):
    try:
        t0 = time.time()
        with session.get(url, stream=True, timeout=cap_sec + 5) as r:
            if r.status_code != 200:
                return None
            got = 0
            for chunk in r.iter_content(chunk_size=65536):
                if not chunk:
                    continue
                got += len(chunk)
                if got >= max_bytes or (time.time() - t0) >= cap_sec:
                    break
        dt = time.time() - t0
        if dt <= 0:
            return None
        return got / dt / 1e6  # MB/s
    except requests.RequestException:
        return None


def yt_video_id(session, query):
    try:
        r = session.get(
            "https://www.youtube.com/results?search_query=" + urllib.parse.quote(query),
            timeout=20,
        )
        m = re.search(r'"videoId":"([A-Za-z0-9_-]{11})"', r.text)
        return m.group(1) if m else None
    except requests.RequestException:
        return None


def yt_stream_url(session, video_id):
    try:
        payload = {
            "context": {
                "client": {
                    "clientName": "WEB",
                    "clientVersion": "2.20241201.01.00",
                    "hl": "zh-CN",
                    "gl": "US",
                }
            },
            "videoId": video_id,
        }
        r = session.post(
            "https://www.youtube.com/youtubei/v1/player?key=" + INNERTUBE_KEY,
            json=payload,
            timeout=20,
        )
        data = r.json()
        sd = data.get("streamingData", {}) or {}
        fmts = (sd.get("formats") or []) + (sd.get("adaptiveFormats") or [])
        fmts.sort(key=lambda f: f.get("height", 0), reverse=True)
        for f in fmts:
            if f.get("url"):
                return f["url"]
        if sd.get("hlsManifestUrl"):
            return sd["hlsManifestUrl"]
    except (requests.RequestException, ValueError, KeyError):
        pass
    return None


def ph_video_url(session):
    try:
        r = session.get("https://www.pornhub.com/random", timeout=25, allow_redirects=True)
        m = re.search(r'"videoUrl"\s*:\s*"([^"]+?\.mp4[^"]*)"', r.text)
        if not m:
            m = re.search(r'https://[^"\']+?\.mp4[^"\']*', r.text)
        return m.group(1) if m else None
    except requests.RequestException:
        return None


def build_config(proxies, names, mixed_port, api_port, secret):
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
        "proxy-groups": [{"name": "TEST", "type": "select", "proxies": names}],
        "rules": ["MATCH,TEST"],
    }


def load_pool(source, max_nodes):
    with open(source, "r", encoding="utf-8") as f:
        data = yaml.safe_load(f)
    proxies = data.get("proxies", [])
    seen = set()
    uniq = []
    skipped = 0
    for p in proxies:
        n = p.get("name")
        if not n or n in seen:
            continue
        if is_decoy_server(p.get("server")):
            skipped += 1
            continue
        seen.add(n)
        uniq.append(p)
    print(f"已跳过 {skipped} 个假节点/内网节点")
    return uniq[:max_nodes]


def is_decoy_server(server):
    s = (server or "").strip().lower()
    if not s or s in ("localhost", "127.0.0.1"):
        return True
    if s.startswith("127."):
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


def score_node(delay, speed_mbs, yt_mbs, ph_mbs, ok_all):
    if not ok_all:
        return 0.0
    delay_score = max(0.0, 100.0 * (1 - (delay or 3000) / 3000.0))
    if speed_mbs is None:
        return round(delay_score, 1)
    speed_score = min(100.0, speed_mbs * 12.5)
    v1 = yt_mbs if yt_mbs else speed_mbs
    v2 = ph_mbs if ph_mbs else speed_mbs
    video_score = min(100.0, ((v1 + v2) / 2.0) * 12.5)
    return round(0.25 * delay_score + 0.35 * speed_score + 0.40 * video_score, 1)


def main():
    ap = argparse.ArgumentParser(description="NoMoreWalls 节点质量筛选器")
    ap.add_argument("--source", default=r"D:\Walls\NoMoreWalls\list.meta.yml")
    ap.add_argument("--mihomo", default=r"D:\Clash Verge\verge-mihomo.exe")
    ap.add_argument("--out", default=r"D:\Walls\tools\output")
    ap.add_argument("--top", type=int, default=12)
    ap.add_argument("--max-nodes", type=int, default=300)
    ap.add_argument("--min-speed", type=float, default=1.0, help="合格最低速度 MB/s")
    ap.add_argument("--quick", action="store_true", help="只测连通性与延迟,不测速度")
    ap.add_argument("--skip-video", action="store_true", help="跳过 YouTube/Pornhub 视频测速")
    ap.add_argument("--video-speed-top", type=int, default=30)
    ap.add_argument("--clash-profile-name", default="best_nodes")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    pool = load_pool(args.source, args.max_nodes)
    if not pool:
        print("候选节点池为空,请先运行 fetch.py 或检查 --source")
        return 1
    names = [p["name"] for p in pool]
    print(f"候选节点: {len(pool)} 个")

    mixed_port = free_port()
    api_port = free_port()
    secret = "qatest2026"
    tmp = tempfile.mkdtemp(prefix="nmwtest-")
    runner = MihomoRunner(args.mihomo, tmp, mixed_port, api_port, secret)
    cfg_path = os.path.join(tmp, "test.yaml")
    with open(cfg_path, "w", encoding="utf-8") as f:
        yaml.safe_dump(
            build_config(pool, names, mixed_port, api_port, secret),
            f,
            allow_unicode=True,
            sort_keys=False,
        )
    try:
        runner.start(cfg_path)
        session = http_session(mixed_port)

        # 阶段一:延迟测试(初筛)
        alive = []
        for i, name in enumerate(names, 1):
            d = runner.delay(name, 4500)
            if d is not None:
                alive.append((name, d))
            if i % 50 == 0:
                print(f"延迟初筛 {i}/{len(names)} ... 存活 {len(alive)}")
        alive.sort(key=lambda x: x[1])
        print(f"延迟初筛完成: {len(alive)}/{len(names)} 存活")

        results = []
        for i, (name, delay) in enumerate(alive, 1):
            row = {
                "name": name,
                "delay_ms": delay,
                "google": False,
                "youtube": False,
                "pornhub": False,
                "speed_mbs": "",
                "yt_mbs": "",
                "ph_mbs": "",
                "score": 0,
                "verdict": "fail",
            }
            try:
                runner.select(name)
                time.sleep(0.15)
                row["google"] = http_ok(session, GOOGLE_204, 8)
                if not row["google"]:
                    results.append(row)
                    continue
                row["youtube"] = http_ok(session, YT_HOME, 10)
                row["pornhub"] = http_ok(session, PH_HOME, 10)
                ok_all = row["google"] and row["youtube"] and row["pornhub"]
                speed = None
                if ok_all and not args.quick:
                    speed = measure_speed(session, CF_SPEED, 4 * 1024 * 1024, 20)
                    row["speed_mbs"] = round(speed, 2) if speed else ""
                if ok_all and args.quick:
                    row["verdict"] = "ok"
                elif ok_all and speed and speed >= args.min_speed:
                    row["verdict"] = "ok"
                else:
                    row["verdict"] = "fail"
                if ok_all and speed and speed >= args.min_speed and not args.skip_video:
                    if i <= args.video_speed_top:
                        vid = yt_video_id(session, YT_QUERY)
                        if vid:
                            u = yt_stream_url(session, vid)
                            if u:
                                row["yt_mbs"] = round(
                                    measure_speed(session, u, 2 * 1024 * 1024, 25) or 0, 2
                                )
                        phu = ph_video_url(session)
                        if phu:
                            row["ph_mbs"] = round(
                                measure_speed(session, phu, 2 * 1024 * 1024, 25) or 0, 2
                            )
            except Exception as e:  # noqa: BLE001
                row["verdict"] = f"error:{e}"
            row["score"] = score_node(
                row["delay_ms"],
                row["speed_mbs"] if isinstance(row["speed_mbs"], (int, float)) else None,
                row["yt_mbs"] if isinstance(row["yt_mbs"], (int, float)) else None,
                row["ph_mbs"] if isinstance(row["ph_mbs"], (int, float)) else None,
                row["google"] and row["youtube"] and row["pornhub"],
            )
            results.append(row)
            if i % 10 == 0:
                print(f"完整测试 {i}/{len(alive)} ... 合格 {sum(1 for r in results if r['verdict'] == 'ok')}")
    finally:
        runner.stop()
        shutil.rmtree(tmp, ignore_errors=True)

    ok = [r for r in results if r["verdict"] == "ok"]
    ok.sort(key=lambda r: r["score"], reverse=True)
    top = ok[: args.top]
    print(f"测试完成: 合格 {len(ok)}, 入选前 {len(top)}")

    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    csv_path = os.path.join(args.out, "report.csv")
    with open(csv_path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(
            f,
            fieldnames=[
                "name", "delay_ms", "google", "youtube", "pornhub",
                "speed_mbs", "yt_mbs", "ph_mbs", "score", "verdict",
            ],
        )
        w.writeheader()
        w.writerows(results)

    best_path = os.path.join(args.out, "best_nodes.yml")
    if top:
        keep = {r["name"] for r in top}
        proxies = [p for p in pool if p["name"] in keep]
        gnames = [p["name"] for p in proxies]
        cfg = {
            "proxies": proxies,
            "proxy-groups": [
                {
                    "name": "🚀 自动选择",
                    "type": "url-test",
                    "url": "https://www.gstatic.com/generate_204",
                    "interval": 300,
                    "tolerance": 80,
                    "proxies": gnames,
                },
                {"name": "🐟 手动选择", "type": "select", "proxies": gnames},
            ],
            "rules": ["MATCH,🚀 自动选择"],
        }
        with open(best_path, "w", encoding="utf-8") as f:
            yaml.safe_dump(cfg, f, allow_unicode=True, sort_keys=False)
        print(f"已生成 {best_path}")

        md = [
            f"# 节点质量报告（{now}）",
            "",
            f"- 候选节点: {len(pool)}",
            f"- 延迟存活: {len(alive)}",
            f"- 完全合格(Google+YouTube+Pornhub 可达且速度≥{args.min_speed}MB/s): {len(ok)}",
            f"- 入选前 {len(top)}:",
            "",
            "| 排名 | 节点 | 延迟(ms) | 速度(MB/s) | YouTube视频(MB/s) | Pornhub视频(MB/s) | 得分 |",
            "|---|---|---|---|---|---|---|",
        ]
        for i, r in enumerate(top, 1):
            md.append(
                f"| {i} | {r['name']} | {r['delay_ms']} | {r['speed_mbs']} "
                f"| {r['yt_mbs'] or '-'} | {r['ph_mbs'] or '-'} | {r['score']} |"
            )
        md.append("")
        with open(os.path.join(args.out, "report.md"), "w", encoding="utf-8") as f:
            f.write("\n".join(md))

    # 尝试把 best_nodes.yml 同步到 Clash Verge 已导入的同名订阅
    profiles_yaml = os.path.join(
        os.environ.get("APPDATA", ""),
        "io.github.clash-verge-rev.clash-verge-rev",
        "profiles.yaml",
    )
    if os.path.exists(profiles_yaml) and os.path.exists(best_path):
        try:
            with open(profiles_yaml, "r", encoding="utf-8") as f:
                pdata = yaml.safe_load(f)
            uid = None
            for it in (pdata or {}).get("items", []):
                if it.get("name") == args.clash_profile_name:
                    uid = it.get("uid")
                    break
            if uid:
                shutil.copy2(best_path, os.path.join(os.path.dirname(profiles_yaml), "profiles", f"{uid}.yaml"))
                print(f"已同步到 Clash Verge 订阅 {args.clash_profile_name} ({uid})")
            else:
                print(f"未在 Clash Verge 找到名为 {args.clash_profile_name} 的订阅,请手动导入 {best_path}")
        except Exception as e:  # noqa: BLE001
            print(f"同步到 Clash Verge 失败(不影响结果): {e}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
