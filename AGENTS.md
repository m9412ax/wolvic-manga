# AGENTS.md — wolvic-manga 接手必读

## 一句话接手（直接复制给下一个 AI）

> 接手 `C:\Users\Administrator\Documents\Qoder\2026-11-13\96859152\wolvic-manga`（GitHub `m9412ax/wolvic-manga`，分支 `main`）：先读根目录 AGENTS.md 再动手。以 **Igalia/wolvic** 为 VR 基座、**saber1008611/mihon-webdav** 为漫画功能参照，把 Mihon 全部功能用 Wolvic 的 VR 控件 UI 复现（启动默认漫画书架、浏览器降为选项；保留原格式扩展 APK/自定义源/网盘 WebDAV/SMB/文件夹本地；加视频优先播放+无缝切图）。GitHub 凭据读 `~/.gh_pat` 注入 `GH_TOKEN`（严禁任何输出/提交打印明文），用便携 gh `C:/Users/Administrator/Documents/Qoder/2026-11-05/3de213cf/gh-cli/bin/gh.exe`；构建走 `.github/workflows/build-picoxr.yml`（CI），分段下载校验后 `adb install -r`；adb 在 `D:/YanRainOTAToolBox/resources/platform-tools/adb.exe`，Pico SN `PA7N10MGF5060233W`，VR 验证需佩戴头显从应用列表启动。出结果直接修不用问。

## 项目定位

基于 **Igalia/wolvic**（VR 浏览器，MPL-2.0）的 **Pico Neo3 独立漫画应用**。把
**Mihon（saber1008611/mihon-webdav）** 的全部漫画功能移植进 Wolvic 的 VR 控件 UI。
旧 `m9412ax/mihon-webdav`（自研 OpenXR 路线，完全失败）已删除，**不复用其 VR 代码**。
Wolvic 原生层已解决 Pico OpenXR/世界锁/虚拟屏等坑，VR 部分全用 Wolvic，不自研。

## 参照项目（接手时必须指定）

| 角色 | 仓库 | 用途 |
|---|---|---|
| VR 基座 | `Igalia/wolvic` https://github.com/Igalia/wolvic | 本仓库是其 fork。UI 控件/VR/OpenXR/浏览器全用 Wolvic |
| 漫画功能参照 | `saber1008611/mihon-webdav` https://github.com/saber1008611/mihon-webdav | Mihon/Tachiyomi fork，含 WebDAV/SMB。**功能全复现，UI 不照搬**（用 Wolvic 控件重写） |
| Pico 官方 | `Pico-Developer` https://github.com/Pico-Developer | SDK 调用方式参照；OpenXR 加载器取自 `PICO-Unity-OpenXR-SDK` |

## 凭据 / 工具获取

- **GitHub PAT**：`~/.gh_pat`（即 `C:\Users\Administrator\.gh_pat`）。每次读入环境变量 `GH_TOKEN`，
  **严禁打印明文**。账号 `m9412ax` 是用户一次性 CI 镜像号，永久有效、全权限，用户已授权复用，不必每次确认。
- **便携 gh**：原路径 `C:/Users/Administrator/Documents/Qoder/2026-11-05/3de213cf/gh-cli/bin/gh.exe` 在 2026-11-14 复核时
  已不存在；若后续重新安装，可用 `GH_TOKEN=$(cat ~/.gh_pat) "<gh路径>" <命令>`，但不要假定它始终可用。
- **Python**：`D:/APP/Python/python`（3.14，有 numpy、PyNaCl）。调 GitHub API 用 `urllib` + `ProxyHandler({})`
  强制直连（环境代理隧道易 502）；输出中文前 `sys.stdout.reconfigure(encoding='utf-8')`。
  仓库 Secret 可用 `/actions/secrets/public-key` + `SealedBox` 加密后 PUT；不要提交私钥到仓库。
- **adb**：`D:/YanRainOTAToolBox/resources/platform-tools/adb.exe`；调用前
  `export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'`（Git Bash 会改写 POSIX 路径）。

## 当前进度（2026-11-14 接手复核）

- **P0 地基**：✅ 已验证。CI 自建 picoxr APK → 装机 Pico → 用户戴头显确认看到浏览器界面，
  logcat 见 `PxrLoader LoadRuntime succeeded OpenXR 1.1` + GeckoView 启动。
- **P2 第一增量**：已提交 `00db847`，构建 run `43482193239` = success；但设备上安装的是更早的 P0 包，
  `/sdcard/Manga` 为空且 `READ_EXTERNAL_STORAGE` 未授予，**原书架从未真机验收**。
- **本轮续修（已发布并装机）**：提交 `e489d7fa88a9e6dec9b4458c7d313de8a5490f9b`；修复
  start/Back 栈（书架不自我销毁、阅读器系统 Back 恢复书架、浏览器可返回书架、避免重复 `addWidget`）、
  Android 10 运行时存储权限与 `requestLegacyExternalStorage`、视频优先播放 + 自动切图 +
  页/播放进度、固定 CI debug keystore。CI run `43492214122` = success，artifact `14070171619`
  （159497649 B，`sha256:` digest 与 ZIP CRC 均通过）。
- **2026-11-14 装机与冒烟实测**：固定签名包已装到 Pico（KeySets 105→106、signature
  `8685376c`→`1139fa3f`、versionCode `2103171716`→`2103171927`）；`classes13.dex` 实测含
  `showMangaLibraryFromBrowser` / `setOnBackListener` / `manga_reader_progress` / `playVideo` /
  `onSurfaceTextureAvailable`，证明本轮代码确实进包。`VrGrantPermissionsActivity` 的
  `sourceActivityInfo` 为 `.dev`，权限申请链路生效；授权后重启不再弹窗，无 FATAL/ANR。
  **启动默认书架、封面/翻页、视频优先播放、播完切图、Back 返回仍属 VR 视觉项，必须佩戴头显
  从应用列表启动验收，当前一律记为「未验证」**。
- **2026-11-14 头显反馈修复（本轮）**：用户戴头显确认「无默认浏览器页面、但也无书架页面，控制栏与设置里没有漫画内容」。
  根因：`MangaLibraryWidget` / `MangaReaderWidget` 写 `translationZ = -0.2f`，而 Wolvic 的
  `WidgetPlacement.translation*` 是**世界单位（1 米 = 360 单位，1f ≈ 2.8mm）**；窗口 FRONT 实际用
  `WidgetPlacement.getWindowWorldZMeters()` ≈ +1xxx。面板落在眼部近裁剪面内被剔除，不报错也不崩溃。
  已改为与浏览器窗口同尺寸同位置，并在 `onResume()` 追加 `updateWidget()` 纠正 onCreate 排队时序。
  书架升级为**漫画主页单面板**：标题 + 内容容器 + 底部漫画标签栏
  （书架 / 本地 / SMB / 网盘 / 扩展 / 设置 / 浏览器）；标签栏与内容共用同一个 surface，必然可见。
  本地页可真实浏览目录并打开阅读器；SMB / WebDAV / 扩展源仅能保存配置或扫描已安装包，
  **协议层仍未接入**。`VRBrowserActivity` 新增 `mMangaMode` 状态，`onResume()` 强制同步
  tray / navigation bar 可见性，避免浏览器控制栏残留在漫画模式。
- **P1 内核 / 网盘 WebDAV / SMB / 扩展 APK / 下载备份**：协议层未开始；不要声称已复现。

## 已验证事实（别再猜）

- Wolvic 官方支持 Pico Neo3：v1.10 有 `Wolvic-picoxr-arm64` APK + `piconeo3` 手柄资产。
- **本 fork CI 可自建 picoxr**：`.github/workflows/build-picoxr.yml`（workflow_dispatch）→
  复用 `build.yml`（flavour=`assemblePicoxrArm64GeckoGenericDebug`, internal=false）。
  多次 run = success（JDK21/Gradle8.13/submodules，暖缓存约 3–8 分钟）。
- picoxr 唯一私有依赖 = Pico `libopenxr_loader.so`，已从 `Pico-Developer/PICO-Unity-OpenXR-SDK`
  （`Runtime/Android/arm64/libopenxr_loader.so`，1141360 字节）取得并提交到
  `third_party/picoxr/libs/android.arm64-v8a/`。**无需私有 wolvic-third-parties**（OVR SDK 仅 OCULUSVR 用）。
- **加载器 SONAME 坑（已修，commit 5837494）**：Pico 的 `libopenxr_loader.so` 内部 SONAME 实为
  `libpico_openxr_loader.so`，native-lib 按 SONAME 依赖它。Wolvic 原 CMake 把副本打包成
  `libopenxr_loader.so` → 启动崩 `UnsatisfiedLinkError: libpico_openxr_loader.so not found`。
  修复：`app/CMakeLists.txt` PICOXR 分支 copy 目标改为 `${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libpico_openxr_loader.so`。
- **包名共存**：设备已装官方 Wolvic（`com.igalia.wolvic`，Igalia 签名）。漫画版用
  `user.properties: simultaneousDevProduction=true` → debug 包名 `com.igalia.wolvic.dev`，与官方版共存，
  避免 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，不卸载用户数据。
- Wolvic UI = `VRBrowserActivity` + `ui/widgets/`，Android Views 渲染进 3D 窗口
  （`UISurfaceTextureRenderer`/`OffscreenDisplay`）。**源码在 `app/src/common/shared/com/igalia/wolvic`，
  `app/src/main/` 没有 java 目录**（自定义 sourceSets）。
- **Mihon ExtensionLoader 可移植**：纯 Android `PackageManager` + `DelegateLastClassLoaderCompat`
  + `source-api`（Source/SourceFactory），签名信任 + lib 版本 1.4/1.6，**与宿主包名无关**（原扩展 APK 兼容）。
  唯一耦合 = Metro DI（`context.appGraph`），移植时改手动注入。
- **参照仓库复核（2026-11-14）**：`saber1008611/mihon-webdav` 的 `source-api`、`source-local`、
  `core:*`、`data`、`domain` 已依赖 Metro、SQLDelight、UniFile、序列化等；`ExtensionLoader.kt`
  使用 `context.appGraph`，`LocalSource.kt` 是 `@Inject`，`WebDavSource.kt` 也位于 `app`
  并依赖 `@Inject`/CloudSync。代码搜索 `smb`、`smbj` 均为 0，**该分支没有现成 SMB 实现**。
- **架构结论**：保留已验证的 Wolvic/Pico 基座续修；不从头构建 VR，也不把 Mihon 整个 Compose
  工程整体塞入。按「统一 Source/Repository 接口 → 本地格式/归档 → 扩展 APK → WebDAV → SMB →
  下载/备份/设置」做独立小模块 + 垂直切片，每片用 Wolvic 控件重写 UI 并出可装 APK 验收。

## Wolvic 控件接入要点（写漫画 UI 必读）

- 新控件 `extends UIWidget`（`ui/widgets/UIWidget.java`）：ctor 传 `Context`=VRBrowserActivity；
  实现 `initializeWidgetPlacement(WidgetPlacement)`（**width/height 单位 dp**，worldWidth 米，
  anchorX/Y=0.5，translationZ 负值靠前，visible=false 初始）。
- 用 **plain `LayoutInflater.inflate(R.layout.x, this, true)` + `findViewById`**，不用 DataBinding（省事、避免生成码风险）。
- `show(REQUEST_FOCUS)` / `hide(KEEP_WIDGET|REMOVE_WIDGET)` / `setVisible(bool)`。
  **手柄射线点击 = 标准 Android `OnClickListener`**，滚动 = 标准 RecyclerView，无需 VR 专用输入代码。
- **启动钩子**：`VRBrowserActivity.initializeWidgets()`（`mWindows.restoreSessions()` 之后）创建并
  `show` 书架，`setVisible(false)` 隐藏 `mNavigationBar`/`mTray`/`focusedWindow`；
  `showBrowserFromManga()` 恢复浏览器，`showMangaLibraryFromBrowser()` 用专用 Back handler 返回书架。
  `TrayWidget.hide()` 有副作用（dismiss 通知），故用 `setVisible`；`show()` 已隐式 `addWidget`，
  创建书架时不要再显式 `addWidget`，否则重复注册同一 handle。
- 网格用 `ui/views/CustomRecyclerView` + `GridLayoutManager`；参照 `ui/adapters/TopSitesAdapterImpl`。
- **已加文件**：`ui/widgets/MangaLibraryWidget.java`、`ui/widgets/MangaReaderWidget.java`、
  `ui/adapters/MangaCoverAdapter.java` + `res/layout/manga_library.xml`、`manga_cover_item.xml`、`manga_reader.xml`。
  书架扫 `/sdcard/Manga` 子文件夹（每文件夹=一部漫画），封面网格→阅读器（上一页/下一页/返回）。

## 阶段计划（每阶段出可装 APK + 头显验收）

- **P0 地基**：✅ CI 出 picoxr APK + 装机 Pico 验证基线。
- **P1 可验收增量**：本地文件夹阅读闭环（权限、Back、进度、视频优先/自动切图）+ 浏览器返回书架。
- **P2 来源抽象**：独立 `Source`/`Repository`/进度存储，先接文件夹、归档格式（zip/cbz/pdf 等），再逐步取代直读 File。
- **P3 扩展与网盘**：扩展 APK 加载/信任、源管理、WebDAV（Range/206 与封面缓存）、SMB 新实现。
- **P4 完整功能**：下载、备份/恢复、设置、浏览/搜索/分类，仍全部用 Wolvic 控件 UI 重写。

## 本机环境 / 网络坑（实测）

- 无本地 NDK/JDK/Android SDK，**只能 CI 构建**，不重试本地完整构建。
- **C 盘可用空间为 0**：旧工作副本 `C:\Users\Administrator\Documents\Qoder\2026-11-13\96859152\wolvic-manga`
  停在 `babd3d0` 且有重复未提交增量，仅作历史参考。后续统一用 D 盘干净稀疏工作副本
  `D:/APP/wb/gzkj/2026-11-14-02-57-34/wolvic-manga`（远端 `main`）。
- `api.github.com` 直连可用（REST/Git Data）；`github.com` git 需代理 `127.0.0.1:30000`，
  但**代理 CONNECT 隧道对大 pack / 懒加载 blob 易 502**；`gh repo clone` 走 GraphQL 也可能 502。
  可用 `git clone --filter=blob:none --no-checkout --depth 1` + REST contents 逐文件 `git hash-object -w`
  校验补齐，再 `sparse-checkout` / `checkout-index -a -f`。
  **Git Bash 传 `/path` 给 sparse-checkout 前必须 `export MSYS_NO_PATHCONV=1`**，否则会被改写成 PortableGit 安装目录。
- `codeload` tar.gz 大文件直连截断（停 ~1.5MB）；release/assets 与 actions artifact 下载
  **限速 ~63KB/s 且 ~1.5MB 断流** → 用**多段并行 Range 下载**（`artifact/seg_download.py <artifactId> <outputDir>`，
  8 段并行；断点续传复用已完整分段，合并后校验 size / `sha256:` digest / ZIP CRC / APK 结构）。
  GitHub artifact 的签名重定向 URL 几分钟即过期，**每次重试必须重新取 URL**，否则单段会一直卡在
  重试里不产出（实测 part-1 卡 4m+，其余 7 段早已完成）。debug APK 通常只有 v2/v3
  APK Signing Block，别把缺 `META-INF/*.RSA` 当成未签名。
- **提交改动优先走 GitHub Git Data API**（blobs→tree→commit→ref，`ProxyHandler({})` 直连），比 git push 过代理可靠。
- **CI debug 签名已固定（不落盘到仓库）**：仓库 Actions Secret `DEBUG_KEYSTORE_BASE64` 保存仅开发用途的
  PKCS12 debug keystore；`build-picoxr.yml` 用 `secrets: inherit` + `stable_debug_keystore: true`，
  `build.yml` 解码为 runner 的 `~/.android/debug.keystore` 并用 `keytool -list` 校验 alias。
  GitHub 拒绝把含私钥的二进制 keystore 作为 Git blob（实测 400/连接重置），禁止绕过。
  首次从旧 runner 随机签名切换会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。实测可行流程：
  先 `run-as <pkg> tar -cf - .` 导出并**逐文件读取校验**备份 → `adb install -r` 试探 →
  普通 `adb uninstall` 彻底清掉 PackageSetting → `adb install` 新包 →
  `cat /data/local/tmp/x.tar | run-as <pkg> tar -xf -` 恢复（用 shell 侧 cat 绕开 app 对
  `/data/local/tmp` 的读限制），再核对文件数与属主。**`pm uninstall -k` 不够**：Pico 会保留
  PackageSetting 和旧签名（`pm list packages -u` 仍列出），新签名安装照样被拒。
  tar 恢复时 `chown ... Operation not permitted` 是无害警告，文件属主自动为新 UID。
  之后同名同签名可直接 `adb install -r` 保数据升级。正式 release 必须换受保护签名密钥。
- **本地存储权限**：Pico 为 Android 10 / API 29。已补 `requestLegacyExternalStorage=true` 与书架
  运行时 `READ_EXTERNAL_STORAGE` 回调；权限结果由 Wolvic `PermissionDelegate` 分发，授权后自动重扫。
- **VR 面板 placement 坑（实测）**：`WidgetPlacement.translationX/Y/Z` 单位是世界单位而非米。
  根级面板必须复刻 `Windows.placeWindow(FRONT)`：
  `translationY = unitFromMeters(ctx, R.dimen.window_world_y)`、
  `translationZ = WidgetPlacement.getWindowWorldZMeters(ctx)`、
  `worldWidth = floatDimension(ctx, R.dimen.window_world_width) * windowWidth / WINDOW_WIDTH_DEFAULT`，
  `width/height` 用 `SettingsStore.getWindowWidth()/getWindowHeight()`（dp）。
  写成 `-0.2f` 会把面板放进近裁剪面，头显里完全看不见且日志无异常。
  **多个独立 widget 各自定位容易错位**：把控制栏和页面放进同一个 UIWidget 的布局里最可靠。
- VR 截图必须佩戴头显且会话 FOCUSED；`adb shell am start` 可用于**非视觉冒烟测试**（实测能起进程、
  触发权限弹窗、加载 OpenXR/Gecko，但灭屏时是 `APP_CMD_PAUSE` + `Will skip draw vr actvity`），
  可靠视觉验收=头显内点图标。Wolvic UIWidget 绘制在离屏 3D surface，`uiautomator dump` 只能拿到
  一个全屏 `android.view.View`，**看不到书架/阅读器控件，不能当作视觉证据**。
- 触发 CI 用 REST `POST /actions/workflows/build-picoxr.yml/dispatches {"ref":"main"}`（gh workflow run 偶发 502）；
  push 到 main 会触发 `ci-internal.yaml`（无 secret 则跳过、无 artifact），**别把它当成自己的构建**。

## 红线

- 保留 Mihon 全部能力：原格式扩展 APK、自定义漫画源、网盘/WebDAV/SMB/本地、下载/备份/设置。
- UI 用 **Wolvic 控件重写**，不照搬 Mihon 的 Compose UI。启动默认落地**漫画书架**，浏览器降为选项。
- 凭据 `~/.gh_pat` 只注入 `GH_TOKEN`，**任何输出/日志/提交不得出现 token 明文**。
- 声称"跑通"必须有证据：编译看 CI conclusion，运行看设备 logcat/截图；设备相关未实测标"未验证"。
- 不删用户数据；`adb install -r` 保数据升级。签名冲突时只处理 `.dev` 开发包，且必须
  **先完整导出并校验备份 → 卸载 → 安装 → 恢复并核对文件数/属主**；官方 `com.igalia.wolvic`
  与用户数据绝不触碰（实测官方包始终未动，342 个数据文件按新 UID 完整恢复）。