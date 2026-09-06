# -*- coding: utf-8 -*-
"""拉取 9527 日志，输出全量 + 关键过滤"""
import json, urllib.request, io

BASE = "http://127.0.0.1:9527"
data = json.loads(urllib.request.urlopen(BASE + "/api/logs", timeout=10).read().decode("utf-8"))
logs = data.get("logs", [])

with io.open("_9527_all.txt", "w", encoding="utf-8") as f:
    for l in sorted(logs, key=lambda x: x.get("timestamp", 0)):
        f.write(f"[{l.get('time','')}] ({l.get('type','')}) <{l.get('tag','')}> {l.get('message','')}\n")

keywords = ["NoSuchMethod", "NoClassDefFound", "UnsatisfiedLink", "dlopen", "Berry", "berry",
            "设备指纹", "UDB", "udb", "失败", "异常", "error", "Error", "FAIL", "超时",
            "SSL", "TLS", "mars", "Mars", "HUYA_CORE"]
lines = []
for l in sorted(logs, key=lambda x: x.get("timestamp", 0)):
    msg = l.get("message", "")
    if any(k in msg for k in keywords):
        lines.append(f"===== [{l.get('time','')}] ({l.get('type','')}) <{l.get('tag','')}> =====")
        lines.append(msg)
        lines.append("")

with io.open("_9527_report.txt", "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
print(f"total={len(logs)} filtered={len(lines)}")
