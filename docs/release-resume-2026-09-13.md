# 双架构下载版暂停点 · 2026-09-13

历史暂停记录：用户已于 2026-09-13 11:13 明确要求“继续”，ARM64 增量构建已恢复；以下暂停状态用于解释旧日志，不能再据此阻止已授权的续跑。

恢复使用相同 24 GiB / 12 路配置。新 wrapper 保存为 `.runtime/release-20260913/pipeline-resume.sh`，仅构建 ARM64，旧日志另存，并修正 TERM/INT 退出状态。原生运行时与下载代码提交 `eb5ef45` 已推送。实时进度窗口过滤准备阶段的小计数，并只读取最后一次恢复后的退出标记。

## 已确认的方向

- Mod 仓库已按用户要求公开，继续使用现有 MIT，NCPB 同为 MIT。
- 一个通用 JAR 内置 Windows AMD64 与 macOS ARM64 的 QEMU/原生依赖；按宿主选择其中一套。
- Android 两架构完整 Go 镜像保留 WebView，放在同仓库 Release，首次使用按来宾架构下载、校验、解压与缓存。停止追逐 500 MB。
- Release 尚未创建/上传；新下载版仍待真实镜像、JAR 启动及跨平台 CI 验收。Mac 新 Go 镜像的实际 HVF/画面须在 M4 验证。

## 已完成与暂停操作

- 公开远端已有提交 `7a71458`；本暂停点的后续代码保存为本地提交，暂不推送触发 CI。
- 通用 Mod 当前版本 `0.2.0-dev.20260913`，完整 build/runtimeSelfTest 通过，日志 `.runtime/evidence/release-final-build-20260913.log`。
- 首次下载的真实 HTTP 回归通过：续传、忽略 Range、错误 Range/哈希、取消、并发、缓存、不向其他主机传凭据、架构/路径保护及保留用户数据。
- 单平台打包兼容性与 XZ 实际 codec 校验回归通过；双平台合并测试通过，要求两包核心代码一致。
- AMD64 full 构建 02:42 成功、三盘 qemu-img check 通过。系统模板 2,163,605,504 字节，SHA-256 `f868f53f91bdf221765aa1baa5676f4c4a8029dce38827abae7abf2e0d081186`。
- ARM64 02:42 开始首次构建，02:50 按用户要求终止。已完成的 Ninja 输出保留；停止引起的 `action cancelled when ninja exited` 不算已确认的源码错误。
- 原 systemd unit `mcphone-full-release-20260913` 已 inactive/dead，cgroup 为空；48 GiB 临时 swap 文件已移除。原 wrapper 的 EXIT trap 在 TERM 后误记 `PIPELINE_EXIT=0`，**不能据此认定 ARM64 成功**。
- AMD64 XZ 压缩进程也已停止，`.xz.partial` 保留，尚无完成的本轮 full 镜像归档。XZ 压缩需重新开始；Android 增量编译可继续。
- 进度窗口已关闭，没有自动续跑任务。

## 本机路径

Windows 项目 `D:/UserFile/Documents/GitHub/MCAndroidPhone`。

WSL Ubuntu-24.04 / zhongbai233，VHDX 在 `E:/WSL/Ubuntu-24.04/ext4.vhdx`：

- Android：`/home/zhongbai233/android/lineage-23.2-mcphone`
- 固定 Android 构建脚本副本：`/home/zhongbai233/src/MCAndroidPhone-release-20260913`，来源提交 `0aa4a76500b70775d1ad0e1b10b8027c95c4b14a`。
- 日志及完整 AMD64 原盘：该 Linux 项目 `.runtime/evidence/full-release-20260913/`，AMD64 子目录 `amd64/` 含三盘、manifest、产品变量与源输入哈希。
- Windows 原盘副本：`.runtime/images/lineage-go-amd64-full-20260913/`。
- 原两架构监督脚本已另保存到 `.runtime/release-20260913/pipeline-original.sh`，**不要直接从头重跑**：AMD64 已保存，脚本会在重复创建证据目录时失败。
- 原生 staging：`.runtime/release-20260913/windows-amd64-stage`、`macos-arm64-stage`，同级 `*-xz` 均已压缩完成。Windows staging 691,198,566 字节 / 301 文件；还需补齐 QEMU 与依赖 DLL 许可文本及来源说明后发布，目前仅见 ANGLE、FFmpeg、EDK2 许可。
- Mac staging 来自 GitHub run `34710841569`，macos-15 ARM64，QEMU 设备探针、重定位与签名验证成功。下载包 `.runtime/downloads/macos-arm64-native-20260913/macos-arm64-native-stage.tar.gz` SHA-256 `da851032252d441590d654a512851d745aa7cbb75c7bd3c883c84593f294adea`。

## 用户恢复后继续

1. 核对磁盘、已有 out 和暂停日志。仅继续 `build-go.sh arm64 full`，沿用 WSL 24 GiB、12 jobs、GOMAXPROCS=6、高内存任务 1、MemoryHigh 20G / Max 22G / SwapMax 48G。临时 swap 的创建和回收沿用监督逻辑，但应显式处理 TERM/INT 为非零退出。不要删除 out、重新同步所有仓库或重编已完成 AMD64。
2. 将旧 `arm64-build.log` 另存再续跑；ARM64 成功后按原监督脚本保存全新三盘与源清单。AMD64 的半成品 XZ 另存或确认后删除，仅重新压缩，不重编 Android。
3. `PackageAndroidImages.java` 用 7 参数：IMAGES ARCH SYSTEM_XZ HTTPS_URL NEW_ZIP NEW_DESCRIPTOR BASE_MOD_JAR，实际私有 codec 验证 XZ 与原系统盘一致。当前 tag 尚未建立，建议统一 `v0.2.0-dev.20260913`。
4. `PackageRuntime ... --xz-dir ... --image-manifest ...` 生成两平台无 Android 载荷的 JAR；`MergeRuntimePackages.java NEW_UNIVERSAL.jar A.jar B.jar` 合成一个。固定下载 URL、大小和 SHA 放入架构清单。公开 GitHub 可匿名下载；可选开发 token 仅发给 api.github.com，不写配置/日志。
5. 验证实际成品首次下载、解码、启动、缓存复用；BootBenchmark 支持 `benchmarkPrepareSeconds`，用于首次下载的准备期，启动帧仍有单独 200 秒上限。保留旧手机和默认 v5 配置。
6. 新 `InstallReleaseRuntime.java PLATFORM_JAR CACHE_GAME NEW_PROPERTIES` 可把发布包安装为源码开发所需的运行配置，随后 `test-phone.sh qemu --runtime-config ...`，帮助 Mac 无需 Windows 继续开发。工具目前已编译，尚待真实发布 JAR 验收。
7. 完成许可/源输入说明、发布说明、Mac 交接及实际测试边界，再推送、CI、创建开发版 Release 并上传。主分支 main 是当前分支祖先；是否快进 main 应在完成新代码验收后处理。
## 恢复后的验证与运行中流程（11:33）

- ARM64 正在增量构建，约 31,645 / 104,495 步（30%）；总步数会随 Ninja 图变化，不能据此推算精确完工时间。
- 原构建监督 unit 仍是 `mcphone-full-release-20260913`。后续流程 `.runtime/release-20260913/finish-local-packages.ps1` 已启动，等待本次成功标记，再压缩 ARM64、复制干净三盘和源输入清单，生成 Mac 包并合并 universal JAR。日志 `.runtime/evidence/release-postbuild-20260913.log`；完成标志 `.runtime/release-20260913/local-packages-completed.json`。失败即停止，不自动发布 Release。
- 后续流程锁定当前裸 JAR 的 SHA-256（`.runtime/release-20260913/base-jar.sha256`），运行期间不要覆盖 `build/libs/mcandroidphone-0.2.0-dev.20260913.jar`；否则会主动失败，避免混合不同代码版本。
- AMD64 XZ 已完成：803,300,256 字节；完整镜像 ZIP 803,309,655 字节，SHA-256 `55e8d0f23e2cb1552035e7c4b107db3f573fd32e47a6c937d80d453228c70ee7`。
- Windows 候选 JAR 113,624,552 字节，位于 `.runtime/release-20260913/artifacts/`；已补齐 86 个 MSYS2 包的元数据、来源和许可证，并从 10 个对应源码包补充上游版权文本。
- 新 full AMD64 三盘真实启动通过：首传输帧 127,265 ms，Android boot completed 133,188 ms，35 帧，关机 `guest-confirmed`。与 ARM64 编译/压缩并行，不能作为空闲性能基准；此测试排空 GPU 帧，未渲染 MC。证据 `.runtime/evidence/release-amd64-full-boot-20260913/result.json`。
- 候选 Windows JAR 实际原生依赖安装、803 MB 镜像解码和移走 ZIP 后的离线复用通过。发布前使用本地已校验 ZIP 填充下载缓存，**还不是 GitHub 匿名下载验证**。安装配置 `.runtime/evidence/release-windows-package-install-20260913/installed.properties` 与 `offline-reused.properties` 字节一致。
- 新代码 `eb5ef45` 的完整 CI run `34735105331` 已全部通过，包含三个宿主核心测试、Mod 构建、XZ 和 universal 合并测试。
- 还需：本轮 universal 成品的实际 Windows 游戏渲染验收、GitHub 匿名首次下载、最后的分发说明/源码输入附件与 Release 发布。新的 Go ARM64/HVF/画面留给 M4 实机验收。用户随后的 zstd 提问尚未改变本轮 XZ 发布基线，没有另启重编或压缩实验。