# 16 KB、终端启动与 SQLite 解包复核

验证日期：2026-10-19。以下记录覆盖本地与设备验证；最新远端 CI 以 [PR #27 checks](https://github.com/soul99soul-glitch/AmberAgent/pull/27/checks) 为准。

## 最终链接与 APK

[失败的 CI](https://github.com/soul99soul-glitch/AmberAgent/actions/runs/39203401293)确实报告 `libhighlight_parser.so` 和 `liboffice_parsers.so` 的四个 LOAD 段均为 4096；原先的修复不能据此认定完成。本机原有产物及独立 target 目录重建均为 16384，未在本地复现 CI 的配置差异。

现在八个 Gradle 任务都使用 `cargo ndk rustc --release --locked --lib --verbose ... -- -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384`。参数直接进入最终库的 rustc 链接步骤，不依赖环境是否覆盖 target rustflags。任务只复制自己负责的库，避免 `cargo-ndk -o` 携带 workspace 中其他旧库。

本地 `assembleFullDebug`、`assembleDeepreadDebug` 和两种 flavor 的 androidTest APK 均成功。最终两份 arm64 APK 各包含 19 个 ELF，所有 LOAD 段均至少 16384；两个解析库分别为 `[16384, 16384, 16384, 16384]`，loader 为 `[16384, 16384]`。

保留了每个 ELF 的 LOAD 检查，并增加文件偏移与虚拟地址的 16 KB 同余检查。负向验证：临时将 APK 内 highlight 库的四个 LOAD 对齐改为 4096，运行真实 `verifyFullDebugNativePageSize`（跳过重新打包）明确失败；恢复原 APK 后 full/deepread 两个验证任务均通过。

## 终端

预编译 proot 的 `get_loader_path` 对非空 `PROOT_LOADER` 直接采用指定路径。旧版在 Android 15 应用进程内启动测试实际失败：第一次 guest `execve` 返回 `Permission denied`。

修复后 loader 由 Android 安装器提取到 `nativeLibraryDir`，不再复制到 filesDir。使用压缩 JNI 包装，使安装器提取库；这是 [Android 官方支持的 16 KB 打包方式](https://developer.android.com/guide/practices/page-sizes#update-packaging)，代价是增加安装后的磁盘占用。只有固定路径的 proot loader 允许 ET_EXEC，它仍参与全部 LOAD 检查；其余 JNI 必须是 ET_DYN。

`EmbeddedTerminalStartupDeviceTest` 验证安装器提取标志、真实 loader 的可执行性，然后从应用进程执行真实 Alpine 批处理与交互 shell，检查 `/bin/uname -m` 返回 aarch64 和 `/workspace` 写回。

- Android 15 / API 35 / arm64 模拟器，fingerprint `Android/sdk_phone64_arm64/emu64a:15/AE3A.240806.019/12368160:userdebug/test-keys`，页大小 4096：通过。
- Android 17 / API 37 / 型号 25128PNA1C，页大小 4096：后续在用户授权下成功安装独立包 `app.amber.deepread.graphite` 及测试 APK，运行 `EmbeddedTerminalStartupDeviceTest` 通过（`OK (1 test)`，0.826 秒）。验证真实批处理、交互 shell、工作区写回及安装器提取 loader；测试临时目录已清理，原主应用未覆盖。
- 16 KB 页设备：后续已完成官方 ARM64 镜像的严格运行验证，详见末节。

首次实机验证的 base APK 与本地产物 SHA-256 均为 `ce4275f3f8c83a85ada6529082052ca18752fe76f79ffcc266e7cc2624f450f7`。从手机实际安装目录回读两个解析库和 loader，其 LOAD 对齐仍分别为 `[16384, 16384, 16384, 16384]`、`[16384, 16384, 16384, 16384]` 和 `[16384, 16384]`。

## SQLite 解包

删除“APK 未找到 entry 也可能提交已有 `.part`”的路径；仅在找到当前 APK/split entry 后复制到本次唯一临时文件。复制完成后校验长度和 CRC、同步文件，再以同目录 rename 原子替换缓存。缓存命中也对照当前 entry 校验，不再依赖可与坏文件一起持久化的 stamp。复制/校验失败保留旧缓存，临时文件在 finally 清理，同进程调用串行化。

`NativeLibraryOnDiskTest` 7 项与 `TerminalAdmissionTest` 5 项全部通过。新增回归覆盖：base 留残片后从 ABI split 恢复、所有 APK 缺库时不返回残片、相同长度且带旧 stamp 的坏缓存修复、CRC 失败保留旧库并可重试。

## PR 范围内的升级保留与 RTL 修复

原先删除整个 guest 的方法改为只清理应用提供的 `local/bin/proot` 和 `local/lib/libtalloc.so.2`。版本变化、版本文件缺失和主动修复均保留 `local/alpine`，其中包含用户 `/root`、自行安装的软件及配置。

`AlpineRuntimeUpgradeTest` 的四项回归在旧实现上全部失败，修复后全部通过，覆盖版本升级、缺失版本标记、主动修复、失败后保留旧版本并重试。加上 SQLite 和准入测试，共 16 项 JVM 测试通过；已加入 PR 的构建 CI。

扩充后的真机终端测试先启动并填充真实 Alpine，再模拟旧运行环境版本，检查 `/root` 用户文件、自定义可执行文件和真实 apk 软件包数据库逐字节保留，验证升级后命令仍可执行；随后验证主动修复和版本标记丢失后的恢复。全部在独立临时目录内执行，不触碰主应用的用户 rootfs。

升级保留与 RTL 修复阶段的 deepread APK 已安装到上述 Android 17 真机，SHA-256 为 `6b9f5ba913826494b173d8786f6b0da3bc0335266509b771fbaa79fe65178774`。该阶段扩充后的测试通过（`OK (1 test)`，1.538 秒）；终端资产源码重建后的复测见末节。

RTL 拖拽继续使用逻辑方向的锚点，将逻辑 offset 转换为物理 translationX，保证卡片跟随手指且操作方向正确。同时修复回归测试发现的单次置顶回调重复执行（旧实现一次触发三次）：只在松手 settling 阶段执行一次，下一次手势独立计数。保留原有弹簧、淡出与收起时长。

`HomeSessionRowSwipeDeviceTest` 在 Android 15 / API 35 模拟器四项通过（`OK (4 tests)`，6.01 秒）：LTR/RTL 下各自的置顶和删除，检查实际移动方向、超过阈值但仍按住时无操作、松手回调只触发一次，以及连续两次置顶手势。旧 APK 的两个 RTL 用例明确检测到反向移动，LTR 置顶用例检测到三次回调。Android 17 真机系统拦截 Compose 测试 Activity 的启动，因此本次 RTL UI 结果来自模拟器，真机终端测试单独执行。

最终 `assembleFullDebug`、`assembleDeepreadDebug` 及各自测试 APK 构建通过，两个 APK 的 NativePageSize 验证保持通过。

## 16 KB 严格运行与远端 CI

使用官方 SDK `system-images;android-35;google_apis_ps16k;arm64-v8a` revision 5，独立 AVD `amber_native_16kb_api35`。实际内核 `getconf PAGE_SIZE` 和应用内 `Os.sysconf(_SC_PAGESIZE)` 均为 16384；fingerprint 为 `google/sdk_gphone16k_arm64/emu64a16k:15/AE3A.240806.043/12960925:userdebug/dev-keys`。

严格运行前设置并回读确认：`bionic.linker.16kb.app_compat.enabled=false`、`pm.16kb.app_compat.disabled=true`。

首次严格运行证实原 Karbon proot/talloc 资产仍为 4096 对齐，终端报 `CANNOT LINK EXECUTABLE`。两个解析库实际原生调用当时已通过。现改为从固定源码构建 proot、talloc 和匹配 loader，构建参数、源码 SHA-256、资源 SHA-256 和许可证见 [TERMINAL_RUNTIME.md](../native/TERMINAL_RUNTIME.md)。最终 APK 检查覆盖 19 个 JNI ELF 和 2 个终端资产 ELF，不再因资源放在 assets 中而漏检。

对新增资产检查也做了负向验证：临时将 APK 内 proot 的 LOAD 对齐改为 4096，真实 Gradle NativePageSize 任务明确失败，随后恢复原 APK 并核对 SHA-256 未变化。

源码重建后的 full APK SHA-256：`2d968e7cfc66898f601a9294ec0c734cd4e8e6d5342911da5fb149319baca7ff`。

在该 16 KB 内核、关闭兼容模式的应用进程内，8 项测试全部通过（`OK (8 tests)`，8.771 秒）：

- `Native16KbPageSizeDeviceTest`：八个 Rust JNI 库直接加载；Java 语法高亮、XLSX、Markdown 解析；使用生产 Requery 扩展配置的隔离文件数据库完成 simple FTS 建表、插入与查询。
- `NativePathDeviceBenchmarkTest`：Markdown、正则、同步哈希、reader-extractor 原生调用与结果断言。
- `EmbeddedTerminalStartupDeviceTest`：真实 Alpine 批处理、交互 shell、工作区写回、版本升级/修复/标记缺失后的用户文件和软件包数据库保留。
- `HomeSessionRowSwipeDeviceTest`：四项 LTR/RTL 手势测试。

新版运行环境也已安装到 Android 17 / API 37 / 4096 页真机，终端扩充测试再次通过（`OK (1 test)`，0.686 秒）。设备测试使用独立包和临时目录，主应用数据保持不变。

远端提交 `f8f54df5403ea25db83b7999bd7585541cec685b` 的 [CI 已通过](https://github.com/soul99soul-glitch/AmberAgent/actions/runs/39210957201)，包含最终 Rust 链接/打包校验、16 项 JVM 回归测试和测试 APK 编译。源码重建终端资源的后续提交沿用同一 CI，并继续通过 PR checks 跟踪，避免把较早提交的成功当作最新提交的结果。

通过的 16 KB 运行日志保存在 [native16kb-api35.txt](test-evidence/native16kb-api35.txt)。测试 runner 使用隔离的 Application；安装替换广播尚未结束时可能与该隔离 fixture 竞争并导致测试进程在用例前退出，日志中的成功结果来自安装广播结束后的完整执行。
