# 0.4.5 验证档 —— INTERNET 权限修复 + 开源首发（隐私政策 / 支持作者）

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

## 改动清单

1. `app/src/main/AndroidManifest.xml`
   - 新增 `<uses-permission android:name="android.permission.INTERNET" />`（远程仓库必需）；
   - `application` 新增 `android:usesCleartextTraffic="true"`：兼容自建服务器（Alist、局域网 WebDAV 等默认 http）；
     公网服务（坚果云 / Google Drive）本身走 HTTPS，不受此开关影响。
2. `app/build.gradle.kts`：0.4.5 / versionCode 17。
3. **隐私政策**（原设置页「隐私政策」为占位 snackbar）：
   - 新增应用内 `ui/PrivacyScreen.kt`（设置 → 隐私政策），替换占位；
   - 仓库新增 `PRIVACY.md` / `PRIVACY_EN.md`（与应用内同文）；README 双语版本补充链接。
4. **支持作者**（新功能）：
   - 新增应用内 `ui/SupportScreen.kt`（设置 → 支持作者），展示微信收款码；
   - 资源 `res/drawable/reward_wechat_qr.png`：用户提供原图（659×1016 截图）裁切——
     去除顶部残横幅，保留二维码本体（598×596）与完整静区；成品 659×686，无压缩伪影。
5. `ui/Navigation.kt`：新增 `settings/privacy`、`settings/support` 两条路由；`ui/SettingsScreen.kt` 接入两个入口。
6. 开源文档：`README.md` / `README_EN.md` 全面重写（功能特性 / 使用教程 / 引用与致谢 / 免责声明），新增 `LICENSE`（MIT）。

## 验证证据

- 单元测试：**75/75 通过**（0 failures / 0 errors / 0 skipped）（`build-045b.log`：BUILD SUCCESSFUL in 6m 13s；
  82 actionable tasks: 38 executed / 1 from cache / 43 up-to-date）
- aapt 核验（`app/build/outputs/apk/release/app-release.apk`）：
  - `versionCode='17' versionName='0.4.5'` ✓
  - `uses-permission: name='android.permission.INTERNET'` ✓（修复生效）
  - `android:usesCleartextTraffic=(type 0x12)0xffffffff`（xmltree，true）✓
  - `drawable/reward_wechat_qr` 在资源表 ✓（包内收缩后为 `res/dz.png`，139,186 B = 源文件字节一致）
- dex 抽检：单 `classes.dex` 4,959,596 B（+45,720 B = 新增两页代码）；
  `支持作者` ✓ / `请作者喝杯咖啡` ✓ / `完全免费` ✓ / `微信收款码` ✓ / `谢谢使用` ✓
- 交付物：`outputs/XY-READER-0.4.5.apk`（22,525,525 B；md5 `6289a369d5a7601e235ce4e11a1bdc8b`）
- （前置构建记录：同版本首轮 `build-045.log` 8m 1s，75/75；当时仅含权限修复，后合并隐私/支持页重构建）

## 待真机确认（用户）

- WebDAV：添加坚果云等仓库 → 测试连接 → 扫描（修复前必然失败）；
- Google Drive：OAuth 授权链路（浏览器回调）与扫描；
- 自建 http 服务器（Alist）：连接测试；
- 新增页面：设置 →「隐私政策」阅读、「支持作者」收款码扫码实测。

## 说明

- 本版本为开源首发版本（README 双语 / PRIVACY 双语 / LICENSE MIT / GitHub Release）；
- 此前版本（≤ 0.4.4）均无网络权限，请升级后使用远程功能。
