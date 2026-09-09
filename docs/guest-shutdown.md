# 正常关机与强制清理

持久手机关闭时，Java 先验证 QEMU UUID，再请求来宾关机，等待最多 15 秒。只有 QMP `SHUTDOWN` 事件同时携带 `guest=true` 和 `reason=guest-shutdown` 才记为正常关机。请求应答、`POWERDOWN` 通知、宿主终止或来宾重启均不视为关机成功。确认后让 QEMU 自行退出；超时或协议错误仍清理自有进程，防止退出游戏后残留 VM。

`session.json` 的 `shutdownOutcome` 区分 `guest-confirmed`、`forced-timeout`、`forced-error`、`immediate-snapshot` 和 `not-requested`。强制清理状态会明确提示未落盘数据可能丢失。快照测试保持直接丢弃的原有行为；JVM 被强杀仍依靠 guardian 清理，无法保证 Android 来得及完成关机。

## 镜像契约

| 配置 | 行为与适用范围 |
| --- | --- |
| `shutdownMethod=qmp`（默认） | 发送 `system_powerdown`；适用于能自动响应该请求的来宾。现成测试 LineageOS 镜像未确认关机，实测进入有界强制清理。 |
| `shutdownMethod=power-key` | 通过 QMP 按住虚拟电源键 1500 ms，适用于长按电源配置为直接正常关机的 Android 镜像。配置不匹配时可能出现电源菜单或助手，最终按超时处理，不假定数据已保存。 |

定制产品的资源覆盖把 `config_longPressOnPowerBehavior` 设为 3、持续时间设为 500 ms，并关闭助手选项入口。其来源是 [AOSP 电源键资源配置](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr1-release/core/res/res/values/config.xml)。已有 Android 用户设置可能覆盖资源默认值；不会自动改写已有手机。

开发阶段可在**独立测试镜像**中用 `settings put global power_button_long_press 3` 配置等效行为；这是测试准备，不是游戏运行时的关机实现，也不会在用户电脑上自动开启 ADB。QMP 完成信号依据 [QEMU 运行状态协议](https://www.qemu.org/docs/master/interop/qemu-qmp-ref.html)。

## 验证

`QmpShutdownSelfTest` 覆盖确认先于命令响应、延迟确认、仅收到请求通知、宿主关闭、来宾重启、错误 UUID 以及长按键参数。已加入 Gradle `check` 和 Java `Dev quick`。图像构建资源覆盖已在独立 fixture 验证应用和重复检查；尚未进行 Linux 完整 Android 编译。

最终 `gradlew build --offline` 和 `runtimeSelfTest --offline` 本机通过；后者涵盖连接/重连、取消、启动失败、自有进程树回收和宿主 SIGKILL 清理。日志为 `final-build.log`、`lifecycle.log`，普通模组 JAR 约 263 KiB，其 SHA-256 记录在同证据目录的 `artifact.sha256`。

2026-09-09 Mac ARM64 / HVF / Android 16 实测：未适配镜像的普通 QMP 请求超时，记录 `forced-timeout`；配好长按行为后，A 首次启动、B 首次启动、A 重启三轮均记录 `guest-confirmed`。A 的测试文件写入后未手动 `sync`，由普通 `ManagedRuntime.close()` 请求关机；重启后内容完整，B 不包含该文件。串口记录 `sys.powerctl=shutdown,userrequested`、卷关闭、卸载及 `reboot: Power down`，系统底盘哈希未变。启动时间分别为 45,016 / 42,784 / 38,385 ms，不包含关闭耗时，不作为性能对照。

证据：`.runtime/evidence/guest-shutdown/android.log` 是未适配的超时案例；`power-key.log`、`power-key/acceptance.json` 和会话串口日志是正常关机验收。`policy-*.txt` 记录实际长按策略。两组四个会话的 48 个 guardian/child PID 均已退出，专用 ADB 服务关闭，两台正常关机设备的 QEMU 磁盘完整性检查通过。

本机新种子位于 `.runtime/images/lineage-arm64/prepared-shutdown-v1/`，来自已正常关机的 B 设备，不含本次测试标记。用户盘和 EFI 变量均通过 `qemu-img check`。`run/config/mcandroidphone-runtime.properties` 已指向该种子并选择 `shutdownMethod=power-key`；原配置备份为 `.runtime/evidence/guest-shutdown/runtime-before.properties`。原系统模板和旧种子保留，已有手机不迁移。它是本地开发用预配置种子，仍包含原开发镜像的用户数据，不是干净的小体积发布镜像；历史 v3 大包没有重建。

新种子另外完成同一台新手机的首次启动和重启验收：40,885 / 36,508 ms，均 `guest-confirmed`，测试标记完整保留。该轮没有执行 `settings put`、没有手动 `sync`，关机模式直接从本机运行配置读取；ADB 仅用于开发验收写入标记和读回状态。证据为 `seed-test.log`、`seed-test/acceptance.json`、`seed-test/cleanup-and-size.json`。此轮只有一台手机两次启动，日志末尾通用打印的 “two independent phones” 不作为双设备验收依据，双设备证据来自前述三轮测试。此轮另外 24 个 guardian/child PID 已退出。

## 拍照后的关闭顺序回归

2026-09-09 镜像裁剪实测中，启用相机并拍照后提前关闭宿主通道导致来宾关机停滞，10 秒及单独增加到 15 秒均超时。现在先使相机帧失效，保持相机/传感器通道连接到 QEMU 退出，再清理通道；15 秒上限下两轮拍照后关机均得到 `guest-confirmed`，重启标记保留。证据见 `.runtime/evidence/image-trim/android-test-v4/`。
