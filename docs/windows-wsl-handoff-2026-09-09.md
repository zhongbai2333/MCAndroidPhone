# Windows / WSL2 交接 · 2026-09-09

2026-09-13 接续：已构建并验证 AMD64 Go；最新 minimal 三盘镜像归档 **497,765,444 字节**，按用户批准的后备方案取消预装 WebView/Jelly。详见 [精简镜像结果与兼容性边界](android-go-compact.md)。这是镜像包，完整离线 JAR 尚未达到该体积；下文原始 Mac 交接目标与“未构建 Go”等描述为历史状态。

Windows 已接续并完成首轮原生 Java / D-Bus / D3D11 及 WSL 回归，发现的问题、修复和后续 Go 构建条件见 [Windows 接续验收](windows-validation-2026-09-09.md)。下文保留 Mac 交接时的状态。 Android Go 的真实源码同步和一次性构建流程已启动，见 [Go 构建推进](go-build-progress-2026-09-09.md)。

接续分支：`codex/windows-wsl-handoff-20260909`，仓库为 [zhongbai2333/MCAndroidPhone](https://github.com/zhongbai2333/MCAndroidPhone)。本文件是当前接续入口；其他按日期记录的文档中“未提交/未推送”、旧体积和临时快照描述属于当时状态。此分支上传源码和少量测试证据，不发布新的正式版本。

## 目标与现状

用户希望离线内置 Android，正常安装普通 APP、游玩部分小游戏，完整平台 JAR 尽量小于 **500 MiB（524,288,000 字节）**。优先继续 LineageOS 23.2 Go 源码产品；保留 WebView、安装器、设置、输入法、中英文字体、ART、核心 APEX 和 HAL。不要通过首次下载镜像或删除核心兼容能力来凑体积。

- 普通用户启动链路已迁至 Java 25，复用 Minecraft 的 Java。Python 只保留在部分开发/旧诊断工具和 Android 构建依赖中，不是玩家运行要求。
- 当前 Mac M4 已有真实 Android 16 ARM64、游戏画面/输入、持久数据、共享系统底盘、游戏相机应用和环境 API 的分阶段证据。标准 Camera2、完整定制 HAL/SELinux 镜像、ARM 转译、音频、多指、六平台原生实测均未完成。
- Windows 上旧版本曾稳定运行，但这批 Java D-Bus/D3D11、Job/guardian 生命周期和新增功能必须重新测试，不能沿用旧结果。
- 最新普通 Android Mac ARM64 内置候选为 **761,655,879 字节 / 726.37 MiB**，SHA-256 `e9d391edcad735ba8449bcde03dab70ee8469b8586f92a188e50136595cef573`。Mac 本地路径 `.runtime/packages/mcandroidphone-macos-arm64-trim-xz-v5-test.jar`。它没有上传，也不是 Windows 包。
- **没有编译完成的 Go 镜像；不能声称已经达到 500 MiB。** 这次准备了 Go 配置、源码补丁、构建前契约检查及可复用 APP 验收。

## 获取与保护已有 Windows 工作

推荐新目录检出，保留原 Windows 项目的配置、运行时和手机数据：

```powershell
git clone --branch codex/windows-wsl-handoff-20260909 --single-branch https://github.com/zhongbai2333/MCAndroidPhone.git MCAndroidPhone-handoff
cd MCAndroidPhone-handoff
git status --short
git log -1 --oneline
java -version
.\test-phone.cmd check
.\test-phone.cmd quick
.\gradlew.bat build runtimeSelfTest
.\test-phone.cmd pattern --world-smoke
```

先配置 Windows 原生 JDK 25 的 `JAVA_HOME`。不要对旧项目执行 `reset --hard`、`clean` 或覆盖运行配置；需要整合旧修改时先检查差异并备份。首次 Gradle 构建需要网络，依赖下载失败与源码编译失败要分别记录。

原生 Windows 运行测试在 PowerShell/CMD 中做。QEMU、FFmpeg、固件/ANGLE、镜像需使用该平台的匹配文件；不要把 Mac 的 dylib、HVF 参数、绝对路径或初始化数据盘直接当 Windows 配置。先读取 [运行时配置](runtime.md)、[GPU 说明](gpu.md)、[测试入口](quick-test.md)，保留旧可用环境另建测试手机。正常游戏从 Java 管理器拉起；旧外部 Python bridge 仅作比较诊断。

## WSL2 构建 Android

优先使用 **x86_64 Windows + WSL2 Ubuntu**，可交叉编译 ARM64 和 AMD64 两个来宾镜像。宿主架构若为 ARM64，应先核对 Android 构建工具链，不能假定 x86_64 Linux 预编译工具可直接使用。

开始前记录以下输出，并核对宿主 SSD 实际剩余空间（WSL 虚拟盘上限不等于宿主空闲空间）：

```powershell
wsl --status
wsl -l -v
Get-CimInstance Win32_ComputerSystem | Select-Object TotalPhysicalMemory
Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors
Get-Volume | Select-Object DriveLetter,SizeRemaining,Size
wsl -d Ubuntu -- bash -lc 'uname -m; free -h; df -h ~; java -version; make --version'
```

发行版名称不是 `Ubuntu` 时使用 `wsl -l -v` 列出的名称。尚未安装 WSL2 时先完成安装并按系统提示重启。微软文档：[安装](https://learn.microsoft.com/en-us/windows/wsl/install)、[资源配置](https://learn.microsoft.com/en-us/windows/wsl/wsl-config)、[文件系统](https://learn.microsoft.com/en-us/windows/wsl/filesystems)。

[AOSP 当前要求](https://source.android.com/docs/setup/start/requirements)为至少 64 GB 内存、400 GB 空闲磁盘；双架构与缓存建议预留 600 GB 以上 SSD。WSL 默认内存上限为宿主的一半，按实际硬件调整 `.wslconfig` 并给 Windows 留余量；不要直接填一个超过物理资源的模板。资源不足时先降低并行度并评估，不承诺完整构建能成功。

源码放在 WSL Linux 文件系统，例如 `~/src/MCAndroidPhone`、`~/android/lineage`，避免 `/mnt/c/...` 跨文件系统编译。Windows 原生 Minecraft 测试和 WSL Android 编译可以使用两个独立 checkout，不共享 Gradle 输出目录。

### 准备源码，再执行项目构建入口

1. 在 WSL 中检出上述同一交接分支。为项目检查安装 Linux JDK 25、GNU Make；Android 自身使用源码树指定的工具链。同步/编译 Android 所需的 Python、repo、Git LFS、C/C++ 等属于开发依赖。
2. 按 [上游构建项目](https://github.com/jqssun/android-lineage-qemu) 准备 LineageOS **23.2** 全部依赖。已有 fork 为 [zhongbai2333/android-lineage-qemu](https://github.com/zhongbai2333/android-lineage-qemu)，本轮特化源在 **MCAndroidPhone 的 `android/image/`**，不要误以为 fork 已包含所有特化。
3. 上次检查上游 main 为 `54fc5dc82fa05778be15c1200240be53f707a542`；上游构建脚本只编译普通产品，且包含全局 Git 配置和强制同步。应审阅后按独立源码目录准备，不能原样覆盖已有开发树。无完整本地锁定 manifest，首次同步后必须保存 `repo manifest -r`；分支头可能漂移。
4. `repo init` 的已核验分支参数为 `-u https://github.com/LineageOS/android.git -b lineage-23.2 --git-lfs --no-clone-bundle --depth 1`。同步前先装齐依赖；对新树执行正常 `repo sync -c`，并按资源限制任务数。准备两个 VirtIO 设备树及依赖（必要时借助上游 roomservice/breakfast 获取，记录实际分支），包括 `device/virt/virtio_arm64only`、`device/virt/virtio_x86_64`、`device/virt/virtio-common`、内核和 `hardware/interfaces`。
5. **当前 Prepare 同时检查两个 Go 设备树**，即使先编译一个架构，也必须先准备两个设备树。缺锚点时检查来源/分支，不跳过检查器或静默删减补丁。旧 overlay 不一致时先审查修改，避免覆盖。

准备好后，在 WSL 的 MCAndroidPhone 根目录运行（路径换成本机实际目录）：

```bash
java android/image/tests/PrepareSelfTest.java
bash -n android/image/build-go.sh
java android/image/Prepare.java "$HOME/android/lineage" "$PWD" --check
set -o pipefail
LINEAGE_ROOT="$HOME/android/lineage" bash android/image/build-go.sh amd64 2>&1 | tee go-amd64-build.log
# 第一架构完成并检查结果后，再构建另一架构；不要在同一源码树并行切目标。
LINEAGE_ROOT="$HOME/android/lineage" bash android/image/build-go.sh arm64 2>&1 | tee go-arm64-build.log
```

脚本选择 `virtio_x86_64_go` / `virtio_arm64only_go` 的 `user` 产品，构建目标为 `vm-utm-zip`。默认构建并行度由 Android 环境决定，应在调用前按机器资源核对；脚本没有自动安装依赖、同步源码或上传产物。它会在源码树 `out/mcandroidphone/` 保存解析后的产品变量、manifest 和本项目输入哈希。产物实际位置从编译日志和 `out/target/product/` 核对，不猜测 ZIP 文件名。

Go 优化包含：根产品直接 include 修复、中英资源语言、普通 APK `verify` 预编译、保留 SystemUI/Go Launcher 热点编译，以及按 Go 目标移除 Backgrounds、BasicDreams、EasterEgg、PrintRecommendationService、vim。`VerifyGoConfig.java` 要求真实解析配置保留 WebView 等核心模块，并防止语言/品牌/低内存设置失效。[实现说明](go-optimization.md)

## 编译后的验收顺序

1. 先在匹配架构环境启动 **新 Go 系统 + 对应新数据盘**，不能复用普通镜像旧 userdata 来证明 Go 正常。检查引导、SELinux、桌面、安装器、网络、WebView、输入法、中英文显示和实际 low-RAM 属性。
2. 使用 [锁定 APK 与测试命令](../android/image/tests/README.md) 重跑 `AppCompatibilityTest ... go`。Mac 普通镜像基线：计算器 `12+34=46`；最终自动化 2048 得分 144、步数 24、包含 32 方块，后台返回后棋盘一致。游戏截图仍需人工审阅，不能将测试进程存活算作可玩。
3. 编译验证 `mcphone-environmentd`、Sensors/GNSS HAL 与 SELinux 接入，再核验普通 APK 读取、暂停/重连/换维度。游戏相机 APP 不是标准 Camera2；另补 WebView/轻量 3D/音视频和所需原生 ABI 的测试。
4. **关机仍是发布阻碍之一**：现成镜像干净用户盘默认 QMP 关机实测为 `forced-timeout`。匹配长按策略的测试种子配合 `shutdownMethod=power-key` 可得到 `guest-confirmed`；Go 资源 overlay 必须编译后确认生效。检查重启写入标记、手机 A/B 隔离、共享底盘哈希及自有子进程回收。[关机契约](guest-shutdown.md)
5. 用对应平台的 QEMU/FFmpeg/固件/库和许可打包，比较完整 JAR **字节数**、解压内存、首启及缓存复用。`StageMacRuntime.java` 仅准备 Mac 原生文件，不能用于 Windows。`PackageRuntime.java` 是通用打包器；AMD64 不可套用 ARM64 BCJ 滤镜。[压缩和打包](runtime-size-budget.md)

两种 Android 镜像不等于六个成品包：每个平台还需要各自的原生依赖与实机验证。Windows ARM64 的具体加速/图形能力仍需另测。

## GitHub Actions 与随交接提供的内容

`.github/workflows/verify.yml` 已含三平台 Java 核心回归、Go 配置检查、旧诊断回归和模组构建。**它不编译 Android 系统**。标准托管 runner 资源不足以承载既定完整源码编译；后续可把准备好的 WSL2 注册为 Linux self-hosted runner，再加手动触发的镜像构建流程。注册需要在 Windows 机器上完成，本次没有创建 runner、购买大型机器或启动 Android 云编译。

已提交：所有本轮 Java/Android/脚本配置源码、上游配置夹具与 APK 版本/哈希、相关文档，以及 [轻量应用基线证据](handoff-evidence/2026-09-09/README.md)。上传后的源码 commit 与 Actions 实际状态应通过 GitHub 核对；Mac 本地缓存构建不等于 Windows/远端 CI 通过。

未提交：`.runtime/`、`run/`、`build/`、Android/QEMU/FFmpeg 二进制、APK、完整离线 JAR、ADB 密钥、用户盘、个人配置和完整原始日志。历史文档中的 `.runtime/evidence/...` 路径在 Mac 本地，**git clone 不会带来这些文件**。需要精确复验旧 Mac 大包时保留这台 Mac 上原文件，通过单独文件传输获取，不能用模组小 JAR 替代它。

给 Windows 端助手的接续指令：**先阅读本文件，核对分支和脏改动，检查 Windows/WSL2 的 CPU、内存、磁盘及 JDK；先完成 Java 原生 Windows 回归，再准备独立 WSL2 LineageOS 23.2 源码树，优先构建 AMD64 Go 并实际验收，随后 ARM64。保留普通镜像比较基线、关机缺口和原生平台差异，不把配置检查通过说成 Go 镜像完成。**
