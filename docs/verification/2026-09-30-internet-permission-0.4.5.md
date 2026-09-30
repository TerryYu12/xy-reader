# 0.4.5 验证档 —— INTERNET 权限修复（远程仓库可用性）+ 开源准备

日期：2026-09-30
版本：**0.4.5 / versionCode 17**

## 背景（问题发现）

开源发布准备期间，对照发布前检查清单复核 aapt 产物时发现：**AndroidManifest.xml 从未声明 `android.permission.INTERNET`** ——
即 0.4.4 及此前所有构建的 APK 均无网络权限，WebDAV / Google Drive 远程仓库功能在真机上必然抛 SecurityException
（`Permission denied (missing INTERNET permission?)`）。

证据：

- `app/src/main/AndroidManifest.xml` 全文无任何 `<uses-permission>`；
- `aapt dump badging XY-READER-0.4.4.apk` 权限列表仅含 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`；
- 代码确有 OkHttp 网络调用（HttpRangeChannel / WebDavClient / GoogleDriveClient / RemoteArchiveSources）；
- 远程仓库功能此前从未做过真机验证（`docs/verification/` 中无相关验证档），缺陷因此长期未暴露；
  单元测试（Robolectric）不校验真机权限模型，静态构建零报错。

## 修复（改动清单）

1. `app/src/main/AndroidManifest.xml`
   - 新增 `<uses-permission android:name="android.permission.INTERNET" />`（远程仓库必需）；
   - `application` 新增 `android:usesCleartextTraffic="true"`：兼容自建服务器（Alist、局域网 WebDAV 等默认 http）；
     公网服务（坚果云 / Google Drive）本身走 HTTPS，不受此开关影响。
2. `app/build.gradle.kts`：0.4.5 / versionCode 17。

## 验证证据

- 单元测试：**75/75 通过**（0 failures / 0 errors / 0 skipped）（`build-045.log`：BUILD SUCCESSFUL in 8m 1s；
  82 actionable tasks: 39 executed / 43 up-to-date）
- aapt 核验（`app/build/outputs/apk/release/app-release.apk`）：
  - `versionCode='17' versionName='0.4.5'` ✓
  - `uses-permission: name='android.permission.INTERNET'` ✓（修复生效）
  - `android:usesCleartextTraffic=(type 0x12)0xffffffff`（xmltree，true）✓
- dex 抽检：单 `classes.dex` 4,913,876 B（与 0.4.4 一致——本次仅 manifest / 版本号变更，代码未动）；
  `章首另起一页` ✓ / `novel_chapter_new_page` ✓
- 交付物：`outputs/XY-READER-0.4.5.apk`（22,352,561 B；md5 `6db3d4c8d8c2b88d32f7afc7c1177764`）

## 待真机确认（用户）

- WebDAV：添加坚果云等仓库 → 测试连接 → 扫描（修复前必然失败）；
- Google Drive：OAuth 授权链路（浏览器回调）与扫描；
- 自建 http 服务器（Alist）：连接测试。

## 说明

- 本版本为开源首发版本（README 双语 / LICENSE MIT / GitHub Release）；
- 此前版本（≤ 0.4.4）均无网络权限，请升级后使用远程功能。
