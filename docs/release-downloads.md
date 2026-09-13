# 双架构下载版与 Mac 接续开发

版本 `0.2.0-dev.20260913`。已完成双架构镜像与通用 JAR 构建，Windows 验收通过；新 ARM64 Go 的 M4 实机验收待接续。附件位于 [开发版 Release](https://github.com/zhongbai2333/MCAndroidPhone/releases/tag/v0.2.0-dev.20260913)。

## 交付布局

- `mcandroidphone-0.2.0-dev.20260913-universal.jar`：同一份 Mod 代码，内置 Windows AMD64 和 macOS ARM64 的 QEMU、FFmpeg、固件及各自依赖。仅安装宿主需要的一套。
- `mcandroidphone-android-go-full-amd64-20260913.zip`、`mcandroidphone-android-go-full-arm64-20260913.zip`：保留 WebView 的完整 Go 系统，分别包含配对的干净系统盘、用户盘、EFI 变量模板。系统盘使用 XZ，Mod 自己解码。
- 每个附件的 SHA-256，以及源输入/构建说明。原生组件和 Android 保留各自许可证，项目 MIT 不重新授权第三方组件。

镜像和 Mod 放在同一公开仓库的 [Release](https://github.com/zhongbai2333/MCAndroidPhone/releases)。固定版本 URL、大小和哈希写入 JAR；公开附件不需要 GitHub 登录。游戏从新手机首次开机时开始下载，有进度和续传；首次安装后可使用本地缓存。下载失败不会覆盖已有手机。

当前目标只包含 Windows AMD64 与 macOS ARM64；一个通用 JAR 不等于六个平台均已支持。Windows 使用 WHPX / D-Bus / VirGL 共享纹理；Mac 使用 HVF / VNC / CPU 帧传输，尚无 QEMU 到 Minecraft 的 IOSurface 零 CPU 拷贝通路。

## M4 使用发布包继续开发

以下步骤使用上述开发版附件。已有 Java 25 即可；玩家运行 Mod 也不需要 Python、独立 QEMU 安装或 Android 编译环境。

1. 下载通用 JAR 到项目的 `.runtime/downloads/`，按 Release 的 SHA-256 验证。
2. 获取对应源代码版本；如继续未合并开发，使用 `codex/windows-wsl-handoff-20260909` 分支。不要把旧 main 的工具混用于新下载版。
3. 在项目根目录执行：

```sh
mkdir -p build/release-tools
javac -d build/release-tools scripts/BundleMetadata.java scripts/InstallReleaseRuntime.java
java -cp build/release-tools InstallReleaseRuntime \
  .runtime/downloads/mcandroidphone-0.2.0-dev.20260913-universal.jar \
  .runtime/release-cache .runtime/mac-release.properties
sh test-phone.sh qemu --runtime-config .runtime/mac-release.properties
```

安装工具使用发布 JAR 内的实际安装/下载实现，生成指向缓存的本机配置，拒绝覆盖已有配置。重复使用已完整安装的缓存不需要再次联网下载。开发首次启动仍需 Gradle/Minecraft 依赖；之后可在 Mac 独立迭代 Mod，不必重新编译 Android。

普通玩家把通用 JAR 放进对应 NeoForge 的 mods 目录，主手右键手机即可，不需要执行这些开发命令。已有显式 disk/iso/kernel 配置会优先使用，测试首次下载时应使用新的游戏目录。

## 新 Go ARM64 在 M4 的验收

此前 M4 的社区 LineageOS 镜像测试不能替代本轮新 Go 镜像测试。Mac 原生打包 CI 已验证 QEMU 启动探针、动态库重定位、签名和触屏设备能力；CI 不代表真实 Android/HVF/游戏画面已经通过。

在 M4 依次验证首次安装、实际 ARM64/HVF 启动、触屏点击拖动、横竖屏握持高度、收进背包再取出、资源重载、正常关机、同一手机再次开机的数据保留。保留 sessions 下的日志与截图，并记录冷启动耗时及分辨率。测试失败时先保留整套 devices 数据，不混用新系统模板和旧用户盘。

迁移已有手机需复制整个 `mcandroidphone/devices`，包括隐藏 `.bases`，不能只复制 UUID 子目录。Windows AMD64 的现有手机不会自动转换成 ARM64；M4 的本轮验收应创建新手机。

## 本轮 Windows 证据与压缩选择

通用 JAR 137,143,696 字节，AMD64 镜像 ZIP 803,309,655 字节，ARM64 镜像 ZIP 683,720,715 字节。完整 GitHub 草稿下载、Mod 校验与解码通过；八个服务器附件哈希与本地一致。

通用 JAR 在新的游戏目录中自动安装 Windows 原生依赖、选择 AMD64 模板并完成真实启动与正常关机。测试预填了已校验的归档，准备 52.17 秒、含准备的 Android 启动完成 133.37 秒；当时与压缩并行，不能作为空闲性能基准。[记录](handoff-evidence/2026-09-13/download-release/universal-boot.json)。

当前源码使用候选 JAR 已安装的运行时和完整镜像完成游戏回归：720×1280、D3D11 零 CPU 像素复制/一次 GPU 缓存复制、背包恢复无需右键、资源重载保留画面。[日志](handoff-evidence/2026-09-13/download-release/minecraft.txt)、[画面](handoff-evidence/2026-09-13/download-release/android-phone-focused.png)。

两架构的更强 XZ/zstd 完整实验均已完成、全部解压哈希一致；发布仍沿用原 XZ。更大字典只省约 2–3%，zstd 的优势在原生解压速度，实际游戏集成需另测。[完整对比](compression-comparison-2026-09-13.md)。
