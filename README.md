# MCAndroidPhone

**下载版与 Mac 接续开发：先读 [双架构下载版](docs/release-downloads.md)**。正在准备一个内置 Windows AMD64、macOS ARM64 原生运行时的通用 JAR；完整 Android Go 镜像保留 WebView，首次使用单独下载。最终产物与新 ARM64 镜像的 M4 实机验收仍在推进。

在 Minecraft 中使用真正运行的安卓手机。独立 NeoForge Mod，无需 NCPB、NetMusic 或 SceneEditor；目标为 Minecraft 26.1.2、NeoForge 26.1.2.76、Java 25。

**主手右键手机后，由 Java 在后台启动 QEMU 和所需的 FFmpeg，并直接处理画面与触控；玩家无需安装 Python。** 不必预先启动桥接脚本。普通构建使用外部运行时；下载版可自动校验、解压原生依赖，并下载、校验和缓存对应架构的 Android 镜像。手机数据按物品 UUID 持久保存。Mac ARM64 本地包与摄像头通道的实际验收范围见 [本机功能验收](docs/local-features.md)，六平台成品尚未全部生成。[配置与启动说明](docs/runtime.md)

M4 Mac mini 从 [迁移与验收步骤](docs/mac-handoff.md) 开始；实测范围见 [跨平台验证](docs/portable-validation.md)。Mac/Linux 默认采用 CPU 传输，GPU 探针通过不等于端到端零拷贝完成。

M4 已通过 Android 16 / LineageOS ARM64 的真实启动、Java 触控和游戏内滑动解锁、桌面显示测试，见 [Android 实测记录](docs/android-arm64-validation.md) 与 [匹配镜像的配置模板](configs/mac-lineage-arm64.properties.example)。

游戏位置/姿态联动已完成第一阶段的真实 Android API 验证；正式 HAL 接入和双架构 Go 镜像构建配置已加入，完整定制系统尚待 Linux 编译。见 [联动实现与验收边界](docs/game-environment.md)、[镜像构建](android/image/README.md)。

下载版版本为 `0.2.0-dev.20260913`，正在构建与验收；最终以 [GitHub Releases](https://github.com/zhongbai2333/MCAndroidPhone/releases) 的附件及说明为准。`v0.1.0-prototype` 是旧 Python 后端，不能代替新包测试。

## 快速开始

本机运行时已准备好，开发测试直接运行：

```bat
test-phone.cmd qemu
rem 或在已配置 Java 25 的终端运行
gradlew.bat runClient
```

进入创造世界，取出“安卓手机”，或执行 `/give @s mcandroidphone:android_phone`，拿在主手右键开机。默认 1080×1920、480 dpi；Windows 使用 D-Bus，Mac/Linux 使用 VNC；输入按 QEMU 能力选择触屏或鼠标。首次冷启动需等待安卓初始化；Live 镜像的数据在关机后丢弃。

```bat
test-phone.cmd qemu --qemu-gpu virgl
test-phone.cmd pattern --world-smoke
test-phone.cmd quick
```

第一条启用实验 GPU 共享纹理，零 CPU 像素复制、一次 GPU 缓存复制；默认 virtio 2D 使用 FFmpeg 转 NV12。[GPU 说明](docs/gpu.md)

第二条由 Mod 启动测试图，自动验证手机显示、触控、收纳恢复和资源重载。第三条只验证核心与协议，不启动 MC 或安卓。[测试入口](docs/quick-test.md)

## 操作

- 主手右键开机、连接并进入触控；左键点击或拖动。
- 指针悬停带动机身倾斜；拖动设备外缘旋转，松手吸附横/竖屏。使用 NCPB 的下方握持枢轴及横屏左移，独立运行。默认只旋转机身；启用可选环境通道后，也会把姿态发给匹配的 Android 接收服务。
- 右键返回，Home 回桌面，End 切换应用（QEMU 使用 Alt+Tab），Esc 退出触控。
- 收进背包、切换快捷栏后保留运行和画面，拿回主手直接显示。
- F8 或 `/androidphone disconnect` 断开画面，安卓继续运行。
- `/androidphone poweroff` 关闭本 Mod 启动的运行环境；退出世界或游戏也会清理。
- `/androidphone runtime` 查看启动状态和日志目录。
- `/androidphone environment` 查看可选游戏环境通道状态。

## 一个主代码项目

Java `core` 和 `mod` 已合并为根 Gradle 项目，不再分别构建。协议层仍保持不依赖 Minecraft 的包边界。

| 目录 | 内容 |
| --- | --- |
| `src/main/java/.../core` | Java 协议、缓冲和运行时管理 |
| `src/main/java/.../phone`、`gpu` | 独立物品、触控、显示与 GPU 导入 |
| `src/main/resources` | 模组元数据、模型和 shader |
| `src/test/java` | 核心、几何和真实进程生命周期测试 |
| `bridge/mcandroid_bridge` | 旧协议/SDK 外部诊断与回归对照，不进入 JAR |
| `scripts` | 开发测试、环境准备和兼容诊断工具 |
| `docs` | 配置、协议、架构和验证记录 |

构建：`gradlew.bat build`。产物：`build/libs/mcandroidphone-0.2.0-dev.20260913.jar`。客户端和服务器均安装该 JAR，原生模拟器仅在客户端运行。[独立版说明](docs/standalone.md)

本次整理将原模块目录和重复启动辅助文件备份到 `.runtime/layout-backup-20260908/`，方便回查；它们不参与构建，也不进入发行 JAR。

## 验证与限制

```powershell
.\gradlew.bat build runtimeSelfTest
$env:PYTHONPATH = Join-Path $PWD 'bridge'
.\.venv\Scripts\python.exe -m unittest discover -s bridge/tests -q
.\.venv\Scripts\python.exe -m unittest discover -s scripts/tests -q
```

`runtimeSelfTest` 和 `test-phone.cmd quick` 只需要 Java，验证 QMP/RFB/D-Bus、帧租约、启动取消、阻塞管道及 JVM 强杀后的子孙进程清理。上面的 Python 命令仅用于旧外部诊断回归。[Java 迁移与实测](docs/java-runtime-migration.md)

当前不支持音频、多指和多人共享。手机数据已支持按物品 UUID 持久保存，正常关机仍需镜像配合，详见 [关机契约](docs/guest-shutdown.md)。普通模组构建使用外置 Android 镜像、QEMU/DLL/固件及 FFmpeg/ANGLE；平台内置候选另行打包，尚未六平台发布。Windows D-Bus/D3D11 Java 路径已通过此前 Go 镜像实机回归；新发布镜像另行验收。SDK Emulator 保留外部诊断入口；VNC/BIOS 也可由管理器启动。

项目代码为 MIT；第三方原生运行时和系统镜像遵循各自许可证。
