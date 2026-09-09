# 开发测试入口

`test-phone.sh` / `test-phone.cmd` 使用 Java 25 执行 `scripts/Dev.java`，默认启动诊断图模式。普通安装直接在游戏中右键手机，配置见 [运行环境](runtime.md)。

| 命令（Windows 换为 test-phone.cmd） | 用途 |
| --- | --- |
| `sh test-phone.sh check` | 显示 Java 和宿主架构 |
| `sh test-phone.sh quick` | 独立编译 Java 核心，验证协议、几何及真实进程清理，无需 Gradle 或 Python |
| `sh test-phone.sh build` | 构建 NeoForge 模组 |
| `sh test-phone.sh pattern --world-smoke` | Java 诊断图，自动验证游戏内显示和触控 |
| `sh test-phone.sh qemu` | 进入游戏后启动已配置的 Android |
| `sh test-phone.sh qemu --android-smoke --warmup 60` | 需已初始化 Android；创建测试世界，投影鼠标滑动解锁、截图、收纳与重载 |
| `sh test-phone.sh qemu --gpu virgl` | Windows 实验 D3D11 共享纹理 |
| `sh test-phone.sh bios --world-smoke` | x86 BIOS 固件诊断，跨架构自动 TCG |
| `sh test-phone.sh runtime-smoke --set backend=pattern` | 不启动 MC，直接测试 Java 运行时画面 |

通过 `--set KEY=VALUE` 设置运行参数，路径包含空格时给整个参数加引号。`--guest-arch arm64`、`--gpu virtio`、`--warmup 30` 为便捷选项。ARM 固件示例：

```sh
sh test-phone.sh qemu --guest-arch arm64 --set bios=true --set accel=hvf --set firmware=/opt/homebrew/share/qemu/edk2-aarch64-code.fd --world-smoke --warmup 30
sh test-phone.sh runtime-smoke --set backend=qemu --set bios=true --set guestArch=arm64 --set accel=hvf --set firmware=/opt/homebrew/share/qemu/edk2-aarch64-code.fd
```

`--world-smoke` 创建独立超平坦世界，发手机并走真实物品入口，检查画面、倾斜、旋转、背包恢复及资源重载，截图后退出。诊断图额外验证按下/松开；QEMU 固件测试不证明 Android 触控通过。预热最长 120 秒，可能仍截到开机动画。

`--android-smoke` 隐含 `--world-smoke`，仅用于 qemu 模式，额外发送经手机投影的鼠标按下、拖动和松开。事件拦截通过不等于来宾已解锁，应再检查 Android 锁屏/前台窗口与截图；已通过的镜像和独立来宾证据见 [ARM64 Android 验收](android-arm64-validation.md)。

截图路径为 `.runtime/evidence/<UUID>/`，世界在 `run/saves/`，原生运行日志在 `run/mcandroidphone/sessions/<UUID>/`。第一次完整构建可能下载 Gradle/Minecraft，quick 无需这些下载。

Gradle 也提供 `build runtimeSelfTest` 和 `runtimeSmoke`；后者通过 `-Pruntime.backend=pattern`、`-Pruntime.guestArch=arm64` 等传参。

旧 Python 协议/SDK 诊断需显式执行 scripts 内相应 Python 文件，详见 [scripts/README](../scripts/README.md)。它们不参与普通游戏运行或 Java quick 测试。
