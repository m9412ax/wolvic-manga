# AGENTS.md — wolvic-manga 接手必读

> **⚠️ 先读文末「## 当前未解决问题 / 交接（2026-11-17）」再动手。**
> 该章节是最新事实来源（当前装机版本、三个未解决问题、视频完整需求、
> 构建/依赖踩坑、参考来源、待办）。本文前半部分是历史进度，如与文末冲突，
> **一律以文末为准**。

## 一句话接手（直接复制给下一个 AI）

> 接手 `D:\APP\wb\gzkj\2026-11-14-02-57-34\wolvic-manga`（GitHub `m9412ax/wolvic-manga`，分支 `main`）：先读根目录 AGENTS.md 再动手。以 **Igalia/wolvic** 为 VR 基座、**saber1008611/mihon-webdav** 为漫画功能参照，把 Mihon 全部功能用 Wolvic 的 VR 控件 UI 复现（启动默认漫画书架、浏览器降为选项；保留原格式扩展 APK/自定义源/网盘 WebDAV/SMB/文件夹本地；加视频优先播放+无缝切图）。GitHub 凭据读 `~/.gh_pat` 注入 `GH_TOKEN`（严禁任何输出/提交打印明文），用便携 gh `C:/Users/Administrator/Documents/Qoder/2026-11-05/3de213cf/gh-cli/bin/gh.exe`；构建走 `.github/workflows/build-picoxr.yml`（CI），分段下载校验后 `adb install -r`；adb 在 `D:/YanRainOTAToolBox/resources/platform-tools/adb.exe`，Pico SN `PA7N10MGF5060233W`，VR 验证需佩戴头显从应用列表启动。出结果直接修不用问。

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
- **2026-11-14 最终包实测（非视觉）**：commit `d6498baf2eb2f658145dc89edfe3ee6e5e67358c`，
  CI run `43500127787` / artifact `14072519727`（161408946 B，`sha256:` digest 与 ZIP CRC 通过）。
  `apksigner verify --print-certs` 的证书 SHA-256 `3a247d16…26bd1` 与 Secret 完全一致，
  固定签名**真正生效**；设备签名 KeySets 107 / `d8128228` / version 3。
  上一轮的 `1139fa3f` 其实仍是 runner 随机签名，不是 Secret 签名，勿再当作固定签名参考。
  logcat 实测 `MangaHome: placement width=802 height=452 worldWidth=4.0 y=126.0 z=-1515.0`
  （与窗口同位置，旧值 `-0.2`）、`scanLibrary items=1`、`switchPage LIBRARY`、
  `show page=LIBRARY visible=true`、`onResume`，无 FATAL/ANR。
  同 APK 再次 `adb install -r` = Success，数据 406 文件 / 7 shared_prefs、权限 `granted=true` 全保留，
  官方包未动。**面板是否真的可见、7 个标签切换、各页面与阅读器仍需戴头显视觉验收**。
- **2026-11-14 第二次头显反馈（方向纠偏，本轮待 CI 验收）**：用户明确要求**在 Wolvic 原生控件体系内
  融合**，不是另起一套 UI：①页面切换要做进**原本浏览器下方托盘**（替换/切换托盘按钮行），
  不许自建底部标签栏；②浏览器与漫画必须双向可逆，设置菜单里要有**分组的漫画完整设置**；
  ③阅读漫画要弹**独立悬浮窗口**，窗口下方只保留原生托盘（内容换成阅读控制），且要有漫画软件
  真实的阅读优化/设置，不接受空壳。
  本轮据此重写：`tray.xml` 增加 `tray_buttons`/`manga_buttons`/`reader_buttons` 三行按钮（全部用原生
  `UIButton` + `trayButton*Theme` + tooltip），`TrayWidget.setMangaMode()` 切换 BROWSER/MANGA/READER；
  `VRBrowserActivity` 三态 `UI_LIBRARY/UI_READER/UI_BROWSER`，托盘与窗口**常驻不隐藏**，
  `applyUiMode()` 统一同步，Back handler 只在浏览器态压栈，可随时切回漫画；
  `settings.xml` 增加独立「漫画 / MANGA」分组（阅读/图源/下载/备份/高级 5 个 HoneycombButton），
  `SettingsView.SettingViewType` + `SettingsWidget.showView()` 路由到新 `MangaOptionsView`，
  复用原生 `SwitchSetting`/`RadioGroupSetting`/`SingleEditSetting`/`ButtonSetting`；
  新增 `manga/MangaSettings`（单一配置源，全部真实持久化）、`MangaWebDav`（PROPFIND+GET，可用）、
  `MangaDownloader`（并发/重试/仅 WiFi）、`MangaBackup`（JSON 导入导出）；
  `MangaReaderWidget` 重写为独立悬浮窗口，消费全部设置（阅读方向/缩放/亮度/双击缩放/点击区域/
  音量键/自动翻页/预加载/裁白边/翻页动画/进度/视频策略），控制项全在原生托盘。
  **仍未接入**：SMB 协议栈（仅 445 探测 + 挂载点打开）、Mihon 扩展 APK 加载层（仅扫描/安装）、
  归档格式 zip/cbz/pdf、浏览搜索分类。不要声称「Mihon 全部功能已复现」。
- **P1 内核 / SMB 协议 / 扩展加载 / 归档格式 / 浏览搜索分类**：未开始；WebDAV 下载、备份、
  阅读优化与设置持久化已落地。
- **「别做空壳」校验**：`MangaSettings` 的 50 个 key 全部有真实消费点。`READER_BACKGROUND`
  （`backgroundColor()`）、`SOURCE_IMAGE/VIDEO_FORMATS`（`imageExtensions()`/`videoExtensions()`）、
  `CACHE_MB`（阅读器 `LruCache` 容量）通过封装方法生效；`SOURCE_THUMB_PX` 接
  `MangaCoverAdapter.mTargetPx`；`ADV_LOGGING` 控制 `MangaReaderWidget.diagnostic()`；
  `DOWNLOAD_QUEUE_PREFS` 由 `MangaDownloader` 持久化并在设置页回显。新增设置项时务必同时写入消费点。
- **Z 层级（勿误改）**：窗口 ≈ -1515、书架 = 窗口 +40、阅读器 = 窗口 +220，数值越大越靠近用户。
  阅读器浮最前、书架遮住浏览器窗口、托盘常驻，所以 `applyUiMode()` 里书架用
  `setVisible(mMangaUiMode != UI_BROWSER)`（阅读器模式保留书架作背景）是正确的。
- **Java 命名坑**：不要在类里定义名为 `notify(...)` 的私有方法；匿名 `Runnable` 内调用会被
  `Object.notify()`（final 无参）遮蔽，javac 报 `method notify in class Object cannot be applied`。
  本仓库已统一使用 `postStatus(...)`。

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
  `show` 书架，再 `applyUiMode()`。**不要再隐藏 `mTray` 或 `focusedWindow`**：原生 tray 是窗口的子控件，
  隐藏窗口会连托盘一起消失，且导致浏览器/漫画无法双向切换。托盘改为三态按钮行
  （`TrayWidget.setMangaMode(MODE_BROWSER|MODE_MANGA|MODE_READER)`），导航条在漫画态隐藏。
  `mMangaBrowserBackHandler` 只在 `UI_BROWSER` 压栈，其余态弹出，保证随时可回漫画。
  `TrayWidget.hide()` 有副作用（dismiss 通知），显隐一律用 `setVisible`；`show()` 已隐式 `addWidget`，
  创建书架时不要再显式 `addWidget`，否则重复注册同一 handle。
- **页面切换入口**：`VRBrowserActivity.onMangaTrayAction(String)` 统一处理托盘 action；
  阅读器由 `openMangaReader(File)` 弹出独立悬浮窗（`UI_READER`），`closeMangaReader()` 回书架。
  不要在任何漫画 widget 内再自建底部标签栏/控制栏。
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
- **C 盘可用空间为 0**：旧工作副本 `D:\APP\wb\gzkj\2026-11-14-02-57-34\wolvic-manga`
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
  **实测坑（2026-11-14）**：两次 CI 的 Secret 证书指纹完全相同（`3A:24:7D:16…:D1`），
  但产物签名不同、`adb install -r` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` —— AGP 把
  PKCS12 当 legacy JKS 读取失败后自行重新生成了随机 debug keystore，
  仅写 `~/.android/debug.keystore` 并不生效。现已在 Gradle 构建后增加
  `Force stable debug signing` step，用 `apksigner sign --ks` 对产出 APK 强制重签名并
  `verify --print-certs`，产物签名才真正固定。
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

---

## 当前未解决问题 / 交接（2026-11-17）

> 这是最新事实来源。前面的「当前进度（2026-11-14）」是历史，其中「视频优先播放」
> 等乐观描述已被后续头显实测推翻，一律以本节为准。用户明确要求：**先别再改代码**，
> 下一位 AI 读完本节再动手，且任何修复必须真机验证后再汇报，不许"没验证就说修好了"。

### 当前装机版本 / 提交

- 最新提交 `7fdf721`（本地 + 远端 main 一致），versionCode `2103201635`，
  APK 219MB（含 libvips 原生库），已装机 Pico `PA7N10MGF5060233W`。
- 近期提交链（视频回归与 AVIF 集成）：
  `7fdf721`(反射调 imagedecoder) ← `b8b23b2`(Kotlin 2.4) ← `089132f`(去 AAR 元数据)
  ← `a342a9e`(本地 AAR) ← `18eb951`(AVIF/视频矩阵回退/设置跳转)
  ← `f71505e`(指针锚点/视频矩阵/格式) ← `e45de0b`(双页大图/平移/设置诊断)
  ← `75e7c4f`(回退视频) ← `352dd00`(伪造 SurfaceTexture，坏) … ← `71e6714`(视频还能播)

### 用户已确认正常（别回归）

| 功能 | 状态 |
|---|---|
| 大图打开不闪退 | ✅ 124.9MB 页面走 1:1 分块（无损）；`SINGLE_BITMAP_LIMIT=72MB` + `safeForImageView()` 兜底 |
| 双页模式（含大图） | ✅ `tiled = !continuous && !mDoublePage && needsTiling(page)`，双页强制走 `decodeDisplay()` 合并 |
| 图片拖动看边缘 | ✅ `mPanX/mPanY` + onScroll 平移，clamp 到溢出范围 |
| 指针位置缩放 | ✅ Activity `handleMotionEvent` → `onPointerPosition()`；`zoomByAt()` 锚点公式：`newPan = pan + (pointer-centre-pan)*(1-newZoom/oldZoom)` |
| 快捷键 | ✅ 裸上下不缩放图片；按住侧键：左右=缩放画布、上下=缩放图片；连续模式上下滚动 |
| 缓存串图 / 切页闪旧图 / 预读黑屏 | ✅ 缓存 key 含 folder；setFolder 清缓存；showPage 清 tiles+strip；独立预读线程、preload=2、cache=256MB |
| 连续长滚动上跳 / 卡顿 | ✅ 跨页前确认邻居已解码、每帧最多跨一页、步长≤30%视口 |

### 未解决问题 #1 — 视频（最复杂，用户最不满）

**现状（7fdf721）**：有声音，但画面**不居中、拉伸变形**；**控制栏看不到**。
用户最初只要求"比例/居中"修好（当时能播有画面有声），几轮下来反而更糟。

#### 视频完整需求书（写给下一位 AI）
1. 打开视频页自动播放（`READER_VIDEO_AUTOPLAY` 默认 true）。
2. **等比适配、居中显示、不拉伸变形**，画面完整不超出画布、不被裁切。
3. 4 档 fit（`mVideoFitMode`：0 适应屏幕 / 1 适应高度 / 2 适应宽度 / 3 原始不放大）
   + 倍率 `mVideoZoom`，切换有提示。
4. 画面随"画布缩放"（单/双页 macro zoom）变化——用户确认这点当前是对的，别破坏。
5. **控制栏必须可见**：播放/暂停、进度拖动、fit 切换、视频下方白色进度条。
   当前 `showVideoBar(false)` 默认隐藏，指针移到画面下缘才显示——用户抱怨看不到，
   需重新确认可见性策略（可能改为默认/始终显示）。
6. 摇杆左右快进/后退（VIDEO_SEEK_STEP_MS）；点画面播放/暂停。
7. 出错不自动跳下一页（会跳到大图触发 Canvas 崩溃），只记 what/extra + 提示。
8. 解码完全交给系统 MediaPlayer 硬解（Pico `OMX.qcom.video.decoder.avc`），不引软解。

#### 回归链（读懂再改，别重蹈覆辙）
| 提交 | 视频状态 | 改动 |
|---|---|---|
| 71e6714 及更早 | ✅ 能播有画面有声，比例拉伸 | 朴素 `TextureView match_parent` + `setDataSource(path)` + 回调里播 |
| 55d29a8 | ✅ 能播 + 控制条 | `applyVideoTransform()` 用 `setTransform(matrix)`：`setScale(scale,scale)` + `postTranslate((vw-bw*scale)/2,(vh-bh*scale)/2)` —— **最后确认能显示画面的矩阵写法** |
| 6e95976 | ⚠️ 比例好但埋雷 | TextureView LayoutParams 改成按视频比例的固定像素，动摇 surface 时序 |
| b9633bb | ❌ | 容器从 `mPageImage` 改 `mRootView` + root `addOnLayoutChangeListener`，反复 `setLayoutParams` → SurfaceTexture 反复重建 |
| 8da6dab | ❌ | `setDataSource(path)` → FileDescriptor（路径本来就 work） |
| 352dd00 | ❌ 黑屏有声 | `new SurfaceTexture(0)` + `setSurfaceTexture()` 强造纹理：解码器有输出但 TextureView 不渲染 |
| 75e7c4f | 回退 | 删 force-create/retry/FD/root listener；比例改 setTransform（归一化坐标算法） |
| f71505e | ❌ 完全看不到 | 矩阵改"像素空间" `setScale(drawW,drawH)` + 像素 translate → 画面消失 |
| 当前 7fdf721 | ⚠️ 有声、画面变形不居中 | 矩阵**回退 55d29a8 写法**，但用户说仍不居中且拉伸；控制栏看不到 |

#### 关键代码位置（MangaReaderWidget.java）
- `showPage()` 视频分支（~563）：`mVideoTexture.setVisibility(VISIBLE)` → `showVideoBar(false)` → `playVideo(page)`
- `applyVideoTransform()`（~2476）：当前 `setScale(scale,scale)` + `postTranslate((vw-bw*scale)/2,(vh-bh*scale)/2)`
- `playVideo()`（~2501）：`setDataSource(path)` → `setSurface()` → `prepareAsync()`；onError 记 what/extra 不跳页
- `onSurfaceTextureAvailable()`（~2629）：建 Surface 后若 isVideoPage 则 playVideo
- `showVideoBar/setupVideoBar/setupVideoProgressTrack/layoutVideoProgressTrack/updateVideoProgressTrackFill`

#### 建议下一步
- 先抓真机日志（**别用诊断 intent，时序不同**）：在 `applyVideoTransform` 打
  `vw/vh/bw/bh/scale/mVideoFitMode/mVideoZoom` + `mVideoTexture.getLayoutParams()`，
  tag `MangaReader`。用户从书架正常点进视频。
- 强烈怀疑 `mVideoTexture.getWidth()/getHeight()` 是 16×16 或陈旧值（设备日志见过
  `SurfaceTexture connect: w*h = 16*16`），导致 scale 错；或历史 LayoutParams 残留。
- 要真正搞清 `TextureView.setTransform` 坐标语义（矩阵把归一化 0..1 纹理坐标映射到
  View 像素）。等比居中的正确写法可能是 `setScale(drawW,drawH)` + 像素 translate，
  但上一版这么改画面消失，必须查清早退原因（`mVideoTexture` 尺寸为 0 时 return）。
- 若继续小改仍失败，**主动向用户提议换 VideoView/ExoPlayer 大改**（用户已明确不满反复小修）。

### 未解决问题 #2 — AVIF 仍"无法解码"

**现状**：目录能显示 `.avif`（扩展名已加 `SOURCE_IMAGE_FORMATS`），打开提示解码失败。
设备 Pico Neo3，平台解码器不支持 AVIF（用户判断正确）。

**当前实现**：`manga/MangaImageDecoder.java` — 非 avif 走原 BitmapFactory；avif 反射调
`ca.mpreg.imagedecoder.ImageDecoder` 的 `new(InputStream)` 工厂（Kotlin 的 `new` 是 Java
关键字，只能反射），`decode()` 返回 `DecodeResult{ByteBuffer image,width,height}` →
`copyPixelsFromBuffer` → 按 sample 降采样。reader 与 `MangaCoverAdapter.decodeScaled()` 都接入。
依赖：`app/libs/imagedecoder-14.aar`（31MB，libvips，`lib/arm64-v8a/libimagedecoder2.so`
已确认入包）。Kotlin 已升 2.4.0。

**集成踩坑（全踩过，别重复）**：
1. `ca.mpreg:imagedecoder` 远程依赖声明 `minCompileSdk=37`，本工程 36 → `checkAarMetadata` 失败；
   所有版本 v3~v18 都是 37，无旧版可用。
2. 下载 AAR 到本地 `files()` 仍被查 → 从 `META-INF/com/android/build/gradle/aar-metadata.properties`
   删掉 `minCompileSdk=37` 行重新打包才通过。
3. 库是 Kotlin 2.4 metadata，本工程 2.2.21 报 `binary version 2.4.0, expected 2.2.0` → `kotlin = "2.4.0"`。
4. `new` 是 Java 关键字 → 反射 `getMethod("new", InputStream.class)`。

**建议下一步**：抓 `adb logcat -s MangaImageDecoder`（代码已有 `Log.w("avif decode failed: "+t)`），
看具体异常（`NoSuchMethodException: new` / .so 加载失败 / ByteBuffer 非 RGBA）。
对照 mihon 的 `app/.../data/coil/ImageDecoder.kt`（本地已下载，见下）；
确认 `new` 是 `@JvmStatic` 还是 Companion 实例方法（可 `getDeclaredMethods()` 打印）。
也要看 `needsTiling()`/`decodePage()` 是否因 AVIF `decodeBounds` 返回 -1 走错分支。

### 未解决问题 #3 — 设置页 5 个漫画按钮点不动

**现状**：SettingsWidget 漫画 tab 顶部 5 个 HoneycombButton（阅读/图源/下载/备份/高级）
点击无反应。用户说"之前几个版本能打开"。

**结构**：有两套五按钮 —— ①`settings.xml` 的 `manga_row` 里 5 个 HoneycombButton
（`mangaReaderButton`/`mangaSourcesButton`/`mangaDownloadsButton`/`mangaBackupButton`/
`mangaAdvancedButton`，在 `SettingsWidget.updateUI()` 绑定）；②`MangaSettingsPanel.buildMangaSections()`
里的文字按钮。当前两套都走 `Activity.showMangaSettingsSubPage(key)` → 新增
`TrayWidget.showMangaSettingsView(type)`（已可见则直接 `mSettingsWidget.showView(type)`，
不再重跑 `show()/updateUI()`）。两处都加 `Log.i("MangaSettingsPanel", ...)`，
漫画子页创建包了 try-catch + Toast。

**关键障碍**：用户点击后 `logcat | grep MangaSettingsPanel` **无任何输出** → 点击可能没到按钮。
Wolvic GLES 自绘，`uiautomator dump` 看不到这些控件，不能当证据。

**建议下一步（别再盲改）**：请用户点一次后立即
`adb logcat -d -v time | grep -i "MangaSettingsPanel\|MangaOptions"`：
- 有 `honeycomb click` → 点击到达，问题在 showView 之后（看 `creating MangaOptionsView` / 异常）
- 无日志 → 命中/布局问题：查 `HoneycombButton` 的 `VectorClippedEventDelegate` 六边形裁剪
  命中区、`mangaTabContent` visibility、是否被 `mangaPanelHost` 覆盖。
  曾把 `manga_row` 改 `weightSum=5` + 按钮 `layout_width=0dp`，意识到会压扁六边形命中区，**已回退，别再这样改**。
- 历史能点版本 `6e95976` 之前：`MangaSettingsPanel.open()` 是 `if(mOpener!=null) mOpener.open(key)`，
  opener 在 `SettingsWidget.setupSettingsTabs()` 设置。

### 本轮新增踩坑（构建 / 依赖 / sparse-checkout）

- **sparse-checkout 行尾坑**：`git sparse-checkout add` + `read-tree -mu` 会把 LF 写成 CRLF，
  `git diff` 冒几千行假变更。改完 `.java/.gradle/.toml` 检查 `b'\r\n' in raw`，有就转回 LF。
- `.gitignore` 忽略 `*.aar`，本地 AAR 要 `git add --sparse -f app/libs/imagedecoder-14.aar`。
- 非 cone 模式 `sparse-checkout add` 传前导 `/` 会写入错误绝对路径，导致 checkout 不出文件；
  直接编辑 `.git/info/sparse-checkout` 用相对路径再 `read-tree -mu HEAD`。
- **Kotlin 升级到 2.4.0 后**，CI 日志出现多条
  `D8: Unexpected error during rewriting of Kotlin metadata for class 'mozilla...'` —— 目前
  只是 WARNING，构建成功；但要留意它是潜在隐患（某些 mozilla/androidx Kotlin 类 metadata 兼容）。
- CI 常 queued 很久：卡住就重新 dispatch，换新 run id 写入 `build_manga.sh`；旧轮询超 10 分钟就停。

### 参考来源（用户指定 + 已查证）

**mihon-webdav**（`saber1008611/mihon-webdav`）：
- git clone 被代理 502；用 GitHub API zipball 直连下载（`urllib` + `ProxyHandler({})`）：
  `https://api.github.com/repos/saber1008611/mihon-webdav/zipball/main`
- 本地已解压：`D:/APP/wb/gzkj/2026-11-14-02-57-34/mihon-webdav/saber1008611-mihon-webdav-60d83b9/`
- 关键文件：
  - `app/src/main/java/eu/kanade/tachiyomi/data/coil/ImageDecoder.kt`（libvips 解 AVIF/JXL/HEIC）
  - `gradle/libs.versions.toml`（`ca.mpreg:imagedecoder` v14、Coil 3.6.2）
  - `app/.../data/coil/Utils.kt`（解码选项）
  - `app/.../source/webdav/`、`source-local/`、`source-api/`（**用户说后续网盘/自定义扩展源从这拿**）
  - 图片优化：`core/common/.../util/system/ImageUtil.kt`、`app/.../util/waifu2x/ImageEnhancer.kt`
    + `ImageEnhancementCache`（磁盘缓存）—— 用户明确要求"图片优化部分也看，能改善就加"，**尚未系统研究**
- Pico 官方：用户给的 `https://github.com/Pico-Developer`（SDK 示例，Native OpenXR 有 VideoPlayer demo）。
  设备视频编码格式文档 `https://developer-cn.picoxr.com/document/discover/xr-devices`：
  Neo3/Pico4 硬解 H.264/H.265，**Pico 4 Ultra 才多支持 VP9/AV1** → Neo3 视频硬解没问题，
  MediaPlayer 默认走 `OMX.qcom.video.decoder.avc`，应用层不指定解码器。

### 后续待办（按优先级）

1. 视频：抓日志 → 修等比居中 + 控制栏可见；继续失败就提议 VideoView/ExoPlayer 大改。
2. AVIF：抓 `MangaImageDecoder` 日志 → 对照 mihon Kotlin 实现修正反射/生命周期。
3. 设置五按钮：抓 `MangaSettingsPanel` 点击日志 → 按日志分支定位，别再盲改。
4. 图片优化：研究 mihon `ImageUtil.kt`/waifu2x/磁盘缓存，挑安全的移植。
5. 网盘/自定义扩展源：以 mihon `source/webdav/`、`source-local/`、`source-api/` 为参考。
6. 收尾：设备上测试视频 `/storage/emulated/0/Manga/video_repro.mp4` 与 `VideoTest/` 是否删除（用户未定）。

### 诊断命令备忘

```
# 构建/装机脚本（RUN_ID 每次改）
D:/APP/wb/gzkj/2026-11-14-02-57-34/build_manga.sh
# 诊断 intent —— 注意：会改变启动时序，不能用于视频验证
adb shell am start -n com.igalia.wolvic.dev/.VRBrowserActivity --es manga_open_folder <目录>
# 视频页必须从书架正常点进去，抓日志：
adb logcat -d -v time | grep -E "MangaReader.*(video|surface)|OMX"
```

