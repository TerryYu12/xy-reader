# XY reader

**Android 本地漫画 / 小说阅读器** — 压缩包直读、本地与远程书库（WebDAV / Google Drive），专注干净、纯粹的阅读体验。

[English](README_EN.md) | **简体中文**

[![Android CI](https://github.com/TerryYu12/xy-reader/actions/workflows/android-ci.yml/badge.svg)](https://github.com/TerryYu12/xy-reader/actions/workflows/android-ci.yml)

## 功能特性

### 支持的格式

| 类别 | 格式 | 说明 |
| --- | --- | --- |
| 漫画 | ZIP / CBZ · RAR / CBR · 7Z / CB7 · TAR / CBT | 压缩包直接读取，不预先解压、不占额外空间 |
| 电子书 | PDF · EPUB · MOBI · AZW3 / KF8 | 分页阅读 |
| 小说 | TXT | 自动章节识别 + 排版引擎 |
| 图片 | 文件夹 | 直接以图片目录为书 |

### 书库管理

- **本地仓库**：把任意本地文件夹添加为仓库并扫描入库，支持多个仓库并存；
- **远程仓库**：
  - WebDAV — 坚果云、Alist、InfiniCLOUD 等均可接入；
  - Google Drive — OAuth 授权（仅申请只读权限），ZIP / 7Z / TAR 流式翻页即时加载，RAR / PDF 首次打开自动下载缓存；
- **整理**：分组、收藏、阅读历史、书签集中管理；
- **封面**：自动提取压缩包内封面图，支持指定封面文件名；
- 书籍详情页：目录、页数、阅读进度一目了然。

### 阅读器

- 翻页模式：左右翻页 / 上下滚动（适合文字小说）；
- 漫画方向：西式（从左到右）/ 日漫（从右到左）；
- 手势：点击两侧翻页（可关闭）、双击缩放、捏合缩放；
- 显示：亮度调节、屏幕常亮、锁定屏幕方向（跟随系统 / 竖屏 / 横屏）；
- 阅读背景：纯黑 / 深灰 / 护眼黄 / 纯白；
- 图片缩放：适合屏幕 / 适合宽度；
- 小说排版：字号、字重、行距、四向边距、字间距、首行缩进、每章自动另起一页；
- 字体：内置霞鹜文楷 / MiSans / 朱雀仿宋，支持导入自定义字体（ttf / otf / ttc）；
- 目录、书签、阅读进度记忆、文字复制。

### 系统集成

- **「打开方式」**：从文件管理器 / MT 管理器 / QQ / 微信等直接以 XY-READER 打开 zip / cbz / cbr / 7z / tar / pdf / epub / mobi / txt 等文件；
- **分享导入**：从任意应用把文件分享到 XY-READER，直接入库开始阅读。

## 安装

### 直接安装（推荐）

前往 [Releases](https://github.com/TerryYu12/xy-reader/releases) 页面，下载最新的 `XY-READER-<版本>.apk` 安装。

- 系统要求：**Android 8.0（API 26）** 及以上；
- 当前发布的 APK 使用 debug 签名（便于直接安装体验）；与其他来源构建的包签名不一致，互相覆盖安装时需先卸载旧版。

### 从源码构建

需要 JDK 17 与 Android SDK（compileSdk 36）：

```bash
# 单元测试
./gradlew :app:testDebugUnitTest

# 构建 Release APK
# 产物：app/build/outputs/apk/release/app-release.apk
./gradlew :app:assembleRelease
```

仓库中的 `outputs/` 目录不入库（APK 可由源码随时重建）；CI（GitHub Actions）在每次 push 到 `main` 后自动执行测试与打包，可在 Actions 页面下载产物。

## 使用指南

### 1. 导入本地书籍

1. 进入「书架」页，点右上角「+」选择存放漫画 / 小说文件的文件夹（系统文件选择器）；
2. 等待扫描完成，书籍自动入库；
3. 可以多次添加不同文件夹，作为多个仓库并存管理。

### 2. 开始阅读

- 在首页 / 书架点击封面即开始阅读；右下角的「开始阅读」悬浮菜单支持快捷继续或从头阅读；
- 阅读中点击屏幕**中间**呼出工具栏：目录、书签、亮度、排版、复制文字等；
- 左右翻页模式下，点击屏幕**两侧**翻页（可在设置中关闭）；双击或捏合可缩放漫画页面。

### 3. 阅读设置

从阅读工具栏进入，或「设置 → 阅读配置管理」。配置分为三组：

- **翻页模式**：左右翻页 / 上下滚动、漫画方向、屏幕方向；
- **页面**：背景色、亮度、图片缩放、点击翻页、双击缩放、屏幕常亮；
- **字体**（文字小说专用）：字体、字号、字重、行距、边距、字间距、首行缩进、章首另起一页，以及导入自定义字体。

### 4. 添加 WebDAV 远程仓库

1. 打开「设置 → 远程仓库」，点「+」新建；
2. 填写服务器地址与账号密码。例：坚果云使用 `https://dav.jianguoyun.com/dav/`，密码处需填**应用密码**（坚果云：账户信息 → 安全选项 → 添加应用密码），而不是登录密码。Alist、InfiniCLOUD 等同类服务配置方式一致；
3. 点「测试连接」确认连通后保存，然后「扫描」即可把云端书籍入库。

### 5. 添加 Google Drive 仓库

1. 按 [GOOGLE_DRIVE_SETUP.md](GOOGLE_DRIVE_SETUP.md) 的指引，在你自己的 Google Cloud 控制台创建一个 OAuth 客户端（桌面应用类型，约 10 分钟，只需一次）；
2. 打开「设置 → Google Drive (beta)」，点「+」填入名称、Client ID、Client Secret（可选填目标文件夹 ID，不填则扫描整个 My Drive）；
3. 点「授权并保存」，在浏览器中完成授权（仅申请只读权限），回到 App 点「扫描」入库。

### 6. 从其他应用打开（打开方式 / 分享）

- 文件管理器 / MT 管理器：长按或直接选择文件 → 打开方式 → XY-READER；
- 其他应用内：分享 → XY-READER；
- 也适用于直接在压缩包管理器中打开压缩包内文件。

## 隐私说明

- 软件**不收集任何数据**：无广告、无统计埋点、无联网上报（除你主动配置的远程仓库请求外，不进行任何网络通信）；
- 书库数据、阅读进度、书签等全部保存在本机；
- 远程仓库（WebDAV / Google Drive）的地址与凭据仅存储在本机；
- Google Drive 授权仅申请 `drive.readonly`（只读）权限，软件无法修改你网盘中的任何内容。

完整隐私政策见 [PRIVACY.md](PRIVACY.md)（应用内「设置 → 隐私政策」同文）。

## 引用与致谢

### 开源组件

| 组件 | 用途 | 许可证 |
| --- | --- | --- |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) / [AndroidX](https://developer.android.com/jetpack) | UI 与基础框架 | Apache-2.0 |
| [Kotlin](https://kotlinlang.org/) | 开发语言 | Apache-2.0 |
| [Room](https://developer.android.com/training/data-storage/room) | 本地数据库 | Apache-2.0 |
| [Coil](https://coil-kt.github.io/coil/) | 封面图片加载 | Apache-2.0 |
| [OkHttp](https://square.github.io/okhttp/) | 网络请求（WebDAV / Range 流式读取） | Apache-2.0 |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | ZIP / 7Z / TAR 解析 | Apache-2.0 |
| [junrar](https://github.com/junrar/junrar) | RAR 解压 | UnRAR License（仅用于解压 RAR，不用于创建压缩包） |

### 内置字体

| 字体 | 来源 | 许可 |
| --- | --- | --- |
| 霞鹜文楷 Lite | [LXGW WenKai](https://github.com/lxgw/LxgwWenKai) | SIL OFL 1.1 |
| MiSans | © 小米科技 | 小米字体许可（免费商用） |
| 朱雀仿宋 | 璇玑造字 | SIL OFL 1.1 |

字体文件随 APK 打包分发；用户导入的自定义字体仅存于本机，版权归字体作者所有。

### 特别致谢

本项目的部分设计（章节分页、阅读器交互行为等）参考了以下开源项目的公开实现思路，在此致谢：

- [Legado（阅读）](https://github.com/gedoor/legado)
- [KOReader](https://github.com/koreader/koreader)
- [Librera Reader](https://github.com/librera/LibreraReader)

## 免责声明

1. 本软件是一款**纯粹的本地 / 私人存储阅读工具**，不提供、不内置、不托管任何书源、漫画或小说内容；
2. 你通过本软件打开的全部内容均来自你自己的设备或个人网络存储，其版权归原作者及版权方所有；
3. 请确保你使用本软件访问的内容来源合法；因使用本软件引起的任何版权纠纷或法律责任，由使用者自行承担；
4. 本软件按「现状」（AS IS）提供，不对可用性、可靠性作任何明示或暗示的担保；因使用本软件造成的任何数据丢失或其他损失，作者不承担责任；
5. 如果你喜欢某部作品，请支持正版。

## 许可证

[MIT License](LICENSE)
