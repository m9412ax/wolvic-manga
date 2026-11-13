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
- **便携 gh**：`C:/Users/Administrator/Documents/Qoder/2026-11-05/3de213cf/gh-cli/bin/gh.exe`（不在 PATH）。
  用法：`GH_TOKEN=$(cat ~/.gh_pat) "<gh路径>" <命令>`。
- **Python**：`D:/APP/Python/python`（3.14，有 numpy）。调 GitHub API 用 `urllib` + `ProxyHandler({})`
  强制直连（环境代理隧道易 502）；输出中文前 `sys.stdout.reconfigure(encoding='utf-8')`。
- **adb**：`D:/YanRainOTAToolBox/resources/platform-tools/adb.exe`；调用前
  `export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'`（Git Bash 会改写 POSIX 路径）。

## 当前进度（2026-11-14）

- **P0 地基**：✅ 已验证。CI 自建 picoxr APK → 装机 Pico → 用户戴头显确认看到浏览器界面，
  logcat 见 `PxrLoader LoadRuntime succeeded OpenXR 1.1` + GeckoView 启动。
- **P2 第一增量**：🔄 漫画书架（启动默认落地）+ 文件夹式本地图片阅读器 + 「浏览器」按钮。
  已提交 commit `00db847`，构建 run `43482193239`。**待装机验证**。
- **P1 内核移植 / P3 SMB / P4 视频**：未开始。

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

## Wolvic 控件接入要点（写漫画 UI 必读）

- 新控件 `extends UIWidget`（`ui/widgets/UIWidget.java`）：ctor 传 `Context`=VRBrowserActivity；
  实现 `initializeWidgetPlacement(WidgetPlacement)`（**width/height 单位 dp**，worldWidth 米，
  anchorX/Y=0.5，translationZ 负值靠前，visible=false 初始）。
- 用 **plain `LayoutInflater.inflate(R.layout.x, this, true)` + `findViewById`**，不用 DataBinding（省事、避免生成码风险）。
- `show(REQUEST_FOCUS)` / `hide(KEEP_WIDGET|REMOVE_WIDGET)` / `setVisible(bool)`。
  **手柄射线点击 = 标准 Android `OnClickListener`**，滚动 = 标准 RecyclerView，无需 VR 专用输入代码。
- **启动钩子**：`VRBrowserActivity.initializeWidgets()`（`mWindows.restoreSessions()` 之后）创建并
  `show` 书架，`setVisible(false)` 隐藏 `mNavigationBar`/`mTray`/`focusedWindow`；
  `showBrowserFromManga()` 恢复浏览器。`TrayWidget.hide()` 有副作用（dismiss 通知），故用 `setVisible`。
- 网格用 `ui/views/CustomRecyclerView` + `GridLayoutManager`；参照 `ui/adapters/TopSitesAdapterImpl`。
- **已加文件**：`ui/widgets/MangaLibraryWidget.java`、`ui/widgets/MangaReaderWidget.java`、
  `ui/adapters/MangaCoverAdapter.java` + `res/layout/manga_library.xml`、`manga_cover_item.xml`、`manga_reader.xml`。
  书架扫 `/sdcard/Manga` 子文件夹（每文件夹=一部漫画），封面网格→阅读器（上一页/下一页/返回）。

## 阶段计划（每阶段出可装 APK + 头显验收）

- **P0 地基**：✅ CI 出 picoxr APK + 装机 Pico 验证基线。
- **P1 内核移植**：source-api / core(common,archive,metadata) / data / domain / 扩展加载 /
  WebDAV/SMB（source-local + unifile）作为 Gradle 模块进 Wolvic 构建；解耦 Metro DI 与 Compose。
- **P2 漫画 UI**：Wolvic 控件书架（🔄 第一增量已提交）+ 浏览/搜索 + 源管理 + 扩展仓库/安装 + 设置；默认落地书架。
- **P3 阅读**：VR 图片阅读器（🔄 基础已有）+ 文件夹式本地 + SMB。
- **P4 视频**：目录内视频优先排前播放，播完无缝切图继续读，保留进度。

## 本机环境 / 网络坑（实测）

- 无本地 NDK/JDK/Android SDK，**只能 CI 构建**，不重试本地完整构建。
- `api.github.com` 直连可用（REST/Git Data）；`github.com` git 需代理 `127.0.0.1:30000`，
  但**代理 CONNECT 隧道对大 pack 易 502**。
- 完整 `git clone` 易 EOF/502；**`--filter=blob:none --no-checkout` 浅克隆可成功**，再
  `sparse-checkout set` + `checkout-index -a -f` 取源码；单 blob `git cat-file -p HEAD:<path>` 懒加载可用。
  本地稀疏克隆在 `wolvic-manga/`，`res/` 未检出，读已有资源用 `git cat-file`。
- `codeload` tar.gz 大文件直连截断（停 ~1.5MB）；release/assets 与 actions artifact 下载
  **限速 ~63KB/s 且 ~1.5MB 断流** → 用**多段并行 Range 下载**（`apk/seg_download.py <artifactId> <size>`，8 段并行 ~300–450KB/s）。
- **提交改动走 GitHub Git Data API**（blobs→tree→commit→ref，`ProxyHandler({})` 直连 + 重试），比 git push 过代理可靠。
- **CI debug 签名每次不同**：GitHub runner 每次生成新 `debug.keystore` → 连续构建签名不一致，
  `adb install -r` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。开发期 `.dev` 包无用户数据，可
  `adb uninstall com.igalia.wolvic.dev` 再装；**正式需提交固定 keystore 统一签名**。
- **本地存储权限**：读 `/sdcard/Manga` 在 Android 10（Pico）需 `requestLegacyExternalStorage=true`
  或 `MANAGE_EXTERNAL_STORAGE`；当前空状态兜底，**待补**。
- VR 截图必须佩戴头显且会话 FOCUSED；`adb shell am start` 常被 Pico HandDialog 拦截，可靠启动=头显内点图标。
- 触发 CI 用 REST `POST /actions/workflows/build-picoxr.yml/dispatches {"ref":"main"}`（gh workflow run 偶发 502）；
  push 到 main 会触发 `ci-internal.yaml`（无 secret 则跳过、无 artifact），**别把它当成自己的构建**。

## 红线

- 保留 Mihon 全部能力：原格式扩展 APK、自定义漫画源、网盘/WebDAV/SMB/本地、下载/备份/设置。
- UI 用 **Wolvic 控件重写**，不照搬 Mihon 的 Compose UI。启动默认落地**漫画书架**，浏览器降为选项。
- 凭据 `~/.gh_pat` 只注入 `GH_TOKEN`，**任何输出/日志/提交不得出现 token 明文**。
- 声称"跑通"必须有证据：编译看 CI conclusion，运行看设备 logcat/截图；设备相关未实测标"未验证"。
- 不删用户数据；`adb install -r` 保数据升级（签名冲突时只卸载无数据的 `.dev` 开发包，不动官方/用户数据）。