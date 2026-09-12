# 双架构下载版暂停点 · 2026-09-13

用户明确要求暂停 ARM64 构建，明天再继续。不要自动恢复、发布 Release 或启动新的构建。

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