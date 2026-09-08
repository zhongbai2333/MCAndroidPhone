# MCAndroidPhone

在 Minecraft 中使用真正运行的安卓手机。独立 NeoForge Mod，无需 NCPB、NetMusic 或 SceneEditor；目标为 Minecraft 26.1.2、NeoForge 26.1.2.76、Java 25。

**主手右键手机后，由 JAR 在后台启动 QEMU、内置 bridge 和所需的 FFmpeg，再连接画面与触控。** 不必预先启动桥接脚本。自动管理已加入 Windows/Linux/macOS 与 AMD64/ARM64 平台选择，使用已有的原生运行时和 Android 镜像；这些二进制仍需在本机准备一次。[配置与启动说明](docs/runtime.md)

M4 Mac mini 从 [迁移与验收步骤](docs/mac-handoff.md) 开始；实测范围见 [跨平台验证](docs/portable-validation.md)。Mac/Linux 默认采用 CPU 传输，GPU 探针通过不等于端到端零拷贝完成。

可直接下载 [预览版 JAR](https://github.com/zhongbai2333/MCAndroidPhone/releases/tag/v0.1.0-prototype)。

## 快速开始

本机运行时已准备好，开发测试直接运行：

```bat
test-phone.cmd
rem 或在已配置 Java 25 的终端运行
gradlew.bat runClient
```

进入创造世界，取出“安卓手机”，或执行 `/give @s mcandroidphone:android_phone`，拿在主手右键开机。默认 1080×1920、480 dpi、D-Bus 直接显示、真实单指触屏。首次冷启动需等待安卓初始化；Live 镜像的数据在关机后丢弃。

```bat
test-phone.cmd qemu --qemu-gpu virgl
test-phone.cmd pattern --world-smoke
test-phone.cmd quick
```

第一条启用实验 GPU 共享纹理，零 CPU 像素复制、一次 GPU 缓存复制；默认 virtio 2D 使用 FFmpeg 转 NV12。[GPU 说明](docs/gpu.md)

第二条由 Mod 启动测试图，自动验证手机显示、触控、收纳恢复和资源重载。第三条只验证核心与协议，不启动 MC 或安卓。[测试入口](docs/quick-test.md)

## 操作

- 主手右键开机、连接并进入触控；左键点击或拖动。
- 指针悬停带动机身倾斜；拖动设备外缘旋转，松手吸附横/竖屏。使用 NCPB 的下方握持枢轴及横屏左移，独立运行。此操作旋转物理机身，不向 Android 注入方向传感器。
- 右键返回，Home 回桌面，End 打开最近任务，Esc 退出触控。
- 收进背包、切换快捷栏后保留运行和画面，拿回主手直接显示。
- F8 或 `/androidphone disconnect` 断开画面，安卓继续运行。
- `/androidphone poweroff` 关闭本 Mod 启动的运行环境；退出世界或游戏也会清理。
- `/androidphone runtime` 查看启动状态和日志目录。

## 一个主代码项目

Java `core` 和 `mod` 已合并为根 Gradle 项目，不再分别构建。协议层仍保持不依赖 Minecraft 的包边界。

| 目录 | 内容 |
| --- | --- |
| `src/main/java/.../core` | Java 协议、缓冲和运行时管理 |
| `src/main/java/.../phone`、`gpu` | 独立物品、触控、显示与 GPU 导入 |
| `src/main/resources` | 模组元数据、模型和 shader |
| `src/test/java` | 核心、几何和真实进程生命周期测试 |
| `bridge/mcandroid_bridge` | Python 后端与进程管理源码，构建时打入同一 JAR |
| `scripts` | 开发测试、环境准备和兼容诊断工具 |
| `docs` | 配置、协议、架构和验证记录 |

构建：`gradlew.bat build`。产物：`build/libs/mcandroidphone-0.1.0-prototype.jar`。客户端和服务器均安装该 JAR，原生模拟器仅在客户端运行。[独立版说明](docs/standalone.md)

本次整理将原模块目录和重复启动辅助文件备份到 `.runtime/layout-backup-20260908/`，方便回查；它们不参与构建，也不进入发行 JAR。

## 验证与限制

```powershell
.\gradlew.bat build runtimeSelfTest
$env:PYTHONPATH = Join-Path $PWD 'bridge'
.\.venv\Scripts\python.exe -m unittest discover -s bridge/tests -q
.\.venv\Scripts\python.exe -m unittest discover -s scripts/tests -q
```

`runtimeSelfTest` 需要 Python（Mac 要求 3.13+），验证启动、取消、失败、正常清理及 JVM 被强制结束后的子进程清理。[验证记录](docs/validation.md)

当前只管理一台本机设备，不支持音频、多指、多人共享或持久化手机数据盘。Android 镜像、QEMU/DLL/固件、Python 解释器及 FFmpeg/ANGLE 仍外置；bridge 源码和管理逻辑已经内置 JAR。SDK Emulator 保留外部诊断入口；VNC/BIOS 也可由管理器启动。

项目代码为 MIT；第三方原生运行时和系统镜像遵循各自许可证。
