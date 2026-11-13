# AGENTS.md — wolvic-manga 接手必读

## 项目定位

基于 **Igalia/wolvic**（VR 浏览器，MPL-2.0）的 **Pico Neo3 独立漫画应用**。把
**Mihon（saber1008611/mihon-webdav）** 的全部漫画功能移植进 Wolvic 的 VR 控件 UI。
旧 `m9412ax/mihon-webdav`（自研 OpenXR 路线，完全失败）已删除，**不复用其 VR 代码**；
Mihon 功能以上游 saber1008611 为参照。Wolvic 原生层已解决 Pico OpenXR/世界锁/虚拟屏等坑。

## 红线

- 保留 Mihon 全部能力：原格式扩展 APK、自定义漫画源、网盘/WebDAV/SMB/本地、下载/备份/设置。
- UI 用 **Wolvic 控件重写**，不照搬 Mihon 的 Compose UI。
- 启动默认落地**漫画书架**，原浏览器功能降为单独选项。
- 凭据 `~/.gh_pat` 只注入 `GH_TOKEN`，**任何输出/日志/提交不得出现 token 明文**。
- 声称"跑通"必须有证据：编译看 CI conclusion，运行看设备 logcat/截图；设备相关未实测标"未验证"。
- 不删用户数据；`adb install -r` 保数据升级。

## 已验证事实（别再猜）

- Wolvic 官方支持 Pico Neo3：v1.10 有 `Wolvic-picoxr-arm64` APK + `piconeo3` 手柄资产。
- **本 fork CI 可自建 picoxr**：`.github/workflows/build-picoxr.yml`（workflow_dispatch）→
  复用 `build.yml`（flavour=`assemblePicoxrArm64GeckoGenericDebug`, internal=false）。
  run **43460955809 = success**（约 8 分钟，JDK21/Gradle8.13/submodules）。
- picoxr 唯一私有依赖 = Pico `libopenxr_loader.so`。已从 **Pico-Developer/PICO-Unity-OpenXR-SDK**
  （`Runtime/Android/arm64/libopenxr_loader.so`，1141360 字节，arm64 Android ELF）取得并提交到
  `third_party/picoxr/libs/android.arm64-v8a/`。**无需私有 wolvic-third-parties**（OVR SDK 仅 OCULUSVR 用）。
- **加载器 SONAME 坑（已修，commit 5837494）**：Pico 的 `libopenxr_loader.so` 内部 SONAME 实为
  `libpico_openxr_loader.so`，native-lib 按 SONAME 依赖它。Wolvic 原 CMake 把副本打包成
  `libopenxr_loader.so` → 启动崩 `UnsatisfiedLinkError: libpico_openxr_loader.so not found`
  （`VRBrowserActivity.<clinit>` loadLibrary("native-lib")）。修复：`app/CMakeLists.txt` PICOXR 分支
  copy 目标改为 `${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libpico_openxr_loader.so`。
- **包名共存**：设备已装官方 Wolvic（`com.igalia.wolvic`，Igalia 签名）。漫画版用
  `user.properties: simultaneousDevProduction=true` → debug 包名 `com.igalia.wolvic.dev`，与官方版共存，
  避免 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，不卸载用户数据。
- Wolvic UI = `VRBrowserActivity` + `ui/widgets/`（Widget/WindowWidget/RootWidget/
  WidgetManagerDelegate/WidgetPlacement），Android Views 渲染进 3D 窗口
  （`UISurfaceTextureRenderer`/`OffscreenDisplay`）。**源码在 `app/src/common/shared/com/igalia/wolvic`，
  `app/src/main/` 没有 java 目录**（自定义 sourceSets）。
- **Mihon ExtensionLoader 可移植**：纯 Android `PackageManager` + `DelegateLastClassLoaderCompat`
  + `source-api`（Source/SourceFactory），签名信任 + lib 版本 1.4/1.6，**与宿主包名无关**（原扩展 APK 兼容）。
  唯一耦合 = Metro DI（`context.appGraph`），移植时改手动注入。

## 待验证

- 本 fork debug APK 在 Pico 实跑（P0 装机验证进行中）。
- 移植后扩展加载/在线源/网盘/SMB/本地/阅读在 VR 内实跑。

## 阶段计划（每阶段出可装 APK + 头显验收）

- **P0 地基**：CI 出 picoxr APK + 装机 Pico 验证基线。✅ CI 已成功。
- **P1 内核移植**：source-api / core(common,archive,metadata) / data / domain / 扩展加载 /
  WebDAV/SMB（source-local + unifile）作为 Gradle 模块进 Wolvic 构建；解耦 Metro DI 与 Compose。
- **P2 漫画 UI**：Wolvic 控件做书架 + 浏览/搜索 + 源管理 + 扩展仓库/安装 + 设置；默认落地书架。
- **P3 阅读**：VR 图片阅读器（分页/条漫/连续）+ 文件夹式本地 + SMB。
- **P4 视频**：目录内视频优先排前播放，播完无缝切图继续读，保留进度。

## 本机环境 / 网络坑（实测）

- 无本地 NDK/JDK/Android SDK，**只能 CI 构建**，不重试本地完整构建。
- Python `D:/APP/Python/python`（3.14，有 numpy）；控制台 GBK，输出中文前 `PYTHONIOENCODING=utf-8`。
- adb `D:/YanRainOTAToolBox/resources/platform-tools/adb.exe`；Pico SN `PA7N10MGF5060233W`。
  adb 前设 `MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'`（Git Bash 改写 POSIX 路径）。
- gh 便携版 `C:/Users/Administrator/Documents/Qoder/2026-11-05/3de213cf/gh-cli/bin/gh.exe`。
- `api.github.com` 直连可用（REST/Git Data）；`github.com` git 需代理 `127.0.0.1:30000`，
  但**代理 CONNECT 隧道对大 pack 易 502**。
- 完整 `git clone` 易 EOF/502；**`--filter=blob:none --no-checkout` 浅克隆可成功**，再
  `sparse-checkout set` + `checkout-index -a -f` 取源码；单 blob `git cat-file -p HEAD:<path>` 懒加载可用。
- `codeload` tar.gz 大文件直连截断（停在 ~1.5MB）；release/assets 与 actions artifact 下载
  **限速 ~63KB/s 且 ~1.5MB 断流** → 用**多段并行 Range 下载**（`apk/seg_download.py`，8 段并行 ~300–450KB/s）。
- **提交改动走 GitHub Git Data API**（blobs→tree→commit→ref），比 git push 过代理可靠。
- VR 截图必须佩戴头显且会话 FOCUSED；`adb shell am start` 常被 Pico HandDialog 拦截，可靠启动=头显内点图标。