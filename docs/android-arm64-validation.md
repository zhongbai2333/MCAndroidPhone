# Mac mini M4：真实 Android 验收 · 2026-09-08

Java 运行时已在 M4 上启动真实 ARM64 Android，并完成 Minecraft 手机中的滑动解锁、桌面显示、收纳恢复和资源重载。输入经过模组的手机投影与 Java 触摸链路；ADB 只读取最终游戏滑动测试的来宾状态，没有注入解锁输入。

## 环境与镜像

- 主机：M4 Mac mini，24 GiB，macOS 27.0；Zulu Java 25、QEMU 11.1.1、FFmpeg 9.0.1。
- 来宾：Android 16 / API 36，LineageOS `23.2-20260822-jqssun-virtio_arm64only`，ABI `arm64-v8a`，SELinux `Enforcing`。
- 来源：[jqssun/android-lineage-qemu 的 v2026.08.22 发布](https://github.com/jqssun/android-lineage-qemu/releases/tag/v2026.08.22)。这是社区 QEMU/UTM 构建，不是 Google 官方系统镜像。
- 下载文件：`UTM-VM-lineage-23.2-20260822-jqssun-virtio_arm64only.zip`，1,124,533,759 字节。完整 SHA-256 与发布资产记录一致：`ed7ec8030d094597d40371bc02ac66f5e4fff532bf70e6af50c108657dde2c00`。
- `vda.qcow2` 为 5 GiB 系统盘，`vdb.qcow2` 为 16 GiB 数据盘。`efi_vars.fd` 虽然以 `.fd` 结尾，实际是虚拟大小 64 MiB 的 QCOW2。
- 运行参数：ARM `virt,gic-version=3,accel=hvf`、`-cpu host`、4 vCPU、4 GiB 内存、virtio GPU、720×1280。镜像默认密度为 160，测试桌面副本在 Android 内设为 320。
- Android 报告 EGL `angle`、Vulkan `pastel`。Mac 当前仍是软件图形及 VNC → Java → FFmpeg → NV12 → OpenGL 路径，没有宣称 IOSurface 零拷贝或 Android GPU 加速。

## 结果

| 检查 | 结果与依据 |
| --- | --- |
| 原始镜像冷启动 | 两次成功，完成设置并进入桌面；`sys.boot_completed=1`。第二次内核日志在约 21.66 秒记录完成，这不是 JVM 启动到可操作桌面的耗时基准 |
| 真实触摸 | `PhoneConnection` 点击完成首次设置，滑动打开应用抽屉、滚动设置；Android 识别 virtio 触摸设备 |
| 返回 / 主页 | 修复后通过；主页键后 `mCurrentFocus` 为 `QuickstepLauncher`，返回键退出设置子页 |
| 应用切换 | Java `APP_SWITCH` 的 Alt+Tab 能切回上一应用；本镜像中不是保持打开最近任务总览 |
| 文本输入 | Java/RFB 输入 `display`，Android UIAutomator 确认 `android:id/search_src_text` 内容一致且获得焦点 |
| 视图重连 | epoch 从 6 更新为 7，立即取得缓存帧；保持同一 QEMU 会话 |
| 游戏输入 | 显式 `--android-smoke` 发送投影后的鼠标按下、12 步拖动及松开。独立只读观察器确认 `isKeyguardShowing` 从 true 变为 false，前台为 Launcher |
| 游戏显示 | 最终两张截图均已目视检查，为解锁后的 Android 桌面，颜色与 Android 原生截图一致 |
| 收纳 / 资源重载 | `sameConnection=true`、`sameEpoch=true`；收进第 9 格再取出不需重新右键；资源重载后保留画面缓存 |
| 构建与回归 | `gradlew build runtimeSelfTest --offline` 通过：核心协议、几何、平台参数、D-Bus 编解码及真实进程生命周期 |
| 清理与镜像保护 | 5 个真实 Android 会话均 stopped；核对 65 个自有 PID、30 个进程组，无活动残留。专用 ADB 服务已关闭；原始提取文件的 SHA-256 全部保持一致 |

游戏滑动通过 NeoForge 鼠标事件完成，覆盖模组事件拦截、三维手机坐标映射和来宾触控；这不等于人工鼠标或 macOS 输入驱动的硬件测试。桌面自动化工具未能识别无应用包的 Java 游戏窗口，因此使用项目自身的游戏输入验收入口。

## 本轮修复

1. ARM64 双 virtio 磁盘及稳定的启动顺序；系统盘和数据盘均保持临时快照。
2. EFI 变量模板复制到独立会话，支持明确的 raw / qcow2 格式，避免把 UTM 的 `.fd` 当作 raw。
3. USB 键盘负责 UEFI，新增 virtio 键盘负责 Android。只有 USB 键盘时 `AC_HOME` 没有生效，新增设备后返回和主页均通过实测。
4. 镜像专用 `colorOrder=bgr` 配置。此组合的 QEMU 原生 screendump 已与 Android screencap 红蓝相反；配置补偿后游戏颜色正确。通用固件配置仍保持独立，不把这个补偿套用到所有 ARM 镜像。
5. 默认关闭的 `adbPort`；启用时只转发 `127.0.0.1:<端口>` 到来宾 5555，仍需 Android 开启调试并授权 RSA 密钥。
6. 新增 `AndroidRuntimeTest` 交互验收入口，以及游戏 `--android-smoke` 的投影鼠标滑动测试。

对应配置见 [LineageOS 模板](../configs/mac-lineage-arm64.properties.example)，通用配置说明见 [runtime](runtime.md)。ARM64 磁盘引导不会读取自动生成的 Android-x86 `density` 参数，应在来宾中配置显示密度。

## 本机复现

本机 `run/config/mcandroidphone-runtime.properties` 已指向已初始化的测试副本，原配置备份为 `.runtime/evidence/android-arm64/run-runtime-before.properties`。常规配置关闭 ADB。

```sh
# 启动游戏，进入世界后主手右键手机；冷启动锁屏可向上滑动解锁。
sh test-phone.sh qemu

# 自动创建新世界，并发送鼠标滑动、截图、收纳、重载，然后退出。
sh test-phone.sh qemu --android-smoke --warmup 60

# 仅当需要读取 Android 状态时开放本机调试端口。
sh test-phone.sh qemu --set adbPort=15555 --android-smoke --warmup 60
```

这份本机副本保留了初始设置和 320 dpi，但每次运行仍使用临时快照，关机丢弃后续安装或修改。它不是模组的数据持久化功能。复制到另一台机器时需自行下载镜像、配置路径和完成初始设置；镜像及本机测试数据没有加入 Git。

独立 Java 验收入口：先构建，然后用 Java 25 运行测试类，classpath 包含 `build/classes/java/main`、`build/classes/java/test` 和 `build/resources/main`，主类为 `com.zhongbai233.mcandroidphone.core.AndroidRuntimeTest`，两个参数分别为 UTF-8 runtime properties 与新证据目录。该入口最长运行 15 分钟，从 `commands/*.json` 读取 `tap`、`touch`、`key`、`text`、`capture`、`reconnect`、`stop`，回执写到 `results/`；写命令应先写临时文件再原子重命名。回执表示接口已接收，来宾行为需用截图或 ADB 独立确认。

## 本机证据与范围

- 镜像来源、哈希、原文件及已初始化副本：`.runtime/images/lineage-arm64/`。副本用显式 `blockdev-add` / `blockdev-backup` 保存，避免 QEMU 临时标志回收备份文件，随后通过 `qemu-img check`。
- 独立 Android 运行：`.runtime/evidence/android-arm64/attempt-01/` 和 `attempt-02/`。
- 最终游戏日志：`.runtime/evidence/android-arm64/minecraft-swipe.log`。
- 独立来宾验收：`game-swipe-before.txt`、`game-swipe-after.txt`、`game-swipe-verification.json`，位于同一证据目录。
- 最终截图：`.runtime/evidence/1de59749-35a9-42f4-b057-251ccd159b14/android-phone-focused.png` 和 `phone.png`。
- 进程、镜像和 JAR 核验：`.runtime/evidence/android-arm64/final-audit.json`。
- 本地产物 `build/libs/mcandroidphone-0.1.0-prototype.jar`：215,191 字节，72 个 Java 类与编译输出逐一一致，0 个 Python 文件，内置 `native-guard.jar`。SHA-256：`7f128e3b6f59c07d78f040e722c4d04c09aaee90c48469ce7c7e8afc32e74e78`。

未提交、推送或更新远端发布。没有进行应用兼容性大全、音频、多指、长时间压力或性能基准测试；Windows 原生 Java D-Bus/D3D11 路径仍待 Windows 实机回归。本机缓存构建不代表远端 CI 或无缓存下载已经验证。
