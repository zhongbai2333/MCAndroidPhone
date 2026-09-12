# Windows AMD64 Android Go 实机验收 — 2026-09-12

Android 16 / LineageOS 23.2 Go 已在本机 Windows / WHPX 运行。本轮验证普通 APK、计算器、2048、WebView、持久化和 Minecraft D3D11 显示，并修复新镜像的 CPU 型号、默认密度、电源资源优先级和欢迎页布局。首版应用兼容测试与后续修复镜像测试分开记录，不能视作每个版本都重跑了全部场景。

## 当前可用产物

最终欢迎页修复镜像（本地标识 v3）：`lineage_virtio_x86_64_go` / user，构建报告 `virtio_x86_64_go-oFxiHCf7`。

- ZIP：1,062,910,559 字节，SHA-256 `5bd799b171e1663584f33ae735270f4416da0f4a4b65c8f882ce753c0e09e1b1`。
- ZIP 全条目 CRC、qcow2 check 均通过；安装树 init 默认密度为 320。
- 全新用户数据在 720×1280 下正常显示欢迎页，Start 位于可见区域；通过生产触摸通道点击后进入英文/中文语言选择，Next 可见。
- 未开启 ADB、未写任何 Android 测试设置，普通 `ManagedRuntime.close()` 得到 `guest-confirmed`，全部自有子进程退出。
- 新物品默认使用独立持久化数据；已有手机不会自动升级到新系统模板。

[欢迎页](handoff-evidence/2026-09-12/windows-go/welcome-v3.png) · [Start 操作后](handoff-evidence/2026-09-12/windows-go/language-v3.png) · [产物与验收摘要](handoff-evidence/2026-09-12/windows-go/welcome-v3-artifact.json)

普通 Mod JAR：429,342 字节，SHA-256 `c66433a28995a6f9b069978b3522d774bf76bb1ea2b40d3c249c1e23d2df4347`，91 个 class，Python/bridge.zip 均为 0。该 JAR 未内置 Android/QEMU，不能用它的小体积证明整包目标已达成。仅 AMD64 Android 压缩包仍约 1 GiB。

## 首版 Go 实际应用与显示结果

首版 ZIP SHA-256 `ad0f29bcd6e48cd9cc6a4eae95d3ec1516bc7e1c25cafc931fcf553f615652bd`，1,062,910,328 字节；不是此前 Android 9 基线。测试使用 Q35 / SandyBridge / UEFI / 系统盘与用户盘 / WHPX / 4 vCPU / 4096 MiB。

| 项目 | 实测结果 |
| --- | --- |
| 产品属性 | `low_ram=true`、`virtio_x86_64_go`、user、ABI `x86_64`、SELinux Enforcing |
| 首次设置和输入 | 桌面、触摸、滑动、应用抽屉通过；语言资源为英文/中文，测试关闭诊断上传，未加账号 |
| 网络 | `10.0.2.15`，到 `10.0.2.2` 两次 ping 全部成功 |
| WebView | `com.android.webview 152.0.7977.64` 有效并启用；本地网页 JavaScript 回调 math=46，Canvas 像素 RGBA=72,223,151,255，截图一致 |
| 原始 APK | 固定版本及 SHA-256 的 Fossify Calculator 1.4.0、2048 Open Fun Game 1.16.2 安装成功 |
| 真实输入 | 生产 `PhoneConnection` 触摸计算 `12+34=46`；24 次游戏滑动后得分 136、步数 24、出现 32 方块 |
| 后台恢复 | 2048 返回后棋盘和分数保持 |
| DEX 编译 | 两款应用 `cmd package compile -m speed -f` 均 Success |
| 关机与持久化 | 首版需显式测试设置长按行为为 3；正常 close 得到 guest-confirmed。未手动 sync 的文件在下一次启动完整读回 |
| Minecraft GPU | 720×1280 D3D11 共享纹理，CPU 像素复制 0 次、GPU 缓存复制 1 次；两轮背包恢复同一连接/epoch、无需右键，资源重载通过 |
| 来宾 GPU | Mesa virgl，经 ANGLE 使用本机 RTX 5070 Ti Laptop GPU；OpenGL ES 2.0 / Mesa 25.3.3 |

[计算器](handoff-evidence/2026-09-12/windows-go/calculator.png) · [2048 操作后](handoff-evidence/2026-09-12/windows-go/2048-after.png) · [后台返回](handoff-evidence/2026-09-12/windows-go/2048-resumed.png) · [WebView](handoff-evidence/2026-09-12/windows-go/webview.png) · [Minecraft GPU 桌面](handoff-evidence/2026-09-12/windows-go/gpu-desktop.png)

应用计算和 2048 操作使用 CPU 显示链路。第二轮 GPU 测试先通过 ADB 启动 2048，但 `--android-smoke` 会回 HOME 再投影滑动，最终截图是应用抽屉，未将它当成游戏 GPU 性能验收。两轮 GPU 首帧等待约 90–100 秒，期间有 `dbus_call_update_gl` 和 `ctrl 0x103, error 0x1203` 警告；最终有真实图像且测试完成。启动延迟仍需排查，诊断帧计数和包含主动等待的整轮用时不是 FPS 基准。

ADB 仅在隔离测试手机中手动开启并授权本机密钥，产品没有默认开放调试。首次设置的 trade-in adbd 不提供普通 shell。WebView DOM 没出现在 UIAutomator 无障碍树中，改用同源回调和截图判定；临时 HTTP 服务、adb reverse 和专用 ADB 服务均已清理。

## 源码修复及验证

1. 新增 `cpuModel` 并贯通 Dev/Gradle/Mod/运行时；旧 AMD64 默认 Nehalem，新 Go 显式使用 SandyBridge。型号校验、完整 `gradlew build --offline` 和原有回归通过。
2. 仅两个 Go 产品生成默认密度 320 的 init 副本；保留 `androidboot.lcd_density` 覆盖，非 Go 的上游 init 和 160 默认值保持不变。
3. 手机框架资源改为优先的 `PRODUCT_PACKAGE_OVERLAYS`，避免 Lineage product overlay 覆盖电源行为。实际 Soong ProductResourceOverlays 列表中手机覆盖位于首位，打包后的 RRO 值为行为 3 / 500ms / 助手选项 false。
4. 上游欢迎页的 56sp 大标题在 360×640dp 挤出 Start；增加 Go 专用 SetupWizard dimen overlay，标题 32sp / 行高 40dp，缩小间距，最终全新启动与真实 Start 点击通过。
5. Linux PrepareSelfTest 通过，覆盖两个 Go 产品、非 Go 不变、幂等、锚点漂移和本地编辑保护。此轮新增配置检查基于 GNU Make 实际求值。
6. 开发交互命令曾被读到半写内容；本轮发命令使用 `.tmp` 完成后原子重命名。早期 forced-timeout 和测试工具失败证据保留。
7. 快速测试脚本新增 `--runtime-config`，按参数顺序读取 properties，后续 `--gpu` / `--set` 可覆盖；配置加载检查通过。

v2（只有密度/电源修复）ZIP SHA-256 `440f637adc5dfeceb36f1e91b0aaf1147af172013bc16f4a360bca4c71e2978e`，已独立验证默认 guest-confirmed；其欢迎页问题由 v3 修复。首版、v2 与 v3 镜像和测试用户数据均保留。

## 构建恢复与本地复现

增量 Soong bootstrap 仍需要大内存。19:59:49 OOM 日志记录 soong_build 常驻约 22 GiB；WSL init.scope 因 OOM 进入停止状态并在 20:11 清理后续进程。重启后的无负载 WSL 生命周期测试通过。不能把中间服务退出码或旧 ZIP 存在当作构建完成。

恢复使用独立 systemd 单元，MemoryHigh=20GiB / MemoryMax=22GiB / MemorySwapMax=48GiB，WSL 总上限仍 24 GiB，构建 12 路 / highmem 1 路。v2 20:27 完成（07:56），v3 20:47 完成（06:36），均 PIPELINE_EXIT=0；临时 48 GiB swap 自动删除，当前仅默认 6 GiB WSL swap。

本轮最初的 `.runtime/go-windows.properties` 指向 v3；后续已更新为启动优化 v5，见 [冷启动验证](windows-go-startup-2026-09-12.md)。GPU virgl、无 ADB 转发保持。运行：

```powershell
$env:JAVA_HOME='D:\Program Files\Zulu\zulu-25'
.\test-phone.cmd qemu --runtime-config .runtime/go-windows.properties
# 临时使用 CPU 显示链路：在末尾加 --gpu virtio
# 自动创建一次性世界验收：在末尾加 --world-smoke --warmup 120
```

该配置使用本机路径，未纳入 Git；其他设备需准备对应平台运行时与镜像。镜像未上传 GitHub，源码、回归和小型截图证据可随分支同步。

进度：`.\scripts\show-go-progress.ps1 -Build defaults`。顶部 100% 只代表 1180 个仓库已就绪；构建阶段看服务与日志。当前构建已结束。

Windows 详细证据 `.runtime/evidence/windows-go-20260912/`；WSL 构建证据位于 Linux 工程 `.runtime/evidence/windows-go-defaults-20260912/`，含 `defaults-v2-build.log`、`go-amd64-build.log`、`pipeline-status.log` 和备份 ZIP。CPU/两轮 GPU/v2/v3 的已审计子进程全部退出。

仍待完成：GPU 冷启动与实际游戏性能、音视频、相机/环境 HAL 在 Go 上的实测、Go 双设备隔离实测、ARM64 Go 与其余平台整包验收、完整离线包体积压缩。
