# 开发测试入口

`test-phone.cmd` 默认等同 `test-phone.cmd qemu`：查找开发用 Java/Python 并启动 Minecraft。真正的 QEMU、bridge 和 FFmpeg 由 Mod 在右键手机时启动。普通游戏安装不需要该脚本；配置见 [JAR 运行环境](runtime.md)。

| 命令 | 用途 |
| --- | --- |
| `test-phone.cmd` | 默认 QEMU，进入世界后右键开机 |
| `test-phone.cmd qemu --qemu-gpu virgl` | JAR 管理的实验 GPU 模式 |
| `test-phone.cmd pattern --world-smoke` | JAR 启动测试图，自动验证显示与触控 |
| `test-phone.cmd quick` | 无 MC/模拟器的核心与协议检查 |
| `test-phone.cmd check` | 只检查依赖，不启动服务 |
| `test-phone.cmd android` | SDK Emulator 兼容诊断，由脚本预启动 |
| `test-phone.cmd qemu --qemu-bios --world-smoke` | 外部管理的固件显示诊断 |

已缓存环境不需重新构造。第一次开发构建可能下载 Gradle/Minecraft；SDK 后端可能安装其 Python gRPC 依赖。项目不自动下载 Android 系统镜像或修改系统环境变量。

## 自动验证

`--smoke` 仅启动到 MC 主菜单，当前托管模式在主菜单不会启动安卓。`--world-smoke` 自动创建独立超平坦世界，发手机并调用真实物品使用入口，检查画面、投影、收进背包 3 秒后无右键恢复、资源包重载，再截图退出。测试图另外验证一次按下/松开，QEMU 不自动点击 Android 应用。

两种自检参数互斥。默认取得可显示帧后即可结束，因此可能截到安卓开机动画；需要桌面验收可直接指定预热时间：

```bat
gradlew.bat runClient -PphoneWorldSmoke=true -PphoneRuntimeGpu=virgl -PphoneSmokeWarmupSeconds=85 -PphoneSmokeScreenshot=D:/test-evidence/phone.png
```

先创建截图父目录，并使用不存在的截图路径。预热不超过 120 秒；不保证所有机器的安卓均已完成开机。脚本自检日志位于 `.runtime/quicktest/<session>/`，游戏世界在 `run/saves/`；JAR 管理的运行日志在 `run/mcandroidphone/sessions/<UUID>/`。

## 指定依赖与兼容诊断

```bat
test-phone.cmd qemu --qemu-exe "D:/qemu/bin/qemu-system-x86_64.exe" --qemu-iso "D:/Android/phone.iso"
test-phone.cmd qemu --qemu-width 720 --qemu-height 1280 --qemu-density 320
test-phone.cmd qemu --external-bridge --qemu-display vnc
test-phone.cmd qemu --external-bridge --qemu-input mouse
test-phone.cmd qemu --external-bridge --qemu-gpu vga
```

托管模式支持 D-Bus、virtio/virgl、触屏与默认颜色策略；VNC、鼠标、标准 VGA、自定义颜色使用 `--external-bridge`。外部模式由脚本先启动模拟器和 bridge，并显式将连接配置传给 MC，退出时由脚本清理；它不是普通游戏启动的必经步骤。

SDK 测试使用现有 AVD 和 `--sdk`、`--avd` 参数，仍由外部工具管理。其他诊断入口见 [scripts/README](../scripts/README.md)。
