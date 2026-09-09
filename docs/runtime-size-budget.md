# 完整离线包向 500 MiB 优化（2026-09-09）

目标按完整离线 JAR **500 MiB = 524,288,000 字节**计算；不通过移出镜像、首次联网下载或把 MB/GiB 混用来满足数字。

## 本轮已测量

上一包为 812,151,403 字节（774.53 MiB），系统盘 XZ 占 727.46 MiB，约 94%。应用代码和原生运行时已经不是体积主体。

| 完整包 | 字节 | MiB |
| --- | ---: | ---: |
| 上一轮 v3 | 812,151,403 | 774.53 |
| 本轮 v4 | 774,385,171 | 738.51 |
| 最终 v5（48 MiB 字典） | **761,655,879** | **726.37** |

v4 减少 36.02 MiB。其中：

- 针对显式 pflash 启动的 ARM64 virt 机器，`StageMacRuntime` 不再复制其他机器的固件、DTB 和重复 UEFI。保留独立启动固件、许可、键盘映射、网络/显示 option ROM。其他架构继续完整复制，不能把本机裁剪规则外推到 AMD64。
- 删除的 QEMU 附属文件原占下载约 15.70 MiB、安装后 **312.76 MiB**。安装后的运行时文件从 2558.91 MiB 降到 2246.15 MiB。
- 对原生可执行文件/库使用 ARM64 BCJ + XZ，对其他大文件使用 XZ，共 35 个文件额外减少约 9.01 MiB。
- 系统 qcow2 使用 ARM64 BCJ + XZ 后从 762,792,372 降到 750,945,584 字节，减少约 11.30 MiB。解压后的系统盘 SHA-256 仍为 `f990d97cbd22a48459833cae293d420584e5e687d5487e2c77b84755764e991f`。

最终 v5 在相同系统文件上改用 48 MiB 字典及 `lc=2,lp=2`，系统 XZ 为 738,216,292 字节（704.02 MiB），比 v4 再少 12.14 MiB。整体比上一轮 v3 少 **48.16 MiB / 6.22%**。官方原生工具报告解码需要 49 MiB；Java 仍限制在 64 MiB 内。

没有改变 Android 的应用、字体、APEX、图形驱动或预编译内容；所有保留文件由打包器使用用户实际使用的私有 Java 解码器验证长度和 SHA-256。用户不需要安装 XZ 或 Python。

## 为什么不能按原始文件大小估算收益

从原始 ext4 中逐个提取大文件，以相同 32 MiB 字典单独压缩，得到下表。**单文件压缩值不是整包中可直接相加的贡献，也不是删除后的确定收益**；整盘压缩还共享字典。

| 组件 | 原始 MiB | 单文件 XZ MiB |
| --- | ---: | ---: |
| WebView | 253.29 | 87.79 |
| AVF | 90.55 | 23.33 |
| Settings | 82.54 | 21.11 |
| 音频 HAL APEX | 64.80 | 41.01 |
| LatinIME | 63.21 | 18.73 |
| SystemUI 预编译代码 | 57.19 | 18.17 |
| ThemePicker | 47.42 | 12.20 |
| Launcher 预编译代码 | 29.26 | 9.55 |
| NotoSans CJK | 30.86 | 11.40 |
| NotoSerif CJK | 25.06 | 15.10 |
| Seedvault | 22.12 | 5.86 |

全部外部 `.odex/.vdex/.art/.oat` 合计也只有 164.86 MiB 原始数据；其中应用目录约 103.23 MiB。为节省几十 MiB 而全部删除，会把工作转移到首次启动或数据盘，且需验证原始 DEX 是否完整保留。此次没有这么做。

对系统 qcow2 按 4 KiB 块做 SHA-256 去重核算，534,752 块中有 510,632 个唯一块，重复原始数据约 94.22 MiB。XZ 已经消除其中一部分重复，这不能证明增加自定义去重容器还能省同等下载量，因此没有新增复杂的镜像解码格式。

## 500 MiB 的后续路径和边界

最终 v5 仍需再少约 226.37 MiB。现有测量没有支持“保留相同系统内容、只调压缩就能到 500 MiB”的结论。

优先路径是仓库已有的 `virtio_arm64only_go` / `virtio_x86_64_go` 源码产品：从构建时决定应用、资源语言、预编译策略和 HAL 集合，并保留 WebView、中文输入/显示、设置、安装器、桌面、网络和相机功能。需要编译后按完整 JAR 实测，**目前没有已编译 Go 产物，也不承诺它一定低于 500 MiB**。

[AOSP ART 配置](https://source.android.com/docs/core/runtime/configure)提供只预编译 boot classpath 和 system server 的构建选项，说明系统分区、数据分区、首次启动与性能之间的取舍。这属于源码产品配置依据，不是对现成镜像任意删除预编译文件的许可。上一轮删除 AVF/CompOS 已失败，仍不采用。

[XZ 官方说明](https://tukaani.org/xz/man/xz.1.html)指出 BCJ 应按目标指令集使用，对混合归档可能变差，因此本轮实际比较两种压缩结果。ARM64 解码由已固定的 XZ for Java 提供，运行时仍执行 64 MiB 解码器内存上限。

## 复现工具

开发者可用新增 Java 工具准备压缩目录，随后使用现有打包器：

```sh
java scripts/CompressRuntime.java STAGE macos-arm64 NEW_XZ_DIR /path/to/xz --arm64-image-compact
java scripts/PackageRuntime.java build/libs/mcandroidphone-0.1.0-prototype.jar \
  STAGE macos-arm64 NEW_PACKAGE.jar --xz-dir NEW_XZ_DIR
java scripts/InspectBundle.java NEW_PACKAGE.jar 500
```

工具只向新目录写入，失败的压缩流保留 `.partial`，不会作为完整 `.xz` 条目打包。工具的多线程块封装可能与诊断时的单线程小文件实验有少量字节差异；以生成包的实际字节为准。最后的 500 MiB 检查在当前产物上应失败，不能忽略失败后宣称达标。

`--arm64-image` 使用 32 MiB 字典；`--arm64-image-compact` 针对系统盘使用 `--arm64 --lzma2=preset=6,dict=48MiB,lc=2,lp=2`，原生小文件仍使用 32 MiB 字典。两者都要求 ARM64 平台及 `runtime.properties` 的 `guestArch=arm64`。对其他架构不能照搬这一滤镜。

证据根目录：`.runtime/evidence/size-500/`。原始镜像、上一版包、默认运行配置和已有手机数据均保留。

## 本机验证

v4 完整测试包：`.runtime/packages/mcandroidphone-macos-arm64-trim-xz-v4-test.jar`；SHA-256 为 `171814efb5548076a1fa06f9eec4d4fac8fcdbb8cc1b84071c6ae1c1d228a11d`。

- `StageMacRuntime`、`CompressRuntime` 经 Java 编译；压缩准备工具的 ARM64 夹具往返通过。现有打包回归继续通过：确定性、ZIP STORED 和拒绝错误预压缩源。
- 打包器通过真实内置 Java 解码器校验全部 36 个 XZ 文件与 staging 的原始内容一致，其中包含 BCJ 系统镜像及原生库。
- `android-v4` 在 `-Xmx256m` 宿主测试 JVM 中直接从完整 JAR 自动解压并启动。两轮真实 Android 16 / ARM64 / HVF 验证了 APK 安装、强制应用编译、WebView 状态、NAT、相机测试图传输和新增照片、重启标记、共享系统底盘不变；两次关机均 `guest-confirmed`。
- 两轮含验收操作分别 113,126 / 58,803 ms。首次解压和虚拟机就绪为 56,371 ms；缓存复用为 2,372 ms。这些不是纯 Android 开机时间，也不是整个虚拟机的内存用量。
- 清理审计 `CLEANUP_OK sessions=2`。此处使用已初始化的独立开发种子与 `power-key` 配置；没有重新证明干净用户盘默认 QMP 关机。上轮的该项超时缺口依旧保留。
- 740 MiB 门槛通过，500 MiB 门槛明确失败，超出 250,097,171 字节。没有六平台真机验收或正式发布。

### 最终 v5 复验

最终包：`.runtime/packages/mcandroidphone-macos-arm64-trim-xz-v5-test.jar`；SHA-256 为 `e9d391edcad735ba8449bcde03dab70ee8469b8586f92a188e50136595cef573`。通过 730 MiB 门槛；500 MiB 门槛失败，仍超出 237,367,879 字节。

打包校验本身和 `FactoryAndroidTest` 均使用 `-Xmx256m` 的 Java 进程，48 MiB 字典被实际私有解码器接受，解压后的所有文件与 v4 同一 staging 校验一致。干净测试目录首次解压和虚拟机就绪耗时 **50,286 ms**；Android 串口出现 `sys.boot_completed=1`，最新渲染帧已人工核验为 “Welcome to LineageOS / Start”。证据为 `factory-v5/verified-setup-screen.png`，没有将启动动画当作完成，也没有走完首次配置向导。

最终干净用户盘仍为 `forced-timeout` 关机结果，与此前默认 QMP 路径的已知缺口一致，不计作正常关机通过。最后的进程审计为 `CLEANUP_OK sessions=3`，三次会话记录的自有子进程和包装进程均已退出。
