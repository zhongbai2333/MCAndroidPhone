# 完整 Go 镜像压缩对比 · 2026-09-13

本轮仅改变分发压缩，不删除组件、不重编 Android；两架构都保留 WebView。现有发布候选保持原 XZ。

## 测量口径

输入是相同的干净 QCOW2 系统模板。AMD64 原始文件 2,163,605,504 字节，ARM64 1,915,813,888 字节。表格统计系统盘压缩文件，完整三盘 ZIP 另有约 8–10 KB 开销；MB 为十进制，MiB 为二进制。

WSL Ubuntu 24.04，XZ 5.4.5、zstd 1.5.5。每个压缩器 2 线程，同时最多运行 2 个压缩任务；压缩耗时包含竞争。全部压缩完成后，每个文件串行解压并流式 SHA-256 校验一次。耗时和峰值 RSS 来自原生工具，不是 Minecraft/Java 或 M4 的性能承诺，也不是严格独占主机的基准。

原 XZ 使用架构匹配的 BCJ 预处理和 48 MiB 字典；增强组为 preset=6e。ARM64 的两种 XZ 均保留 lc=2,lp=2；zstd 两组使用 --long=27（128 MiB 窗口）。原生解压进程的峰值 RSS 不等于整个游戏内存。

## AMD64

| 方案 | 压缩后 MB | 相比现有 XZ | 压缩秒 | 解压并校验秒 | 解压进程峰值 MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| 现有 XZ / 48 MiB | 803.30 | +0.00 MB | 未重测 | 33.29 | 49.8 |
| 增强 XZ / 48 MiB | 803.31 | +0.01 MB | 462.6 | 33.26 | 50.0 |
| 增强 XZ / 128 MiB | 777.16 | -26.14 MB | 580.8 | 31.94 | 130.0 |
| zstd 19 / 128 MiB 窗口 | 827.92 | +24.62 MB | 373.1 | 2.83 | 132.2 |
| zstd 22 / 128 MiB 窗口 | 809.47 | +6.17 MB | 589.1 | 2.84 | 132.2 |

[完整数据](handoff-evidence/2026-09-13/download-release/compression-amd64.json)。五个文件解压后均与同架构原始镜像的 SHA-256 一致。

## ARM64

| 方案 | 压缩后 MB | 相比现有 XZ | 压缩秒 | 解压并校验秒 | 解压进程峰值 MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| 现有 XZ / 48 MiB | 683.71 | +0.00 MB | 未重测 | 27.18 | 50.0 |
| 增强 XZ / 48 MiB | 683.33 | -0.38 MB | 382.8 | 27.01 | 50.0 |
| 增强 XZ / 128 MiB | 669.75 | -13.96 MB | 501.9 | 26.50 | 130.0 |
| zstd 19 / 128 MiB 窗口 | 747.22 | +63.51 MB | 308.5 | 2.52 | 132.5 |
| zstd 22 / 128 MiB 窗口 | 730.59 | +46.88 MB | 463.1 | 2.50 | 132.2 |

[完整数据](handoff-evidence/2026-09-13/download-release/compression-arm64.json)。五个文件解压后均与同架构原始镜像的 SHA-256 一致。

## 选择

- 仅提高 XZ 搜索强度几乎没有收益；128 MiB 字典分别省 26.14 MB（3.25%）和 13.96 MB（2.04%），不足以把完整包压至 500 MB。
- zstd 22 分别增大 6.17 MB（0.77%）和 46.88 MB（6.86%），原生解压与校验分别为 2.84 秒和 2.50 秒，约为现有 XZ 耗时的 1/12 和 1/11。
- 当前 Mod 内置 XZ 解码器限制 64 MiB。成品 JAR 实测拒绝 128 MiB 字典：需要 131181 KiB，上限 65536 KiB。采用大字典需要调整预算；采用 zstd 需要新增并验证解码支持，不能只更改扩展名。
- 本轮发布维持原 XZ；后续更值得评估 zstd 的实际游戏首次安装收益。下载/解压只影响首次准备，不能把原生解压提速算成每次 Android 冷启动提速。

## 复现

Linux 主机安装 Python 3、xz、zstd 和 GNU time 后，使用 `scripts/compare-image-compression.py`：

```sh
python3 scripts/compare-image-compression.py \
  --image /path/to/vda.qcow2 --baseline /path/to/vda.qcow2.xz \
  --arch amd64 --output /path/to/new-comparison-directory --jobs 2
```

ARM64 使用 `--arch arm64`。输出目录必须是新目录，既有镜像和发布包不会被覆盖。完整压缩产物保留在本地 `.runtime/evidence/compression-compare-*` 对应 Linux 证据目录；仓库只保存小型数据与脚本。
