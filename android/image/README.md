# 双架构精简系统特化

Fork 已创建：[zhongbai2333/android-lineage-qemu](https://github.com/zhongbai2333/android-lineage-qemu)，上游为 [jqssun/android-lineage-qemu](https://github.com/jqssun/android-lineage-qemu)。特化代码随当前 MCAndroidPhone 仓库的 `android/image` 交接，不在上游 fork 中；尚未触发完整 Android 远端编译。读取到的上游 main 为 `54fc5dc82fa05778be15c1200240be53f707a542`。Windows/WSL2 接续步骤见 [交接文档](../../docs/windows-wsl-handoff-2026-09-09.md)。

产品基于 LineageOS 23.2 的 `virtio_arm64only_go`、`virtio_x86_64_go`，共用环境服务和 HAL 改动。六个平台组合见 `platforms.json`。Windows AMD64 Go 已完成构建与运行测试，见 [Windows 验收](../../docs/windows-go-validation-2026-09-12.md)；本轮新增可选的 [compact / minimal 体积配置](../../docs/android-go-compact.md)。这些结果不等于六平台验收。

已另外实现六平台通用 JAR 打包器，并制作、验证现成 Android 镜像的 Mac ARM64 本地内置包。该包不是 Go 定制系统，状态与证据见 [本机功能验收](../../docs/local-features.md)。

## 构建前提

完整构建需要准备好的 Linux 主机、LineageOS 23.2 源码及依赖。上游建议 64 GB 内存、约 400 GB 磁盘空间；双架构和缓存可能需要更多。历史的 Mac 可用空间数字已不适用，应以 `df -h` 实测为准。本轮没有在 Mac 上下载整棵源码或启动完整 Android 编译。

需先按照上游说明拉取两个 VirtIO 目标及其依赖（包括 `device/virt/virtio-common`、内核与 `hardware/interfaces`），本脚本不会 `sudo apt install`、修改全局 Git 配置或自动同步/重置源码。

```sh
# JDK 17+ 用于 Prepare.java；Android 自身使用其构建树指定的工具链。
java android/image/Prepare.java /path/to/lineage /path/to/MCAndroidPhone --check
LINEAGE_ROOT=/path/to/lineage bash android/image/build-go.sh arm64
LINEAGE_ROOT=/path/to/lineage bash android/image/build-go.sh amd64
```

`Prepare.java` 先检查所有上游修改锚点及目标覆盖情况，再落盘；上游变化时失败，不静默套错补丁。重复运行相同版本不会追加重复配置。已有 overlay 内容不同会拒绝覆盖，应先审查本地变更。两套系统采用 non-A/B 构建以减少系统盘占用；本阶段仍保留上游 Go 的 WebView、安装器、设置和桌面。

脚本已在对应上游真实源码片段组成的 fixture 上验证：检查 → 应用 7 个上游文件 → 重复检查为 0 个待改动文件。这不是 Soong、SELinux、CTS/VTS 或系统启动通过的证据。

## 接入范围

- `guest/environmentd.cpp`：原生 VirtIO 接收器及原子状态文件。
- `guest/EnvironmentState.h`：有界二进制解析、500 ms 新鲜度校验、虚拟磁场计算。
- `sepolicy/`：接收服务独立域、专用设备节点类型、HAL 只读状态权限；需要在 Linux 编译及实际启动时验证。
- `Prepare.java`：Sensors 正常模式覆盖五类传感器，GNSS 定位回调覆盖游戏虚拟坐标；暂时禁用不一致的示例 NMEA。
- `product.mk`：共用品牌、中文/英文配置、环境服务；不启用调试权限或宽松 SELinux。

接入后仍需核对 GNSS 批量/原始测量等示例功能，完成 HAL VTS、普通 APK 读取、开关定位/传感器、暂停/重连/换维度、跨架构镜像启动与性能测试，再将镜像和各平台 QEMU/FFmpeg 原生依赖打入 JAR。ARM 转译和标准 Camera2 仍未完成。

## Go 体积优化与兼容性（2026-09-09 更新）

`Prepare.java` 现在将定制 `product.mk` 直接 include 到两个 Go 根产品末尾，修复子产品无法覆盖父产品已设置品牌等标量的问题；并替换根产品的 `languages_full.mk` 继承，避免最后追加中英文仍混入全语言资源。该设置针对源码资源构建，不会重新裁切现有预编译 APK。

`go-optimization.mk` 对普通预装应用使用 `verify` 编译策略，保留 SystemUI/Go Launcher 的 speed 应用名单；AMD64 system server 使用完整 speed 预编译以降低启动期解释/JIT 工作，ARM64 保留 speed-profile，不删除原始 DEX、不全局关闭 dexpreopt。调试 ART 和 Java 局部变量调试信息沿用上游精简策略，不把上游已有优化算成新增收益。WebView、输入法、全部字体、核心框架、APEX 和 HAL 保留。运行时 JIT/AOT 仍可按需编译；镜像大小、首启耗时和性能必须在真正 Go 产物上测量。

五个可选模块（Backgrounds、BasicDreams、EasterEgg、PrintRecommendationService、vim）在实际声明处按两个 Go 目标条件省略，不使用对未展开继承列表无效的末尾 `filter-out`。非 Go 产品保留原包。

Linux 脚本只选择 Go 目标，构建前读取解析后的变量，由 `VerifyGoConfig.java` 检查品牌、中英文资源、预编译策略、低内存属性、必需模块及已移除模块。输入的 repo manifest 和本地 overlay/camera 哈希在构建前记录，失败也保留证据。已修改的旧 overlay 仍会被拒绝覆盖，应先比对本地修改后在干净/独立 checkout 应用。

本机离线测试和真实应用基线入口见 [测试说明](tests/README.md)，本轮结果见 [Go 推进记录](../../docs/go-optimization.md)。这些配置尚未经过完整 Android 构建；最新公开发布也没有 Go 预编译包，因此现有 726.37 MiB 普通镜像仍是已验证的分发候选。

2026-09-09 增加 `MCPhoneCamera` 产品模块：Prepare 会复制 `android/camera` 中的源码、manifest 和 Android.bp；产品配置预装游戏相机应用。Mac 已完成独立 APK 编译与真实 Android 预览、前后摄拍照、相册打开/返回实测。该应用使用固定来宾网络地址，不注册 Camera2。新的十文件 overlay 已在单独 fixture 中应用并重复检查，尚不代表 Soong 整体构建通过。见 [相机应用](../camera/README.md)。

Java 游戏侧当前需显式 `environment=true`。测试接收器与正式系统服务的区别及验收证据见 [游戏环境联动](../../docs/game-environment.md)。

2026-09-09 增加关机资源覆盖：`overlay/frameworks/base/core/res/res/values/config.xml` 把长按电源设为无确认正常关机（行为值 3、500 ms），关闭长按呼出助手的设置入口。配套运行时配置必须使用 `shutdownMethod=power-key`，Java 将按住虚拟电源键 1500 ms，并等待 QEMU 的来宾关机事件。系统默认短按电源仍可用于息屏。已有用户数据中显式保存的电源键设置可能覆盖资源默认值，不会自动重写旧设备设置。普通用户关机无需 ADB。

该十一文件特化已在独立 fixture 应用并重复检查；资源默认值仍需完整系统编译后验收。本机真实 Android 验证使用开发测试配置设置等效长按行为，不等于已编译此 overlay。配置与证据见 [正常关机](../../docs/guest-shutdown.md)。

Windows/WSL 接续现使用 `MCANDROIDPHONE_BUILD_JOBS=2` 为默认并行度，构建调用为 `m -j 2 vm-utm-zip`。可根据实际内存显式调整，范围 1–256；该限制不会把物理内存变大，也不能保证大规模 Soong 配置阶段在低内存机器上成功。构建证据增加实际并行度、内存和文件系统剩余空间。

### 构建清单与只读沙箱

`build-go.sh` 在进入 Ninja 前，将真实 Repo 锁定清单保存到本轮报告目录，并原子更新 `out/mcandroidphone/build-manifest.xml`。两个 Go 产品的镜像清单规则依赖这个输入，只在 `out/` 内生成输出，保留上游 proprietary 排除逻辑。其他产品继续使用上游规则。这样避免 Repo 在编译沙箱中尝试更新用户目录或 `.repo` 下的缓存。

直接执行 Go 的 `m build-manifest.xml` 前，也必须先准备这个锁定输入；正常使用 `build-go.sh` 会自动完成。输入缺失时构建应失败，不能拿空清单或旧镜像产物绕过源码版本记录。
Go 启动模板将 GRUB 等待设为 1 秒，默认安静启动并省去开机动画；普通非 Go 产品仍使用上游模板。已有设备的持久化设置和系统版本不会自动改写。VirtIO 图形探测同步发布 EGL 实现，避免 SurfaceFlinger 在属性链尚未处理时首次启动失败。上游 ART GC 选择保留；虚拟设备属性显式声明。quiet 模式仍输出一条完成启动标记。Windows AMD64 实际计时及镜像哈希见 [冷启动验证](../../docs/windows-go-startup-2026-09-12.md)。
