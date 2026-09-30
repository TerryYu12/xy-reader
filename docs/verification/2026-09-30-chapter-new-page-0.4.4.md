# 0.4.4 验证档 —— 章首另起一页（小说章节自动分页）

日期：2026-09-30
版本：**0.4.4 / versionCode 16**

## 需求

「给 xy-reader 加个分章功能，小说章节要自动分页」——经确认具体含义为：
**每章另起一页**（此前章节是连排的，上一章结尾与下一章标题会挤在同一页）。

## 调研结论（先看别人怎么做，再动手）

两份带源码证据的子代理调研（Legado / KOReader / Librera / NBReader）：

- **阅读/Legado（Kotlin）**：按章独立分页——`TextChapter` 持章内页表，章标题排进该章新建首页；
  页码语义=章内（`index+1/pageSize`）；进度锚点=（章号, 章内字符偏移），页号是派生值；
  目录不显示页码。
- **KOReader / Librera**：全书一条页流 + CSS `page-break-before:always`；上一页余量**留白、不补空页**；
  crengine 对「已在页首的断点」做折叠以防真空页。
- **业界默认**：章首另起一页默认开启（多为结构性默认而非用户开关）；短章后留白被普遍接受。
- **本项目的落地选择**：保留「全书一次性预分页」架构，`paginate()` 内对章首段落强制断页
  （最小改动；翻页/滚动两模式共用同一页序）。不引入按章懒分页（更大重构，收益主要是大书
  首屏延迟，另行评估）。

## 实现（改动清单）

1. `archive/NovelPageSource.kt`
   - `NovelStyle` 新增 `chapterNewPage`（数据类默认 false——保持既有调用方行为）；
   - `paginate()`：章首段落前 `flushPage()` 强制断页（余量留白、不补空页；空页天然跳过——
     `flushPage` 对空集合无操作，段落 0 在首页不会产生前置空页）；
     `firstLinePage`/`buildChapters` 语义不变，章节页区间因此严格相邻（不共页、不留缝）；
   - 构造函数 `chapterMarks` 改为 `private val`（分页函数需要读取）。
2. `core/ReaderPrefs.kt`：新增 `novelChapterNewPage`（**默认 true**，主流阅读器惯例）。
3. `data/ReaderPrefsStore.kt`：新增 `novel_chapter_new_page` 键（缺省 true，读写全链路）。
4. `reader/ReaderViewModel.kt`：`styleKey` 纳入新字段（开关一改即触发整本重排）、
   `buildNovelStyle` 透传。
5. 两处 UI 开关：阅读弹层字体分组（「首行缩进」下方）+ 阅读设置页同名 SwitchCard。
6. `app/build.gradle.kts`：0.4.4 / versionCode 16。

## 验证证据

- 单元测试：**75/75 通过**（`test-044-chapter.log`：BUILD SUCCESSFUL in 3m25s；
  结果 XML 聚合 0 failures / 0 errors / 0 skipped）。
  新增用例 `chapterNewPageStartsEachChapterOnFreshPage`：
  ①相邻章页区间严格相邻（末页+1=下章首页，不共页）；②每章首页以该章首段起排；
  ③字符偏移锚点在章首分页下精确回位；④章首分页不减少总页数。
  另：`ReaderSheetCapsuleTest` 增加「章首另起一页」开关在弹层可见的断言
  （已验证编译进本轮测试、断言随 75/75 通过）。
- Release + lint：**BUILD SUCCESSFUL in 6m 22s**（`build-044-chapter.log`，80 tasks：
  31 executed / 49 up-to-date；lint **0 errors / 19 既有 warnings**，基线保持）。
- aapt 核验：`versionCode='16' versionName='0.4.4'` ✓
- APK 抽检（R8 后字符串留存）：单 `classes.dex` 4,913,876 B（非丢 dex）；
  `章首另起一页` ✓ / `novel_chapter_new_page` ✓（新 UI 文案、偏好键均进包）。
- 视觉抽检：Robolectric 渲染导出三张位图（章首页 / 章尾留白 / 连排对照），存档于
  `attachments/2026-09-30-chapter-preview/`。目检确认——章首页从页顶起排；章尾自然留白
  不填充；连排对照页可见旧行为（第二章内容与第一章同页连排、页面排满）与新行为的差异。

## 交付物

| 文件 | 字节 | md5 |
|---|---|---|
| `outputs/XY-READER-0.4.4.apk` | 22,352,497 | `09da59d3c0bde87515bba90deac3fab5` |

## 边界与已知取舍

- **短章章尾留白**：业界普遍接受（KOReader/crengine 甚至专门折叠重复断点防真空页），不填充。
- **升级后阅读位置一次性偏移**：章首分页令全书页数变多（平均每约两章多一页），旧版保存的
  页号在新分页里落在略靠前处；继续读一小段后进度自动重新对齐（任何重排的固有行为）。
- **上下滚动模式**：与翻页模式共用页序（各章自新页卡片开始）；业界「滚动=连续流」在本项目
  属另一功能（真·连续滚动视图），未列入本版。
- 卷/分册标题（`第X卷/部/集`）：本就会生成独立章标记，现在同样各自另起一页。

## 回滚

- 上一版：`outputs/XY-READER-0.4.3-spacing-controls.apk`；
- 行为级回退：应用内关闭「章首另起一页」开关即回到连排（会触发一次重排）。

## 真机待验证（用户执行）

1. 覆盖安装 `XY-READER-0.4.4.apk` → 设置页底部确认版本 **0.4.4**。
2. 打开一本有「第X章」的小说，翻到任意章尾：上一章最后一页应留白，下一章标题应
   从新页顶部起排，不再与上一章挤在同一页。
3. 阅读弹层（字体分组）与设置页应出现「章首另起一页」开关；关闭后章节恢复连排。
4. 若某本书章节没分开：截图其章标题格式发我（扩展识别规则属另一改动，不在本版）。
