# Windows Android Go 冷启动优化 — 2026-09-12

最终候选 v5 在本机已有数据的冷启动测到 **82.09 秒首个传输帧、84.76 秒完成启动**；本轮 v3 对照为 122.14 / 122.57 秒。首帧少约 40 秒（33%），但仍超过一分钟。全新手机首次开机为 115.75 / 121.35 秒，包含用户数据创建。这里是从关机状态启动，没有恢复 RAM 快照。

## 测量范围

Windows AMD64 / WHPX / SandyBridge / 4 vCPU / 4096 MiB / 720×1280 / VirGL D-Bus。计时从 `ManagedRuntime.start()` 开始，不含 Minecraft 客户端加载。已有数据的测试采用已正常关机手机的临时磁盘快照。v3 与 v5 分别使用各自初始化的欢迎页数据；它们不是相同镜像上的严格单变量实验。宿主缓存和镜像布局也有差异，单次结果不能代表稳定百分比。

| 测试 | 运行时就绪 | 首个传输帧 | Android 完成启动 |
| --- | ---: | ---: | ---: |
| v3，VirGL，4 vCPU | 1.47 s | 122.14 s | 122.57 s |
| v3，VirGL，2 vCPU | 1.22 s | 124.66 s | 125.20 s |
| v3，1 秒菜单 + quiet | 1.28 s | 103.78 s | 无可见标记 |
| 再关闭开机动画 | 1.65 s | 93.90 s | 无可见标记 |
| v3，CPU 显示 | 0.93 s | 1.40 s（固件） | 62.60 s |
| v4 实验，WHPX hyperv=off | 1.15 s | 82.82 s | 无可见标记 |
| v4 再改 CPU=max（未选作默认） | 1.45 s | 77.17 s | 无可见标记 |
| **v5 首次创建手机** | 8.07 s | **115.75 s** | **121.35 s** |
| **v5 已有数据冷启动** | 1.10 s | **82.09 s** | **84.76 s** |

计时工具只接收并释放 GPU 更新租约，不渲染。CPU 首帧可能是固件，不能直接与 Android GPU 首帧比较。完整串口 logcat 实验影响性能并达到 200 秒保护退出，仅用于诊断；v4 首启收到画面并正常关机，但 quiet 隐藏完成标记导致计时工具退出失败，不是 Android 开机失败。

## 最终修改

- 两个 Go 产品的 GRUB 默认等待从 10 秒降至 1 秒，quiet=1、android_nobootanim=1；非 Go 保留上游模板。已有手机的持久化设置与系统版本不自动改写。
- VirtIO 探测同步发布对应 EGL 实现，尊重已有图形后端，避免属性触发链晚于核心服务启动导致 SurfaceFlinger 首次崩溃后等待 5 秒重启。
- 修复 Gradle 的 Minecraft client 参数转发遗漏：CPU 型号及 WHPX 选项必须送入实际游戏 JVM，不能只在独立计时工具生效。
- 新增运行时 `whpxHyperv=auto|on|off`，默认 auto 不改变其他平台。此 Windows Go 配置显式 off，仍用 WHPX 硬件加速；显式 WHPX 不静默退回 TCG，accel=auto 保留回退。
- 本机原内核没有 CONFIG_HYPERV，检测到 Hyper-V 时把 TSC 标记不稳定并使用 HPET。关闭 WHPX 的来宾 Hyper-V 接口后，日志显示使用 TSC。未强制 `tsc=reliable`，未关闭时钟看门狗或安全缓解。该变化只解释部分收益。[QEMU WHPX 配置](https://www.qemu.org/docs/master/system/qemu-manpage.html)
- AMD64 Go 的 system server 使用 `speed` 完整预编译；ARM64 保留 `speed-profile`，普通 APK 仍用 verify/DEX/JIT。v5 对比 v4 的组合结果没有显示明确额外启动收益，不能单独宣称完整预编译有效。最终恢复并保留上游 UFFD GC；声明 `ro.hardware.virtual_device=1`。
- quiet 模式输出一条可见的 `MCANDROIDPHONE_BOOT_COMPLETED` 标记；增加有界 `boot-benchmark` 开发入口，默认隔离磁盘写入。

ART 的 `MOVE ioctl seems unsupported` 文本也可能来自正常回退，errno 可能残留，不能单凭它认定 MOVE 调用发生超时。减少 vCPU、关闭 IRQ chip 没有提供足够理由替换默认配置。V4 的 CC GC 实验未作为最终配置。

## 构建和产物

v5 Android 增量构建成功（08:13），实际 dexpreopt 配置确认 system server=speed，成品 UFFD GC=true。ZIP CRC、系统盘 qcow2 check 均通过；全新测试手机正常关机得到 guest-confirmed。Mod 完整离线 build/check 通过，Linux PrepareSelfTest 通过两个 Go 产品、非 Go 不变、GNU Make 实际求值、幂等与本地编辑保护。

ZIP：1,103,426,413 字节，SHA-256 `7df844a80c9fd3ed494a1b7ec392f7fc78bc08c07887675330e71428b3a799e6`。比 v3 多约 38.6 MiB。三盘哈希和计时原始结果见 [结果摘要](handoff-evidence/2026-09-12/windows-go-startup/startup-results.json)。镜像留在本机 `.runtime/images/lineage-go-amd64-20260912-startup-v5/`，未放入 Git。

构建继续使用 WSL 24 GiB 上限、12 路/highmem 1 路、systemd 20 GiB memory.high / 22 GiB memory.max。通过 `systemd-run --wait --pipe` 保持 Windows 侧 WSL 调用活跃；48 GiB 临时 swap 在构建后已删除，仅剩默认 6 GiB。源码和 VHDX 仍在 E 盘。

Windows 详细证据：`.runtime/evidence/boot-20260912/`；Linux 构建报告：`out/mcandroidphone/virtio_x86_64_go-1B9gSlIO/`。CPU/应用完整验收的历史结果见 [前一轮](windows-go-validation-2026-09-12.md)，不把旧版应用测试当作 v5 重测结果。

首轮 Minecraft 自动测试虽报告 GPU 帧和收纳成功，但截图为黑屏；实际 QEMU 命令仍是 Nehalem/默认 Hyper-V，因为 client 参数转发漏项。该轮不计为画面验收通过，保留失败证据用于说明自动结构检查的边界。

## Minecraft 实际验收与使用

修复后的 QEMU 实际命令确认 `-cpu SandyBridge`、`-accel whpx,hyperv=off`。23:03:10 通过真实物品入口右键，23:04:34 收到首个 GPU 帧，约 **84 秒**。测试主动等到 120 秒再截图，画面为正常 LineageOS 欢迎页，Start 可见；这不是测出欢迎页恰在 120 秒出现。此轮没有重新执行应用或 Start 点击回归。

D3D11 共享纹理 720×1280，CPU 像素复制 0 次、GPU 缓存复制 1 次；背包恢复同一连接和 epoch、无需右键，资源重载通过。整轮 127.996 秒含主动等待、收纳、重载，不能当作纯启动时间或 FPS。

[游戏内欢迎页](handoff-evidence/2026-09-12/windows-go-startup/minecraft-welcome.png) · [收纳与重载后](handoff-evidence/2026-09-12/windows-go-startup/minecraft-restored.png)

本机 `.runtime/go-windows.properties` 已切到 v5、SandyBridge、whpxHyperv=off，保留 VirGL、持久化存储、无 ADB。运行：

```powershell
$env:JAVA_HOME='D:\Program Files\Zulu\zulu-25'
.\test-phone.cmd qemu --runtime-config .runtime/go-windows.properties
```

要使用新系统模板，请创建一个新的手机物品。旧物品保留原系统版本和数据；修改默认镜像不会自动升级它。优化源码覆盖两个 Go 产品的共用引导/EGL 部分，但本轮只构建并实测 Windows AMD64，未重新验收 ARM64/Mac。
