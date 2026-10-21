#!/usr/bin/env python3
"""沙盒侧调用手机工具的命令行客户端。
用法：
  phone.py status
  phone.py call <tool> ['<args-json>'] [--timeout N]
例：phone.py call files.list '{"path":"/sdcard/MuseShell"}'
"""
import json
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(HERE, "tokens.json")) as f:
    ADMIN = json.load(f)["admin"]
BASE = "http://127.0.0.1:8390"


def req(method, path, body=None, timeout=30):
    r = urllib.request.Request(BASE + path, method=method)
    r.add_header("X-Token", ADMIN)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        r.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(r, data=data, timeout=timeout) as resp:
        raw = resp.read().decode()
        return json.loads(raw) if raw else {}


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    if sys.argv[1] == "status":
        print(json.dumps(req("GET", "/status"), ensure_ascii=False, indent=1))
        return 0
    if sys.argv[1] == "call":
        tool = sys.argv[2]
        args = json.loads(sys.argv[3]) if len(sys.argv) > 3 and not sys.argv[3].startswith("--") else {}
        timeout = 60
        if "--timeout" in sys.argv:
            timeout = float(sys.argv[sys.argv.index("--timeout") + 1])
        try:
            res = req("POST", "/call", {"tool": tool, "args": args, "timeout": timeout},
                      timeout=timeout + 15)
        except urllib.error.HTTPError as e:
            res = json.loads(e.read().decode() or "{}")
        print(json.dumps(res, ensure_ascii=False, indent=1))
        return 0 if res.get("ok") else 1
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
