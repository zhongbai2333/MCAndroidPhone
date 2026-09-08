# 跨平台验证 · 2026-09-08

## 当前范围

| 宿主 | 已验证 | GPU 状态 |
| --- | --- | --- |
| Windows AMD64，本机 RTX 5070 Ti Laptop | 独立 NeoForge Mod、JAR 管理 QEMU、Android 1080×1920、真实触屏；进程正常/取消/失败/强制终止；图案与安卓世界测试、倾斜、旋转、收纳恢复、F3+T | D3D11 → OpenGL，零 CPU 像素复制、一次 GPU 缓存复制通过 |
| Linux AMD64，Ubuntu 24.04 / WSL2 | Python/Java 核心、几何、进程清理；完整 Mod 构建；QEMU 8.2.2 BIOS/TCG；KVM Android 1080×1920 进入 Home 选择界面；Linux Minecraft 图案世界交互与收纳恢复 | D3D12 OpenGL 可用；DMA-BUF 导出/导入能力探针未通过，保持 CPU 路径 |
| macOS ARM64，目标 M4 | 平台参数与 ARM 启动配置单元测试；POSIX 管理、原生查找、Java 首线程启动和 IOSurface 探针源码已准备 | Mac 实机及 QEMU IOSurface 后端待验证/实现 |
| macOS Intel | 平台选择与参数单元测试；共享 POSIX/CPU 路径源码 | 未做实机测试 |
| Windows ARM64 | 原生架构识别、ARM virt 参数及 WHPX/TCG 选择单元测试 | 未做实机测试，不能推断虚拟机 GPU 互操作可用 |
| Linux ARM64 | ARM virt 参数及 KVM/TCG 选择单元测试 | 未做实机测试，DMA-BUF 生产后端尚未实现 |

Windows 通过 `gradle build runtimeSelfTest` 与 `scripts/dev.py quick`。Python bridge 112 项（Windows 跳过 2 项 POSIX 测试），脚本 76 项。Linux bridge 112 项（跳过 1 项 Windows 实装测试），脚本 76 项（跳过 2 项 Windows Job 测试），其余通过。FFmpeg 的 POSIX 退出问题已修复：先关闭原始视频 stdin 解除阻塞，再结束和等待进程。

Windows 图案世界证据目录 `.runtime/evidence/1abdf229-4d55-42e5-b206-7d6619ce2157/`，日志确认 `ANDROIDPHONE_HOVER_OK`、`ANDROIDPHONE_ROTATION_OK`、输入拦截、原连接/epoch 收纳恢复与资源重载；已目视检查横屏下缘与手部相接。真实安卓 GPU 世界目录 `.runtime/evidence/135f86b6-7ff0-4a2a-aa94-268227101147/`，进入 Android Home 选择界面，正常完成收纳及重载。

Linux 测试在独立 `/tmp/mcandroidphone-portable/` 源码副本内执行，Java 25.0.4、Python 3.12、QEMU 8.2.2、FFmpeg 6.1.1、内核 6.6.87.2。BIOS 通过 Java 管理器启动获得 720×400 帧；KVM Android 获得 1080×1920 画面，延长冷启动到 85 秒后进入 Home 选择界面。Linux Minecraft 的图案世界完整通过悬停、旋转、触控、收纳恢复和资源重载（`/tmp/mcandroid-linux-world-final.log`）；首次安装引导页已纳入自动测试。默认用户未加入 kvm 组，本次 KVM 测试临时以 root 运行，没有修改用户组；普通用户 auto 会选择 TCG。实际运行命令和原生进程记录保留在对应 session.json。

WSL 默认选 llvmpipe；显式设置 `MESA_LOADER_DRIVER_OVERRIDE=d3d12 GALLIUM_DRIVER=d3d12` 后，GLX 报告 D3D12 (AMD Radeon 610M)、硬件加速和 OpenGL 4.6。硬件 EGL 探针仍返回 `DMABUF_UNAVAILABLE`，因此不能因为 WSL 有 /dev/dxg 就宣称 Linux 零拷贝完成。

## 横屏握持修复

对应 NCPB 的 `MP4FocusState.ROTATION_PIVOT_U/V = .58/.88` 和 `LANDSCAPE_LEFT_SHIFT_FRACTION = .50`。原手机实现对旋转后中心做反向平移，抵消下方握持枢轴的纵向位移，导致横屏悬空。现按 NCPB 顺序：平滑横屏左移 → 平移至握持点 → Z/X/Y 旋转 → 平移回局部坐标；机壳、画面和触点投影共同使用该变换。转横屏也不再自动放大设备。

## 可重复入口

```sh
python scripts/dev.py quick
python scripts/dev.py check
python scripts/dev.py pattern --world-smoke
python scripts/dev.py native-probe
python scripts/dev.py runtime-smoke --set backend=qemu --set bios=true --set guestArch=amd64 --set accel=tcg
```

`runtime-smoke` 可指定 `expectedWidth`、`expectedHeight`、`warmupSeconds`；通过后保存一帧诊断 NV12。BIOS 或开机动画不能证明安卓桌面、触控或 GPU 导入完成。世界测试使用新世界，不触碰已有存档。GitHub CI 包含三个宿主的核心/生命周期测试、Linux Mod 构建及 Mac 探针编译；CI 结果不等于六个平台实机验收。

Mac 接续步骤见 [mac-handoff.md](mac-handoff.md)。运行时、Android 镜像、存档和本机证据不上传；GitHub 包含源码和复现步骤。
