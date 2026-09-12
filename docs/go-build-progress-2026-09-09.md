# AMD64 Go 构建推进 · 2026-09-09

本轮接续 Windows 原生验收，已进入真实 LineageOS 23.2 源码准备阶段。**尚无编译完成的 Go 镜像，也未证明完整离线包小于 500 MiB。** 本文记录启动时状态，实时结果以 WSL 阶段日志为准。

## 已完成

- 在 Ubuntu-24.04 中安装 Repo、Git LFS、Android/上游 VirtIO 编译依赖，包括 multilib、Meson 1.5、GCC、protobuf、qemu-utils 等。保留现有 JDK 25，Android 使用源码树指定的预编译工具链。
- 独立源码树：`/home/zhongbai233/android/lineage-23.2-mcphone`。Repo manifest 初始化到 LineageOS `lineage-23.2`，初始提交 `705406eb22c0efc833a7821ca198e3f0c79b4115`。
- 核验并锁定两个 VirtIO 目标和递归依赖的 16 个提交；全部使用实际存在的 `lineage-23.2` 分支。清单见 [VirtIO 源码输入](../android/image/lineage-23.2/README.md)。这不是完整的 repo 锁定 manifest。
- 先同步 build/make、hardware/interfaces、vendor/lineage 和三个 VirtIO 设备树。**真实源码**上 `Prepare.java --check` 通过：10 个上游文件、12 个 overlay 文件；尚未应用补丁，保持完整同步前源码干净。
- Go 配置自测再次通过：两个根产品、幂等、漂移拒绝、非 Go 产品保留和必需组件检查。
- `build-go.sh` 默认显式 `m -j 2 vm-utm-zip`，可用 `MCANDROIDPHONE_BUILD_JOBS` 调整；新增并行度、内存和磁盘状态的构建证据。脚本语法已检查，尚未执行完整 Android 构建。

## 当前正在执行

完整 `repo sync -c -j4 --no-clone-bundle --retry-fetches=3 --fail-fast` 已在 WSL 运行。使用普通用户、独立 Git 配置文件 `.mcphone-gitconfig`；未执行全局 Git 配置、force-sync 或清理已有目录。20:54 左右核验：`repo list -a -p` 实际选中 1180 个仓库，其中 179 个已存在 checkout index，整个源码目录约 15 GiB。CTS 的未完成 Git pack 约 1.01 GB，修改时间持续更新；先前的 Cronet pack 和 WebView Git LFS 下载进程已结束，其他仓库继续推进。仓库数量比例不是下载字节进度，源码目录占用也不是最终系统体积。同步已运行约 90 分钟，仍未进入编译；目前无 Go 镜像产物。注意 `repo list -p` 默认只显示已有 checkout，不能用来统计总项目数。

已启动一次性接续流程，等待本轮同步进程退出：

1. 只有完整同步成功且生成 `manifest-locked.xml` 才继续。
2. 检查承载 VHDX 的 **E 盘宿主**至少剩余 220 GiB，避免把 WSL 虚拟盘上限当作实际可用空间。
3. 创建专用 `/var/tmp/mcandroidphone-go-20260909.swap`，临时增加 48 GiB 交换空间。当前 WSL 约 15 GiB RAM + 4 GiB 原有 swap，保持现有内存上限；原 `.wslconfig` 未改动，WSL 未重启。交换文件只在同步完成后创建，目前尚未启用。
4. 以普通用户、nice 10 / ionice 低优先级、GOMAXPROCS=2 和构建并行度 2 执行 AMD64 Go。构建进程提高 oom_score_adj，降低其挤占其他进程生存空间的优先级。
5. 成功时保存产物路径清单；失败时保留日志。退出时关闭并删除本轮专用交换文件；若系统无法安全关闭它，会明确记录 `SWAP_CLEANUP_PENDING` 并保留文件，不能手工删除活动 swap。

交换空间不能替代物理内存性能，也不能保证 Soong 大规模分析阶段或完整构建成功。此流程尚未运行到编译阶段，真正的配置/编译/镜像启动问题需在日志出现后继续处理。构建成功也只会记录 `ANDROID_RUNTIME_ACCEPTANCE_PENDING`，不会声称已通过 Windows 启动或 APP 验收。

## 实时日志与接续

WSL 工程：`/home/zhongbai233/src/MCAndroidPhone-handoff-20260909`。

该工程 `.runtime/evidence/android-go-20260909/` 中：

- `pipeline-status.log`：当前阶段及最终退出码；启动时为 `WAITING_FOR_SOURCE_SYNC`。
- `repo-full-sync.log`：完整同步输出；失败不会强行进入构建。
- `manifest-locked.xml`：只有完整同步后生成。
- `go-amd64-build.log`：同步完成后才开始写入编译输出；预创建的空文件不表示编译启动。
- `build-artifacts.txt`：构建成功后列出的候选输出；空文件不表示成功。
- `real-source-prepare-check.log`、`prepare-self-test.log`：本轮已通过的真实锚点检查及离线配置回归。
- `source-sync.sh`、`pipeline.sh`：本次实际执行脚本的留档，供排查复核；不要在当前流程运行时重复启动。

PowerShell 查看当前状态：

```powershell
wsl -d Ubuntu-24.04 -- cat /home/zhongbai233/src/MCAndroidPhone-handoff-20260909/.runtime/evidence/android-go-20260909/pipeline-status.log
wsl -d Ubuntu-24.04 -- tail -n 30 /home/zhongbai233/src/MCAndroidPhone-handoff-20260909/.runtime/evidence/android-go-20260909/repo-full-sync.log
wsl -d Ubuntu-24.04 -- tail -n 60 /home/zhongbai233/src/MCAndroidPhone-handoff-20260909/.runtime/evidence/android-go-20260909/go-amd64-build.log
```

下一轮先读阶段日志及进程状态。若同步失败，保留下载缓存，处理具体错误再续传；若构建失败，保留输入 manifest、Go 配置报告和日志。不要重复启动同步/构建进程，也不要重置源码树。

仅做过一次 TUNA 镜像对比：同一 AOSP 标签 SHA 与原源一致，但镜像实际 fetch 排队超过 600 位并触发低速超时，因此未切换镜像。完整同步继续使用 LineageOS/AOSP 原服务器。[TUNA 说明](https://mirrors.tuna.tsinghua.edu.cn/help/AOSP/)；[AOSP 构建要求](https://source.android.com/docs/setup/start/requirements)。

完成 AMD64 镜像后，仍需先用新 userdata 在 Windows 验证引导、Go low-RAM 属性、安装器/WebView/输入法、普通 APP、关机、双手机数据隔离及 D3D11 显示，再尝试 ARM64。当前旧 Android 9 的通过结果不替代新 Go 镜像验收。

## 23:17 恢复记录（优先于上面的启动时状态）

23:15 检查时，1180 个仓库中已有 745 个 checkout index，源码目录约 34 GiB。原同步与等待构建进程均已不存在，阶段日志却仍停留在 WAITING_FOR_SOURCE_SYNC；没有锁定 manifest、编译输出或 Go 镜像。未查到明确退出原因，内核近期日志没有 OOM 记录，不能把中断原因断言为内存或终端退出。

保留源码缓存与原同步日志后，23:17 已通过 WSL systemd 启动一次性服务 `mcandroidphone-go-20260909.service`。确认服务 active/running，进程组内已有真实 repo sync、git fetch 和 checkout；真实源码补丁检查再次通过。服务使用证据目录内 `pipeline-service.sh`，直接等待普通用户执行的 `source-sync.sh` 返回成功，再检查锁定 manifest 并进入原来的临时 swap / 2 并行构建步骤，取消依赖旧 PID 和成功提示字符串。同步、manifest 导出或干净检查任一步失败都会记录 PIPELINE_EXIT 并停止。

这项服务独立于当前终端会话，但不跨 WSL 关闭或 Windows 重启自动恢复；不是定时任务，也未设置无限重试。目前仍无 Go 镜像。查看实时状态：

```powershell
wsl -d Ubuntu-24.04 -- systemctl status mcandroidphone-go-20260909 --no-pager
wsl -d Ubuntu-24.04 -- tail -n 20 /home/zhongbai233/src/MCAndroidPhone-handoff-20260909/.runtime/evidence/android-go-20260909/pipeline-status.log
```

后续以服务状态、最新阶段日志和实际产物为准，不以旧 WAITING 行单独判断任务存活。不要在服务运行期间再次启动同步或构建。

## 2026-09-11 21:26 重启后恢复

恢复前仍为 745 / 1180 个 checkout index，剩余 435；9 月 9 日 23:23 的最后失败是 CTS HTTPS 连接超时，并未进入编译。9 月 11 日用户再次重启设备后，原一次性服务不存在。交互用户当前通过代理可访问 CTS（HTTP 200），但 root 环境未带这些代理变量。已将网络环境单独保存在忽略的证据目录 `.service-network.json`（0600，不记录内容到文档或 Git），服务入口 `service-launch.py` 读取它再运行既有流程。

去掉同步的 --fail-fast，让一个仓库失败后其他仓库仍有机会完成；完整同步返回失败时仍停止，不进入构建。旧失败日志已备份。单独启动 transient 服务后再次检查为 inactive 且未记录退出，因此增加隐藏的 Windows wsl.exe 会话，通过 `systemd-run --wait` 维持 WSL 调用。启动脚本在 Windows 临时目录 `phone-go-keepalive-0911.sh`，stdout/stderr 同目录；启动时 Windows PID 40024，仅供历史定位，后续必须重新核对身份。

21:26:37 实测 Windows 等待进程仍在、服务 active/running，服务内有 Git 下载进程。尚无新 Go 镜像。该方案仍不跨 Windows 重启自动启动；后续先查实际服务/进程状态，不能只看阶段日志旧行。代理快照可能因网络配置变化失效，重启后恢复时需重新核验。
## 2026-09-11 23:56 最后 7 个仓库恢复

23:53 服务已停止；最近一次同步在 23:44:55 以退出码 1 结束。7 个未检出的仓库为 cts、frameworks/base、kernel/prebuilts/6.1/arm64、6.12/{arm64,x86_64}、6.6/{arm64,x86_64}。网络日志为连接重置。另一个已检出的 build/soong 因 shallow.lock 残留同步失败，因此本轮需修复 8 项。

核实没有源码树中的 Git/Repo 进程后，将唯一残留的 build/soong.git/shallow.lock 移入证据目录留档，未删除源码或缓存。有效 manifest 给出 LineageOS 两项目的 refs/heads/lineage-23.2，以及六个 AOSP 项目的 refs/tags/android-16.0.0_r4。新 repair-fetch.py 从有效 manifest 解析目标，使用显式 depth=1、no-tags 和 HTTP/1.1 定向获取目标，2 路并行、每项最多 3 次；成功后逐项 repo sync -l 检出。不会切换分支或镜像服务器。原来的失败 fetch 曾回退到不带 depth 并拉取全部 tags，导致传输量增加。

source-sync.sh 已备份并接入定向修复；全部修复成功后执行整树本地同步、生成锁定 manifest 和干净检查，再进入原构建流程。阶段/构建脚本维持不变。每项传输日志为证据目录 fetch-*.log，主同步日志记录 FETCH/CHECKOUT 事件。新的隐藏 WSL 等待进程启动时 Windows PID 7368，后续必须重新核对身份。

23:57 确认 build/soong FETCH_OK + CHECKOUT_OK；frameworks/base 已出现 Receiving objects，速度约 2.46 MiB/s，同时开始下载 6.1/arm64。此时仍在修复下载，未声称 7 项全部完成或已进入 Android 编译。
## 2026-09-12 00:06 完整同步通过，开始 AMD64 Go 构建

1180 / 1180 个仓库已完成检出。8 项定向修复、整树本地同步和源码干净检查均通过；00:06 生成 manifest-locked.xml（约 282 KiB）。流水线于 00:06:30 记录 FULL_SOURCE_SYNC_AND_CLEAN_CHECK_SUCCEEDED，随后启用专用 48 GiB 临时 swap，进入 BUILDING_AMD64_GO_JOBS_2。

实际构建日志确认 Go overlay 检查并应用，TARGET_PRODUCT=lineage_virtio_x86_64_go、TARGET_BUILD_VARIANT=user、TARGET_ARCH=x86_64、PLATFORM_VERSION=16。00:07 仍在构建配置核验阶段，服务 active/running；存在 nsjail setpriority 权限警告，目前未见其导致退出。此时未生成 Go 镜像，不能将下载进度 100% 当作编译完成。
## 2026-09-12 00:14 配置失败修复并恢复编译

第一轮构建在 00:07:07 退出。`setpriority(5): Permission denied` 为 nsjail 优先级警告：外层 nice 10 无权升到 nice 5。真正的退出点是 VerifyGoConfig 的 Unexpected resource locales，实际 PRODUCT_LOCALES 为 en_US zh_CN 加上 ast_ES ckb_IQ ckb_IR gd_GB cy_GB fur_IT nn_NO；build/make/core/product_config.mk 在解析产品之后将 vendor/lineage/config/common.mk 的 CUSTOM_LOCALES 再追加进去。

Prepare.java 现在仅对两个 MCAndroidPhone Go 目标跳过上游 CUSTOM_LOCALES 声明，其他 Lineage 产品保留原行为。新增 GNU Make 回归覆盖后置追加、两个 Go 目标及非 Go 产品；VerifyGoConfig 保持严格校验，并在错误中显示实际语言列表。离线回归全部通过，真实源码检查仅新增修改 1 个上游文件。变更已同步 Windows 主仓库与 WSL 工程。

保留第一次失败日志后，增加证据目录 pipeline-build-only.sh 从已完成同步的源码继续，不重新联网同步或用干净检查否定已应用的 overlay。启动 nice 与 nsjail 统一为 5，保留普通用户执行、沙箱、ionice、2 并行和临时 swap。00:14:10 恢复构建；实际日志出现 GO_CONFIG_OK lineage_virtio_x86_64_go，未再出现 Permission denied，随后进入 Soong 工具 bootstrap 编译（检查时 242/284）。该百分比仅属于 bootstrap 阶段，不代表整个 Android 镜像进度。隐藏 WSL 等待进程启动时 Windows PID 12752，后续需重新核验身份。
## 2026-09-12 00:28 编译并行度调整

用户反馈 CPU 利用率低。00:26 实测已进入主 Ninja 编译（约 1231/156162 条构建动作），命令行确认为 -j 2；vmstat CPU 约 84–85% 空闲、实时换页接近 0。WSL 15 GiB 内存中 Ninja 自身约 7 GiB RSS，可用内存约 6 GiB；Windows 宿主可用约 4 GiB，因此不重启 WSL 扩大内存，也不直接使用全部 32 个逻辑 CPU。

对核验过的 Ninja PID 发 SIGINT 正常中断，保留所有增量产物，确认旧流水线退出并清理专用 swap 后，于 00:28:39 恢复：MCANDROIDPHONE_BUILD_JOBS=6、GOMAXPROCS=4、NINJA_HIGHMEM_NUM_JOBS=1，nice 5 保持不变。高内存并发参数已从本机 build/soong/ui/build/config.go 核实。该调优只修改本机 pipeline-build-only.sh，通用 build-go.sh 的保守默认值仍为 2。旧日志留档 go-amd64-build-jobs2-interrupted-*.log；其中 cancelled/FAILED 是本次人工中断产生，不能误报为源码编译错误。

隐藏 WSL 等待进程启动时 Windows PID 38668，后续需重新核验身份。新配置已启动，实际吞吐需在进入主编译后测量；并行度三倍不等于总耗时缩短到三分之一。
后续主编译实测：6 个 Java/Rust 编译任务并行，CPU 总利用率约 35–41%（原约 15–16%），可用内存约 4.9 GiB、swap 占用约 27 MiB，未见持续大量换页。新 Ninja 图剩余约 154789 项，已复用先前产物；重启后的动作计数重新开始，不表示从零编译。这是阶段采样，不是全程耗时预测。

## 2026-09-12 00:34 提升到 8 路

用户继续要求提高并行度。调整前 6 路主编译已到约 3292/154789，CPU 阶段采样约 23–24%，WSL 可用内存约 6.1 GiB，Windows 可用约 5 GiB，实时换页为 0。对核验的 Ninja 发 SIGINT，旧流水线清理后，将本机 MCANDROIDPHONE_BUILD_JOBS 从 6 调到 8，维持 GOMAXPROCS=4、NINJA_HIGHMEM_NUM_JOBS=1 和 nice 5。已增量恢复，旧日志保存为 go-amd64-build-jobs6-interrupted-*.log。隐藏等待进程启动时 Windows PID 30244，后续需核验身份。Ninja 调度图本身约 7 GiB RSS，因此目前不将并行度直接提高到全部 32 线程。
## 2026-09-12 00:37 用户批准 24 GiB WSL / 12 路构建

用户释放宿主内存后，Windows 可用约 15 GiB，但 WSL 仍受默认约 16 GiB 上限限制。用户明确批准重启整个 WSL（包括其中会话/Docker），将内存上限改为 24 GiB、编译 12 路。

对唯一核验的 Android Ninja 发送 SIGINT，等待流水线结束并清理临时 swap；备份 .wslconfig 到 Windows 临时目录 mcphone-wslconfig-before24gb-20260912-003744，保留 networkingMode=Mirrored，仅新增 memory=24GB。执行 wsl --shutdown 后恢复隐藏构建会话，启动时 Windows PID 35756。本机 pipeline-build-only.sh 使用 MCANDROIDPHONE_BUILD_JOBS=12、GOMAXPROCS=6、NINJA_HIGHMEM_NUM_JOBS=1、nice 5。先前 8 路日志已保留，增量产物未清理。

00:37:53 新流水线启动。重启后 free -h 显示约 23 GiB 总内存，证明 24 GiB 上限已生效；服务 active/running，阶段为 BUILDING_AMD64_GO_JOBS_12_HIGHMEM_1。WSL 默认 swap 随内存上限变为约 6 GiB，叠加本轮临时 48 GiB swap，显示总量约 53 GiB。仍在重新加载构建配置时不据此判断编译 CPU 利用率；最终镜像尚未完成。
## 2026-09-12 00:49 用户要求暂停

用户准备休息，明确要求暂停构建。对已核验的 Ninja PID 8364 正常发送 SIGINT，00:48:54 流水线退出；随后确认服务 inactive/dead，ninja/clang/rustc/java/soong_ui 均无残留，专用 48 GiB 临时 swap 已关闭并移除，仅保留 WSL 默认 swap。隐藏等待进程已退出，进度窗口 PID 48256 已关闭。所有增量产物和源码保留，阶段追加 USER_PAUSED_INCREMENTAL_OUTPUTS_RETAINED；本次退出码 1 是用户主动暂停，不是新的构建失败。

用户补充持续观察结果：12 路构建 CPU 平均约 60%，峰值可到约 90%。这比之前代理的短时 38–41% 采样更能反映用户观察期间负载；不能把任何一次采样或该观察当作全阶段恒定吞吐，也未证明已经跑满整机全部性能。

24 GiB WSL 上限和本机 12 路 / 高内存任务 1 路配置保留。当前处于用户主动暂停状态，不应自动重新启动构建；等用户明确要求继续后，从现有增量产物恢复即可，无需重新下载或 clean。
## 2026-09-12 16:48 用户要求恢复

用户要求开始，已解除前面的主动暂停状态并增量恢复。本次维持已批准的 24 GiB WSL 上限、12 路普通编译、1 路高内存任务、GOMAXPROCS=6 和 nice 5，未继续调优。恢复前将暂停日志备份为 go-amd64-build-before-resume-20260912-*.log。

16:48:47 流水线记录 RESUMING_BUILD_FROM_SYNCED_SOURCE / BUILDING_AMD64_GO_JOBS_12_HIGHMEM_1；16:49 确认服务 active/running、真实构建配置核验正在进行，专用 48 GiB 临时 swap 已启用。隐藏 WSL 等待进程启动时 Windows PID 27792，重新打开的进度窗口 PID 6636；后续需核验实际身份。源码和既有产物均保留，未重新下载或 clean；尚未生成已验收镜像。
## 2026-09-12 18:22 失败：Repo 缓存触碰只读沙箱，已修复

恢复后的主构建在本轮约 126203/130137（96%）处失败，唯一失败目标为 product/etc/build-manifest.xml。完整异常栈显示 Repo 想更新 /home/zhongbai233/.repo_.gitconfig.json，nsjail 将其挂载为只读，缓存写入和后续删除均抛出 Errno 30。末尾内核配置合并输出不是失败根因；构建约运行 1 小时 33 分。

修复：build-go.sh 在沙箱外导出本轮真实锁定 manifest，并原子暂存至 out/mcandroidphone/build-manifest.xml；Prepare.java 仅对两个 Go 产品替换上游构建清单规则，声明这个输入依赖，保留 proprietary 过滤，并在 out/ 中原子生成目标。不调整沙箱写权限，也不修改 Repo 工具。非 Go 产品保留原规则。

新增实际上游 build-manifest_xml.mk fixture，GNU Make 回归覆盖两个 Go 产品、清单内容及过滤、依赖变更后重新生成、输入缺失拒绝、非 Go 原规则。Prepare 回归、幂等/漂移保护、脚本语法和真实上游锚点检查通过。Windows 与 WSL 的 Prepare.java/build-go.sh/测试文件已同步；脚本使用 LF 换行。

定向真实沙箱测试执行 m -j 12 build-manifest.xml，成功完成（01:23），随后校验安装清单与锁定输入完全一致，输出 SANDBOX_BUILD_MANIFEST_OK projects=1180。日志在证据目录 manifest-sandbox-test.log。之后保留完整失败日志并按原 24 GiB / 12 路 / 高内存任务 1 路配置恢复完整增量构建；启动时 Windows 等待进程 PID 36452，后续需核验实际身份。定向目标通过不等于整个镜像已经完成。
## 2026-09-12 18:51 AMD64 Go 镜像构建成功

完整构建于 18:51:23 返回 0，阶段为 AMD64_GO_BUILD_COMMAND_SUCCEEDED / ANDROID_RUNTIME_ACCEPTANCE_PENDING / PIPELINE_EXIT=0。最后一次增量构建 06:53，主动作完成 4021/4021。服务已停止，专用 48 GiB swap 已释放。

最终目录：`/home/zhongbai233/android/lineage-23.2-mcphone/out/target/product/virtio_x86_64_go/VirtualMachine/UTM/`。

- `UTM-VM-lineage-23.2-20260912-UNOFFICIAL-virtio_x86_64_go.zip`：1,062,910,328 bytes，1013.67 MiB。
- SHA-256：`ad0f29bcd6e48cd9cc6a4eae95d3ec1516bc7e1c25cafc931fcf553f615652bd`。
- 主盘 `LineageOS_Go_on_x86_64.utm/Data/vda.qcow2`：2,076,114,944 bytes；原始 disk-vda.img 虚拟长度 5 GiB。
- ZIP 全条目 CRC 检查通过；qemu-img check 主盘通过，check-errors=0。机器可读结果在证据目录 artifact-integrity.json。

修正产物收集深度为 6 并重新生成 build-artifacts.txt，包含先前 maxdepth=3 漏掉的 UTM ZIP 与 qcow2。真实运行验收尚未进行，不能把编译/CRC/qcow2 一致性通过等同于 Android 已能启动或 APP 兼容。当前仅 AMD64 镜像压缩包已约 1 GiB，尚未达到完整离线多平台包 <500 MiB 的目标。

## 2026-09-12 Windows Go 运行验收

首版 AMD64 Go 已在 Windows / WHPX 启动，低内存 user / Enforcing 属性、APK 安装、计算器、2048、正常关机后的持久化、Minecraft D3D11 图像及背包恢复已有实测证据。默认密度、电源覆盖和欢迎页问题已修复并完成 v3 增量重建；全新数据启动、Start 点击和默认正常关机通过。详见 [Windows Go 实机验收](windows-go-validation-2026-09-12.md)。

最终 v3 20:47:21 PIPELINE_EXIT=0，ZIP SHA-256 `5bd799b171e1663584f33ae735270f4416da0f4a4b65c8f882ce753c0e09e1b1`，临时 swap 已清理。当前没有仍在运行的构建。
