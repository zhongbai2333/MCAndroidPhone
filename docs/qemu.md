# 使用独立 QEMU 测试手机

在项目目录运行下面的命令。`qemu` 使用独立的 `qemu-system-x86_64`，无需 Android SDK、AVD 或 gRPC；开发测试需本机 JDK 25 和 Python；手机 Mod 完全独立，无需 NCPB/NetMusic。默认 CPU 模式还需要 FFmpeg；实验 GPU 模式使用项目内 ANGLE，不需要 FFmpeg。

```bat
rem 只有 QEMU 时，先查看固件画面并自动验证 MC 手机显示
test-phone.cmd qemu --qemu-bios --world-smoke

rem 启动自己的 Android-x86 ISO，再进入 MC 手工操作
test-phone.cmd qemu --qemu-iso ".runtime\images\android-x86_64-9.0-r2.iso"

rem 已放好默认运行时和镜像后，直接启动
test-phone.cmd qemu
```

默认入口现在只启动 MC，JAR 在右键手机时启动 QEMU 和内置 bridge；普通游戏不需要预启动脚本。配置见 [运行环境](runtime.md)。

进入创造世界，从安卓手机分类取出物品，或执行 `/give @s mcandroidphone:android_phone`；主手右键打开，F8 连接/断开。

手机聚焦后高度最多占窗口的 66%，为上下状态栏和操作提示留出空间；窄窗口会进一步缩小，保持外壳和画面比例。左键点击、按住滑动并松开即可操作 Android。

**固件测试与 Android 启动验收分开判断。** `--qemu-bios` 不加载 Android。`--world-smoke` 自动新建测试世界，验证物品使用、bridge 帧上传、渲染和投影，并保存截图；即使截图显示 BIOS，也不能据此认定 Android 已启动。

独立 QEMU 固件到 MC 手机的世界自检已通过：会话 `20260907-135445-22339f`，720×400 原生画面，上传 5 帧、绘制 89 次。Android-x86 9.0-r2 也已完成实际启动并显示 Quickstep 桌面：会话 `20260907-143546-285ab0` 的 `android-home.png`。完整验收结果见 [验证记录](validation.md)。该旧版 ISO 是本次兼容性测试用例。

## 外部诊断的运行时和参数参考

下文有关脚本发现规则和预启动流程适用于 `--external-bridge` 兼容诊断。默认 JAR 管理模式的路径发现、启动和清理规则以 [runtime.md](runtime.md) 为准；VNC、鼠标、标准 VGA、自定义颜色需要显式加 `--external-bridge`。

### 放置运行时和镜像

将完整 QEMU 运行时保留在 `.runtime/qemu/`：`bin/` 放程序和 DLL，`share/qemu/` 保留固件，`lib/qemu/` 保留模块；不要只复制一个 EXE。也可以显式指定安装目录中的程序：

```bat
test-phone.cmd qemu --qemu-exe "D:\Program Files\qemu\qemu-system-x86_64.exe" --qemu-iso "D:\Android Images\android.iso"
```

镜像默认从 `.runtime/images/`、`.runtime/downloads/` 中查找完整 `.iso` 文件，忽略 `.part`；有多个候选时取修改时间最新的文件。建议用 `--qemu-iso` 指明镜像，避免选中其他 ISO。也支持环境变量 `MCANDROIDPHONE_QEMU_ISO`；QEMU 程序可用 `MCANDROIDPHONE_QEMU_EXE` 指定。脚本不会把 Android SDK 内的 QEMU 分支当作独立 QEMU。

`test-phone.cmd check` 和 `scripts/doctor.ps1` 用限时 `-display help` 报告 QEMU 的 dbus 能力，不启动虚拟机。doctor 的 SDK/AVD 检查只适用于 SDK 后端；能力探测失败显示未知，不能据此判断为支持。

如果 ISO 同名目录中同时存在 `kernel` 和 `initrd.img`，且未指定磁盘或其他内核，脚本会自动采用直接内核启动，跳过 ISO 引导菜单。例如：

```text
.runtime/images/android-x86_64-9.0-r2.iso
.runtime/images/android-x86_64-9.0-r2/kernel
.runtime/images/android-x86_64-9.0-r2/initrd.img
```

这两个文件需要来自该 ISO。脚本当前只发现已有文件，不负责下载镜像或从 ISO 提取它们；也可通过 `--qemu-kernel PATH --qemu-initrd PATH` 显式指定。默认内核参数针对 Android-x86 Live 镜像，其他发行版可能需要调整启动代码。

直接内核启动默认将安卓配置为 **1080×1920 竖屏、480 dpi**，对应约 360×640 dp 的手机界面。分辨率和密度在客体中设置；bridge 读取实际 framebuffer，MC 手机按比例显示，不把横屏画面拉伸成竖屏。降低渲染分辨率可同时调整密度，例如：

```bat
test-phone.cmd qemu --qemu-width 720 --qemu-height 1280 --qemu-density 320
```

这组设置同样对应约 360×640 dp。只有直接内核启动会把分辨率与密度参数传入 Android；普通 ISO 菜单或已有磁盘引导时，客体可能采用虚拟显示器报告的首选尺寸，也可能继续使用自己的显示设置，需要在客体中配置。

`--qemu-gpu auto` 在 D-Bus 加直接 Android 内核启动时选择 **virtio-vga 2D**，其他启动方式使用标准 VGA；`vga`、`virtio` 可显式覆盖。virtio-vga 使用单输出和 EDID，已验证 Android-x86 9.0-r2 的 1080×1920 显示与共享 Map。内核仍保留 `HWACCEL=0`，不增加 GRALLOC/HWC 设置。分辨率校验仍要求宽×高×8 不超过 64 MiB；标准 VGA 使用 64 MiB 显存。

直接内核配置默认保留 FFmpeg 转 NV12 时的 BGR 通道兼容修正，virtio-vga 和标准 VGA 的 Android 显示均已验证该设置；不额外缩放。其他镜像可用 `--qemu-color-order rgb` 关闭修正；普通 ISO/磁盘/固件引导默认使用 RGB。

## 常用参数

| 参数 | 当前行为 |
| --- | --- |
| `--qemu-exe PATH` | 指定独立 QEMU 可执行文件。 |
| `--qemu-iso PATH` | 以只读方式挂载 ISO，选择光盘引导。 |
| `--qemu-disk PATH` | 使用已有磁盘，写入临时快照；退出后丢弃本次修改。 |
| `--qemu-disk-format qcow2` / `raw` | 明确磁盘格式，默认 `qcow2`。 |
| `--qemu-accel auto` / `whpx` / `tcg` | 默认尝试 `whpx:tcg`；`tcg` 可用于无硬件加速的验证，通常较慢。 |
| `--qemu-memory 4096` | 虚拟机内存，单位 MiB。 |
| `--qemu-cpus 2` | 虚拟 CPU 数量。 |
| `--qemu-width 1080` | 客体目标宽度，默认 1080；必须为 320–4096 内的偶数。 |
| `--qemu-height 1920` | 客体目标高度，默认 1920；必须为 320–4096 内的偶数。 |
| `--qemu-density 480` | Android 界面密度，默认 480 dpi；范围 120–640，直接内核启动时应用。 |
| `--qemu-color-order auto` / `rgb` / `bgr` | 默认 auto：直接内核配置使用 BGR 兼容修正，其他引导使用 RGB；可显式覆盖。 |
| `--qemu-input auto` / `mouse` / `touchscreen` | 默认 auto：直接 Android 内核启动使用真实触屏，普通固件、ISO 菜单或磁盘引导使用鼠标；显式设置优先。 |
| `--qemu-display auto` / `dbus` / `vnc` | 默认 auto：Windows 使用 D-Bus 直接显示，其他平台选择 VNC；可显式覆盖。 |
| `--qemu-gpu auto` / `vga` / `virtio` / `virgl` | 默认 auto：D-Bus + 直接 Android 内核使用 virtio-vga 2D，其余使用标准 VGA；`virgl` 显式启用实验 GPU 模式，见 [GPU 说明](gpu.md)。 |
| `--qemu-kernel PATH --qemu-initrd PATH` | 显式直接内核启动。 |
| `--qemu-bios` | 只验证固件画面，不能与 ISO、磁盘或直接内核参数组合。 |
| `--world-smoke` | 自动进入新测试世界，检查显示并截图退出。 |
| `--smoke` | 只验证 MC 启动到主菜单，与 `--world-smoke` 互斥。 |

例如，使用已有磁盘做一次可丢弃的测试：

```bat
test-phone.cmd qemu --qemu-disk "D:\Android Images\phone.qcow2" --qemu-disk-format qcow2 --qemu-accel tcg
```

当前命令使用 Nehalem CPU 和 e1000 网卡，按输入模式选择 virtio 触摸屏或 USB 绝对坐标 tablet；直接内核启动通过客体显示模式配置竖屏分辨率及 32 位颜色。Nehalem 保留 Android 所需指令，同时避开本机 `max` 型号的 WHPX XSAVE 错误。ISO 为只读；磁盘通过 `-snapshot` 保存本次临时修改。相关参数见 [QEMU 启动参数](https://www.qemu.org/docs/master/system/invocation.html)。

本机已准备 QEMU 11.1.1 和经过官方 SHA1 校验的 Android-x86 9.0-r2 ISO、内核与 initrd。运行 `test-phone.cmd qemu` 即可；本次默认 WHPX 配置约 100 秒检测到系统启动完成。首次桌面选择中选 Quickstep，再点 Always。Live 数据在退出后丢弃，下次仍是首次启动。

只检查 QEMU 的显示、键盘和分辨率切换，可运行下列快速测试。它使用内嵌的 512 字节 BIOS 用例，无需 Android 镜像、Minecraft 或 NASM：

```bat
.venv\Scripts\python.exe scripts\qemu-transport-smoke.py --display dbus --screenshot
.venv\Scripts\python.exe scripts\qemu-transport-smoke.py --display vnc --screenshot
```

QMP 在每次命令后关闭连接，供进程管理器继续查询和关机；bridge 不长期占用 QEMU 的单客户端监视器。

## 画面和输入

Windows 一键启动默认路径为 **QEMU D-Bus（可共享 surface 用 Win32.Map，否则原始像素更新）→ FFmpeg NV12 → 本地共享内存 → MC 手机纹理**。没有共享句柄时会自动使用 D-Bus Scanout/Update，不要求退回 VNC。两种 D-Bus 路径都省去 VNC 像素传输和空闲轮询退避，并将原生像素交给 FFmpeg，省去 Python RGB24 重排；仍包含 CPU 转换、复制和纹理上传，尚未实现 D3D/GPU 零拷贝。接口见 [QEMU D-Bus display](https://www.qemu.org/docs/master/interop/dbus-display.html)。

默认 virtio-vga 2D 配置已在 Android-x86 9 的 1080×1920 画面上验证真实共享 Map，MC 内显示和颜色正确。标准 VGA 的 Android 高色深 surface 直接引用 VRAM、没有 `share_handle`，选择 `--qemu-gpu vga` 时仍自动使用原始像素更新；它的 BIOS surface 可以使用 Map。GPU 选择影响 QEMU 显示设备，不需要给 bridge 增加 GPU 参数；实际性能以 [验证记录](validation.md) 为准。

`--qemu-display vnc` 保留 **VNC/RFB → RGB → FFmpeg NV12** 兼容路径。两种显示方式都保持原生尺寸，没有 MP4/H.264 编解码；手机表面保持画面比例，空余区域留黑。bridge 与 Java core 不依赖 NCPB，当前 Mod 的显示和交互仍经由可替换的 NCPB 适配层。

一键启动的 `--qemu-display auto` 在 Windows 选 dbus，在其他平台选 vnc；手工 bridge 为保持兼容仍默认 vnc。D-Bus 模式使用 `-display dbus,p2p=on,gl=off`，不启动 VNC 监听，默认 CPU 路径仍需要 FFmpeg。手工接入需给 bridge 传入 `--qemu-display dbus --qemu-pid QEMU_PID --qmp-port PORT`；PID 必须是实际 `qemu-system-x86_64.exe` 的 PID。脚本从受管助手原子写入的 `qemu-*.process.json` 中验证并取得直接子进程 PID；`session.json` 中原有的助手 `pid` 不能用于共享 QEMU 的 Windows socket。

QEMU 在固件、引导和 Android 桌面之间切换分辨率时，bridge 会终止旧连接、释放按下状态、创建新尺寸的共享内存文件，并通过新的 `WELCOME` 让客户端自动重连。旧尺寸的迟到帧会被丢弃。支持宽高均为偶数、各边在 2–4096 范围内的原生画面。RFB 的像素更新和桌面尺寸通知依据 [RFC 6143](https://www.rfc-editor.org/rfc/rfc6143)。

默认 `--qemu-input auto` 在发现直接启动的 Android 内核后选用 **touchscreen**，包括从 ISO 同名目录自动发现内核的情况。直连模式通过持久 D-Bus `MultiTouch.SendEvent` 发送触点开始、更新和结束；结束时对同一显示控制台补发 `BTN_TOUCH` 松开，避免 QEMU 仅结束 tracking ID 后留下按压状态。VNC 兼容模式使用 QMP `input-send-event` 批次。虚拟设备使用 `virtio-multitouch-pci`，Android 接收真实触屏输入。MC 当前只使用一个触点，虚拟设备具备多点能力并不代表已经支持双指缩放。QMP 事件结构与显示设备路由见 [QMP 输入事件](https://www.qemu.org/docs/master/interop/qemu-qmp-ref.html#command-input-send-event)。

本机 Android-x86 已将设备识别为 `InputSources: 0x1002`、`DeviceType: touchScreen`，同时报告 `INPUT_PROP_DIRECT`、`BTN_TOUCH` 和触点 tracking ID；应用列表滑动后未见安卓鼠标箭头。Android 对 `INPUT_PROP_DIRECT` 的触摸屏分类规则见 [AOSP 触摸设备](https://source.android.com/docs/core/interaction/input/touch-devices)，本项目的实测记录见 [验证记录](validation.md)。

普通固件、ISO 菜单或已有磁盘引导时，auto 使用 **mouse**，通过 USB tablet 操作一个鼠标指针，按显示路径使用 D-Bus Mouse 或 VNC 指针消息。镜像缺少 virtio 触屏驱动时可显式退回鼠标；已配置触屏驱动的磁盘镜像也可显式启用触屏：

```bat
test-phone.cmd qemu --external-bridge --qemu-input mouse
test-phone.cmd qemu --qemu-disk "D:\Android Images\phone.qcow2" --qemu-input touchscreen
```

手工启动 QEMU/bridge 时，触屏模式需同时配置以下设备，并给 bridge 传入 `--qemu-input touchscreen --qmp-port PORT`；一键脚本会自动匹配两端。显示器 ID 将触点和触摸按钮路由到同一个触屏，避免落到其他鼠标设备：

```text
-vga none
-device virtio-vga,id=phone-display,xres=1080,yres=1920,max_outputs=1,edid=on
-device virtio-multitouch-pci,id=phone-touch,display=phone-display
```

文本输入按显示路径使用 D-Bus Keyboard 或 VNC 键盘发送，限每条 256 个可打印 ASCII 字符；中文输入法和多指手势尚未实现。

右键/Backspace 返回、Home 桌面、End 最近任务通过 QMP `send-key` 发送：分别使用 `ac_back`、`ac_home`、`Alt+Tab`。前两者对应的 Linux 键码在 AOSP 通用键位中映射为 BACK/HOME；具体镜像可以使用自己的键位配置，最近任务尤其依赖系统对 `Alt+Tab` 的处理。参见 [QMP 输入命令](https://www.qemu.org/docs/master/interop/qemu-qmp-ref.html#command-send-key) 和 [AOSP Generic.kl](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/data/keyboards/Generic.kl)。

## 退出和日志

关闭 MC 或在终端按 Ctrl+C，会清理本次会话创建的 QEMU、bridge、FFmpeg 和 MC 子进程；Windows Job Object 负责进程归属，不按名称结束其他 QEMU。磁盘快照中的本次数据不会写回原始镜像。

日志保存在 `.runtime/quicktest/<本次会话>/`：主要查看 `qemu.log`、`qemu-serial.log`、`bridge.log`、`bridge-first-frame.log` 和 `minecraft.log`。世界自检的截图为该目录中的 `android-phone.png`。

bridge 和 QMP 只监听 `127.0.0.1`；VNC 模式的 VNC 端口也只绑定本机，D-Bus 模式不开放 VNC 端口。bridge 使用随机 token 认证；当前 VNC/QMP 本地端口未启用额外认证，因此同机进程仍可能访问它们。启动参数没有宿主目录共享或网络端口转发。QEMU 对 VNC 本地绑定和认证的说明见 [VNC security](https://www.qemu.org/docs/master/system/vnc-security.html)。

## 后续把 QEMU 随 JAR 分发

技术上可以把各平台 QEMU 原生运行时作为资源随 Mod JAR 分发，在首次使用时校验并解压到独立缓存，再启动子进程。**当前实现仍从外部目录启动 QEMU，尚未实现 JAR 内嵌、自动解压或原生运行时更新。** Python bridge 与启动管理代码已经打入 JAR，由 Mod 管理运行生命周期；Python 解释器和 FFmpeg/ANGLE 仍为外部依赖。后续若要内嵌完整原生运行时，还需处理其校验、解压、版本更新和分发。Android 系统镜像继续由用户单独提供。

后续目录职责应分别管理：

- **运行时缓存**：按操作系统、架构和 QEMU 版本保存可重新解压的程序、DLL、固件；更新运行时只替换这部分。
- **系统镜像**：外部提供的只读 ISO/基础磁盘，记录来源和校验值。
- **手机用户数据**：每台手机单独持久化的数据盘或差分盘；应保留和备份，不放入可清理的运行时缓存。当前测试使用临时快照，还没有这套持久化管理。

分发 QEMU 时还需随包保留适用的许可证和声明，并按照对应许可证提供所分发二进制的相应源代码；固件和依赖库各有自己的许可文件，应一起保留。QEMU 整体采用 GPLv2，具体组成及固件有各自说明；这里仅记录打包需要处理的事项，不对整个 Mod 的许可作结论。参见 [QEMU LICENSE](https://github.com/qemu/qemu/blob/master/LICENSE) 与 [QEMU 附带的 GPLv2 第 3 条](https://github.com/qemu/qemu/blob/master/COPYING)。

## 实验 GPU 共享纹理路径

`test-phone.cmd qemu --qemu-gpu virgl` 选择 Android VirGL、ANGLE 和 D3D11 共享纹理；手工 bridge 使用 `--qemu-display d3d11 --qemu-pid PID --qmp-port PORT`。这条新增路径不经过 FFmpeg、NV12 或像素 mmap，Java 按 GPU 协议 v2 消费句柄；上文有关 NV12/PBO/FFmpeg 的流程仍适用于默认 CPU 与兼容后端。MC 保留一次 GPU 缓存复制，尚非严格零拷贝。默认 auto 未改变。快速测试、依赖和静止画面重连限制见 [GPU 说明](gpu.md)。
