# Windows GPU 共享画面（实验）

已实现 **Android VirGL → ANGLE/D3D11 → QEMU D-Bus 共享纹理 → Java/OpenGL → MC 手机**。生产画面路径不读回 GPU 像素、不调用 FFmpeg、不转换 NV12、不创建像素 mmap。Python、TCP 和 Java core 只处理句柄、尺寸、更新通知与输入。

这实现了 **零 CPU 像素拷贝的 GPU 传输**，尚不是整个显示路径的严格零拷贝：MC 为延后绘制和静止画面保留 **一次 GPU 内部缓存复制**。不要将它称为“完全零拷贝”或将约 1 ms 的导入测试耗时当成触摸到屏幕的端到端延迟。

手机 Mod 已独立；收进背包保留连接和 GPU 缓存，不重新注册静止桌面的监听器。此行为避免了收纳后等待客体下一次更新的问题；真正断开并重新连接时的静态 scanout 限制仍存在。

JAR 已负责右键开机时启动 QEMU 和内置桥接；不预先注册首帧探测客户端。原生依赖配置见 [运行环境](runtime.md)。

## 启动

本机 ANGLE 运行库已准备好，在项目目录执行：

```powershell
.\test-phone.cmd qemu --qemu-gpu virgl
```

保留 1080×1920 / 480 dpi；直接内核参数为 `HWACCEL=1 GRALLOC=gbm HWC=drm`，设备为 `virtio-vga-gl`，显示为 `dbus,p2p=on,gl=on`。GPU 模式不要求 FFmpeg，不依赖 Android SDK 的任何 DLL。

`auto` 仍使用已验证的 virtio 2D/CPU 路径。GPU 模式是显式选择；需要兼容路径时运行 `test-phone.cmd qemu --qemu-gpu virtio`。不要用 `--qemu-bios` 验收 GPU 纹理：BIOS 只有系统内存画面，D3D11 模式会跳过它。

其他机器一次性准备 ANGLE：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/setup-angle.ps1
```

脚本下载固定版本的 MSYS2 ANGLE，验证固定 SHA256，仅安装到 `.runtime/angle/`，保留许可证和来源记录。不会修改系统 PATH、驱动、Android SDK 或系统镜像。QEMU 需要同时具备 VirGL、D-Bus、OpenGL 支持；Android 镜像也必须具备相应客体驱动。MC OpenGL 与 D3D11 纹理必须位于同一显卡，并支持 `GL_EXT_memory_object_win32`、`GL_EXT_win32_keyed_mutex` 与 copy-image。

## 快速验证

```powershell
.venv\Scripts\python.exe scripts/gpu-smoke.py
```

使用项目已有 QEMU/Android 运行时和缓存的 JDK/Gradle/LWJGL；编译项目类，但不启动 Minecraft、不创建 MC 世界。启动临时 Android，实际将三帧 D3D11 纹理导入 OpenGL，验证同步、GPU 复制和诊断读回，输出 `GPU_SMOKE_OK` 后清理受管虚拟机。首次纹理导入包括驱动/设备初始化，耗时高于预热后的帧。

这里的像素读回和 CRC 仅在诊断程序中，生产 Mod 不调用它们。桥接自己的 `mcandroid_bridge.smoke` 在 GPU 模式只验证描述符和 ACK，不足以证明 GPU 导入或最终画面正确。

## 纹理所有权和同步

1. `ScanoutTexture2d` 交付 QEMU 已复制到 bridge 进程的 NT 句柄。旧 Scanout 句柄在不再使用时关闭。
2. 只有 `UpdateTexture2d` 才作为可读租约。QEMU 在这期间释放 KeyedMutex(0)；bridge 暂缓这条 D-Bus 调用的回复。
3. Java 收到元数据后用 `DuplicateHandle` 创建自己的句柄，OpenGL 通过 `GL_HANDLE_TYPE_D3D11_IMAGE_EXT` 导入同一个 GPU 分配。
4. MC 获取 mutex，在 GPU 上复制到自身 RGBA 纹理，释放 mutex，再发送 `GPUACK`。源画面和缓存没有 CPU 像素往返。
5. bridge 收到匹配 ACK 后才回复 D-Bus，QEMU 重新获取源纹理。每条连接最多一张待 ACK 的更新，不积压旧帧。
6. 手机未绘制时，Java 丢弃更新并立即 ACK；暂时拿不到 GPU 锁时保留旧画面、丢弃该更新。断线清理未领取帧、句柄、输入状态；重新连接使用新 D-Bus listener。

本机 NVIDIA 610.88 驱动的 GL Release 返回 `false`，同时 GL error 为 0；独立测试表明实际已经释放。代码不直接忽略该返回值：使用 GL 的设备 LUID 选择相同 DXGI adapter，再通过 D3D11 `OpenSharedResource1`、`IDXGIKeyedMutex::AcquireSync/ReleaseSync` 独立验证并完成释放。真正超时或错误会失败，不会冒充成功。

ANGLE 的 D3D 分配与其 GL scanout 采用相反的纵向约定；手机 UV 在导入端处理方向，已实际验证 Android 桌面，无 CPU 翻行或红蓝重排。

## 当前边界与下一步

- 已验证 Windows + NVIDIA RTX 5070 Ti Laptop / 610.88 + QEMU 11.1.1 + Android-x86 9.0-r2。不是所有宿主显卡、镜像或 Android 应用的兼容承诺。
- **静止画面重连首帧**：当前 QEMU 可能只给出 Scanout 元数据，直到客体下次内容变化才发出 Update。不能在 QEMU 持有 mutex 时擅自采样。实测一次静止桌面重连等待了约 26 秒；新触摸或时钟刷新可产生下一帧。已有 MC 缓存能正常重复绘制，但首次连接/重连可能需要等待。
- GPU 输出没有 CPU surface，因此 QMP `screendump` 可能报告 `no surface`。使用 MC 截图或显式 GPU 诊断读回；不要为截图在生产路径重新启用 CPU 转换。
- 严格零拷贝还需要修改 QEMU 的纹理生命周期/初始更新握手，或提供可被 MC 持续借用、同步归还的 GPU 纹理池。直接去掉当前 GPU 缓存复制会导致静止重绘与 QEMU 再次写入竞争，不能仅凭共享句柄就声称完成。
- 尚未测量 GPU 模式的触摸到最终显示延迟与持续有效帧率；更新通知数量不等于不同画面帧数。先前 VNC/D-Bus CPU 数据不能直接用来宣称本次提升倍数。

接口依据：[QEMU D-Bus display](https://www.qemu.org/docs/master/interop/dbus-display.html)、[OpenGL Win32 external memory](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_external_objects_win32.txt)、[Win32 keyed mutex](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_win32_keyed_mutex.txt)。本机结果见 [验证记录](validation.md)。
