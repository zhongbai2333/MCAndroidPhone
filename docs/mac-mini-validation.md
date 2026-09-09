# Mac mini M4 本机验证 · 2026-09-08

> 本文保留 Java 迁移前的历史验证。当前生产运行时已移除 Python，最新结果见 [Java 运行时迁移](java-runtime-migration.md)。历史 Windows/Linux 验收不等于新 Java 后端已在这些宿主验收。

后续真实 ARM64 Android 启动、触控和 Minecraft 滑动解锁已完成，见 [Android 验收记录](android-arm64-validation.md)。下文保留固件阶段的历史状态。

本轮在 M4 Mac mini 上完成项目构建、协议/生命周期、Minecraft 图案世界和 QEMU 固件测试，并修复了普通启动器 PATH 下误选旧 Python 的问题。真实 ARM64 Android 系统尚未启动；下一阶段准备匹配 QEMU `virt` 的镜像。

## 环境与范围

- 主机：Mac16,10，Apple M4，24 GiB 内存，macOS 27.0（26A5425a），原生 ARM64。
- Java：Zulu 25.0.2；Python：Homebrew 3.14.6；FFmpeg：9.0.1；本轮安装 QEMU 11.1.1。
- Minecraft 26.1.2、NeoForge 26.1.2.76、Gradle 9.5.0；游戏报告 Apple M4 OpenGL 4.1 Metal。
- 基线提交 `19a910b`，开始时工作区干净。本轮源码改动集中在 Python 自动发现及其回归测试；没有提交或推送。
- 检查了构建/打包、公共与客户端入口、手机显示/输入、协议缓冲、运行时发现、进程所有权、跨平台命令和测试入口。Windows 专属 D3D11 路径仅检查源码及已有测试，本轮未进行 Windows 实机验证。

## 结果

| 检查 | 本机结果 |
| --- | --- |
| `sh test-phone.sh quick` | bridge 113 项：112 通过，1 项因未装可选 protobuf 跳过；scripts 76 项：74 通过，2 项 Windows 专属测试跳过；Java 核心及几何通过 |
| 真实进程生命周期 | 正常退出、启动取消、失败清理、JVM 强杀后的 EOF 清理全部通过 |
| `sh gradlew build runtimeSelfTest -PruntimeTestPython=/opt/homebrew/bin/python3 --no-daemon` | 修复后的完整构建、Java 核心/几何、Python 发现回归及生命周期全部通过 |
| `sh test-phone.sh native-probe` | `IOSURFACE_PROBE_OK`；Metal/CGL 均为 Apple M4；像素 `64,128,191,255`，`GLerror=0` |
| 图案世界 | 1080×1920 图案显示、悬停倾斜、外缘旋转、单指按下/松开、背包恢复、资源重载及截图通过；三张截图已目视检查 |
| x86 BIOS / TCG | Java 管理器启动真实 QEMU，收到 720×400 NV12 帧；截图为 BIOS/iPXE，无系统盘 |
| ARM64 UEFI / HVF | Java 管理器启动真实 QEMU，收到 1080×1920 帧；初始截图为 TianoCore 固件 |
| ARM64 固件 Minecraft 世界 | 预热 30 秒后进入 UEFI Shell；手机显示、背包恢复、资源重载、截图及退出通过；未注入 Android 触控 |

运行中记录的 QEMU 参数包含 `-machine virt,gic-version=3,accel=hvf -cpu host`，固件以只读 pflash 加载。两种固件都没有 Android 磁盘。显示链路为 **QEMU VNC → FFmpeg → NV12/mmap → Java → OpenGL**。

结束后核对了 11 份会话记录中的 35 个自有 PID 和 12 个进程组，无运行时进程残留（包括组内 FFmpeg）。最终 JAR 的 39 个 Java 类与本机编译输出一致，内置 bridge 的 20 份 Python 文件与源码逐一一致。产物为 `build/libs/mcandroidphone-0.1.0-prototype.jar`，SHA-256：`5856d5208360b08d2e1240d2c872cd15dc8e257eb3970ac3ca7a2fa30a638705`。

本机完整构建复用了部分依赖缓存，不能据此宣布此前 CI 的上游 HTTP 502 或干净环境下载问题已经解决。图案和固件的帧数是诊断计数，不是 Android 帧率基准。

## 修复：启动器误选系统 Python

复现环境为 `PATH=/usr/bin:/bin:/usr/sbin:/sbin` 且不指定 `python`。原管理器选中了 `/usr/bin/python3`（3.9.6），在 bridge 启动时因缺少 `os.waitid` 失败，尽管 `/opt/homebrew/bin` 中已有兼容解释器。

修复后，候选必须通过真实解释器版本和 POSIX `waitid` 探测；旧版本继续跳过。补充版本化 Python 名称发现，保留虚拟环境路径，并去重实际相同的可执行文件。显式指定不兼容解释器会直接报告版本要求。探测采用隔离模式，丢弃输出，每个候选限时 3 秒。

同一受限 PATH 复测已收到 `RUNTIME_FRAME_OK: 1080x1920 frames=3`。新增回归覆盖缺失/不兼容候选回退、显式不兼容路径拒绝、只有版本化名称的解释器发现。运行时随后完成正常及强杀清理测试。

## 复现与本机证据

```sh
sh test-phone.sh check
sh test-phone.sh quick
sh gradlew build runtimeSelfTest -PruntimeTestPython=/opt/homebrew/bin/python3 --no-daemon
sh test-phone.sh pattern --world-smoke
sh test-phone.sh native-probe
sh test-phone.sh runtime-smoke --set backend=qemu --set bios=true --set guestArch=amd64 --set accel=tcg
sh test-phone.sh runtime-smoke --set backend=qemu --set bios=true --set guestArch=arm64 --set accel=hvf --set firmware=/opt/homebrew/share/qemu/edk2-aarch64-code.fd
sh test-phone.sh qemu --guest-arch arm64 --set bios=true --set accel=hvf --set firmware=/opt/homebrew/share/qemu/edk2-aarch64-code.fd --world-smoke --warmup 30
PATH=/usr/bin:/bin:/usr/sbin:/sbin /opt/homebrew/bin/python3 scripts/dev.py runtime-smoke --set python= --set backend=pattern
```

以下证据留在本机，不纳入 Git：

- 汇总日志、能力探测、产物校验和进程核对：`.runtime/evidence/mac-mini-20260908/`。
- 图案三张截图：`.runtime/evidence/4e4ef8d9-3206-4df3-80c9-909e9e95df5b/`。
- ARM 固件两张游戏截图：`.runtime/evidence/e5d21049-74cd-482d-9786-694a41e13058/`。
- 图案会话：`run/mcandroidphone/sessions/99c3a61c-73d8-4113-a6cf-4d4c8c5b8823/`。
- ARM 游戏会话：`run/mcandroidphone/sessions/6120b6a9-f678-4562-9d42-84b9235b1368/`。

QEMU 正常关机时，bridge 可能先看到 VNC 断开并以 1 退出，日志显示 `VNC server disconnected`；会话管理器仍记录 `stopped`、`error=null`。这与运行期间断连不同，检查时应结合会话状态和进程是否残留，不能只搜索日志中的 `failed`。

## 下一阶段

准备 ARM64 Android 镜像及匹配的引导文件，确认支持 QEMU `virt`、virtio GPU/网络/磁盘。磁盘启动需要可用的 ARM64 UEFI 引导链；直接内核启动需要对应 kernel、initrd 和明确的 `kernelAppend`。配置模板见 `configs/mac-arm64.properties.example`。SDK 镜像、手机 ROM 和 Android-x86 ISO 不能直接视作这套 ARM64/HVF 配置的兼容镜像。

待验收：真实 Android 桌面、Android 输入与导航、长时间运行及性能；QEMU IOSurface 跨进程导出/同步/Java 导入尚未实现。探针通过只证明同进程 Metal/IOSurface/OpenGL 互操作能力。
