# Android 镜像第一轮裁剪（本机 ARM64，2026-09-09）

本轮采用离线修改镜像副本的方法，未在 macOS 上完整编译 Android。输入为现有 Lineage 23.2 / Android 16 ARM64 镜像。源镜像、已用手机目录和默认运行配置均保留；产物先作为本地测试候选。

## GitHub 方案如何用于本项目

| 参考 | 实际采用的经验与边界 |
| --- | --- |
| [AndyCGYan/lineage_build_unified](https://github.com/AndyCGYan/lineage_build_unified) 与 [lineage_patches_unified](https://github.com/AndyCGYan/lineage_patches_unified) | 参考 Light / vanilla 构建及产品包清单。核对的 `lineage-21-pre-qpr2-light` 分支补丁反而补入 Contacts、DeskClock、Gallery2、SettingsIntelligence 等常用组件；因此精简不能只追求删除数量。该分支不是本项目 Android 16 的兼容保证，未照搬个人补丁里的 neverallow 等改动。 |
| [UAD-NG](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation) | 核对 `resources/assets/uad_lists.json` 中彩蛋、屏保、打印推荐的用途与风险。其 ADB 卸载/禁用效果不等于物理缩小系统分区；本项目使用它的组件说明，未运行整套去预装清单。 |
| [MinDebloater](https://github.com/FriendlyNeighborhoodShane/MinDebloater) | 参考显式路径清单、物理移除和保留原件的方式。其默认列表含 CalendarProvider 等本项目仍需的组件，未整体套用；Magisk 模式只隐藏文件，不适合减少分发包。 |
| [ponces/treble_aosp](https://github.com/ponces/treble_aosp) | 已于 2026-04-08 归档，仅作为历史构建参考，不作为当前 Android 16 验收依据。 |
| [unix3dgforce/lpunpack](https://github.com/unix3dgforce/lpunpack) | 使用已有动态分区解析工具提取 super 及 extents，没有从零猜测分区格式。 |
| [e2fsprogs](https://git.kernel.org/pub/scm/fs/ext2/e2fsprogs.git/) | 在 Mac 本地编译官方 1.47.3；用 debugfs 删除明确文件，用 `e2image -ra` 保留所有分配中的数据/元数据并回收空闲块，用 `e2fsck -fn` 检查前后状态。 |

审阅/工具版本记录：

- Andy patches：`01a11cee86eeef3fffa744c8efa37985d28312fc`。
- MinDebloater：`f77bb51168deac6a8d05905e4c99a174d202d1a5`。
- lpunpack：`c59b8f3b069c5a8aa438a049fa4a091177172434`，LGPL-3.0；开发工具未打入用户 JAR。
- UAD 清单下载内容 SHA-256：`0fd756ad820ee34e282c32500b55f0d032cd495ece50266bb9be6176a465d7ef`。未将其全部说明复制到分发包。
- 官方 e2fsprogs 1.47.3 源码归档 SHA-256：`7d4612f4e4f7ca6c2f669679028bcb02763e3b6280c9c19b2cf168eaf65e88af`。

## 明确裁掉什么

[清单](../android/image/trim/lineage-arm64-v1.json) 固定每个输入分区的 SHA-256，逐条列出路径，不使用通配删除。

- product：Backgrounds、PhotoTable；额外铃声、闹钟和通知声音。保留 Argon、Hassium、Orion，以及全部 UI 音效。删除文件 46,802,981 字节。
- system：BasicDreams、EasterEgg、PrintRecommendationService。删除文件 2,903,504 字节。
- system_ext：Vim 程序和配套文档、语法资源。删除文件 22,849,866 字节。
- 合计 72,556,351 字节（69.19 MiB）；壁纸包、屏保、彩蛋、额外声音选择、打印推荐、Vim 不再预装。保留普通壁纸设置和打印基础服务。

保留 WebView、输入法、全部字体（含中文）、桌面、设置、APK 安装器、下载/文件/媒体服务、相机和图库、通讯录/日历、无障碍、网络、所有 APEX、HAL 和内核模块。清单没有删除系统内的 Traceur 或 Stk：这两个并未出现在本次输入的 APK 清单中。

GPT 和 LP 布局保持不变，5 GiB 是虚拟磁盘容量而不是下载量。原镜像 `fstab.virtio` 的动态分区条目没有 avb 参数；本轮未改 boot、vendor_boot、固件、SELinux 或验证策略，也没有重新签名平台 APK。该离线流程不能直接套用到要求验证签名的任意手机镜像。

## 可复现开发流程

仅开发者使用 Python 3.11+ 和本地 e2fsprogs；用户仍通过 Minecraft 的 Java 自动启动运行时。

1. 用 `qemu-img convert -f qcow2 -O raw` 转换**副本输出**，按 GPT 的 super 偏移提取，再用固定版本 lpunpack 提取分区。
2. 输入原始 raw 的 SHA-256 必须为 `0d1c697e29c179fefaafd2646483968a2582fdd0c01373e955fbb9727462b275`；动态分区映射已固定在 [layout](../android/image/trim/lineage-arm64-layout.json)。不同镜像需要重新审阅映射、校验值和清单。
3. 运行以下开发工具；输出目录/文件必须不存在，失败时保留证据，使用新输出目录重试。

```sh
python3 android/image/trim/trim_ext4.py \
  --parts PATH_TO_ORIGINAL_PARTITIONS \
  --plan android/image/trim/lineage-arm64-v1.json \
  --e2build PATH_TO_E2FSPROGS_BUILD \
  --output NEW_TRIMMED_PARTITIONS
python3 android/image/trim/repack_raw.py \
  --source ORIGINAL_RAW \
  --layout android/image/trim/lineage-arm64-layout.json \
  --parts NEW_TRIMMED_PARTITIONS --output NEW_TRIMMED_RAW
qemu-img convert -f raw -O qcow2 NEW_TRIMMED_RAW NEW_TRIMMED_QCOW2
qemu-img check NEW_TRIMMED_QCOW2
```

`--compact-only` 创建不删文件的对照组。工具检查全部输入、已删除路径、未删除路径集合、文件系统、输出哈希和分区边界；重组后逐字节比较逻辑分区之外的数据。未改变已运行虚拟机的底盘或已有差分盘。

## 测量

同一 zlib、ZIP level 4、原有 qcow2 格式实测：

| 系统盘 | qcow2 文件字节 | ZIP 条目压缩字节 |
| --- | ---: | ---: |
| 原始 | 2,264,989,696 | 1,110,384,397 |
| 只回收空闲块 | 2,264,989,696 | 1,109,570,702 |
| 第一轮裁剪 | 2,190,344,192 | 1,055,423,944 |

系统盘 ZIP 总共减少 54,960,453 字节（52.41 MiB），其中纯空闲块回收仅 813,695 字节。这一轮属于温和裁剪，不能据此承诺几百 MiB 的完整 Android 包。既有 XZ 实验没有在本轮集成。

本地证据在 `.runtime/evidence/image-trim/`：清单、分区检查、三组大小测量、构建日志、原件及候选均分开保存。原镜像与候选属于上游系统的本地实验，未发布到 GitHub。

## 本机验收与交付边界

最终候选 `.runtime/packages/mcandroidphone-macos-arm64-trim-v1.1-test.jar`：**1,104,623,075 字节（1053.45 MiB / 1.02876 GiB）**。比历史 v3 的 1,159,575,082 字节减少 **54,952,007 字节（52.41 MiB，4.74%）**。新包包含当前 Java 改动，因而整包差值与纯系统盘差值略有差异；263 个运行时文件经过打包清单校验。此包仍携带原有干净用户盘，并不是已初始化的开发手机数据。

- `android-test-v4/`：基于独立开发种子副本，两轮真实 Android 16 ARM64 / HVF 均完成 APK 安装、核心包与 WebView 状态、来宾 NAT 连通、相机传输、MediaStore 新增照片、无手工 sync 的重启标记和共享底盘不变检查。每轮含验收操作约 62 / 58 秒，不能当作纯开机时间。
- 相机使用明确标记的绿色测试图，验证 Java 到 Android 应用的传输和保存；这不代表本轮重新进行了 Minecraft 世界画面测试，也不代表标准 Camera2 HAL 已实现。
- `android-test` 首轮网络尚未就绪；等待网络后通过。`android-test-v2/v3` 拍照后关机超时。保持相机/传感器连接到 QEMU 退出后，v4 两轮均 `guest-confirmed`；没有把仅延长超时的中间方案算作修复成功。普通退出和宿主 SIGKILL 清理回归通过。
- `build.log` 的项目 build/check 和 `lifecycle-test.log` 的 `runtimeSelfTest` 通过。所有原始、对照、裁剪分区均经过离线文件系统检查，qcow2 检查通过。
- `factory-test` 使用 JAR 自动解压和全新用户盘，ADB 启动判据没有完成，记录为失败。不能把该测试误报为正常完成，也不能把开发种子上的通过直接外推到全新用户首次配置。

系统盘候选：`.runtime/evidence/image-trim/trimmed-v1.qcow2`，SHA-256 `f990d97cbd22a48459833cae293d420584e5e687d5487e2c77b84755764e991f`。原系统盘 SHA-256 仍为 `7fdcc082c535f6a0e7e8f38d982cd27d4824c502d7d61ecab34462111811f563`。

下一轮大幅缩小需要审阅 APEX、预编译资源及产品构建配置。WebView、输入法、CJK 字体和图形库占比高，但删除会直接损害已有功能；本轮没有为追求数字而移除它们。更换旧 Android 或完整套用通用去预装列表没有得到本机兼容性保证，未执行。

完整 JAR 首次渲染复验：`factory-render-test/verified-setup-screen.png` 已人工核验为 **Welcome to LineageOS / Start** 初始化界面，来自 Java 自动解压运行时、启动 QEMU 和全新用户盘，不依赖宿主 Homebrew 路径或 Python 来启动。该结果证明能进入 Android 首次设置；没有完成整套首次设置向导，也未证明干净用户盘的默认 `shutdownMethod=qmp` 能正常关机。后者仍按已有超时路径清理，不等同于已初始化开发种子中 `power-key` 的正常关机通过。

最终清理审计 `cleanup-audit.json` 覆盖本轮 7 次虚拟机会话，自有 QEMU/FFmpeg/守护进程均已退出。全新包复验记录为 `FACTORY_SHUTDOWN_OUTCOME forced-timeout`，是明确保留的缺口，不算正常关机通过。最终 JAR SHA-256：`2455db9536588d7d084710c9bd27c9e295768ae4d2d8ed317381c64d1a94815b`。
