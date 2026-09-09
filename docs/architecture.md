# 主项目与运行时边界

项目是一个 Java 25 / NeoForge Gradle 项目，输出一个模组 JAR。core 不引用 Minecraft、NeoForge 或 LWJGL，可独立编译测试。

| 目录 | 职责 |
| --- | --- |
| `src/main/java/.../core` | Java 运行时、原生守护、QMP/RFB/D-Bus、帧所有权和输入 |
| `src/main/java/.../phone` | 物品、游戏生命周期、渲染与触控 |
| `src/main/java/.../gpu` | Windows D3D11/OpenGL 互操作 |
| `src/test/java` | 独立协议、几何、真实进程和运行时验证 |
| `scripts/Dev.java` | 无 Python 的开发测试入口 |
| `bridge/mcandroid_bridge` | 保留的旧协议/SDK 外部诊断与回归对照，不打入 JAR |

ManagedRuntime 在游戏 JVM 中创建 LocalDevice，提供 PhoneConnection 视图。默认启动 QEMU，诊断模式直接生成 NV12。F8 关闭视图而不关闭设备；poweroff/退出世界停止设备。外部 bridge 配置仍使用 BridgeClient 实现同一接口。

每个原生进程由私有 native-guard.jar 启动独立 Java guardian。只有 guardian 加入 POSIX session/process group 或 Windows Job，游戏 JVM 不加入。guardian 在父 JVM 注册后才启动 native child，并直接继承其 stdin/stdout，不转发视频像素；独立 watchdog 观察父 JVM 死亡。外部二进制只有 QEMU、FFmpeg/ANGLE 和 Android 媒体。

## 显示与输入

- Mac/Linux CPU：QEMU VNC → Java RFB → FFmpeg NV12 → 四槽直接缓冲池 → OpenGL R8/RG8 → BT.709 shader。
- Windows CPU：QEMU D-Bus Map/像素更新 → Java 有界 surface → FFmpeg → 同一缓冲池。
- Windows GPU：QEMU D3D11 scanout handle → Java GPU lease → OpenGL 导入/缓存。UpdateTexture2d 回复必须等渲染器释放；重连重新注册监听器取得静态帧。Java 原生 Windows 路径仍需实机验收。

CPU 只保留最新帧和最多一张待转换帧，缓冲区直到最后一个租约关闭才复用。尺寸变化/重连更新 epoch，输入任务在执行前核对视图和 epoch。QMP 用于导航；VNC 用于鼠标/文本；Windows D-Bus 支持直接触屏、鼠标和键盘。

PhoneConnection 视图关闭不影响 QEMU。主手/背包恢复、设备 UUID、内屏比例和触控命中由 phone 包管理。外部桥接只在显式 bridgeConfig 下启用，不接管其进程。

当前不包含音频、多指、多人共享和持久化手机数据盘。游戏位置/姿态已加入可选独立通道，并通过真实 Android 的开发测试接口验证；正常模式的传感器/GNSS HAL 特化源码尚待完整镜像编译，见 [环境联动](game-environment.md)。完整运行与测试边界见 [runtime](runtime.md) 和 [Java 迁移记录](java-runtime-migration.md)。
