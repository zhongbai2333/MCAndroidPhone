# 主项目与运行时边界

项目只有一个 Gradle Java/NeoForge 主项目。原来的 core 与 mod 源码均位于 `src/`，输出一个 JAR；协议包仍不引用 Minecraft、NeoForge 或 LWJGL，保留独立测试能力。

```text
src/main/java/.../core   协议、帧所有权、ManagedRuntime
src/main/java/.../phone  物品、会话生命周期、渲染与触控
src/main/java/.../gpu    Windows D3D11/OpenGL 互操作
src/main/resources      独立模型、shader、元数据
src/test/java           协议、几何、进程生命周期验证
bridge/mcandroid_bridge 后端协议与 _launch 进程辅助模块
                        └─ build 时归档成 bridge.zip，进入主 JAR
scripts                 开发入口与诊断工具，复用 bridge 的辅助模块
```

保留 Python 源码目录有实际意义：它需要由 Python 解释器执行，且可以单独做后端和协议测试；它已经是同一构建产物的一部分，不需要额外的 core.jar 或 bridge 安装项目。重复的进程启动辅助代码统一放入 `mcandroid_bridge/_launch/`。

客户端主手右键调用 `ManagedRuntime`，后台从 JAR 写出 bridge.zip，启动其管理模块，管理模块拉起 QEMU 与 bridge。CPU 路径的 FFmpeg 由 bridge 启动，也属于同一 Windows Job。进程跟随游戏会话结束；安卓镜像和原生程序外置。细节见 [运行环境](runtime.md)。

## 显示与输入

默认路径：QEMU D-Bus 共享 surface → FFmpeg NV12 → mmap → Java direct buffer → 自有 GL_R8/GL_RG8 纹理 → BT.709 shader。只保留最新 surface，转换器最多一张待输出帧，避免旧帧排队；尺寸变化重建会话，Java 自动重连。

实验路径：Android VirGL → ANGLE/D3D11 → QEMU 共享纹理 → Java/OpenGL → MC。无 CPU 像素复制，MC 保留一次 GPU 缓存复制，详见 [GPU 说明](gpu.md)。启动管理不预建首帧探测客户端，MC 直接消费启动时的 GPU scanout。

AndroidPhoneItem 使用自己的 UUID，连接绑定设备身份。手机在玩家库存、副手或鼠标携带格中时保留连接与缓存；离开主手释放触点和焦点，隐藏期间继续消费最新帧。拿回主手不用重新握手，资源重载也保留动态缓存。F8/disconnect/丢失物品只释放显示连接，poweroff/退出世界关闭自有运行环境。

手机内屏固定 9:16，四边等厚窄框；其他比例等比留边，触点只在内屏区域开始。默认 D-Bus 真实单指触屏，QMP 用于导航和管理。

外部 bridge 仍支持 VNC 和 SDK gRPC，供诊断和兼容使用。该模式不接管外部进程。当前不包含音频、多指、多人共享、传感器或持久化手机数据盘。
