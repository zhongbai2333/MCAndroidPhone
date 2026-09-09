# 共享系统底盘

新建持久手机默认使用 `diskLayout=overlay`。首次把系统模板复制到设备目录的内容寻址底盘；同一系统版本的其他手机只创建 256 KiB 系统差分盘，不再完整复制系统盘。用户盘和 EFI 变量保持独立。底盘复制和校验由 Java 完成，最小 qcow2 v3 差分盘也由 Java 创建；之后的读写由 QEMU 处理，无新增用户工具依赖。

```text
mcandroidphone/
  bundles/                         可重新解压的运行时缓存
  devices/
    .bases/<sha256>.qcow2           持久系统底盘，不属于缓存
    <phone-uuid>/disk.qcow2         该手机的系统修改
    <phone-uuid>/dataDisk.qcow2     该手机的应用和用户数据
    <phone-uuid>/firmwareVars.*     该手机的 EFI 变量
    <phone-uuid>/storage.properties
```

差分盘用 `../.bases/<sha256>.<格式>` 相对引用底盘。完整移动 `devices`（包括隐藏目录）后仍可启动；仅移动某台手机目录不构成完整备份。当前没有自动删除底盘的功能，不要把 `.bases` 当作可清理缓存。每次使用会验证底盘 SHA-256 及差分盘引用，缺失/变化时保留设备并报错，不会悄悄改绑新系统。

## 版本与兼容性

- 原 `schema=1` 手机继续使用完整磁盘，默认设置变化不会迁移旧数据。
- 新 `schema=2` 手机记录底盘内容哈希；mod 或镜像更新后，旧手机仍引用旧底盘，新手机按新模板创建。
- 首个底盘独立复制一次，保证不依赖外部模板或安装包缓存的寿命。它会额外占用一份系统模板空间；节省的是后续每台手机的系统盘复制，安装包下载大小不变。
- 同一底盘创建由文件锁保护；临时文件复制后再次验证哈希，再原子安装。设备自身保持 UUID 排他锁。取消复制会清理当前临时文件并释放锁。
- 当前允许扇区对齐、最大 1 TiB 的 raw 或独立 qcow2 v2/v3 系统模板。带外部底盘、加密或非零不兼容特征位（包括脏盘标记）的 qcow2 被拒绝。此类模板需要先离线整理，或明确为新手机选择 `diskLayout=copy`。
- `storage=snapshot`、固件诊断和非 QEMU 后端保持原有行为。

格式依据：[QEMU qcow2 规范](https://www.qemu.org/docs/master/interop/qcow2.html)。只实现空差分盘初始化，没有在 Java 中实现完整 qcow2 读写器。

## 验证入口

`./gradlew build --offline` 包含便携存储回归。`java scripts/Dev.java quick` 同样运行这些检查。原生工具仅用于开发验证：

```sh
java -cp build/classes/java/main:build/classes/java/test \
  com.zhongbai233.mcandroidphone.core.SharedSystemDiskSelfTest /opt/homebrew/bin
```

本机原生检查已通过 `qemu-img check`、真实读写、raw/qcow2 底盘、两设备隔离、系统模板更新、删除原模板后重启、目录迁移、底盘缺失/损坏拒绝。便携回归还验证并发创建、哈希取消、复制取消后的临时文件清理及锁释放。其他操作系统的原生执行仍需目标平台实测。

2026-09-09 本机 Android 16 / ARM64 / HVF 实测完成：A 首次启动 41,141 ms，B 首次启动 38,022 ms，A 重启 35,876 ms。A 写入并同步的测试标记在重启后完整保留，B 不包含该标记；三轮共用一份系统底盘，最终 SHA-256 不变。两块系统差分盘最终分别为 2,949,120 字节（2.81 MiB）和 1,835,008 字节（1.75 MiB），均通过 QEMU 磁盘完整性检查。此计时包含设备准备和 Android 启动，不作为与旧实现的启动速度对照。

证据位于 `.runtime/evidence/shared-system-disk/`：`build.log`、`native-check.log`、`synced-android.log`、`synced/acceptance.json`、`synced/cleanup-and-size.json`。初轮未同步写入的失败日志 `android.log` 也保留，不算持久化通过证据。两轮六个会话共 72 个 guardian/child PID 已退出，专用 ADB 服务已关闭。新普通模组 JAR 约 261 KiB，SHA-256 为 `b6f3bb766100a8cec335203f559eb236eb1ab8126f745b5e8a6b94d934b43be6`；没有重新制作历史 1.08 GiB 大包。

## 关机持久化边界

本次真实测试还暴露了已有关闭流程的限制：写文件后立即通过 QMP `quit` 终止 VM，重启后该文件出现空内容；对应两块差分盘的 `qemu-img check` 均通过，底盘哈希也未改变。测试驱动原先没有等待 Android 同步写缓存。后续持久化验收明确在测试写入后执行来宾 `sync`，不能把它表述为“任何未落盘写入都能在直接断电后保留”。

以上为关机修复前的实验。后续源码已加入来宾正常关机请求和确认，适配镜像使用长按电源完成 Android 关机；不支持的镜像会超时并记录强制清理。具体兼容条件与真实验收见 [正常关机](guest-shutdown.md)。生产关机不调用 ADB 或脚本；直接强杀 JVM 仍不具备正常关机保证。
