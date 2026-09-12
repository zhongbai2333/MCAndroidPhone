# 开发与诊断入口

日常入口是 `../gradlew.bat runClient`，游戏内右键手机后由 JAR 启动运行环境。`../test-phone.cmd` 默认也只启动 MC，执行 Dev.java，支持临时世界自检；不再预启动 QEMU/bridge。

- `Dev.java`：当前 Java 25 开发入口，quick、build、pattern、qemu、bios、runtime-smoke 均不依赖 Python。
- `StageMacRuntime.java` / `PackageRuntime.java`：准备本机运行时并构建内置镜像包。清单、配置和 ZIP 元数据不含构建时间，同一份输入不会因重新打包而生成另一份运行时缓存。`BundleMetadata.java` 是共享构建辅助类，需与入口文件保留在同一目录；不属于用户运行依赖。
- `PackageRuntimeSelfTest.java`：小型合成包验证字节可复现、时区/文件时间/ZIP 顺序变化、配置转义、mod 更新复用缓存及镜像更新失效；运行 `java scripts/PackageRuntimeSelfTest.java`。
- `dev.py`：保留的 Python 开发兼容入口，生产后端仍使用 Java。
- `quick-test.py`：MC 开发启动、无游戏快速检查。`--external-bridge` 明确启用旧协议诊断；SDK Emulator 和 BIOS 诊断保留外部模式。
- `transport-smoke.py`：直接编译 Java core 包，验证 TCP/mmap/触摸；不加载 Minecraft。
- `gpu-smoke.py`：验证真实 D3D11 纹理导入；`qemu-transport-smoke.py` 验证固件尺寸变化。这些是底层诊断，不是游戏运行依赖。
- `doctor.ps1`、`setup-angle.ps1`：检查或准备本机开发依赖。
- `start-emulator.ps1`、`emulator-probe.py`：旧 SDK Emulator 兼容诊断。
- `tests/`：进程所有权、命令转义和失败清理的回归测试，仍有实际用途。

生产 QEMU 参数、Windows Job 和子进程门控现位于 Java core。`bridge/mcandroid_bridge/_launch/` 仅供旧 Python 诊断/回归；不打入 JAR。

## Android 镜像裁剪（仅开发阶段）

离线 ext4 副本裁剪工具、固定输入哈希与删除清单位于 `android/image/trim/`，完整步骤和开源方案依据见 [裁剪记录](../docs/android-image-trimming.md)。这些开发工具不进入用户 JAR，不增加用户端 Python 依赖。真实验收入口为 `TrimmedAndroidTest`；`FactoryAndroidTest` 从 classpath 内置包启动新用户盘并保存供人工核验的画面，截图本身不自动等同于 Android 启动成功。

## XZ 镜像分发

`PackageRuntime.java ... --xz-dir DIRECTORY` 支持校验并嵌入预压缩镜像，用户首次启动由纯 Java 解压，无额外环境依赖。步骤、包体积与兼容边界见 [压缩记录](../docs/runtime-compression.md)。运行 `java scripts/PackageRuntimeXZSelfTest.java build/libs/mcandroidphone-0.1.0-prototype.jar` 可验证压缩打包的确定性和镜像匹配。

### 更小的 Mac ARM64 离线包

`StageMacRuntime` 针对显式 UEFI 的 ARM64 virt 机器仅保留需要的 QEMU 数据类别；AMD64 路径保持完整复制。`CompressRuntime.java` 可准备原生文件和系统镜像的 XZ/ARM64 BCJ 压缩目录，再交给 `PackageRuntime --xz-dir` 做逐文件解码校验。500 MiB 预算、实测分项与取舍见 [体积预算记录](../docs/runtime-size-budget.md)。这些均为开发构建工具，普通用户通过 Java 自动解压运行时。


### Windows AMD64 Go 镜像压缩

`CompressRuntime` 增加 `--amd64-image-compact`，仅对明确的 AMD64 系统镜像使用 x86 BCJ + XZ 48 MiB 字典；平台和 `guestArch` 必须都匹配，原 ARM64 选项保持。普通 Java 用户不需要运行压缩工具。

`java scripts/CompressRuntimeSelfTest.java /path/to/xz` 验证原生 XZ 往返与架构保护（源码联编需要 Java 25，也可 javac 两个工具类后运行）。`VerifyImageXZ` 使用成品 Mod JAR 内置解码器恢复压缩镜像，并与原盘的完整 SHA-256/字节数核对，只创建新的输出文件：

```sh
java -Xmx256m scripts/VerifyImageXZ.java BASE_MOD.jar SYSTEM.qcow2.xz ORIGINAL.qcow2 NEW_DECODED.qcow2
```

本机 766 MiB 镜像归档、首次 Java 解压开销、QCOW2 内部压缩的兼容性和启动验证见 [Windows Go 镜像体积](../docs/windows-go-image-size-2026-09-12.md)。镜像归档不等于完整离线 Mod 包。
