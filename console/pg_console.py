#!/usr/bin/env python3
"""phoneguard 家人端主控（任何有 Python 3 的裝置都能跑：電腦、NAS、手機 Termux）。

單向隔離：
  · 只送指令(cmd topic)、只讀狀態(st topic)，全部對轉發站(ntfy)連出，永不連長輩手機。
  · 手機傳回的狀態一律當「資料」：只取已知欄位、截斷、絕不 eval / 不進 shell。
  · 每則指令包成 {"c":指令,"t":時間,"n":隨機碼} 再加密；手機拒收 ±5 分鐘外或重複的指令（防重放）。

指令：
  pg_console.py -s <配對碼> watch            持續看手機狀態
  pg_console.py -s <配對碼> rescue           遠端救援：立刻叫手機回主畫面
  pg_console.py -s <配對碼> block <包名>     封鎖某 App（一到前景就踢回主畫面）
  pg_console.py -s <配對碼> allow <包名>     放行某 App（停止介入）
  pg_console.py -s <配對碼> unallow <包名>   取消放行
  pg_console.py -s <配對碼> lift <分鐘>      暫停守門 N 分鐘
  pg_console.py -s <配對碼> guard            立即恢復守門
  pg_console.py -s <配對碼> auto off|on      關/開自動偵測
  pg_console.py -s <配對碼> ring [秒]        找手機：大聲響 N 秒(預設 60，5~300；靜音模式也會響)
  pg_console.py -s <配對碼> stopring         停止響鈴
環境變數：PG_SECRET（取代 -s）、PG_NTFY（轉發站網址，預設 https://ntfy.sh；要與手機端設定相同）
需要：pip install cryptography
"""
import sys
import os
import json
import time
import base64
import hashlib
import argparse
import urllib.request

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except Exception:
    sys.exit("需要 cryptography 套件：pip install cryptography")

NTFY = os.environ.get("PG_NTFY", "https://ntfy.sh").rstrip("/")


def topic(secret, kind):
    h = hashlib.sha256(f"pg-topic:{kind}:{secret}".encode()).hexdigest()
    return f"pg-{kind}-{h[:20]}"


def _key(secret):
    return hashlib.sha256(f"pg-key:{secret}".encode()).digest()


def enc(secret, plain):
    iv = os.urandom(12)
    ct = AESGCM(_key(secret)).encrypt(iv, plain.encode("utf-8"), None)
    return base64.b64encode(iv + ct).decode()


def dec(secret, b64):
    try:
        raw = base64.b64decode(b64)
        iv, ct = raw[:12], raw[12:]
        return AESGCM(_key(secret)).decrypt(iv, ct, None).decode("utf-8", "replace")
    except Exception:
        return None


def _http(method, url, data=None, timeout=20):
    req = urllib.request.Request(url, data=data, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")


def envelope(cmd):
    return json.dumps({"c": cmd, "t": int(time.time()), "n": base64.b64encode(os.urandom(9)).decode()})


def send_cmd(secret, cmd):
    body = enc(secret, envelope(cmd)).encode()
    _http("POST", f"{NTFY}/{topic(secret, 'cmd')}", data=body)
    print(f"已送指令 → 手機: {cmd}")


def show_status(plain):
    # 手機傳來的一律當資料：只取已知欄位、截斷、不執行
    try:
        o = json.loads(plain)
    except Exception:
        print("· (無法解析的狀態,略)")
        return
    k = str(o.get("k", ""))[:20]
    d = str(o.get("d", ""))[:160]
    t = o.get("t", 0)
    ts = time.strftime("%m/%d %H:%M:%S", time.localtime(t)) if isinstance(t, (int, float)) and t else "?"
    a = o.get("allow", "?")
    b = o.get("block", "?")
    tag = {"event": "⚠事件", "applied": "✓已套用", "heartbeat": "·心跳"}.get(k, k)
    print(f"[{ts}] {tag}  {d}  (放行{a}/封鎖{b})")


def read_status(secret, follow=False):
    st = topic(secret, "st")
    since = "10m"
    while True:
        try:
            txt = _http("GET", f"{NTFY}/{st}/json?poll=1&since={since}")
        except Exception as e:
            print("讀取失敗(離線?):", e)
            if not follow:
                return
            time.sleep(6)
            continue
        for line in txt.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                o = json.loads(line)
            except Exception:
                continue
            t = o.get("time", 0)
            if t:
                since = str(int(t) + 1)
            if o.get("event") != "message":
                continue
            plain = dec(secret, o.get("message", ""))
            if plain is not None:
                show_status(plain)
        if not follow:
            return
        time.sleep(6)


def main():
    ap = argparse.ArgumentParser(add_help=True, description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--secret", "-s", help="配對碼(或設環境變數 PG_SECRET)")
    sub = ap.add_subparsers(dest="op")
    sub.add_parser("watch")
    sub.add_parser("rescue")
    sub.add_parser("guard")
    sub.add_parser("stopring")
    sub.add_parser("ring").add_argument("seconds", nargs="?", type=int, default=60)
    for name in ("allow", "block", "unallow", "lift", "auto"):
        p = sub.add_parser(name)
        p.add_argument("arg")
    a = ap.parse_args()

    secret = a.secret or os.environ.get("PG_SECRET")
    if not secret:
        sys.exit("請給配對碼：--secret XXXX 或設 PG_SECRET")

    if a.op == "watch":
        read_status(secret, follow=True)
    elif a.op in ("rescue", "guard", "stopring"):
        send_cmd(secret, a.op)
    elif a.op == "ring":
        send_cmd(secret, "ring %d" % max(0, min(300, a.seconds)))
    elif a.op in ("allow", "block", "unallow", "lift", "auto"):
        send_cmd(secret, f"{a.op} {a.arg}")
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
