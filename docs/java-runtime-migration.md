# Java 运行时迁移 · 2026-09-08

生产后端已从 Python 迁至 Java 25，复用 Minecraft 的 Java。玩家无需 Python；JAR 不再包含 bridge.zip 或任何 .py 文件。此次变更未提交、推送或发布，GitHub 上此前的预览版仍是旧实现。

## 变更范围

- ManagedRuntime 在 JVM 内管理 pattern/QEMU 后端，QMP、VNC/RFB、D-Bus 编解码、触摸/导航/文本输入均迁入 Java。
- PhoneConnection 统一内部视图与显式外部 BridgeClient。断开视图保留 QEMU，重连和尺寸变化使用新 epoch；CPU 直接缓冲区有租约，仍在渲染的旧帧不会被覆盖。
- Java FFmpeg 转换器限制一帧在途；Windows D-Bus 支持共享 surface 和像素消息，D3D11 保留 handle/元数据通道，渲染器释放租约后才回复 QEMU。
- native-guard.jar 仅包含 NativeGuard 和 JSON 辅助类。守护进程复用 java.home，在 POSIX 独立进程组或 Windows Job 内启动原生子进程，并独立监视父 JVM。正常关闭先停显示、校验 QEMU UUID、发送 QMP quit；阻塞管道和异常退出由守护进程回收。
- test-phone.sh / test-phone.cmd 改用 scripts/Dev.java；quick 可独立编译运行，不要求 Gradle、Minecraft 或 Python。CI 的 Java 主测试作业与旧 Python 外部诊断分开。
- bridge/ 与 Python 脚本保留作外部兼容诊断和回归对照，不进入发行产物。SDK Emulator 仍是外部诊断入口。

Windows socket sharing 使用 WSADuplicateSocketW，并禁止本地 socket 被后续子进程继承；共享描述交给指定的 QEMU PID。[Microsoft API 说明](https://learn.microsoft.com/en-us/windows/win32/api/winsock2/nf-winsock2-wsaduplicatesocketw)

## Mac mini 实测

环境：Apple M4 Mac mini，24 GiB，macOS 27.0；Zulu Java 25.0.2、QEMU 11.1.1、FFmpeg 9.0.1。以下是本机测试，不代表远端 CI 或 Windows 实机结果。

| 检查 | 结果 |
| --- | --- |
| 完整 Gradle 构建 | build、核心 1096 条断言、几何、runtimeSelfTest 通过 |
| 无 Python PATH 的 quick | 只在 PATH 放 Java/dirname，协议、缓冲区和真实进程回归通过 |
| Java 协议 | RFB 3.3/3.7/3.8、Raw/重叠 CopyRect/DesktopSize、非法矩形；QMP 事件/ID 隔离；JSON 边界通过 |
| D-Bus | 与原 Python 实现生成的金样本逐字节一致；匿名 peer 握手、双向调用、消息边界、surface stride/dirty update 通过 |
| 平台参数 | Mac HVF、跨架构 TCG、Windows WHPX/D-Bus、Linux KVM、只读快照、ARM 直接内核与逗号路径通过 |
| 生命周期 | 重复启动、断开重连、10 次启动取消、启动失败、正常清理、阻塞 stdin、父 JVM 强杀及子孙进程回收通过 |
| x86 BIOS / TCG | 无 Python PATH，收到 720×400 NV12 帧，正常结束 |
| ARM64 UEFI / HVF | 无 Python PATH，收到 640×480 NV12 帧，QMP 正常退出，QEMU exitCode=0 |
| Minecraft 诊断图 | 1080×1920 画面、触控、悬停倾斜、外缘旋转、背包恢复和资源重载通过；截图已检查 |
| Minecraft ARM 固件 | HVF + 只读 ARM UEFI，30 秒预热后显示 UEFI Shell；背包恢复、资源重载及退出通过；截图已检查 |
| 保留的旧诊断 | Python bridge 113 项：112 通过、1 跳过；scripts 76 项：74 通过、2 跳过 |

最终核对 32 份 Java 会话、89 个记录的自有 PID、36 个进程组，均无残留进程或组成员。游戏世界测试的诊断图与固件分别记录 296 和 12 次帧上传；这是诊断计数，不是 Android 帧率基准。

产物 `build/libs/mcandroidphone-0.1.0-prototype.jar`：213759 字节，72 个 Java class 均与编译输出一致，Python 文件数为 0。SHA-256：`cbdff1ace93ff549a373f70606fbb4abeb86dceabf2ce1c2c5a0cc03d5be470c`。

## 本机证据与复现

日志和产物/进程核对：`.runtime/evidence/java-migration/`，其中 `build-package.log` 为最终打包回归，`build-final.log` 包含真实 ARM 固件与正常关闭测试，`no-python-quick.log`、`x86-firmware-no-python.log`、`arm-firmware-no-python-final.log` 记录无 Python PATH 验证。

- 诊断图世界：`.runtime/evidence/8517f21f-4c18-44d3-b797-e8eab1cc97ac/`。
- ARM 固件世界：`.runtime/evidence/b55b30c5-b3b6-46c7-8c52-1e98e0243ce9/`。
- `process-audit.json` 列出各个会话的实际路径；`artifact-audit.json` 记录最终 JAR 核对。
- 迁移前的未提交工作备份在 `.runtime/java-migration-baseline/`，未覆盖存档或镜像。

```sh
sh test-phone.sh quick
sh gradlew build runtimeSelfTest
sh test-phone.sh pattern --world-smoke
sh test-phone.sh qemu --guest-arch arm64 --set bios=true --set accel=hvf --set firmware=/opt/homebrew/share/qemu/edk2-aarch64-code.fd --world-smoke --warmup 30
```

## 尚未验收

Windows AF_UNIX/Job、D-Bus 共享句柄和 D3D11 的 Java 实现需要 Windows 实机回归；Mac 上的编解码测试不能验证这些原生 API。Linux 原生 Java guardian 也尚未在本轮实机运行。CI 配置已更新，但没有推送或运行远端 CI。

本次迁移基线仅完成固件显示；后续已完成真实 ARM64 Android 桌面、Java 触控和 Minecraft 投影滑动解锁，见 [独立验收记录](android-arm64-validation.md)，不改变上表的历史测试范围。Mac/Linux 的生产 GPU 零拷贝通路仍未实现。QEMU、FFmpeg/ANGLE、固件和 Android 镜像仍需外置，数据盘仍使用临时快照。配置见 [runtime](runtime.md) 与 [已验证的 LineageOS 模板](../configs/mac-lineage-arm64.properties.example)。
