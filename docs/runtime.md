# JAR 管理运行环境

客户端第一次右键手机时创建 `config/mcandroidphone-runtime.properties`，在后台启动内置 Python 管理模块，再启动 QEMU 和 bridge。默认 CPU 路径由 bridge 启动 FFmpeg；GPU 路径使用 ANGLE，不启动 FFmpeg。游戏主线程不会等待安卓开机。

## 安装与配置

安装 `build/libs/mcandroidphone-0.1.0-prototype.jar` 及匹配的 NeoForge。原生依赖只需在客户端准备一次，不需要开发仓库、Gradle 或 `scripts/` 目录。自动启动已加入 Windows/Linux/macOS 与 AMD64/ARM64 选择。Windows/Linux 需要 Python 3.10+，Mac 需要 Python 3.13+；跨平台实测范围见 [验证记录](portable-validation.md)，Mac 准备见 [交接文档](mac-handoff.md)。

配置是 UTF-8 Java properties，路径使用正斜杠。以本机已准备好的运行时为例：

```properties
backend=qemu
root=D:/UserFile/Documents/GitHub/MCAndroidPhone/.runtime
python=D:/UserFile/Documents/GitHub/MCAndroidPhone/.venv/Scripts/python.exe
gpu=virtio
ffmpeg=E:/Program Files/ffmpeg/bin/ffmpeg.exe
width=1080
height=1920
density=480
memory=4096
cpus=2
accel=auto
```

配置修改在下一次开机时生效；先 `/androidphone poweroff`，待关闭后再右键。未指定 root 时默认 `<游戏目录>/mcandroidphone/runtime`；开发 `runClient` 默认项目 `.runtime`。其余相对文件路径相对于 root。

| 配置 | 默认发现规则或用途 |
| --- | --- |
| `python` | root/python/python.exe、root/python.exe、root 相邻 .venv、PATH（跳过 WindowsApps） |
| `qemu` | root/qemu/bin/qemu-system-x86_64.exe、root/qemu/qemu-system-x86_64.exe、PATH |
| `iso` | root/images 下唯一 ISO；多个候选需显式指定 |
| `kernel`、`initrd` | ISO 同名目录中的 kernel、initrd.img，必须来自同一镜像 |
| `disk`、`diskFormat` | 已有磁盘，格式默认 qcow2；写入临时快照 |
| `ffmpeg` | root/ffmpeg/bin/ffmpeg.exe、root/ffmpeg/ffmpeg.exe、PATH |
| `angle` | GPU 模式默认 root/angle/bin，需 libEGL.dll 和 libGLESv2.dll |
| `gpu` | virtio 默认 CPU 路径；virgl 为实验 D3D11 共享纹理路径 |
| `backend` | qemu 默认；pattern 为无需模拟器的测试图 |

完整保留 QEMU 的 DLL、固件和模块。只提供 ISO 时需准备同名目录中的 kernel/initrd.img 才能跳过引导菜单自动进入 Android；当前不会下载镜像或提取内核。直接内核启动参数针对 Android-x86 Live 镜像。已有磁盘也使用临时快照，本次修改在关机后丢弃。

启用 GPU：设置 `gpu=virgl`，准备支持 D-Bus/VirGL/OpenGL 的 QEMU 和 ANGLE；本机已具备。详见 [GPU 说明](gpu.md)。

## 跨平台选项

- `guestArch=amd64|arm64`：默认宿主原生架构；ARM64 使用 QEMU virt/virtio 设备。
- `accel=auto|tcg|whpx|kvm|hvf`：根据系统与客体架构验证；跨架构只能用 TCG。
- `display=auto|vnc|dbus`：Windows 默认 D-Bus；Linux/Mac 默认 VNC。Unix D-Bus FD 传输尚未实现。
- `gpu=virgl`：目前仅支持 Windows 已验证的共享纹理后端。其他宿主会给出明确错误。
- `firmware`：ARM64 磁盘/UEFI 启动的只读固件；`kernelAppend`：显式直接内核参数，作为单一参数传给 QEMU。
- `input=auto|touchscreen|mouse`：自动检查 QEMU 设备支持，旧版会明确回退 mouse。
- `bios=true`：无系统镜像的启动诊断；ARM64 仍需固件。

Unix 可从 PATH 查找 `qemu-system-x86_64`/`qemu-system-aarch64`、`ffmpeg`，Python 还会查找 `.venv/bin/python3` 及 Homebrew 路径。非 Windows 的 `gpu=virtio` 使用 VNC → FFmpeg → NV12 CPU 路径，不是 GPU 零拷贝。

## 生命周期

右键开机 → 后台准备独立会话 → 启动 QEMU → 启动 bridge → MC 接收画面。桥接就绪不代表安卓已进入桌面；启动期间可看见开机动画。

收纳手机或退出触控保留连接和安卓。F8、disconnect、物品离开库存只断开画面；`poweroff`、退出世界、退出游戏关闭本 Mod 启动的整个会话。`/androidphone runtime` 和 `/androidphone status` 可查看状态；失败后显示原因，下次右键可重试。

每次开机使用 `<游戏目录>/mcandroidphone/sessions/<UUID>/`，内含 runtime.log、qemu.log、bridge.log、session.json 和实际启动配置。管理器从 JAR 的 bridge.zip 加载代码，不依赖仓库内脚本。Windows Job 跟踪启动门之后创建的所有子进程；JVM 的 stdin 管道关闭后触发清理，JVM 被强制结束也适用。POSIX 为每个子进程建立独立进程组与生命周期管道；先保留组长 PID、再清理组内子孙进程，避免误杀复用 PID。正常关机先校验自身 QEMU UUID 再发送 QMP quit，随后关闭 Job。临时帧映射和发现配置在清理时删除，诊断日志保留。

启动有超时，单个日志超过 64 MiB 时结束本会话。未实现日志历史自动轮转；需要时可在关机后清理旧 sessions。不会关闭外部已运行的模拟器或任意同名进程。

## 开发与兼容入口

```bat
gradlew.bat runClient
gradlew.bat runClient -PphoneRuntimeGpu=virgl
gradlew.bat runClient -PphoneRuntimeBackend=pattern
```

其他覆盖项为 `-PphoneRuntimePython=...`、`-PphoneRuntimeIso=...` 等，配置优先级为 JVM/Gradle 覆盖 > properties > 默认值。普通启动器不需要这些 Gradle 参数，编辑配置文件即可。

显式传入 JVM 参数 `-Dmcandroidphone.config=绝对路径/bridge.properties` 时使用外部桥接，不启动或接管该外部运行环境。开发对应 `-PbridgeConfig=...`。SDK Emulator 诊断由外部工具管理；VNC 和 BIOS 已支持内部管理，见 [测试说明](quick-test.md)。

JAR 已内置桥接和启动逻辑；原生 QEMU、Python 解释器、FFmpeg/ANGLE 和 Android 镜像仍外置。后续可加入按平台校验、解压原生运行时的逻辑，当前尚未实现。
