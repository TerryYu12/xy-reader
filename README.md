# XY reader

Android 本地漫画 / 小说阅读器（Jetpack Compose + Room + 归档解析）。

> 私有仓库，暂不开源。

## 构建

```bash
# 测试（先跑，XML 统计为准）
./gradlew :app:testDebugUnitTest

# 交付包（release + lint；debug 包按需再加 :app:assembleDebug）
./gradlew :app:assembleRelease :app:lintDebug
```

本机构建已设资源上限（见 `gradle.properties` 注释：workers=4、Kotlin 编译 in-process 等，
避免构建吃满全机）；APK 产物在 `outputs/`（不入库）。

## 结构

- `app/src/main` — 源码（Compose UI / Room / 归档解析 / 阅读器内核）
- `app/src/test` — Robolectric + Compose 测试
- `docs/verification` — 每次交付的验证记录（含实机清单）
- `scripts/copy-delivery.py` — 交付拷贝助手（构建产物 → outputs → 校验）

## 版本

当前 `0.4.3`（versionCode 15）。核心能力：本地 / 远程仓库管理、漫画与文字小说阅读、
主页与书架「开始阅读」快捷菜单、阅读设置弹层胶囊分页、书本多操作菜单、
系统「打开方式」与分享导入。
