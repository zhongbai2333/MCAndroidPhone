# Mac mini M4 交接

2026-09-13 AMD64 转译补充：Digitalis 已完成完整镜像构建，纯 ARM64 JNI 应用及重启后验收通过；实验 ZIP 可在 0.2.1 镜像管理中导入。M4 应继续使用原生 ARM64 官方包，不需要此 AMD64 转译镜像。详见 [转译验收](../android/native-bridge/README.md)。

2026-09-13 镜像管理更新：0.2.1 已加入 zstd 及“选项 → 安卓镜像…”；Windows、Linux、macOS CI 的私有原生解码和镜像库测试通过。M4 仍需验证完整 Go ARM64 安卓启动和交互，不能将解码 CI 当作 HVF/图形验收。见 [镜像管理](image-manager.md)。

2026-09-13 新增：现在可以通过一个通用 JAR 加两套独立 Android Go 下载包接续开发。M4 可以复用发布包的 QEMU 和镜像，无需保持 Windows 开机或同步整个 Android 源码；步骤及本轮尚待验收项目见 [双架构下载版](release-downloads.md)。以下记录仍用于区分此前社区镜像的实测结果。

M4 Mac mini 已通过 Java 核心/生命周期、完整构建、图案和固件测试，并完成 Android 16 / LineageOS ARM64 的启动、触控及游戏内滑动解锁、桌面显示、收纳和资源重载，详见 [真实 Android 验收](android-arm64-validation.md)。Mac 默认走 VNC → Java → FFmpeg → NV12 → OpenGL 的 CPU 路径；IOSurface 同进程探针已通过，但 QEMU 的 IOSurface 跨进程后端尚未实现。

## 在 M4 上开始

Java 迁移后，玩家和常规开发测试只需 Java 25、QEMU、FFmpeg，无需 Python。已有 Homebrew 时可安装 `openjdk@25 qemu ffmpeg`；原生探针需要 Xcode Command Line Tools。最新代码与旧发布版的范围见 [Java 迁移记录](java-runtime-migration.md)。

```sh
git clone https://github.com/zhongbai2333/MCAndroidPhone.git
cd MCAndroidPhone
export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
sh test-phone.sh check
sh test-phone.sh quick
sh test-phone.sh pattern --world-smoke
sh test-phone.sh native-probe
```

`quick` 不需要 Minecraft 或 Android，只编译独立 Java 核心、运行 Java 协议测试和真实进程清理测试。`pattern --world-smoke` 首次需要下载 Gradle/Minecraft，之后自动创建独立世界验证物品、悬停倾斜、外缘旋转、触控、背包恢复及资源重载。所有运行依然由 Mod 管理器启动。截图位于 `.runtime/evidence/<UUID>/`。正常游戏：`sh test-phone.sh pattern`。

## 无需 Gradle 的预览版

新的通用下载版见 [开发版 Release](https://github.com/zhongbai2333/MCAndroidPhone/releases/tag/v0.2.1-dev.20260913)。发布 JAR 可放入已安装的 Minecraft 26.1.2 / NeoForge 26.1.2.76；本地 `sh gradlew build` 仍输出不内置平台运行时的裸 JAR。它复用游戏的 Java 25；测试图只需 `backend=pattern`。此前 GitHub 预览版仍是旧 Python 实现，不能用来验收移除 Python 依赖。

此前 CI 干净环境受 NeoForge 依赖元数据端点 HTTP 502 阻断。本次 M4 本机已完成 Gradle 构建，使用了已有的部分依赖缓存；该段描述历史状态；2026-09-13 新代码的三平台核心检查与远端完整 Mod 构建已通过。`quick` 和安装好的游戏使用预览版 JAR 不依赖这个构建端点。[详细验证记录](portable-validation.md)

## QEMU 分阶段验收

先确认管理器可以启动 QEMU，不依赖 Android 镜像：

```sh
sh test-phone.sh bios --world-smoke
# 上面是 x86 BIOS + TCG；M4 上验证 ARM UEFI：
sh test-phone.sh runtime-smoke --set backend=qemu --set bios=true \
  --set guestArch=arm64 --set accel=hvf \
  --set "firmware=$(brew --prefix qemu)/share/qemu/edk2-aarch64-code.fd"
```

固件文件以本机 QEMU 包实际内容为准。BIOS/UEFI 出图只证明启动与传输链路，不代表 Android 已兼容。

M4 的 HVF 必须搭配 ARM64 客体。`configs/mac-arm64.properties.example` 列出需要准备的文件；当前没有随仓库提供可直接启动的 ARM64 Android 镜像。镜像必须支持 QEMU `virt`、virtio 显示/网络/磁盘及对应引导方式，不能直接把 Android-x86 ISO 或任意手机 ROM 当作 ARM 客体。直接内核启动必须给出该镜像自己的 `kernelAppend`；磁盘启动必须提供匹配的 ARM64 UEFI 固件。[QEMU virt 文档](https://www.qemu.org/docs/master/system/arm/virt.html)

已验证的社区 LineageOS UTM 镜像使用 [专用模板](../configs/mac-lineage-arm64.properties.example)，需要系统盘、数据盘和明确格式的 EFI 变量文件；镜像来源、校验值及本机直接启动方法见 [Android 验收记录](android-arm64-validation.md)。

已有 Android-x86 9.0-r2 ISO、同版 kernel/initrd 时，可先用 M4 的 TCG 跑 x86 功能测试：

```sh
sh test-phone.sh qemu --guest-arch amd64 --set accel=tcg \
  --set iso=/absolute/path/android-x86_64-9.0-r2.iso \
  --set kernel=/absolute/path/kernel --set initrd=/absolute/path/initrd.img
```

这条路径较慢，不能据此评价 ARM64/HVF 的性能。所有磁盘使用临时快照，退出后不保留客体更改。QEMU 版本不支持 `virtio-multitouch-pci` 时自动回退鼠标输入，日志会明确显示；真实触屏需要支持该设备的 QEMU。

## GPU 下一步

`native-probe` 先验证 Metal 写入 IOSurface → OpenGL 导入 → GPU 缓存复制的同进程能力。探针会读回一个诊断像素校验颜色；生产目标是零 CPU 像素复制、一次 GPU 缓存复制。返回 77 表示能力不可用，返回 1 表示测试错误，只有 PASS 才证明探针通过。

M4 上已验证 Metal → IOSurface → OpenGL 导入和 GPU 缓存复制，诊断像素为 `64,128,191,255`，`GLerror=0`。仍需完成 QEMU 显示生产端 IOSurface 导出、跨进程对象传递、帧生命周期与同步、Java OpenGL 导入、重建与回收，以及真实 Android/MC 端到端测试。现有 QEMU D-Bus 协议未提供可直接复用的 IOSurface 通路，不能将探针通过写成零拷贝后端完成。Linux 同理，还需要 DMA-BUF FD、modifier 和同步协议。

优先带回以下信息：`check` 输出、`quick` 结果、pattern 三张截图、IOSurface 探针输出、Android 镜像来源/架构/引导参数，以及 `run/mcandroidphone/sessions/<UUID>/` 下的 原生 *.log、*.process.json、session.json。勿提交镜像、虚拟机数据或整套 Windows 运行时目录。
