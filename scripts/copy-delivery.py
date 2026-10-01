#!/usr/bin/env python3
"""交付拷贝脚本：把构建产物复制到 outputs/，命名带应用名 + 版本号。

命名约定：
  release → outputs/XY-READER-<版本>.apk
  debug   → outputs/XY-READER-<版本>-debug.apk

版本号唯一来源：app/build.gradle.kts 的 appVersionName。
拷贝前用 aapt 读 APK 内 versionName 校验，与目标版本不符（陈旧产物）则跳过并告警
——历史事故：陈旧 debug APK 被当作当前版本拷进 outputs（见
docs/verification/2026-10-01-book-zoom-0.4.9.md 遗留问题 2）。
用法：在项目根目录执行 python scripts/copy-delivery.py
"""
import hashlib
import os
import pathlib
import re
import shutil
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parent.parent
gradle = (root / "app" / "build.gradle.kts").read_text(encoding="utf-8")
m = re.search(r'appVersionName\s*=\s*"([^"]+)"', gradle)
if not m:
    sys.exit("未找到 appVersionName（app/build.gradle.kts）")
version = m.group(1)


def find_aapt():
    """定位 Android SDK build-tools 中版本最高的 aapt：local.properties → 环境变量 → 常规位置"""
    sdk = None
    lp = root / "local.properties"
    if lp.exists():
        lm = re.search(r"sdk\.dir\s*=\s*(.+)", lp.read_text(encoding="utf-8"))
        if lm:
            # Java Properties 转义：\: → :，\\ → \（任意 \x 还原为 x）
            sdk = pathlib.Path(re.sub(r"\\(.)", r"\1", lm.group(1).strip()))
    if sdk is None or not sdk.exists():
        env = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if env:
            sdk = pathlib.Path(env)
    if sdk is None or not sdk.exists():
        default = pathlib.Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"
        if default.exists():
            sdk = default
    if sdk is None or not (sdk / "build-tools").exists():
        return None

    def ver_key(p):
        return [int(x) for x in re.findall(r"\d+", p.name)]

    for c in sorted((sdk / "build-tools").iterdir(), key=ver_key, reverse=True):
        exe = c / ("aapt.exe" if sys.platform == "win32" else "aapt")
        if exe.exists():
            return str(exe)
    return None


def apk_version(apk_path, aapt_path):
    """读 APK 内 versionName；aapt 缺失时返回 None（表示未能校验）"""
    if aapt_path is None:
        return None
    out = subprocess.run(
        [aapt_path, "dump", "badging", str(apk_path)],
        capture_output=True, text=True, errors="replace",
    ).stdout
    vm = re.search(r"versionName='([^']+)'", out)
    return vm.group(1) if vm else None


aapt = find_aapt()
if aapt is None:
    print("[警告] 未找到 aapt，无法校验 APK 版本；本轮跳过校验直接拷贝")

jobs = [
    ("release", f"XY-READER-{version}.apk"),
    ("debug", f"XY-READER-{version}-debug.apk"),
]

copied = []
for kind, out_name in jobs:
    src = root / "app" / "build" / "outputs" / "apk" / kind / f"app-{kind}.apk"
    if not src.exists():
        print(f"[跳过] 源不存在: {src}")
        continue
    found = apk_version(src, aapt)
    if found is not None and found != version:
        print(f"[跳过] {src.name} 内 versionName={found}，与目标 {version} 不符（陈旧产物，拒绝拷贝；请先重新构建）")
        continue
    dst = root / "outputs" / out_name
    dst.parent.mkdir(exist_ok=True)
    shutil.copyfile(src, dst)
    md5 = hashlib.md5(dst.read_bytes()).hexdigest()
    print(f"{out_name}\t{dst.stat().st_size} bytes\tmd5 {md5}")
    copied.append(out_name)

if not copied:
    sys.exit("[失败] 没有任何产物被拷贝")
