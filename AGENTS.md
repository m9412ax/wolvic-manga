# AGENTS.md — wolvic-manga 接手必读（2026-11-21 Round O 更新）

## 一句话接手（直接复制给下一个 AI）

> 接手 `D:\APP\wb\gzkj\2026-11-14-02-57-34\wolvic-manga`（GitHub `m9412ax/wolvic-manga`，分支 `main`）：先读根目录 AGENTS.md（在线最新）再动手。以 **Igalia/wolvic** 为 VR 基座、**saber1008611/mihon-webdav** 为漫画功能参照，把 Mihon 全部功能用 Wolvic 的 VR 控件 UI 复现。基础阅读功能已稳定（tag `stable-2026-11-17`），当前阶段：扩展 APK 自定义源兼容（最高优先）、SMB、115/阿里云盘。凭据读 `~/.gh_pat` 注入 `GH_TOKEN`（严禁任何输出/提交打印明文）；构建走 `.github/workflows/build-picoxr.yml`，分段下载校验后 `adb install -r`；adb `D:/YanRainOTAToolBox/resources/platform-tools/adb.exe`，Pico SN `PA7N10MGF5060233W`，VR 验证需佩戴头显从应用列表启动。出结果直接修不用问。

## 项目定位与参照

- 基于 Igalia/wolvic（MPL-2.0）的 Pico Neo3 独立漫画应用。VR/OpenXR/浏览器全用 Wolvic，不自研。
- 漫画功能参照 `saber1008611/mihon-webdav`（本地解压：`D:/APP/wb/gzkj/2026-11-14-02-57-34/mihon-webdav/saber1008611-mihon-webdav-60d83b9/`）。功能全复现，UI 用 Wolvic 控件，不照搬 Compose。
- 旧 `m9412ax/mihon-webdav`（自研 OpenXR，失败）已删除。C 盘 `Documents/Qoder/2026-11-13/96859152/wolvic-manga` 是**过时副本**，一律以 D 盘 checkout + 远端 main 为准。

## 凭据 / 工具

- PAT `~/.gh_pat` → 只注入 `GH_TOKEN`，严禁打印。账号 `m9412ax` 用户已授权复用。
- 便携 gh：`C:/Users/Administrator/Documents/Qoder/2026-11-13/96859152/gh-portable/bin/gh.exe`。
- Python `D:/APP/Python/python`（`-X utf8`，中文路径写脚本文件别用 `-c`）。GitHub API 直连（`ProxyHandler({})`）。
- **推送用 Git Data API**（blobs→tree→commit→ref），git push 走代理会 502。本地提交后 API 推送的远端 SHA 与本地不同（committer 时间戳），tree 相同；`git fetch` + `diff --stat` 确认一致后 `reset --hard origin/main` 对齐。
- adb 调用前 `export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'`；中文路径用 Python subprocess。
- 无本地 NDK/JDK/Android SDK，**只能 CI 构建**。

## 红线

- 凭据不得出现在任何输出/日志/提交。
- 声称"跑通"必须有证据：编译看 CI conclusion，运行看设备 logcat/头显实测；未实测标"未验证"。
- 不删用户数据；`adb install -r` 保数据升级。官方包 `com.igalia.wolvic` 绝不触碰；开发包是 `com.igalia.wolvic.dev`（固定 CI keystore，签名已稳定）。
- 用户已确认正常的功能（见下）不许回归。

## 稳定版存档（回退点）

- **tag `stable-2026-11-17` = 提交 `8313c5a`**，用户头显实测确认基本功能完善。
- APK 存档：`D:/APP/wb/gzkj/2026-11-14-02-57-34/stable/wolvic-manga-stable-2026-11-17-8313c5a.apk`（219620100 B，SHA256 `e211b21283513d2515bdb8fbfebd3107f76c448f560048525f919810ad19f631`，同目录 SHA256SUMS.txt）。
- 回退方法：直接 `adb install -r` 该 APK（同签名保数据）；或代码回退 `git checkout stable-2026-11-17` 后走 CI。
- 之后新功能出问题，先回退到此版本再排查。

## 当前装机版本（Round H，2026-11-18）

- 远端 main `2abadec4`（iOS/AGP 构建修复后），**CI run `44263887430` success**，artifact `14410110154` 用 `gh run download` 下载（直连 Range+token 会 302 丢 Authorization 报 401，gh 处理签名）。APK `222291463 B`，SHA256 `712aeadc923bb22d655eea0703939d96b3aec6ea88872377197fe0ab1fe79a3a`，ZIP CRC `testzip OK`、29 dex；`adb install -r` Success，versionCode `2103220645`→`2103220805`，firstInstallTime 保留 `2026-11-14 04:25:15`。
- 装机后 APK 内 9 个新符号全命中：`MangaChapterSession/chapterDelta/jumpToPage/notifyTrayProgress/updateReaderProgress/readerPageSeek/ChipFlowLayout/reader_progress_row/openMangaReaderSession`。**功能本身待用户头显逐项实测（未验证）**。
- 本轮提交经 Git Data API 推送（本地 `4ec681d` 与远端 Round F/G 分叉，远端更新，以远端为 base 用 blobs→tree(base_tree)→commit→PATCH ref 合入 5 个代码文件，AGENTS.md 保远端版）。首构 run `44263456102` 失败：`android:allCaps` 属性不被本 AGP 资源编译器识别（tray.xml 两处），删除该属性后重构建成功——**以后 tray.xml 按钮别写 `android:allCaps`**。

### Round H 修复（用户本轮 4 项，代码已装机未头显实测）

1. **标签/筛选 chip 按实际宽度换行**：`MangaLibraryWidget.ChipFlowLayout` 自定义 `onMeasure/onLayout`，按测量宽度换行替代旧"每行硬编码 3 个"；`addChip/endChips` 改为创建/关闭该布局。
2. **换漫画仍显示上一本**：`MangaReaderWidget` 加 `mChapterSession` 代次号，切新漫画时 ++；异步章节下载回调校验代次，旧漫画的回调不再把旧目录装进新漫画；`setChapters`/`setFolder` 瞬时清空 `mCurrent`/strip/封面；`VRBrowserActivity.openMangaReaderSession` 把 `setUiMode(UI_READER)` 提前到 `setChapters` 之前（隐藏 widget 不重绘会残留旧帧）。
3. **多章无法切下一章**：章节边界兜底——`showPage`/`pageDelta`/连续条带滚动越界时调 `advanceChapter(±1)`；`chapterLoadingGuard()` 20s 超时复位防卡死；订阅预取相邻章；页码计数显示"章n/总·页x/y"；`chap:<key>` 进度 `章|页|比例` 恢复。
4. **隐藏式阅读进度控制行**：`tray.xml` 新增 `reader_progress_row`（上一章/可拖动 SeekBar/页码标签/下一章，默认 `gone`）；`TrayWidget.setupReaderProgress` 只在指针悬停 reader 按钮行时显示（移出 350ms 后隐藏）；SeekBar 停拖调 `reader.jumpToPage()`，章节按钮调 `reader.chapterDelta()`；阅读器 `notifyTrayProgress` 回写进度。

## Round I（2026-11-18 下午）：首开图片/进度条修复 + COS君扩展

### 主机修复（CI `44271638729` success，装机 versionCode `2103220859`，firstInstallTime 保留）
- **首开图片不显示**（用户反馈"打开显示加载文字、图片不出、退出重进几次才好；切下一章正常"）：根因=异步解码落地时 widget 未 measure，`applyImageTransform()` 因 vw/vh==0 直接 return，MATRIX ImageView 不绘制。修复=`displayLoadedPage`/`applyPageDetail` 里 setImageBitmap 后 `mPageImage.post(this::applyImageTransform)` 等布局就绪再落地；`openMangaReaderSession` 把 `reapplyCanvasSize()` 提前到 `setChapters()` 之前先定尺寸。
- **悬浮进度条无效**（用户反馈"指向控制栏没有多出进度条"）：两因叠加——① hover listener 挂在 `reader_buttons` LinearLayout 容器上，VR 射线命中子 UIButton，hover 不冒泡到容器，永不触发；② 进度行作为新行加在 66dp 托盘下方会被裁切。修复=`TrayWidget.dispatchHoverEvent()` widget 级捕获（同已验证的视频控制栏方案）；把 `reader_progress_row` 移进按钮行的 FrameLayout 叠加层（40dp 锚底、覆盖按钮行）。**以后 VR 里给容器挂 OnHoverListener 无效，必须 widget 级 dispatch。**

### COS君扩展（mihon 格式，源码 `extensions/cosjun/`，专用 CI `build-extension-cosjun.yml`）
- 站点 `https://www.cosjun.cn`：WordPress/ripro。分类 `/photo`(COS妹子)/`/benzi`/`/life`/`/zhuanqu`；列表 `article.post`，封面 `img[data-src]`(timthumb 需还原)，日期 `time[datetime]`，`fa-lock`=需登录。排序 `?orderby=date&order=asc|desc`（默认新到旧）；分页 `/page/N`；搜索 `/?s=关键词`；标签 `/tag/<urlencoded>`。
- **登录**：`POST wp-admin/admin-ajax.php` `action=user_login&username=&password=&rememberme=forever` → `{"status":"1"}`；靠 **PHPSESSID** cookie 维持（不是 wordpress_logged_in_*），登录后 gallery 从 4 张试读变全量（如 753789：4→67 张 + 1 mp4）。扩展里用 `ConfigurableSource` 的 `EditTextPreference` 让用户填账号密码，`ensureLoggedIn()` 30 分钟缓存一次。
- **详情**：gallery 有多个（gallery-2 试读 4 张、gallery-3 全量），取 items 最多的那个取 `.gallery-icon a[href]` 原图；视频 `<video class=wp-video-shortcode><source src=*.mp4>` 放最前。COS 名=第一个 `/tag/` 链接文本，放到 `SManga.author`（宿主可点击作者筛选）。
- **下一本**（用户要求按点进来时的排序/搜索/筛选）：一部漫画=一个图片目录不分章；扩展内存缓存最近一次浏览列表 URL `browseQueue`，`getMangaUpdate` 把它从当前项往后的尾部当伪章节返回，宿主的跨章机制即变"下一本"。**注意 browseQueue 是内存态，app 重启后"继续阅读"进入时队列为空→只有自身一本**，此场景无下一本（已知限制）。
- **扩展 ABI 契约**：继承 `HttpSource`+`ConfigurableSource`，直接覆写 suspend `getPopularManga/getLatestUpdates/getSearchManga/getMangaUpdate/getPageList`（**不要**覆写不存在的 `getMangaDetails/getChapterList`）；`compileOnly project(':source-api')` 绝对不能把 ABI 类打进 APK（DelegateLastClassLoader 要求）。gradle 坑：coroutines 用 source-api 传递的 1.6.1，别在扩展里显式声明 `core-jvm:1.7.3`（BOM 严格冲突）；jsoup 是 source-api 的 implementation 不传递，扩展需显式 `compileOnly 'org.jsoup:jsoup:1.23.2'` + `import eu.kanade.tachiyomi.util.asJsoup`。
- 扩展 APK `694352 B`，SHA256(zip) `c1e0f88c...965`，zip OK、**未打包任何 eu/kanade/tachiyomi/source 类**；已 `adb install -r` 到 Pico（包 `eu.kanade.tachiyomi.extension.zh.cosjun`）。**运行时是否出现在书架"漫画源扩展"页、能否真正加载/登录/翻页，需用户戴头显实测（未验证）**。诊断 intent `manga_test_source` 在 `loadFromIntent()` 里，普通 `am start` 不触发，别用它判断加载成功。

## Round J（2026-11-18 晚）：进度条位置 + 扩展"未识别"修复

用户反馈：① 进度条能弹出了但覆盖控制栏按钮（要浮在控制栏上方）；② cos君扩展在 VR 项目显示"未识别扩展"、官方 mihon 不显示（第三方 mihon 软件能用）。

- **进度条覆盖按钮**：进度行原本 `layout_gravity=bottom` 盖住 reader_buttons。改为 `layout_gravity=top` + `translationY=-40dp`，浮到按钮行上方（status_bar 那一带），不再遮挡按钮。主机 CI `44277586393` success，装机 versionCode→`2103220938`。**需头显确认视觉位置**。
- **扩展"未识别"根因（重要 ABI 坑）**：logcat `MangaSource: LinkageError: Cosjun.getSearchManga(... Continuation) resolves differently in superclass HttpSource: Continuation DelegateLastClassLoader vs PathClassLoader`。原因是 Kotlin 插件默认把 `kotlin-stdlib`/coroutines 当 implementation 打进扩展 APK（2.3MB dex），`DelegateLastClassLoader` 于是加载了扩展私有的 `Continuation`，与宿主 HttpSource 的不匹配。修复：根 `gradle.properties` 加 `kotlin.stdlib.default.dependency=false`（app/source-api 都显式声明 stdlib 故不受影响）+ 扩展依赖全 compileOnly。新 APK 从 2.3MB 降到 **30KB**，CI `44277595624` success。装机后 logcat 确认 `loaded source: name=COS君 id=6145523130229688195 ... failures=0`——**已成功识别加载**。
- **扩展 logo**：下载站点 `https://www.cosjun.cn/wp-content/themes/ripro/assets/images/logo/logo.png`（380×120）存为 `extensions/cosjun/src/main/res/mipmap-hdpi/ic_launcher.png`，manifest 加 `android:icon="@mipmap/ic_launcher"`。
- **签名变更坑**：扩展 workflow 注入了 stable debug keystore，与首版（默认 debug）签名不同，`install -r` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`——需先 `adb uninstall eu.kanade.tachiyomi.extension.zh.cosjun` 再装。**以后扩展重新构建若签名变了要先卸载**。
- **官方 mihon 不显示属正常**：官方只收录其签名/仓库的扩展；本扩展是私密自签，只在 VR 项目和第三方 mihon 规则软件可用。
- 诊断验证：扩展加载在 app 启动 `initializeWidgets→fillExtensionsPage→MangaSourceManager.init` 时就发生（不用进扩展页），冷启动后 `logcat | grep MangaSource` 看 `loaded source` / `init done`。

## Round K（2026-11-19）：进度条裁切/渐进下载/登录持久化/滚动

用户反馈：① 进度条又完全不显示；② 首图慢、后续秒切；③ 切章只 4 张、外面重开完整（怀疑登录）；④ 其他源首开串上一本、章节偶无图；⑤ 去掉"试读"；⑥ 连续模式下滑回滚；⑦ 滚动卡顿。

- **进度条完全不显示根因**：Round J 的 `translationY=-40dp` 把进度行整体移出父 FrameLayout 边界被裁，且 tray surface 仅 66dp 放不下两行。修复：进度行从按钮 FrameLayout **移出** 为 tray_rows 独立子元素（按钮行上方）；`applyMangaMode` 在 MODE_READER 隐藏 `status_bar` 腾 20dp；进度行 24dp+按钮 40dp=64dp<66dp。**别再用 translationY 负偏移，会被父容器裁掉**。
- **"切章只 4 张"根因（关键，非登录）**：切章复用同一 source/client/cookieJar，登录态没丢。真因=`prefetchChapter(...,4)` 只落 4 页 + `loadChapter` 把"目录非空"当完整章节 → 永久停 4 页；"4"与匿名试读 gallery 撞号。修复：① `downloadChapter` 渐进式——先下前 `OPEN_AFTER_PAGES=8` 页即 `cb.onResult(dir)` 打开，剩余后台续传；② prefetch 4→64；③ `loadChapter` 有页立即打开+后台 `ensureChapter` 补齐，`DirCallback.onProgress` 增量 `refreshChapterPageList()` 增长 mPages（保持 mIndex）；④ ready 判定 `countImageFiles()` 只数真实图片/视频（排 .tmp/0字节）。
- **其他源串上一本**：`setChapters` 补齐 setFolder 式完整重置（mPages.clear/mIndex=0/mLoadGeneration++/取消 mPendingPages/cache.evictAll/清 executor 队列）。
- **连续模式回滚**：`onControllerScroll` 里 continuous 仍把水平漂移当 pageDelta→showPage 重置 strip。修复：isContinuous() 时 `mScrollAccumX=0`；向下 neighbour 未解码钳在 seam(`curH+0.001f`) 而非整页底避免来回弹。
- **流畅度**：`decodeSampled(..., Bitmap.Config.RGB_565)`（内存/带宽减半）+ MangaStripView 去 `DITHER_FLAG` 留 FILTER_BITMAP_FLAG。
- **"试读"按钮**：删 MangaLibraryWidget 两处 `addButton("试读",...testFetch)`。
- **登录持久化**：扩展加 `KEY_SESSION=cosjun_phpsessid`，登录成功后从 `Set-Cookie` 提取 PHPSESSID 存 prefs；覆写 `client` 加 interceptor 对 cosjun.cn 请求注入 `Cookie: PHPSESSID=...`（进程重启仍有效）；加"退出登录/重新登录" Preference；账号/密码变更清 session。之前 cookie 只在 WebView CookieManager（会话级、重启丢）。站点若轮换 session 仍可能失效，30 分钟节流会重登刷新。
- **构建坑**：Kotlin `var opened` 必须声明在 try 外（catch 要用），放 try 内会 `Unresolved reference` 编译失败（`MangaSourceRepository.kt`）。
- CI：扩展 `44500883269` success、主机 `44501704559` success（首构 `44500875432` 因 opened 作用域编译失败）。主机装机 versionCode `2103220938`→`2103231002`，firstInstallTime 保留。远端 main 最终 `0a2b59c2`；扩展已装（先 uninstall 旧版）。logcat 确认 `loaded source: name=COS君 ... failures=0`。
- **待头显实测**：进度条位置、首图速度、切章完整张数、其他源串本/章节完整性、连续模式回滚、滚动流畅度。

## Round L（2026-11-19 晚）：进度条常驻/状态栏/返回键/视频栏/快滚

用户反馈：滚动好点了但快滚仍不流畅（移动方式）；进度条效果可以但要常驻；恢复状态栏（时间/电量）+圆角协调；手柄返回键不生效；视频控制栏进度条不可点+移出消失太快。

- **手柄返回键（重要链路）**：native B/APP → `VRBrowserActivity.handleBack()` → `dispatchKeyEvent` → `onBackPressed()` → `mBackHandlers` 栈顶。根因：`setUiMode()` 只在 UI_BROWSER push handler，READER/LIBRARY 不 push；且 `dispatchMangaKey()` 会把绑到 BACK 的动作吞掉。修复：① `dispatchMangaKey` 开头 `if (code==KEYCODE_BACK) return false`；② `MangaLibraryWidget.goBack()`（FOLDER→上级、LOCAL→上级、WEBDAV→上级、SOURCE_*→EXTENSIONS、其它→LIBRARY）；③ Activity `setUiMode` 按模式切换 back handler（READER→`closeMangaReader` 回书架、LIBRARY→`handleMangaLibraryBack` 返回上级，书架根则回浏览器），用 `mMangaBackHandlerMode` 避免叠加。
- **视频控制栏**：`MangaReaderWidget.setupVideoBar()` 代码构建，`mVideoSeek` 有 OnSeekBarChangeListener（fromUser→seekTo）。"点不到/晃一下就没"根因：`dispatchHoverEvent` HOVER_EXIT 无差别即时隐藏（无 timer；tray 的 400ms timer 是翻页 scrubber 不是视频栏）。修复：加 `mHideVideoBarRunnable` 延时 `VIDEO_BAR_HIDE_DELAY_MS=1000`，HOVER_EXIT→postDelayed，ENTER/MOVE→removeCallbacks 立即显示，拖动 seek 时 `scheduleVideoBarHide` 不隐藏；显示仍走 `updateVideoBarVisibility` 即时。
- **进度条常驻+状态栏+圆角**：reader 模式进度行不再 hover 显示，`applyMangaMode` 里 MODE_READER 直接 VISIBLE、status_bar 所有模式恢复 VISIBLE；新增 `tray_height_reader=88dp` + `setTrayHeightForMode()` 在 reader 增高 placement.height 并同步 mBaseTrayHeight（onMangaParentResized 会 re-pin）；**tray.xml 根高度必须 match_parent**（原来固定 `@dimen/tray_height` 66dp 会裁掉增高后的三行——这个坑导致二次构建）；进度行 26dp 独立行、背景 `tray_background_middle_plain.xml` 无圆角，配 top/bottom 圆角协调；mHideProgressRunnable 改 no-op、dispatchHoverEvent 不再控制进度行显隐。
- **快滚流畅度**：`MangaStripView.setStrip` 加变化检测（无变化不 invalidate，改 postInvalidateOnAnimation 合并帧）；`setOffset` 检测帧间 delta>12% 屏高时临时关 FILTER_BITMAP_FLAG，120ms 后恢复。Round K 已做 RGB_565+去DITHER。
- CI：主机 `44524305832` success（但发现根布局裁切 bug），修复 tray 根 match_parent 后重构 `44525027525` success。本地 `89d652c`+tray fix，远端 main `c1b66363`。扩展无改动不重建。**待头显实测**：进度条常驻+状态栏圆角视觉、手柄返回键、视频进度条点击+1s消失、快滚流畅度。

## Round M（2026-11-19 夜）：COS君点击/切章慢 —— 重复抓 HTML + 登录节流失效

用户反馈：COS君点进去章节很慢"无法点击进去"；从上一本切下一本若没加载完，后面图片很慢很慢。

- **根因（探查确认，非"等图片下载"）**：UI 线程无阻塞（全 IO + main.post）；getMangaUpdate 不下载图；getImageUrl 灾难排除（COS君 parsePages 已填 imageUrl，1图1直链）。真因：① **同一详情页 HTML 被抓 2-3 次**（getMangaUpdate 详情文本 + prefetch getPageList + download getPageList 各抓一次 `/photo/ID` 大 HTML）；② **登录节流失效**——loginStamp 是内存变量，进程重启=0，而 session cookie 由 prefs 持久=true → `currentMillis-0` 远超30分钟 → 每次 ensureLoggedIn 都 `synchronized + client.execute()` 同步登录，每个请求前多一次往返；③ downloadChapter 旧版 head=8 全下完才回调，首图被前置 HTML 抓取拖累。
- **修复**：Host `MangaSourceRepository` 新增 `pageListCache`（key=sourceId:chapter.url，TTL 5min）`cachedPageList()` 供 download/prefetch 共用；downloadChapter **第一页落地即 cb.onResult 打开**（去 OPEN_AFTER_PAGES=8）；`MangaChapterSession` prefetch 64→3 页（64 页预取占满同主机 5 并发，反而拖慢刚切的章）。扩展 `Cosjun`：`loginStamp` 持久化 prefs（KEY_LOGIN_STAMP）；`docCache`（Document+60s TTL）供 getMangaUpdate/getPageList 共用，登录成功 clear（匿名4图→全量）。
- **教训**：COS君 chapter.url == manga.url == 详情页 URL；page list 抓取=大 HTML 请求，必须缓存复用，别在 prefetch/download 里重复抓。首图快的关键是缓存前置 HTML + 首图落地即显示，不是多预下载。
- CI：主机 `44530431447`、扩展 `44530447102` 均 success。本地 `d6bc022`，远端 `75667d3e`。扩展已装（先 uninstall），logcat 确认 `loaded source: name=COS君 ... failures=0`。**待头显实测**：点详情/章节速度、切章后图片速度。

## Round N（2026-11-20）：进度分母/提前切章、多线程下载、视频seek锁、SMB2/3、115扫码

用户反馈：进度显示已加载数(1/8而非1/15)；翻到已加载末尾再翻页直接切下一章；要求多线程分层加载；视频摇杆快进回弹；并要求做 SMB 真协议栈 + 115 扫码登录（用户已确认方向：真做SMB、115原生+App内扫码、自己登录）。

- **进度分母/提前切章（同根因）**：分母/章节末尾都用 `mPages.size()`(已落盘数)，真实 total 被 onProgress 丢弃。修：MangaReaderWidget 加 `mPageTotal`(真实总数)+`pageTotal()`=max(mPageTotal,mPages.size())，onProgress 捕获 total；所有分母(counterText/notifyTrayProgress/goto)与4处章节边界(pageDelta/showPage/连续跨页/onAutoTick/预取)改用 pageTotal()；新增 `requestPage()`+`mPendingTargetPage`：未落盘但属本章的页等待下载落地后自动跳(refreshChapterPageList 里 jumped 逻辑)，不再误切章。
- **多线程分层下载**：`MangaSourceRepository.downloadChapter` 串行 forEach → coroutineScope+async 四阶段：①page0落地即打开 ②前4页 ③startPage后4页 ④剩余全部并发(Semaphore 6)。每页只处理一次(reported set)、progress 报真实 total。`ChapterProvider.ensureChapter` 加 startPage 参数(传 reader 当前 page)。`NetworkHelper` okhttp dispatcher perHost 5→8/maxRequests 32（否则协程并发被卡）。enforceCacheCap 中途每8页触发+清 .tmp。图片落 `externalCacheDir/manga-sources/...`，LRU 按 SOURCE_CACHE_MB 按最旧整章删（已满足用户"按大小存超了删最早"）。
- **视频摇杆快进回弹**：seekTo 异步 + 400ms tick 用 getCurrentPosition()(旧位置)回写 UI；进度条拖动有 mVideoSeekDragging 抑制但摇杆没有。修：加 `mVideoSeeking/mVideoSeekTargetMs` 锁，seekVideo 以目标为基准累加(连续摇杆不丢步)+立即 paintVideoPosition，tick 跳过回写，onSeekCompleteListener 解锁，1500ms 超时兜底；拖动/按钮也纳入锁。
- **SMB2/3（新增）**：用 **smbj 0.13.0**（纯 SMB2/3 Apache-2.0；jcifs-ng 2.1.10 根本不存在、最新2.1.9 还带 servlet/log4j12 不适合 Android）。`MangaSmb.java` 每次操作新建 SMBClient/Connection/Session/DiskShare 并 finally close；list() + download() 到 `.tmp`→rename。SMB 页重写：连接/列出、目录浏览、点文件下载到 DOWNLOAD_DIR/SMB 后 openReader。API 从 smbj sources jar 核对：openFile 6参(FILE_OPEN,null)；大小=getFileInformation(path).getStandardInformation().getEndOfFile()；ProgressListener.onProgressChanged(num,total)。
- **115 扫码登录（新增，仅登录）**：参照仓库无 115 App 内扫码代码（只有停摆的 Bearer token）。`Manga115Api.java` 三步：qrcodeapi token → get/status 轮询 → passportapi/qrcodeapi 换 cookie（双域名回退，app=tv 避免踢号，cookie=UID/CID/SEID/KID），user/info 校验。WebDAV 页内嵌 115 登录区（不改 tray.xml）：zxing 渲染 QR、1.5s 轮询、登录态存 MangaSettings(P115_COOKIE/P115_DEVICE)。**列目录/直链/m115加密暂缓，等用户头显验证扫码链路**。
- **编译踩坑（3轮CI）**：①新类未 import → package does not exist；②`return written` 应为 `written[0]`；③`URLEncoder.encode(str,"UTF-8")` 受检异常 → `encode(str, StandardCharsets.UTF_8)`。`gh run view --log-failed` 常超时，改用 `gh api .../jobs/$JOBID/logs > file` 再 grep error。
- **api_push.py 致命坑**：git trees API 的 content 必须传**原始 UTF-8 文本**，不能 base64（曾双重编码把4个源码文件写成 base64 文本致全 CI 失败）。用前确认。
- CI：主机 `44756396547` success（含全部）。本地 `e502f29b6a`，远端 main `e502f29b6a`。扩展无改动（沿用上轮已装）。**待头显实测**：进度分母/切章、多线程加载速度、视频快进、SMB 列目录+下载、115 扫码登录。

## Round O（2026-11-21 凌晨）：SMB 自动连接 + 115 二维码闪退

用户反馈：SMB 填完 IP/账号密码无法点连接，要求保存后下次自动连接直接显示共享目录；115 获取到二维码但未扫码就闪退。

- **SMB 交互重构**：新增 `smbConfigured()`/`mSmbAutoConnected`/`mSmbConfigOpen`。`switchPage` 切到 SMB 且已配置且本次会话未自动连接过时自动 `connectSmb()`；`fillSmbPage` 分两态——已配置=纯文件夹浏览器（▣目录/▤文件、上级/刷新/设置/打开本地目录），未配置或点「设置」=表单+「保存并连接」。不再重复出现连接设置。
- **115 二维码闪退**：根因推断=poll 每 1.5s 触发 `refreshPage(WEBDAV)` 重建，旧代码每次重新生成 320×density px 的 QR Bitmap 且不 recycle → 内存暴涨 native SIGSEGV（logcat 的 `signal 11` 多为 Pico crashhelper 噪音，主进程日志未崩，但用户实测闪退）。修复：QR Bitmap 缓存字段 `mP115QrBitmap` 只生成一次；固定 320px（不乘 density）+ ErrorCorrectionLevel.L + int[]/setPixels 一次性写；`catch(Throwable)`（WriterException/OOM 都不闪退）；会话结束（退出登录/失效/登录成功失败/重扫）recycle 并清空。生成失败时降级显示二维码原始链接文本。
- CI：主机 `44781732617` success。远端 main `e172e8b1`。构建前推送曾遇 WinError 10060 超时（commit 未创建、main 未变），重试即成。**待头显实测**：SMB 自动连接/文件夹浏览、115 扫码不再闪退。

## 已确认解决（用户头显实测认可，别回归）

1. **视频**：Preparing 期间 getDuration/seek/start 全部加 `mVideoPrepared` 守卫+代次校验；控制栏/进度条锚定实际视频矩形。
2. **滑动预读**：当前页 ±N 移动窗口、任务去重、过期取消、前台/后台独立线程池、按页分摊字节预算。
3. **AVIF 基本解码**：本地 AAR `app/libs/imagedecoder-14.aar`（libvips→libheif→dav1d），Kotlin 工厂 `new(InputStream)` 反射调用。
4. **AVIF 高分辨率 OOM**（22745f8）：`app/src/main/cpp/MangaImageDecoder.cpp` JNI dlopen libimagedecoder2.so，SDR 在 native 缩放到目标尺寸后才生成小 Bitmap；HDR 保留 F16 路径。
5. **AVIF 切图慢/无缓存感**（3c57aaf，用户确认"缓存翻页正常了"）：解码结果转存 JPEG 磁盘缓存（512MB LRU，`avif-cache/`，重读 ~100ms）；前台解码优先于预读（ThreadLocal+waiter 计数）；**AVIF 不再做高清二次解码**（预览已够窗口分辨率，二次解码 3s+ 且 54MB 超单页预算被秒逐）。
6. **闪烁大幅改善**（bbb3690+3c57aaf）：阅读背景"黑色"原来是全透明（每帧与 VR 环境合成），改不透明 0xFF000000；去掉 AVIF 预览→高清替换。用户反馈"好很多"，开背景图偶发残留未完全定位，勿乱改 VR 合成层。
7. **连续模式上滚跨页跳变**（8313c5a，用户确认"可以了"）：根因是跨页偏移公式符号反了——`Math.min(0, offset+prevH)` 把本应为正大数（视口在上一页尾部≈页高）的偏移钳成 0=页顶，每次上滚跨页必跳顶部、按住摇杆连环前跳（跳远距离=预读半径）。修复为 `clamp(offset+prevH, 0, prevH)`。配套：shiftStrip 立即调 updateStripTransform 重算宽度锚定缩放+偏移换算（mLastStripSourceWidth）。
8. 更早确认：大图分块（>72MiB 走 tiles）、双页合并、指针锚点缩放、拖动平移、快捷键、缓存串图修复、连续下滚跨页、设置五按钮布局（逐项功能未复验）。

## 当前阶段任务（按优先级）

1. **扩展 APK / 自定义漫画源兼容（最高优先，用户最担心）**：协议管线已打通（见下"扩展阶段进展"），剩设备逐项实测。
2. **SMB2/3 真读取**：Round N 已用 smbj 0.13.0 实现列目录+下载（MangaSmb.java），待头显实测；若实测有问题优先查认证(domain 空/guest)、中文路径编码、445 连通。
3. **115 网盘**：Round N 已做扫码登录（Manga115Api.java+WebDAV 页内嵌 QR UI），待头显验证扫码链路；**未做**：列目录(webapi.115.com/files)、直链(proapi downurl+m115 加密~150行)、下载。阿里云盘未做。
4. WebDAV 浏览/下载已真，"下一章"与免下载阅读不完整。
5. 下载队列持久化/断点续传；备份明文导出可能含凭据，需加密或脱敏。

## 扩展阶段进展（2026-11-17 晚，main `697e6c6`）

- **source-api 移植**（`source-api/` 模块）：Mihon 1.6 ABI（Source/HttpSource/ParsedHttpSource/ConfigurableSource/SourceFactory/Filter/FilterList/SManga/SChapter/Page/MangasPage + network/okhttp 拦截器 + preference store + injekt 桥）。版本钉死：okhttp **5.3.0**（5.5 要 compileSdk>36）、coroutines **1.7.3**（1.9 的三参 resume 编译不过）、injekt **1.16.1**（`Injekt.importModule` + `InjektRegistrar.registerInjectables()`）、kotlinx-serialization 1.11.0、Kotlin 2.4.0。
- **加载器** `manga/MangaSourceManager.kt`：reqFeatures `tachiyomi.extension`；meta `tachiyomi.extension.class`（分号分隔、前导 `.` 补包名）/`.nsfw`/`tachiyomix.name`/`tachiyomix.extensionLib`；lib 1.4/1.6；`DelegateLastClassLoader(sourceDir,null,context.classLoader)`；**自动信任，无授权弹窗**。Injekt 模块注册 Context/**Application**/Json/NetworkHelper——**Application 必须注册**：jinmantiantang/picacomic 在 `<clinit>` 里 `Injekt.get<Application>()`，缺了抛 InjektionException（697e6c6 修复，设备日志证实）。每个失败包记录 `LoadFailure` 并在扩展页显示 ⚠ + 错误详情，logcat tag `MangaSource` 带完整堆栈。
- **扩展列表 UX**（`MangaLibraryWidget.java`）：按包折叠（单源包默认展开）、应用图标、优先内置中文名（`source.name`，如 哔咔漫画/禁漫天堂），完整英文名只在设置详情；ConfigurableSource 动态设置（`PreferenceManager` prefs `source_$id` + `setupPreferenceScreen` 递归渲染，**persistBoolean/persistString 是 protected，用 setChecked/setValue/setText**）；筛选 chips 来自 `getFilterList()`（Select 单选/CheckBox/TriState/Sort/Group/Text），默认"新到旧"排序；搜索+热门+最新；封面网格浏览（`SourceCoverAdapter` + `MangaSourceRepository.loadCover`）。
- **详情页**：封面/作者/标签/简介/状态，作者与每个标签可点击→搜索；章节列表（名称+发布日期+缓存✓，chapter_number/date_upload 稳定排序）；开始阅读=第一章第一页，继续阅读=保存位置。
- **跨章节阅读**（Round D，`manga/MangaChapterSession.java` + 阅读器）：章节播放列表；单双页与连续模式在章末/章首自动跨章（下一页→下一章第一页；上一页→上一章末页，连续模式落到其底部）；`preloadNext` 接近章边界时磁盘预缓存相邻章首 4 页（`prefetchChapter`），缓存上限 `source_cache_mb`（256/512/1024/2048 MB，漫画设置可调），超限按最旧目录淘汰；进度键 `chap:<key>` = `章|页|滚动比例`（连续模式精确滚动位置，500ms 防抖+翻页即存）；在线 key `src:<sourceId>:<mangaUrl>`，本地 `local:<rootPath>`。
- **本地文件夹章节化**：大文件夹=一部漫画，含媒体的子文件夹=章节（自然排序）；直接打开子文件夹自动识别父级；`openLocalSession` 统一入口，`VRBrowserActivity.openMangaReaderSession`。
- **CI 记录**：Round C `cfe7c99` run 44058339884 success；Round D `ba2b6a5` run 44060140732 success（已 install -r）；Round E `697e6c6`（Injekt Application 修复）CI 见 run 记录。**设备实测待用户逐项确认（未验证）**。
- 扩展 APK 分析工具：androguard（`pip install androguard`，4.x 返回 lxml，`logger.remove()` 关日志）；8 个扩展入口都是 `keiyoushi.source.Generated`（混淆继承链），meta 结构一致，差异在 dex 引用。

## 构建 / 部署要点（踩过的坑，别再踩）

- CI：`build-picoxr.yml`（workflow_dispatch，REST POST `{"ref":"main"}` 触发）→ 复用 build.yml，flavour `assemblePicoxrArm64GeckoGenericDebug`，`stable_debug_keystore: true`。**push main 还会触发 `ci-internal.yaml`（无 artifact），按 head_sha 查 run 时必须过滤 `path == .github/workflows/build-picoxr.yml`**，别认错。
- artifact 下载：`D:/APP/wb/gzkj/2026-11-14-02-57-34/artifact/seg_download.py <artifactId> <size>`，**需先注入 GH_TOKEN**；8 段并行 Range，SHA256+ZIP CRC 校验；输出目录按 **size** 命名不是 id。
- 日志：后台 logcat 采集 10 分钟超时，用户测试往往更久——**测完直接 `logcat -d` dump 缓冲区**更可靠。
- **模式验证陷阱**：验证连续模式前先 `run-as com.igalia.wolvic.dev cat shared_prefs/manga_settings.xml` 确认 `reader_direction=2`，且日志出现 `continuous shift`；用户可能实际在单页模式测试（曾因此误判两轮）。
- sparse-checkout：`read-tree -mu` 会把 LF 写成 CRLF，提交前检查 `b'\r\n'`；`.gitignore` 忽略 `*.aar` 需 `git add -f`；cpp 等非 cone 路径 `git add --sparse`。
- AAR 依赖坑：imagedecoder minCompileSdk=37 需删 AAR 元数据行；Kotlin 须 2.4.0。
- Pico 加载器 SONAME `libpico_openxr_loader.so`；CMake 新库加 `-Wl,-z,max-page-size=16384`。
- `WidgetPlacement.translation*` 是世界单位（1 米=360）；诊断 intent 会改变启动时序，视频验证必须从书架正常点进；`uiautomator dump` 看不到 GLES 自绘控件，用 logcat。
- adb push 整个中文目录会 std::bad_alloc 崩溃，逐文件 push；shell 内中文/空格路径必须加引号。

## 关键代码位置

- `app/src/common/shared/com/igalia/wolvic/ui/widgets/MangaReaderWidget.java`：阅读器+视频+连续条带+章节播放列表（~3260 行）。
- `manga/MangaImageDecoder.java`：AVIF 解码+磁盘缓存+前台优先。
- `manga/MangaSettings.java`：全部偏好（PREFS=manga_settings；进度 PROGRESS_PREFS；`source_cache_mb` 在线缓存上限）。
- `ui/widgets/MangaStripView.java`：连续模式 5 段条带绘制。
- `manga/MangaSourceManager.kt`：扩展加载器+Injekt 注册+失败记录。
- `manga/MangaSourceRepository.kt`：浏览/搜索/筛选/封面/章节下载/预缓存/缓存淘汰。
- `manga/MangaChapterSession.java`：本地/在线章节会话与 ChapterProvider。
- `ui/widgets/MangaLibraryWidget.java`：书架+扩展页+筛选+设置+详情+本地章节化入口。
- `ui/adapters/SourceCoverAdapter.java`：在线封面网格。
- `source-api/`：Mihon 1.6 ABI 移植模块。
- `app/src/main/cpp/MangaImageDecoder.cpp` + `app/CMakeLists.txt`：native 缩放 JNI。