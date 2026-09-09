# 本机功能验收 · 2026-09-08～09

后续存储优化已接入新源码：新手机共享固定系统底盘，旧手机完整磁盘保持原样。真实 Android 两设备隔离、同步写入后的重启持久化及存储占用见 [共享系统底盘验收](shared-system-disk.md)。下文 v2/v3 大包属于此前历史产物，不包含此次优化。

后续正常关机修复与适配种子见 [正常关机验收](guest-shutdown.md)：适配镜像已验证无需测试工具手动 sync 的重启持久化；未适配镜像明确记录超时强制清理。

用户要求先完成当前 Mac 能实现和验证的内容。本机为 Apple Silicon Mac mini；不把 Mac 结果推及 Windows/Linux 原生路径，也不把主机端画面传输当作 Android Camera HAL 完成。

## 本轮实现

- Java 自动解压平台内置包：逐文件 SHA-256、长度和路径校验；原子安装、并发锁、取消后清理暂存目录。缓存被修改时拒绝启动，不覆盖设备数据。无需下载、Python 或用户启动脚本。
- `PackageRuntime.java` 支持六个平台标签，校验来宾与包架构一致，从已准备的原生运行时和双盘镜像制作 JAR。当前只有 Mac ARM64 有实际本地包；它使用现成 LineageOS 23.2 Android 16，**不是已完成的 Go 精简系统**。
- `StageMacRuntime.java` 复制当前 Homebrew QEMU/FFmpeg 的 48 个 Mach-O 文件，将非系统依赖改为 `@loader_path`，对副本签名并保留 QEMU Hypervisor entitlement。原 Homebrew 安装不变。包含 QEMU 资源、媒体、依赖来源与可找到的许可证文件；本地产物尚未公开发布、Developer ID 签名或公证。
- 按手机 UUID 保存系统、数据和 EFI 变量；暂存初始化后整体提交，拒绝并发写入与跨架构复用。镜像升级不自动重置已有手机。
- 从手机模型的实际变换计算镜头世界位置和朝向，前/后摄各自使用 60° 视野、640×480 独立世界渲染；镜头跟随机身倾斜和旋转。前摄可看到玩家模型。只在接收器请求时工作，最高 10 fps，只有一份截图/编码在途。暂停、收纳、第三人称和离开世界时失效，不读取桌面或真实摄像头。
- 镜头切换、收纳和连接关闭均使旧请求代次失效；异步 JPEG 编码完成时核对代次，防止前后摄串帧。第二次世界渲染后恢复主相机、全局渲染参数和可见区块。当前只验证基础 NeoForge/OpenGL 场景，额外世界渲染有性能成本，未验收光影包及其他渲染模组兼容性。
- 摄像头通道与屏幕、触控、传感器独立。Android 开发接收器实际通过 `BitmapFactory` 解码 JPEG、检查尺寸，并保存图像供目视核对。

## 实测

1. `gradlew build --offline`：几何、核心协议、环境、存储/运行包、摄像头通道测试通过。
2. `java scripts/Dev.java quick`：Java 守护进程、阻塞输入、启动取消、父 JVM 强杀等回归通过。
3. Minecraft 新测试世界，物品右键拉起 Android：来宾成功解码 **404** 帧；目视方向、颜色正常，无手部、手机和 HUD。收纳恢复、资源重载通过，`WORLD_SMOKE_OK` 约 107.6 秒。
4. 内置包在新游戏目录自动安装并启动 Android，配置只提供测试 UUID、ADB 端口和尺寸，不提供原生程序或镜像路径。会话中的 QEMU、固件、ROM 和磁盘均在该游戏目录内。初次试验暴露 macOS 原生程序首次执行超过旧 5 秒探测限制，已将 QEMU 探测和 FFmpeg 第一帧宽限改为 30 秒；后续帧仍为 5 秒。修复后的完整 `local-v2.jar` 在另一全新目录成功启动，`sys.boot_completed=1`，有实际画面输出。
5. 同一 UUID 两次启动：亮度 `73`、测试文本和游戏 JPEG 保留，图像 SHA-256 前后一致。测试在 `sync` 后关闭 VM，证明已提交写入的持久性，不声称突发断电绝不丢失未落盘数据。
6. 完整 `local-v2.jar` 直接加载进另一个全新 Minecraft 目录，开发 classes/resources 未加入类路径。NeoForge 日志确认从该 JAR 加载模组；实际物品右键自动解压并启动 Android，目视截图为 Android 锁屏。收纳恢复、资源重载和 `WORLD_SMOKE_OK` 通过，约 128 秒；该次使用默认持久模式。Android 开机属性的另一次读取发生在游戏自动退出之后，连接已关闭，因此游戏侧以截图和完整会话日志验收，不把空的属性文件当作通过证据。

证据在 `.runtime/evidence/features-local/`：`camera-game.log`、`android-camera.log`、`android-camera-frames/`、`persistence-before.txt`、`persistence-after.txt`、内置包启动日志。首轮失败会话也保留。

上轮完整包 `.runtime/packages/mcandroidphone-macos-arm64-local-v2.jar`，2,189,550,431 字节，约 2.04 GiB；保留原包和配套 `.sha256`，不覆盖旧设备数据。

最终 `cleanup-audit.json` 审计六个会话的 62 个原生 guardian/child PID，存活列表为空；独立测试 ADB 服务也已关闭。完整包 SHA-256：`300c83e25f8b89df45e9c0c6f9084d5f3bd30ec68cf20f4ac60077de28a938bd`。

## 独立镜头与干净用户盘追加验收

- `camera-lens-build.log`：构建及回归通过，新增镜头切换、切换后异步旧帧丢弃、失效后重新请求的测试。
- `.runtime/evidence/camera-lens/game.log`：独立镜头第一轮真实游戏测试通过，`WORLD_SMOKE_OK` 134917 ms；倾斜、-90° 旋转、收纳恢复、资源重载通过。Android 日志记录至少 725 帧 `BitmapFactory` 解码；进程随游戏退出，未记录完整接收器结束标记。已保存前后摄样张，后摄为前方世界，前摄可见玩家模型。
- `game-confirm.log` 补测保存了旋转后的前摄样张 `frames-confirm-retry/mcphone-camera-confirm/front-336.jpg`，但收纳恢复断言因额外界面而失败，不能计为整轮通过。已为 opt-in smoke 关闭失焦自动暂停并在结束时恢复，同时补充失败界面名称诊断；原失败日志保留。该轮接收器第一次在启动阶段被来宾终止，原因未确认，重试记录至少 325 帧解码。
- `clean-seed/`：用未初始化的 196864 字节 `verified/vdb.qcow2` 首次启动，逐页触控完成 LineageOS 设置并进入桌面，证据为 `27-desktop.png`。默认未开启 ADB，连接失败不作为系统启动失败，也不声称该轮取得 `sys.boot_completed` 属性。此次只用干净种子盘替换用户盘模板，系统镜像没有 Go 精简。
- 新本地完整包 `.runtime/packages/mcandroidphone-macos-arm64-local-v3.jar`：1,159,575,082 字节，约 1.08 GiB，比 v2 小约 47%。包含本轮独立镜头代码和干净用户盘；每台新手机首次开机仍需完成 Android 设置。SHA-256：`6b982217267948c9dbeb4722c4e2994d2acd202ea52db894dbf95372eeacd1ce`。
- `packaged-game-v3.log`：完整 v3 JAR 直接加载进全新游戏目录，开发主 classes/resources 不在类路径，物品右键自动安装并启动 Android 到首次设置界面；倾斜、-90° 旋转、收纳恢复和资源重载全部通过，`WORLD_SMOKE_OK` 134588 ms。`packaged-camera.log` 的主机接收器解码 950 帧（后摄 507、前摄 443），样张在 `packaged-game-v3/camera-frames/`；该次是主机接收器，不能算作 Android 解码次数。主视角截图 `packaged-game-v3/phone.png` 中地平线保持水平，手机为横屏。
- `cleanup-audit.json`：本轮四个会话均为 stopped，48 个原生 guardian/child PID 无存活项；独立 15037 端口 ADB 服务已关闭。

## 尚未完成的预期功能

2026-09-09 已进一步实现真实安卓游戏相机 APK：预览、前后摄拍照、MediaStore 发布、相册打开及返回恢复都经过本机实测。普通 Camera2 仍受 ANGLE 缺少 `GL_EXT_YUV_target` 阻塞；这是现成系统服务的实际错误，而不只是缺少 APK。详细证据与安装范围见 [游戏相机](../android/camera/README.md)。

| 功能 | 当前缺口 |
| --- | --- |
| 普通 Android 相机应用预览/拍照 | 尚无 Camera HAL/provider；前后镜头独立渲染和 JPEG 通道已实现，接收器仍不注册 Camera2 设备 |
| 正常系统定位/传感器 | 已有上轮原生服务与 HAL 特化源码，待 Linux 完整编译、SELinux/VTS 和启动验证；现成镜像不内置该服务 |
| 双架构 Go 精简系统 | Fork 与特化工具已准备；本 Mac 未下载/编译完整 Android，尚无定制系统产物和体积结论 |
| ARM 应用转译 | ARM64 来宾原生执行 ARM64；AMD64 来宾的 ARM64 NativeBridge 未集成，不承诺任意 ARM32/ARM64 APK 兼容 |
| 六平台发布 | 通用打包器已实现，只有 Mac ARM64 有本地真实系统验收；其他原生依赖、签名和目标设备验收尚缺 |

## 体积复核 · 2026-09-09

用户认为 1.08 GiB 仍过大，因此 v3 只保留为历史测试包。本轮没有将它标为精简版或重新生成另一个近似体积的完整包。

`scripts/InspectBundle.java` 按 ZIP 实际压缩长度列出最大文件，并可接收 MiB 上限，超限时非零退出。现有 v3 中系统盘压缩后为 1,110,384,397 字节，全部镜像合计占包的 95.90%。只裁剪原生库和 QEMU 配套固件不足以显著解决总体积。

独立 `xz -T2 -6` 对比得到 843,666,724 字节（804.6 MiB）的系统盘，解压 SHA-256 与原盘完全一致，约 4.57 秒完成本机解压/哈希。若只替换压缩格式，完整包粗略估算仍为 892,857,409 字节（约 851.5 MiB）；这是估算，**未构建该包，也未集成 XZ 到 Java 运行时**。仍需要减少系统组件、语言资源、预装应用等系统本体，完整 Android 定制编译条件未解决。

证据为 `.runtime/evidence/camera-system/bundle-report.txt`、`compression-verification.txt`、`budget-check.txt`（用 1024 MiB 验证超限失败，不代表正式发布预算）。

```sh
java scripts/InspectBundle.java /path/to/runtime.jar
# 可选：指定团队确定的最大 MiB，作为构建验收门槛。
java scripts/InspectBundle.java /path/to/runtime.jar 512
```

后续系统相机需遵循 [AOSP Camera HAL](https://source.android.com/docs/core/camera/camera3) 的 provider/device/buffer 和元数据契约，直接传送 JPEG 不能替代这些接口。

## 制作本地内置包

```sh
# JDK 25；只在开发机执行，stage 与 output 使用新路径。
java scripts/StageMacRuntime.java run/config/mcandroidphone-runtime.properties .runtime/mac-arm64-stage
java scripts/PackageRuntime.java build/libs/mcandroidphone-0.1.0-prototype.jar .runtime/mac-arm64-stage macos-arm64 .runtime/packages/mcandroidphone-macos-arm64-local.jar
```

其他平台需自行准备同样结构的 staging 目录和相对路径 `runtime.properties`；通用打包命令不会编译 QEMU、Android 或 ARM 转译器。服务端可安装体积较小的普通模组 JAR。

制作小体积本地包时，在传给 `StageMacRuntime` 的开发配置副本中，将 `dataDisk` 指向同架构、同系统版本的干净用户盘种子。先验证首次启动与设置，不要截断/删除已有手机的用户盘。打包器不会自动清理个人数据；staging 会移除开发测试 `deviceId` 并关闭 ADB、环境和摄像头开发通道。

## 摄像头开发协议 v1

宿主只监听 127.0.0.1 临时端口。QEMU 使用 `com.mcandroidphone.camera` 命名 VirtIO 串行端口；现成来宾验证通过 QEMU 用户网络回环连接，**正式 VirtIO 来宾接入未验证**。

上段描述 v3 及更早测试。后续源码默认 `cameraTransport=network`，固定来宾地址 `10.0.2.100:18765`；显式选择 `virtio` 才使用命名串口。新增安卓游戏相机 APK 的预览、拍照与相册实测见 [安卓游戏相机](../android/camera/README.md)。v3 大包没有这个 APK，也没有新固定地址路由。

全部大端：接收端先发送 magic `0x4d435043` 与 version `1` 两个 uint32；每次请求 uint32 `1`（后摄）或 `2`（前摄）。切换镜头先清空旧帧。无帧回复 uint32 `0`；有帧回复 payload 长度 uint32，随后 magic/version uint32、sequence int64、width/height/jpegLength uint32、JPEG。payload 恰好 `28 + jpegLength`，JPEG 上限 2 MiB。帧超过 500 ms 不再发送，最多 10 次响应/秒，传输超时 2 秒，断线释放需求。不能用宿主时钟代替 Android capture timestamp。

```sh
JAVA_HOME=/path/to/jdk25 R8_JAR=/path/to/r8.jar sh android/tools/build-probe.sh
java scripts/Dev.java qemu --world-smoke --warmup 100 --set camera=true --set adbPort=15555
# 从本次 qemu-command.json 获取 phone-camera PORT。仅对独立测试 VM：
adb push android/tools/build/environment-probe.jar /data/local/tmp/mcphone-probe.jar
adb shell 'CLASSPATH=/data/local/tmp/mcphone-probe.jar app_process /system/bin com.zhongbai233.mcandroidphone.guest.CameraProbe 10.0.2.2 PORT /data/local/tmp/mcphone-camera alternate 60'
```
