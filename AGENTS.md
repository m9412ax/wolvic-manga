# AGENTS.md — wolvic-manga 接手必读（2026-11-17 精简版）

## 一句话接手（直接复制给下一个 AI）

> 接手 `D:\APP\wb\gzkj\2026-11-14-02-57-34\wolvic-manga`（GitHub `m9412ax/wolvic-manga`，分支 `main`）：先读根目录 AGENTS.md 再动手。以 **Igalia/wolvic** 为 VR 基座、**saber1008611/mihon-webdav** 为漫画功能参照，把 Mihon 全部功能用 Wolvic 的 VR 控件 UI 复现（启动默认漫画书架、浏览器降为选项；保留原格式扩展 APK/自定义源/网盘 WebDAV/SMB/文件夹本地；视频优先播放+无缝切图）。凭据读 `~/.gh_pat` 注入 `GH_TOKEN`（严禁任何输出/提交打印明文），便携 gh `C:/Users/Administrator/Documents/Qoder/2026-11-13/96859152/gh-portable/bin/gh.exe`；构建走 `.github/workflows/build-picoxr.yml`，分段下载校验后 `adb install -r`；adb `D:/YanRainOTAToolBox/resources/platform-tools/adb.exe`，Pico SN `PA7N10MGF5060233W`，VR 验证需佩戴头显从应用列表启动。出结果直接修不用问。

## 项目定位与参照

- 基于 Igalia/wolvic（MPL-2.0）的 Pico Neo3 独立漫画应用。VR/OpenXR/浏览器全用 Wolvic，不自研。
- 漫画功能参照 `saber1008611/mihon-webdav`（本地解压：`D:/APP/wb/gzkj/2026-11-14-02-57-34/mihon-webdav/`）。功能全复现，UI 用 Wolvic 控件，不照搬 Compose。
- Pico 官方 `Pico-Developer` 仓库可参考 SDK 调用；Neo3 硬解 H.264/H.265，视频走系统 MediaPlayer。
- 旧 `m9412ax/mihon-webdav`（自研 OpenXR，失败）已删除，不复用。C 盘 `Documents/Qoder/2026-11-13/96859152/wolvic-manga` 是**过时副本**，一律以 D 盘 checkout + 远端 main 为准。

## 凭据 / 工具

- PAT `~/.gh_pat` → 只注入 `GH_TOKEN`，严禁打印。账号 `m9412ax` 用户已授权复用。
- 便携 gh：`C:/Users/Administrator/Documents/Qoder/2026-11-13/96859152/gh-portable/bin/gh.exe`。
- Python `D:/APP/Python/python`；GitHub API 直连（`ProxyHandler({})`），git push 走 `HTTPS_PROXY=http://127.0.0.1:30000`；artifact 下载直连分段 Range。
- adb 调用前 `export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'`。
- 无本地 NDK/JDK/Android SDK，**只能 CI 构建**。

## 红线

- 凭据不得出现在任何输出/日志/提交。
- 声称"跑通"必须有证据：编译看 CI conclusion，运行看设备 logcat/头显实测；未实测标"未验证"。
- 不删用户数据；`adb install -r` 保数据升级。官方包 `com.igalia.wolvic` 绝不触碰；开发包是 `com.igalia.wolvic.dev`（固定 CI keystore，签名已稳定）。
- 用户已确认正常的功能（见下）不许回归。

## 当前装机版本

- 提交 `056b978`（main），versionCode `2103201816`，APK 219583157 B，CI run `43926922886` success，artifact SHA256/ZIP CRC 校验通过，`install -r` 成功且 firstInstallTime 保留。
- 关键代码：`app/src/common/shared/com/igalia/wolvic/ui/widgets/MangaReaderWidget.java`（阅读器+视频）、`manga/MangaImageDecoder.java`（AVIF）、`ui/adapters/MangaCoverAdapter.java`（封面）。

## 已确认解决（用户头显实测认可，别回归）

1. **视频**（056b978）：首次打开不再报"无法播放"——根因是 Preparing 期间调 getDuration/seek/start 触发 native -38，现全部调用加 `mVideoPrepared` 守卫+播放器代次校验。默认 fit=0（等比适应画布）/zoom=1，打开时不读旧的 fit2/zoom0.5 偏好；`TextureView.setTransform` 用 `setScale(drawW/vw, drawH/vh)+postTranslate(居中偏移)`。控制栏与白色进度条锚定**实际视频矩形**（`mVideoRect`）：栏宽=视频可见宽、贴视频底缘、默认隐藏，指针进入视频底部 32dp 才显示；白进度条 2dp 恒显于视频最底部。
2. **滑动预读**（056b978）：以当前页为中心 ±N（N=设置预读数）的移动窗口；请求不可变快照+任务去重+过期取消，前台/后台独立单线程池，缓存按窗口逐出、按页数分摊字节预算；连续模式滚动即移窗。用户确认连续翻页不再黑屏等待。
3. **AVIF 基本解码**（c153931）：平台解码器不支持 AVIF，走本地 AAR `app/libs/imagedecoder-14.aar`（libvips→libheif→dav1d）。Kotlin 工厂 `new(InputStream)` 是 Java 关键字，反射调用；实际签名 `decode(int page, boolean crop, boolean getTrim)`，无参重载不存在。
4. **设置页五按钮**（c153931）：`VectorClippedEventDelegate` 命中区改为按实际尺寸懒更新（隐藏时不再残留 0×0），布局改 3+2。日志证明点击已达子页创建；**用户未逐项复验，仍标待确认**。
5. 更早确认：大图分块（>72MiB 单 Bitmap 走 tiles）、双页合并、指针锚点缩放、拖动平移、快捷键、缓存串图修复、连续滚动跨页。

## 未解决问题（当前工作）

### #1 AVIF 宽图闪烁（未定位，勿乱改 VR 合成层）

现象：宽屏 AVIF 打开后间歇闪烁，转头/稍等后停止，每次切页重现；JPEG 正常。
已排除/已知：`applyImageTransform` 无头姿输入、无 Surface 重建；转场动画仅 220ms；清透明不是单独提交帧。JPEG 大图走 tiles、AVIF 走单 Bitmap 是路径差异。**尚无根因证据**，不能拿"CPU 压力"当解释。
下一步：对照实验（禁转场/禁高清替换/同尺寸 JPEG 对照），记录 Bitmap config/alpha、替换时机；确认后再动代码。

### #2 高分辨率 AVIF 解码 OOM（修复进行中，未提交）

样本：`E:\合集\如月灰 NO.023 LUNA YD [31P-303MB]`，31 张多为 5464×8192 / 8192×5464，ffprobe 确认 8bit SDR yuv444p。
证据（PID 8940 logcat）：`ImageDecoder$OutOfMemoryException: Out of memory allocating the pixel buffer (179044352 bytes)`，Java heap growth limit 256MiB。同尺寸 JPEG 正常是因为 `inSampleSize` 在解码期就缩小；AVIF 包装层**先整图 ByteBuffer+整图 Bitmap 再缩小**，预算生效太晚。库本身限制：输入 512MiB、像素≤2^28、面积≤16384²，不是格式不支持。
修复方案（工作区已有未提交改动）：
- 新增 `app/src/main/cpp/MangaImageDecoder.cpp` 薄 JNI（`libmanga-decoder.so`），dlopen 现有 `libimagedecoder2.so` 的导出 C 接口（vips_image_new_from_source/thumbnail_image/colourspace 等，已逐一验证导出存在），**SDR 在 native 层缩放到目标尺寸后才生成小 Bitmap**；bounds 只读头信息。HDR 保留原 F16 路径。
- Java：`AVIF_LOCK` 串行化所有 AVIF 解码（前台/预读/封面不并发全图 native 解码）；`restorePageDetail` 对 AVIF 比较采样档位，档位相同不再重复解码替换。
- 注意：普通单图 AVIF 无法真正低内存分块（AAR 未导出 tile 接口），native 解码峰值仍在，只是输出侧不再翻倍。别盲开 unlimited。
状态：**未编译、未装机、未验证**。完成后走 CI→分段校验→install -r→头显实测这批样本。

### #3 设置五按钮逐项复验（见上，低优先）

## 构建 / 部署要点（踩过的坑，别再踩）

- CI：`build-picoxr.yml`（workflow_dispatch，REST POST 触发，gh workflow run 偶发 502）→ 复用 build.yml，flavour `assemblePicoxrArm64GeckoGenericDebug`，`stable_debug_keystore: true`。push main 触发的 `ci-internal.yaml` 无 artifact，别认错。
- artifact 下载限速易断流：多段并行 Range（参考 `D:/APP/wb/gzkj/2026-11-14-02-57-34/artifact/seg_download.py`），校验 HTTP206/Content-Range/总 SHA256/ZIP CRC 后才安装。`build_manga.sh` 里 RUN_ID 是旧的，别直接跑。
- sparse-checkout：`read-tree -mu` 会把 LF 写成 CRLF，改完检查 `b'\r\n'`；`.gitignore` 忽略 `*.aar`，本地 AAR 需 `git add -f`；非 cone 模式路径别带前导 `/`。
- AAR 依赖坑（已解决，升级时注意）：imagedecoder 各版本 minCompileSdk=37 需删 AAR 元数据行；Kotlin 须 2.4.0（metadata 兼容），CI 有 D8 Kotlin metadata WARNING 但构建成功。
- Pico 加载器 SONAME 是 `libpico_openxr_loader.so`，CMake 复制目标名必须一致（已修）。
- `WidgetPlacement.translation*` 是世界单位（1 米=360），不是米；窗口 Z 用 `getWindowWorldZMeters()`。
- 诊断 intent（`--es manga_open_folder`）会改变启动时序，**视频验证必须从书架正常点进**。
- `uiautomator dump` 看不到 Wolvic GLES 自绘控件，不能当点击证据；用 logcat。

## 后续待办（按优先级）

1. 完成并验证 AVIF 两项修复（上文 #1/#2）。
2. 设置五按钮逐项复验。
3. 扩展源真实读取闭环：扩展安装器已有，Source ABI 加载/搜索/章节/页面管线不完整（参照 mihon `source-api/`）。
4. 真 SMB2/3（现仅探测/配置/本地挂载）；WebDAV 浏览/下载已真，"下一章"与免下载阅读不完整。
5. 下载队列持久化/断点续传；备份目前明文导出全部设置（可能含凭据），需加密或脱敏。
6. 图片管线优化：native 采样落地后评估视口分块/取消；mihon `ImageUtil.kt`/waifu2x 磁盘缓存可挑选移植。