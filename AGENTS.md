# AGENTS.md — wolvic-manga 接手必读（2026-11-17 稳定存档版）

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

1. **扩展 APK / 自定义漫画源兼容（最高优先，用户最担心）**：用户设备已装多个扩展 APK，在其他 Mihon 系应用正常。必须参照 mihon-webdav 的 `source-api/` 与扩展加载器实现完整协议兼容（列表/搜索/详情/章节/页面/imageUrl）。扩展首次读取的授权弹窗改为**默认同意**。现状：扩展安装器已有，Source ABI 加载/搜索/章节/页面管线不完整。
2. **SMB2/3 真读取**（现仅探测/配置/本地挂载）。
3. **115 网盘读取 + 阿里云盘（主要用于数据同步）**。
4. WebDAV 浏览/下载已真，"下一章"与免下载阅读不完整。
5. 下载队列持久化/断点续传；备份明文导出可能含凭据，需加密或脱敏。

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

- `app/src/common/shared/com/igalia/wolvic/ui/widgets/MangaReaderWidget.java`：阅读器+视频+连续条带（~3000 行）。
- `manga/MangaImageDecoder.java`：AVIF 解码+磁盘缓存+前台优先。
- `manga/MangaSettings.java`：全部偏好（PREFS=manga_settings）。
- `ui/widgets/MangaStripView.java`：连续模式 5 段条带绘制。
- `app/src/main/cpp/MangaImageDecoder.cpp` + `app/CMakeLists.txt`：native 缩放 JNI。