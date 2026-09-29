# Google Drive（Google One）接入配置指南

XY-READER 支持 Google Drive 作为远程漫画仓库（流式阅读：ZIP/7Z/TAR 翻页即时加载，RAR/PDF 首次打开自动下载缓存）。

Google 的要求是：**每个 App 必须使用自己的 OAuth 客户端凭据**访问你的 Drive 数据。所以首次使用需要花约 10 分钟在 Google Cloud 控制台创建凭据（只做一次，之后长期使用）。

## 一、创建 OAuth 客户端（浏览器操作）

1. 打开 [console.cloud.google.com](https://console.cloud.google.com)，登录 Google 账号，顶部新建项目（名字随意，如 `xy-reader`）。
2. 左侧菜单 → **API 和服务 → 库** → 搜索 **Google Drive API** → 点 **启用**。
3. **API 和服务 → OAuth 同意屏幕**：
   - User Type 选 **外部** → 创建；
   - 应用名称随意填，用户支持电子邮件填自己的；
   - **测试用户** → 添加你自己的 Google 账号（测试模式下只有添加过的账号能授权，个人用足够，无需走 Google 审核）。
4. **凭据 → 创建凭据 → OAuth 客户端 ID**：
   - 应用类型选 **桌面应用**（必须，Web/Android 类型不适用本 App 的授权流程）；
   - 创建后得到 **客户端 ID**（形如 `1234567890-xxxx.apps.googleusercontent.com`）和 **客户端密钥**。

## 二、在 App 里添加账号

1. 手机确保能访问 Google（挂代理）。
2. XY-READER → 设置 → **Google Drive (beta)** → 右下角 **+**；
3. 填写：名称（随意）、Client ID、Client Secret；目标文件夹 ID 可选（留空扫描整个 My Drive 根目录）；
4. 点 **授权并保存** → 跳转浏览器登录并同意授权（注意 scope 只有只读 `drive.readonly`，App 不能修改你网盘里的任何东西）→ 授权成功自动回到 App；
5. 回到账号卡片点 **扫描**，云端漫画自动入库。

## 三、怎么拿文件夹 ID

在 [drive.google.com](https://drive.google.com) 打开你想扫描的文件夹，看地址栏：

```
https://drive.google.com/drive/folders/1AbCdEfGhIjKlMnOpQrStUvWxYz
                                        └────── 这一段就是文件夹 ID ──────┘
```

把这一段填进"目标文件夹 ID"即可只扫这个文件夹（推荐，比扫全盘快）。

## 四、常见问题

| 现象 | 原因与处理 |
|---|---|
| 授权页打不开 | 手机没通 Google，先开代理 |
| 提示"Google 授权已失效" | refresh_token 过期或被撤销，删除账号重新授权 |
| 403 配额受限 | Drive API 免费下载配额 10TB/天，正常使用碰不到；碰到等一天即可 |
| 扫描结果为空 | 检查文件夹 ID 是否填对；漫画文件扩展名需是 zip/cbz/7z/cb7/rar/cbr/tar/cbt/pdf/epub |
| 授权时报 invalid_client | Client ID 或 Secret 复制错了，重新复制粘贴 |
