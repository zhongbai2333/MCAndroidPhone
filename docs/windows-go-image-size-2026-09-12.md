# Windows AMD64 Go 镜像压缩 — 2026-09-12

本轮以已验收的启动优化 v5 为固定输入，保留全部 Android 文件、预编译内容和功能。**镜像分发归档从 1,103,426,413 字节降至 803,320,927 字节（766.11 MiB），减少约 27.2% / 286.2 MiB。**这只是 Android 三盘的镜像归档，未包含 QEMU、ANGLE、FFmpeg 或 Mod，不能当作完整离线 JAR 的大小。

## 两种体积分别计算

| 形式 | 字节 | MiB | 用途/结果 |
| --- | ---: | ---: | --- |
| 原 v5 系统 qcow2 | 2,163,605,504 | 2063.38 | 解压后系统模板 |
| 原 UTM ZIP | 1,103,426,413 | 1052.31 | 含三盘及 UTM 配置 |
| 普通 XZ，48 MiB 字典 | 807,674,824 | 770.26 | 无损系统盘分发 |
| **x86 BCJ + XZ，48 MiB 字典** | **803,311,332** | **766.10** | 最终分发用系统盘，较普通 XZ 再少 4.16 MiB |
| **最终三盘镜像归档** | **803,320,927** | **766.11** | ZIP 内的 XZ 条目用 STORED，不重复压缩 |
| QCOW2 内部 Zstd | 1,135,440,896 | 1082.84 | 内容一致，但现有 SharedSystemDisk 拒绝该扩展特性，未进入 QEMU，未采用 |
| QCOW2 内部 zlib | 1,139,972,608 | 1087.16 | 内容一致，兼容现有 QCOW2 校验；运行结果见下文 |

虚拟系统盘容量仍为 5 GiB；稀疏/压缩改变物理文件大小，不会缩小来宾地址空间。用户数据盘模板 196,864 字节、EFI 变量 335,360 字节，应用安装后独立数据盘仍会增长。内部压缩模板也可作为共享底盘，日常写入继续进入各手机的普通差分盘。

XZ 分发版解压后仍是原来的 2.02 GiB 文件。QCOW2 内部压缩版落盘约 1.06 GiB、减少约 47.3%，属于另一种表示形式。不能把 XZ 的下载大小和 QCOW2 的落盘节省相加，也不假定两层压缩叠加更小。

## 校验和耗时

固定输入 SHA-256：`4dc2e820a38ac65e28009b9c3dcdeb047a8a39ad2b7fb8c506cde604cec66c3e`。

- 两组 XZ 同时各 4 线程运行于 E 盘 WSL，x86 BCJ 386.09 秒、普通 XZ 398.17 秒。压缩是开发构建步骤，普通用户无需 XZ 工具。
- **成品 Mod 内置 Java 解码器**在 `-Xmx256m` 下完整解压并写入 2,163,605,504 字节，SHA-256 与原盘一致。耗时 **50,087 ms**，不含先读取原盘作对照哈希的时间。解码器维持 64 MiB 内存限制，未引入新用户依赖。
- Java 解压属于首次缓存准备；运行时复用已校验缓存时不需要每次冷启动重新解压。本轮测的是独立镜像解码，不是整包首次安装时间。
- 内部压缩的 zlib / Zstd 都通过 `qemu-img check` 和 `qemu-img compare`，来宾磁盘内容完全一致。Zstd 被生产存储层在创建手机时提前拒绝，不能把格式比较通过当作运行兼容通过；保留该失败记录，未放宽特性校验。
- 最终镜像 ZIP CRC 通过，SHA-256：`1f7d8819819a2511ce374a65962dece8868a8c202a5f15505f92a9cf45c64478`。

压缩准备工具 AMD64 原生 XZ 往返、错误平台/错误 guestArch 拒绝通过；实际大镜像由同一 Java codec 进行往返验证。没有修改 Android 产品源码或普通运行时代码，因此本轮不需要重编 Android。

## 产物和复现

本机镜像归档：`.runtime/packages/lineage-go-amd64-v5-images-xz.zip`。含 `vda.qcow2.xz`、`vdb.qcow2`、`efi_vars.fd`、SHA256SUMS 和说明。XZ 系统盘须先恢复为 qcow2 后才可直接交给 QEMU；完整离线 Mod 可复用已有 `PackageRuntime --xz-dir` 清单/自动解压链路。

```sh
java scripts/CompressRuntime.java STAGE windows-amd64 NEW_XZ_DIR /path/to/xz --amd64-image-compact
java -Xmx256m scripts/VerifyImageXZ.java BASE_MOD.jar SYSTEM.qcow2.xz ORIGINAL.qcow2 NEW_DECODED.qcow2
java scripts/PackageRuntime.java BASE_MOD.jar STAGE windows-amd64 NEW_PACKAGE.jar --xz-dir NEW_XZ_DIR
```

`STAGE/runtime.properties` 必须含 `guestArch=amd64` 和正确相对系统盘路径。大镜像比较使用 XZ `-T4 --x86 --lzma2=preset=6,dict=48MiB`；工具保留既有 `-T2`，线程数影响构建吞吐，不改变指定滤镜和字典。实际打包器会再次检查每个 XZ 文件与 staging 原文件匹配。

全部原始镜像、旧归档和手机数据保留。实验盘位于 `.runtime/images/lineage-go-amd64-20260912-compact-zlib-v6/`；原 Zstd 候选保留但不推荐使用。本机默认配置仍指向已验收的 v5，XZ 解压后的内容与它完全相同。

[完整数字与哈希](handoff-evidence/2026-09-12/windows-go-image-size/results.json)。Windows 详细证据 `.runtime/evidence/image-size-20260912/`，WSL 同名证据目录在 Linux 工程下。

系统内容主要体积为 WebView APK（约 332 MiB 原始数据）、APEX、framework、图形库和 SystemUI。这个单架构镜像归档仍高于 500 MiB；当前测量不支持仅靠无损压缩即可达到完整离线包 500 MiB 的结论。没有把其他平台旧包的大小当作本机 Go 镜像对照，也没有本轮六平台验收。


## 内部压缩盘实际运行验收

Windows QEMU 11.1.1 / WHPX / SandyBridge / 4 vCPU / 4096 MiB / VirGL，zlib 压缩底盘 SHA-256 与构建报告一致。创建全新持久化手机：运行时就绪 4.66 秒，首个传输帧 112.93 秒，Android 完成启动 118.64 秒，关闭得到 `guest-confirmed`。压缩前 v5 的对应单次结果为 8.07 / 115.75 / 121.35 秒。

从这台已初始化手机的临时磁盘快照进入 Minecraft，真实物品入口到首个 GPU 帧约 87 秒（前次未内部压缩版本约 84 秒）。主动等待到 120 秒后截图确认欢迎页正常，收纳恢复同连接/epoch、无需右键，资源重载通过。D3D11 共享纹理 CPU 像素复制 0 次、GPU 缓存复制 1 次。未重测完整应用性能；两轮宿主缓存等条件不是严格相同，不对几秒差异作确定归因。

[压缩盘游戏内欢迎页](handoff-evidence/2026-09-12/windows-go-image-size/compressed-disk-welcome.png)

已准备本机独立配置，测试新手机物品即可使用压缩底盘：

```powershell
$env:JAVA_HOME='D:\Program Files\Zulu\zulu-25'
.\test-phone.cmd qemu --runtime-config .runtime/go-windows-compact.properties
```

现有默认 `.runtime/go-windows.properties` 和旧手机不变。测试虚拟机及其守护进程均已退出；Zstd 失败发生在启动前，没有留下 QEMU 进程。最终仅同步源码、文档与小型证据，镜像归档保留在本机。
