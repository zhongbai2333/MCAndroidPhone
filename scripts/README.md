# 开发与诊断入口

日常入口是 `../gradlew.bat runClient`，游戏内右键手机后由 JAR 启动运行环境。`../test-phone.cmd` 默认也只启动 MC，支持依赖路径发现和临时世界自检；不再预启动 QEMU/bridge。

- `quick-test.py`：MC 开发启动、无游戏快速检查。`--external-bridge` 明确启用旧协议诊断；SDK Emulator 和 BIOS 诊断保留外部模式。
- `transport-smoke.py`：直接编译 Java core 包，验证 TCP/mmap/触摸；不加载 Minecraft。
- `gpu-smoke.py`：验证真实 D3D11 纹理导入；`qemu-transport-smoke.py` 验证固件尺寸变化。这些是底层诊断，不是游戏运行依赖。
- `doctor.ps1`、`setup-angle.ps1`：检查或准备本机开发依赖。
- `start-emulator.ps1`、`emulator-probe.py`：旧 SDK Emulator 兼容诊断。
- `tests/`：进程所有权、命令转义和失败清理的回归测试，仍有实际用途。

QEMU 参数生成、Windows Job 和子进程门控已移入 `bridge/mcandroid_bridge/_launch/`，由运行代码和诊断共用。生产 JAR 不依赖本目录。
