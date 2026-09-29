#!/usr/bin/env python3
"""交付拷贝脚本：把构建产物复制到 outputs/，命名带应用名 + 版本号。

命名约定：
  release → outputs/XY-READER-<版本>.apk
  debug   → outputs/XY-READER-<版本>-debug.apk

版本号唯一来源：app/build.gradle.kts 的 appVersionName。
用法：在项目根目录执行 python scripts/copy-delivery.py
"""
import hashlib
import pathlib
import re
import shutil
import sys

root = pathlib.Path(__file__).resolve().parent.parent
gradle = (root / "app" / "build.gradle.kts").read_text(encoding="utf-8")
m = re.search(r'appVersionName\s*=\s*"([^"]+)"', gradle)
if not m:
    sys.exit("未找到 appVersionName（app/build.gradle.kts）")
version = m.group(1)

jobs = [
    ("release", f"XY-READER-{version}.apk"),
    ("debug", f"XY-READER-{version}-debug.apk"),
]

for kind, out_name in jobs:
    src = root / "app" / "build" / "outputs" / "apk" / kind / f"app-{kind}.apk"
    if not src.exists():
        print(f"[跳过] 源不存在: {src}")
        continue
    dst = root / "outputs" / out_name
    dst.parent.mkdir(exist_ok=True)
    shutil.copyfile(src, dst)
    md5 = hashlib.md5(dst.read_bytes()).hexdigest()
    print(f"{out_name}\t{dst.stat().st_size} bytes\tmd5 {md5}")
