# JAR 管理运行环境

主手右键手机后，Java `ManagedRuntime` 在后台启动设备，游戏主线程不等待安卓开机。画面、输入、配置、QMP/RFB/D-Bus 协议均在游戏 JVM 内处理。**普通玩家无需安装 Python**；模组复用 Minecraft 使用的 Java 25。

可选 `environment=true` 增加独立 VirtIO 环境通道，发布游戏位置和手机姿态；默认关闭，需配套的定制 Android 服务。现成镜像的开发验收及正式 HAL 尚待验证的范围见 [游戏环境联动](game-environment.md)。

## 安装与配置

普通构建 `build/libs/mcandroidphone-0.1.0-prototype.jar` 配合匹配的 NeoForge，需准备 QEMU、FFmpeg、Android 镜像等依赖。平台内置包另外包含对应架构的原生依赖、固件和 Android 媒体；未显式配置 disk/iso/kernel 时，Java 自动校验并解压到 `<游戏目录>/mcandroidphone/bundles/<平台>-<清单SHA256>/`。两种包都不要求开发仓库、Gradle、scripts 或 Python。制作与验收见 [本机功能验收](local-features.md)。

游戏手机默认持久保存：新手机把系统模板固定为 `mcandroidphone/devices/.bases/<SHA256>.<格式>` 中的共享底盘，在 `<物品UUID>/` 中创建初始 256 KiB 的 qcow2 系统差分盘；用户盘和 EFI 变量继续各自复制。底盘首次独立复制一次，之后同一模板复用，运行中的写入只进入手机差分盘。差分盘随系统写入增长，用户安装应用及照片仍占用用户盘空间。已有完整磁盘保持原样；升级不会自动替换底盘或迁移数据。一个 UUID 同时只允许一个写入实例。

底盘属于持久设备数据，独立于可重新解压的 `bundles` 缓存。迁移/备份时要带上整个 `devices` 目录，包括隐藏的 `.bases`；不能只拷贝某个 UUID 文件夹或删除正在引用的底盘。详见 [共享系统底盘](shared-system-disk.md)。

`storage=snapshot` 明确选择关闭后丢弃修改。开发 world-smoke 自动使用该模式；没有 deviceId 的独立测试运行时也默认快照。普通玩家无需配置 deviceId，右键流程自动传入物品身份。

首次运行创建 `config/mcandroidphone-runtime.properties`，使用 UTF-8 和正斜杠路径。例如 Mac ARM64：

```properties
backend=qemu
guestArch=arm64
accel=hvf
display=vnc
gpu=virtio
qemu=/opt/homebrew/bin/qemu-system-aarch64
ffmpeg=/opt/homebrew/bin/ffmpeg
firmware=/opt/homebrew/share/qemu/edk2-aarch64-code.fd
disk=/absolute/path/android-arm64.qcow2
diskFormat=qcow2
width=1080
height=1920
density=480
memory=4096
cpus=2
```

该例需要支持 QEMU virt/virtio 的 ARM64 Android 磁盘，不代表任意 ARM 镜像都能启动。已实测的 Android 16 / LineageOS 双磁盘配置见 [专用模板](../configs/mac-lineage-arm64.properties.example) 和 [Mac Android 验收](android-arm64-validation.md)。没有 Android 镜像时，可移除 `disk` 并使用 `bios=true` 验证固件。

配置修改在下一次开机生效；先 poweroff，关闭后再右键。root 默认 `<游戏目录>/mcandroidphone/runtime`，开发 runClient 默认项目 `.runtime`；其余相对路径相对于 root。

| 配置 | 用途或发现规则 |
| --- | --- |
| `qemu` | root/qemu/bin、root/qemu、PATH；Unix 还检查 Homebrew、/usr/local/bin、/usr/bin |
| `ffmpeg` | root/ffmpeg/bin、root/ffmpeg、PATH 及同样的 Unix 目录 |
| `iso` | 未指定媒体时查找 root/images 下唯一 ISO |
| `kernel`、`initrd` | x86 ISO 自动匹配其同名目录中的 kernel、initrd.img |
| `disk`、`diskFormat` | 独立 raw 或 qcow2 系统模板，新游戏物品默认使用固定底盘和独立差分盘 |
| `diskLayout` | overlay（默认）或 copy；只决定新手机的系统盘布局，已有手机沿用保存的格式 |
| `cpuModel` | QEMU CPU 型号；AMD64 默认 Nehalem，新构建的 AMD64 Go 镜像使用 SandyBridge。仅接受型号名，不接受附加 feature 参数 |
| `shutdownMethod` | qmp（默认）或 power-key；后者要求镜像配置长按电源直接关机。等待来宾确认，超时记录强制清理；见 [正常关机](guest-shutdown.md) |
| `dataDisk`、`dataDiskFormat` | 可选第二块 virtio 用户盘，同样按物品持久化。AMD64 双盘选择 Q35 和 virtio-net；新增 AMD64 Go 镜像尚待实机验证 |
| `storage` | persistent 或 snapshot；无设备 UUID 的测试入口默认 snapshot |
| `camera` | 默认 false；从手机模型的前/后镜头独立渲染世界，640×480、最高 10 fps，需要来宾接收器。尚未接入普通 Camera2 HAL |
| `cameraTransport` | 默认 network；为游戏相机 APK 提供每 VM 内部 `10.0.2.100:18765` 到本会话私有端口的转发。virtio 保留给定制原生接收器；两种前端互斥 |
| `qemuData` | 内置 QEMU 固件/ROM 目录，通过独立 `-L` 参数传入 |
| `angle` | Windows GPU 模式默认 root/angle/bin，需 libEGL.dll、libGLESv2.dll |
| `backend` | qemu 默认；pattern 为 Java 生成的诊断图 |
| `gpu` | virtio 为 CPU 路径；virgl 为 Windows 实验 D3D11 路径 |
| `guestArch` | amd64 或 arm64，默认宿主架构 |
| `accel` | auto、tcg、whpx、kvm、hvf；跨架构必须 TCG |
| `display` | auto：Windows 用 D-Bus，Mac/Linux 用 VNC；也可显式指定 |
| `input` | auto 检测 virtio-multitouch 支持；touchscreen 或 mouse 可显式指定 |
| `firmware` | ARM64 UEFI 的只读 pflash；也可使用直接内核引导 |
| `firmwareVars`、`firmwareVarsFormat` | 可选 EFI 变量模板，持久模式按手机保存，快照模式复制到独立会话；raw（默认）或 qcow2，UTM 的 .fd 也可能是 qcow2 |
| `adbPort` | 可选本机 ADB 转发端口，默认 0 关闭；启用时只绑定 127.0.0.1，映射来宾 5555，仍需来宾开启并授权 ADB |
| `kernelAppend` | ARM64 直接内核引导必须填写镜像匹配的参数 |
| `width`、`height`、`density` | 宽高通过 virtio 显示的 EDID 请求，实际尺寸由来宾决定；density 仅写入自动生成的 Android-x86 内核参数，磁盘引导的系统需使用镜像默认值或在来宾中设置显示密度 |
| `colorOrder` | rgb 或 bgr；默认匹配 ARM/固件及 Android-x86 的原有策略 |

旧配置中的 `python` 已无作用，可以删除。QEMU 的 DLL、固件和模块应完整保留。当前不会自动下载镜像、提取内核或安装原生依赖。ARM64 兼容性已验证到上述特定 LineageOS 镜像，其他镜像仍需分别验收。

## 显示与生命周期

Mac/Linux：QEMU VNC → Java RFB → FFmpeg → Java NV12 direct buffer → OpenGL。Windows CPU：QEMU D-Bus 共享 surface → Java → FFmpeg → 同一 NV12 队列。Windows GPU：D3D11 handle → Java/OpenGL，保留零 CPU 像素复制、一次 GPU 缓存复制。GPU 租约在渲染器释放后才回复 QEMU；重连重新注册监听器以取得静态桌面首帧。Windows AMD64 已完成本轮 Java 原生 D-Bus/D3D11、真实 Android 输入及进程回收验收，修复与边界见 [Windows 接续记录](windows-validation-2026-09-09.md)；其他 Windows 架构仍需分别验证。

CPU 帧用四个有引用计数的直接缓冲区，只缓存最新帧；尺寸变化和重连更新 epoch，旧帧不会覆盖新会话。每个 FFmpeg 转换器最多一帧在途。输入经过有界工作队列，不在游戏线程等待本地 I/O。

收纳手机保留运行和缓存；F8/disconnect 只断开视图，安卓继续运行；poweroff、退出世界或游戏关闭整个自有会话。

每个 QEMU/FFmpeg 由复用 `java.home` 的轻量 Java 守护进程持有。守护进程注册后才释放启动门；POSIX 独立进程组和 Windows Job 包含对应子孙进程。守护进程单独观察实际父 JVM，即使视频管道阻塞或游戏被强杀也能清理。正常关机先关闭显示、校验自有 QEMU UUID 并发送 QMP quit；强制回收时先终止守护进程，再关闭视频管道，避免管道写入阻塞关闭线程。不会按程序名称关闭其他进程。

日志位于 `<游戏目录>/mcandroidphone/sessions/<UUID>/`：`runtime.properties`、`session.json`、`qemu-command.json`、`*.process.json`、原生程序 `*.log`。记录保留 guardian/child PID、QEMU UUID 和退出状态。启动与转换有超时，单个日志上限 64 MiB；历史会话尚未自动轮转。

## 开发与兼容入口

```sh
sh test-phone.sh quick
sh test-phone.sh pattern --world-smoke
sh test-phone.sh qemu --set guestArch=arm64 --set bios=true --set firmware=/path/to/edk2-aarch64-code.fd
sh gradlew build runtimeSelfTest
```

Windows 使用 test-phone.cmd / gradlew.bat；入口同样只需 Java。覆盖优先级：JVM/Gradle > properties > 默认值，例如 `-PphoneRuntimeIso=...`。完整测试入口见 [quick-test](quick-test.md)。

显式 `-Dmcandroidphone.config=/path/to/bridge.properties`（开发 `-PbridgeConfig=...`）仍可连接外部旧桥接，此时不启动或接管外部进程。Python 目录仅保留作旧协议回归和 SDK Emulator 等外部诊断，不参与发行 JAR，也不是普通启动流程的一部分。

开发入口也支持 `test-phone.cmd qemu --runtime-config path/to/runtime.properties`（macOS/Linux 使用 `test-phone.sh`）。按命令行顺序应用配置，后面的 `--gpu` 或 `--set` 可覆盖文件值；不会改写已有手机数据。Windows Go 当前验收与本机配置见 [Windows Go 实机验收](windows-go-validation-2026-09-12.md)。
