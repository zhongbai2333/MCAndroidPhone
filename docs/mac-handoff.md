# Mac mini M4 交接

代码支持按宿主系统和架构选择运行时，Mac 实机验收尚待完成。Windows D3D11 GPU 链路已验证；Mac 默认走 VNC → FFmpeg → NV12 → OpenGL 的 CPU 路径。`native/macos/iosurface_probe.m` 是 IOSurface 能力探针，并非已经完成的 QEMU GPU 后端。

## 在 M4 上开始

在原生 ARM64 终端安装 Java 25、Python 3.13 或更新版本、QEMU 和 FFmpeg。已有 Homebrew 时可安装 `openjdk@25 python@3.13 qemu ffmpeg`；编译原生探针还需要 Xcode Command Line Tools。设置 Java 后确认 `java -version` 和 `python3 --version`，不要使用 macOS 自带的旧 Python。Python 3.13 起 macOS 才提供本项目安全清理所需的 `os.waitid`。[Python 文档](https://docs.python.org/3/library/os.html#os.waitid)

```sh
git clone https://github.com/zhongbai2333/MCAndroidPhone.git
cd MCAndroidPhone
export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
"$(brew --prefix python@3.13)/bin/python3.13" -m venv .venv
sh test-phone.sh check
sh test-phone.sh quick
sh test-phone.sh pattern --world-smoke
sh test-phone.sh native-probe
```

`quick` 不需要 Minecraft 或 Android，只编译独立 Java 核心、运行 Python/协议测试和真实进程清理测试。`pattern --world-smoke` 首次需要下载 Gradle/Minecraft，之后自动创建独立世界验证物品、悬停倾斜、外缘旋转、触控、背包恢复及资源重载。所有运行依然由 Mod 管理器启动。截图位于 `.runtime/evidence/<UUID>/`。正常游戏：`sh test-phone.sh pattern`。

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

已有 Android-x86 9.0-r2 ISO、同版 kernel/initrd 时，可先用 M4 的 TCG 跑 x86 功能测试：

```sh
sh test-phone.sh qemu --guest-arch amd64 --set accel=tcg \
  --set iso=/absolute/path/android-x86_64-9.0-r2.iso \
  --set kernel=/absolute/path/kernel --set initrd=/absolute/path/initrd.img
```

这条路径较慢，不能据此评价 ARM64/HVF 的性能。所有磁盘使用临时快照，退出后不保留客体更改。QEMU 版本不支持 `virtio-multitouch-pci` 时自动回退鼠标输入，日志会明确显示；真实触屏需要支持该设备的 QEMU。

## GPU 下一步

`native-probe` 先验证 Metal 写入 IOSurface → OpenGL 导入 → GPU 缓存复制的同进程能力。探针会读回一个诊断像素校验颜色；生产目标是零 CPU 像素复制、一次 GPU 缓存复制。返回 77 表示能力不可用，返回 1 表示测试错误，只有 PASS 才证明探针通过。

仍需要在 Mac 实机完成：QEMU 显示生产端 IOSurface 导出、跨进程对象传递、帧生命周期与同步、Java OpenGL 导入、重建与回收，以及真实 Android/MC 端到端测试。现有 QEMU D-Bus 协议未提供可直接复用的 IOSurface 通路，不能将探针通过写成零拷贝后端完成。Linux 同理，还需要 DMA-BUF FD、modifier 和同步协议。

优先带回以下信息：`check` 输出、`quick` 结果、pattern 三张截图、IOSurface 探针输出、Android 镜像来源/架构/引导参数，以及 `run/mcandroidphone/sessions/<UUID>/` 下的 runtime.log、qemu.log、bridge.log、session.json。勿提交镜像、虚拟机数据或整套 Windows 运行时目录。
