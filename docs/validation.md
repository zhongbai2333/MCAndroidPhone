# JAR 自启动与主项目合并验证：2026-09-08

当前产物为 `build/libs/mcandroidphone-0.1.0-prototype.jar`，148,590 字节，SHA256 `b7941d155ffccf81c808f82821da3034f2c5e1cf2b08c3286d5a77ef0f36f204`。包检查包含 ManagedRuntime、独立手机、D3d11Importer，以及带完整启动辅助模块的 bridge.zip（22 个归档条目）；无 NCPB 包和原生 EXE/ISO。

Java core/mod 已合并为根 `src/`，只保留一个 Gradle 项目；共享启动模块移入 `bridge/mcandroid_bridge/_launch/`。旧目录完整保存在 `.runtime/layout-backup-20260908/retired/`。下方旧记录中的 core/mod 路径、产物大小和脚本预启动描述均为对应阶段的历史状态。

## 构建与生命周期

- 根 `gradlew --offline --no-configuration-cache build runtimeSelfTest` 通过，core 报告 1330 次断言，几何测试通过。
- scripts 76 项、bridge 106 项 Python 测试全部通过。独立 transport-smoke 在新源码路径下传输 90 帧 1080×1920，约 3 秒，触控回传成功。
- 真实内置 Python 运行时测试通过：重复 start 复用同一 future、收到测试图、正常清理、启动前和启动中取消、缺失 QEMU 后失败清理、强制结束自建 JVM 后 stdin EOF 清理整组进程。证据在本机 Temp 的 `phone-runtime-selftest-12887245056552577201/`。
- 测试发现并修复：Python daemon 线程用 buffered stdin 监听时，依赖失败会在解释器退出阶段卡住；改为 `os.read` 后失败和强制退出检查均通过。
- 关机后临时 `.nv12` 与 bridge.properties 已清理，诊断日志保留。

## 真实 Minecraft → JAR → QEMU

两次均直接运行根 `runClient`，无外部 bridgeConfig、无预启动桥接脚本，按真实手机物品使用入口开机；预热 85 秒再执行收纳和资源重载检查。

| 路径 | 截图和 MC 日志 | 结果 |
| --- | --- | --- |
| virtio 2D / FFmpeg | `.runtime/managed-smoke/merged-cpu/` | 546 次上传、6038 次绘制、世界测试 92.8 秒 |
| VirGL / D3D11 | `.runtime/managed-smoke/merged-gpu/` | 622 次上传、6820 次绘制、世界测试 92.8 秒；零 CPU 像素复制、一次 GPU 缓存复制 |

已查看两张截图：安卓到达首次“Select a Home app”选择界面，尚未自动选择 Quickstep，因此本次不将它称为已进入 Quickstep 桌面。画面为 1080×1920，方向和导航栏正常。两次均通过 `ANDROIDPHONE_STOW_RESUME_OK`（同一连接/epoch、背包第 9 格、无需右键）和 `ANDROIDPHONE_RESOURCE_RELOAD_OK`，最后 `ANDROIDPHONE_WORLD_SMOKE_OK`。

CPU 托管会话 `run/mcandroidphone/sessions/0189efac-b377-4a4c-9d80-35284b401c20/`，GPU 会话 `4d9643ce-d153-4209-9e9a-e6b01677aa9d/`：均为 stopped、error=null；根据 session.json 中 supervisor/wrapper/native PID 逐一确认全部退出。CPU bridge 因关机时 QEMU 先断开 D-Bus 而退出码为 1，日志明确是连接关闭；GPU bridge/QEMU 退出码均为 0。帧文件无残留。

当前 JAR 自行管理已有 QEMU/Python/FFmpeg/ANGLE，而非内含全部原生程序；Android 镜像继续外置。该验证不覆盖完整安卓应用操作、持久化数据、所有 Windows 驱动或其他操作系统。

---

# 独立版验证：2026-09-08

环境：Windows、MC 26.1.2 / NeoForge 26.1.2.76 / Java 25；QEMU 11.1.1、Android-x86 9、1080×1920 / 480 dpi、RTX 5070 Ti Laptop。

- 最终 `gradlew --offline --no-configuration-cache build` 通过：core 自检报告 1330 次断言，手机几何自检通过。Python bridge 106 项、启动脚本 75 项测试全部通过。
- 产物 `mcandroidphone-0.1.0-prototype.jar`，88,685 字节，SHA256 `cfee8741167f0c5b4b53cdea99e474119c3e36f697e4d23a36983b5849a9dccd`。压缩包检查没有 NCPB 类引用、旧 `ncpb` 包或内嵌依赖 JAR，带独立 NV12/RGBA shader。元数据只要求 Minecraft 和 NeoForge。
- 无 NCPB 的普通路径世界回归：`.runtime/quicktest/20260908-112653-12ccd2/`。实际右键、中心触点拦截、259 次帧上传、757 次绘制；手机移到背包第 9 格 3 秒后拿回，原连接与 epoch 保持不变，无需再次 use。随后资源包重载，缓存继续显示。3 个受管进程退出码全为 0。
- 最终 GPU 桌面回归：`.runtime/quicktest/20260908-112823-7c5f7c/`。Android 完成启动并进入 Quickstep；前置诊断真实触屏滑动收到 148 次更新通知，无错误（通知数不等于不同画面帧数）。MC 导入 2 次 GPU 帧、绘制 3537 次，收纳恢复与资源包重载检查均通过，截图仍是完整安卓桌面。4 个受管进程退出码全为 0。
- 两个世界均检查未加载 NCPB、NetMusic、SceneEditor。保留主手手机、副手原版木棍；已检查 `android-phone-focused.png` 和 `android-phone.png`，手机边框、方向和底部导航完整，手臂移到屏幕下方。

关键标记：`ANDROIDPHONE_STOW_RESUME_OK`、`ANDROIDPHONE_RESOURCE_RELOAD_OK`、`ANDROIDPHONE_WORLD_SMOKE_OK`。默认 `--world-smoke` 已包含收纳和资源重载回归，可用 `test-phone.cmd pattern --world-smoke` 快速复现。GPU 桌面验收使用此前保存的 `20260907-233819-10fbfd/diagnostics/gpu-android-world.py`，该本机诊断只更改本次临时客体，并已随最新会话归档。

本轮解决的是收纳时销毁会话/纹理导致的黑屏；GPU 真正断开重连仍可能等待静止客体的下一次 Update。本次初次连接也等待了桌面时钟更新。GPU 生产路径仍是零 CPU 像素复制加一次 GPU 缓存复制，未宣称严格端到端零拷贝；没有测量触摸到显示的端到端延迟。

原适配器源码与被替换文件完整保留在 `.runtime/migration-backup-standalone-20260908/`，未改动 NCPB 项目。

---

# 历史验证记录

日期：2026-09-07。环境：Windows 11、Zulu Java 25.0.1、Python 3.13、Android Emulator 37.1.11.0、WHPX 可用。

## 已执行

| 验证 | 结果 |
| --- | --- |
| 独立 Java 核心 | 通过。真实 TCP 与映射文件往返，检查逐帧内容、最新帧替换、缓冲归还、非法帧描述、认证消息、输入回传、Windows 映射释放。 |
| Python bridge | 13 项测试通过。包括三槽不可覆盖、背压丢帧、过期 ACK、认证、单客户端限制、断连释放触摸、重连新文件、测试图输入。 |
| 本机 FFmpeg | 实际运行持久 RGB→NV12 子进程，检查 EOF 前输出、帧边界、上下方向与 BT.709 limited 灰度。 |
| Python→mmap→Java 测试图 | 1080×1920，90 帧，3.051 秒，设定 30 FPS；DOWN/MOVE/UP 往返通过。 |
| 真 Android→bridge | Android 35 x86_64，2560×1600；导航与触摸期间收到 12 帧、6 个不同画面，用时 3.693 秒。 |
| 真 Android→bridge→Java | Java 成功接收一帧原生 2560×1600 NV12 direct buffer，共 6,144,000 字节。 |
| 透视输入数学 | 121 个透视采样点、黑边拒绝、拖出内容区限制、退化投影检查通过。 |
| 模组构建 | `mcandroidphone-ncpb-adapter-0.1.0-prototype.jar` 构建成功。 |
| Minecraft 启动 | NeoForge 26.1.2.76 / MC 26.1.2 实际启动；NCPB 0.7.8-beta、NetMusic 1.5.1 和适配器加载，进入标题界面，强制加载目标 MP4 渲染类并通过必需的 Mixin 注入，然后正常退出。 |

启动测试的成功标记：

```text
ANDROIDPHONE_SMOKE_OK: title screen reached; MP4 renderer Mixin loaded
Stopping!
```

可以用 `gradlew.bat :mod:runClient -PphoneSmoke=true` 重跑启动测试；该参数仅用于测试，会在标题界面检查后退出。正常游玩时省略它。

## 性能与覆盖范围

测试图结果说明 1080p30 的本机共享内存传输在本次运行中可行。真实安卓测试含静态画面、导航和动画，不是持续帧率测试，不能用它宣称 Android 或 MC 已达到 1080p30/60。静态桌面只需收到一帧就能显示，后续没有更新属于正常情况。

后续已完成下方独立手机的游戏世界测试，覆盖真实手持 GPU 输出和测试图点击标记。完整安卓应用触控体验、长时间游戏性能、独立服务器部署仍未验证。

真实模拟器测试采用现有 AVD 的只读运行模式，未安装系统镜像、未保存快照。测试过程中只通过 gRPC 进行画面采集和输入，不依赖 ADB 授权。

## 一键入口验证（同日新增）

- 新增脚本与进程管理测试 34 项通过，包含真实 Windows 孙进程在父进程退出后仍能被会话 Job 清理的验证。
- `test-phone.cmd check` 自动找到 JDK 25、Python 3.13、E:\AndroidSdk、Medium_Tablet、FFmpeg、Gradle 缓存和 NCPB 0.7.8-beta JAR。
- `test-phone.cmd quick` 通过：90 帧 1080×1920，传输耗时 3.023 秒；Java 核心与 13 项 bridge 测试通过。
- `test-phone.cmd android --smoke` 从无项目 `.venv` 开始，自动创建环境并安装缓存的 grpcio/protobuf，启动只读 AVD，自动识别 2560×1600，收到共享内存首帧，MC 进入主菜单并输出 `ANDROIDPHONE_SMOKE_OK` 后退出。
- 结束后核对会话状态为 passed；本次进程、共享帧和 token 文件已清理，gRPC/bridge 端口均关闭。当前正常交互模式使用独立安卓手机物品，主手右键连接。

## 独立手机物品（同日新增）

- 新注册 `mcandroidphone:android_phone` 和安卓手机创造栏；模型资源、语言、客户端与集成服务器注册通过实际加载。第一人称复用 MP4 手持外壳与投影，物品栏和第三人称使用青色手机模型。
- 最终 `build` 通过；Java 核心与 121 点透视/黑边/拖动检查通过；启动器、模拟器探测和 Windows Job 共 41 项测试通过。
- `test-phone.cmd pattern --world-smoke` 在全新独立平坦创造世界中服务端发放手机，同时副手持普通 MP4，经 Minecraft 真实物品使用路径右键打开手机。成功连接 1080×1920 测试图，累计上传 79 帧、提交绘制 299 次；按下/松开事件均由手机拦截。
- 已人工检查该次自动截图：安卓测试图和普通 MP4 界面分别显示，手机中央出现输入标记。测试从进入世界到完成约 2.7 秒，完整客户端启动约 27 秒。此数据是短时功能验证，不代表持续帧率。
- 测试图证据：`.runtime/quicktest/20260907-124104-81b9ad/android-phone.png` 和同目录 `minecraft.log`。成功标记为 `ANDROIDPHONE_WORLD_SMOKE_OK`；`session.json` 为 `passed`，三个受管进程退出码均为 0。
- `test-phone.cmd android --world-smoke` 同样通过：自动启动现有只读 Medium_Tablet，手机内显示原生 2560×1600 安卓画面，上传 1 个静态帧并持续绘制 228 次，未模拟安卓触摸。截图位于 `.runtime/quicktest/20260907-124249-cda622/android-phone.png`；该次 AVD 停留在系统弹窗，因此只确认真实画面显示，不将它记为已体验安卓桌面/应用。横屏 AVD 在竖屏手机上按比例显示并保留黑边。
- 安卓会话的 5 个受管进程退出码均为 0，结束后未发现模拟器残留，共享帧及 discovery token 文件已清理。
- `--world-smoke` 每次创建唯一新世界，并将截图保存在本次独立日志目录；不会给已有存档发物品。测试图模式额外模拟一次中心触摸，安卓模式只验证显示。
- 本轮发现早期手工模拟器测试遗留进程持续写重复锁错误，已终止该进程并清除其约 10 GB 临时日志。当前启动器增加每个会话日志 64 MiB 限制和末尾 64 KiB 诊断，超限通过已有 Job 清理本次会话。

## 独立 QEMU 集成（同日新增）

- 本机独立运行时为 MSYS2 UCRT64 QEMU 11.1.1；放置在项目 `.runtime/qemu/`，`--version` 和 `-accel help` 通过，支持 TCG/WHPX。没有使用 Android SDK 的 QEMU 分支，也没有修改既有 AVD。依赖 DLL 与官方包的校验、来源保存在运行时 `provenance/`。
- `test-phone.cmd qemu --qemu-bios --world-smoke` 通过，使用默认 WHPX 加速。真实链路为 stock QEMU → VNC/RFB → FFmpeg NV12 → bridge mmap → Java → MC 手机。
- 固件世界测试会话：`.runtime/quicktest/20260907-135445-22339f/`。原生分辨率 720×400，上传 5 帧、绘制 89 次，游戏内检查约 2.2 秒；人工查看 `android-phone.png`，手机显示 BIOS/iPXE 文字，副手普通 MP4 保持自身界面。`session.json` 为 `passed`，4 个受管进程退出码均为 0。这个测试只证明固件显示链路，不代表 Android 已启动。
- 完整 bridge 测试现为 46 项，通过。覆盖真实 FFmpeg、分片 RFB、raw/CopyRect/DesktopSize、静态帧、指针释放、QMP 导航、故障传播，以及尺寸切换时迟到帧和旧会话清理竞态。新增 QMP 短连接在启动、每次导航及失败后释放的检查，停止等待正在执行的命令并拒绝后续输入。
- 启动器、QEMU/QMP、依赖探测、Windows Job 测试共 55 项通过。QMP 退出前核对本次虚拟机 UUID；已有磁盘写入临时快照，ISO 只读。
- 最终 Gradle `build` 通过。Java 新增真实 TCP/mmap 三次连接测试（4×4 → 4×4 → 6×4），各次重新接收序号 1；旧帧像素和所属会话标识在重连、删除映射及关闭客户端后仍保持有效。121 点手机几何检查通过。
- 当前使用单指针输入、可打印 ASCII 文本及客体导航键；中文输入、多点触控、音频、持久数据盘和长期性能尚未验收。Python bridge、FFmpeg 和 QEMU 仍由脚本从 JAR 外启动。
- 最终真实 QEMU 快速自检会话 `20260907-151232-d41d1a` 通过：640×400 → 720×400 → 640×400，文本输入改变像素，两次连接退役并新建 mmap；即使返回相同尺寸也不复用旧映射。独立 QMP 截图成功、原始测试磁盘哈希不变，受管进程正常清理。可用 `python scripts/qemu-transport-smoke.py --screenshot` 重跑，不需要 Android 或 Minecraft。
- Android ISO 的官方 SHA1 为 `1cc85b5ed7c830ff71aecf8405c7281a9c995aa0`，已下载并再次校验。镜像和提取文件保存在 `.runtime/images/`，来源及逐文件 SHA256 在对应 `verified-manifest.json`。
- 联调发现并修正默认 16 位 VESA 的花屏，现用 1024×768 的 32 位模式；本机 WHPX 下 `-cpu max` 出现 XSAVE 状态问题，现用满足 Android x86_64 指令要求的 Nehalem。默认中断控制器下约 100 秒检测到 `sys.boot_completed=1`，Quickstep 桌面截图见 `20260907-143546-285ab0/android-home.png`。该次后续输入检查暴露了长期占用 QMP 的问题，因此不能将整个会话记为通过；问题已由短连接方案修正并进行上述真实自检。
- **最终 Android → bridge → MC 手机验收通过**：会话 `.runtime/quicktest/20260907-151305-468075/`，默认 WHPX、Nehalem、32 位 VESA，检测到 `sys.boot_completed=1` 后通过 RFB 选择 Quickstep 桌面。通过实际 bridge TCP 输入发送拖动，打开应用列表；发送 BACK 回到桌面，随后发送 HOME 保持桌面。人工核对 `android-input-drawer.png`、`android-input-back.png`、`android-input-home.png`。
- 该次 MC 新建测试世界，主手独立手机显示 1024×768 Android 桌面，副手普通 MP4 保持自身界面；上传 1 个静态帧、绘制 208 次，约 2.159 秒完成游戏内检查。`android-phone.png` 已人工检查，`session.json` 为 passed，4 个受管进程退出码均为 0，帧映射和 discovery token 已清理。桌面操作通过同一个 bridge 输入协议测试；本次没有自动模拟 MC 内的安卓点击，也没有验收安卓应用长期运行。
- 最终 Android 验收额外使用临时诊断脚本读取客体串口启动状态、等待桌面、保存 QMP 截图；普通 `test-phone.cmd qemu` 不需要该脚本，会在取得首帧后启动 MC，Android 可继续在手机画面中启动。单独使用 `--world-smoke` 可能在系统仍处于开机动画时完成，不能替代桌面验收。

## QEMU 手机竖屏修正（同日新增）

- 上述 1024×768 横屏仅作为早期兼容性验证，现已替换为标准 VGA/Bochs DRM 原生 **1080×1920、32 位颜色、480 dpi**。直接内核参数使用 `video=Virtual-1:1080x1920-32@60e HWACCEL=0 DPI=480`，虚拟显存为 64 MiB；安卓逻辑布局约为 360×640 dp。MC 仍按原始比例显示，没有把横屏图像拉伸。
- 最终会话 `.runtime/quicktest/20260907-162129-897f51/` 使用默认 WHPX、Nehalem，约 100 秒检测到 `sys.boot_completed=1`；串口实际报告 `Physical size: 1080x1920`、`Physical density: 480`，framebuffer 同为 1080×1920/32bpp。`bridge-first-frame.log` 确认收到 1080×1920 NV12 BT.709 limited。
- 通过真实 bridge 协议发送上滑，打开应用列表；BACK 返回桌面，HOME 保持桌面。已检查 `android-input-drawer.png`、`android-input-back.png`。这些 QMP 原始截图保留 Android-x86 9.0-r2 Bochs 软件合成的红蓝互换；直接内核配置在既有 FFmpeg 转换中以 BGR 解读修正，单独 bridge 和普通固件默认仍为 RGB。其他镜像可用 `--qemu-color-order rgb` 覆盖。
- 已检查最终 `android-phone.png`：MC 主手手机显示竖屏桌面，Gmail、Google、Chrome 图标及壁纸颜色正常，旧横屏的大块黑边消除；副手普通 MP4 显示正常。游戏内自检上传 1 个静态帧、绘制 234 次，耗时 2144 ms。`session.json` 为 passed，4 个受管进程退出码均为 0。桌面输入通过 bridge 协议验收，本次没有额外模拟 MC 内的安卓点击，也不代表持续帧率达到 30 FPS。
- 全量 Python 检查通过：bridge 50 项、启动器等脚本 62 项，无跳过；包括真实 FFmpeg 的 RGB/BGR 饱和红蓝与 NV12 UV 顺序，以及颜色设置跨尺寸重建、显示尺寸/密度/显存限制、启动参数覆盖。实际 Minecraft 启动构建成功。
- 通用 QEMU 快速自检会话 `20260907-162422-ba0a28` 通过：640×400 → 720×400 → 640×400，输入改变像素，两次旧连接退役、重新创建映射，QMP 截图成功。确认安卓兼容配置没有破坏通用固件测试。
- 最终诊断包装脚本归档为该 Android 会话内的 `diagnostic-script.py`；它仅增加串口就绪检查、桌面选择与输入截图，生产测试入口仍是 `test-phone.cmd qemu`。

## 手机显示边距与真实触屏（同日新增）

- 手机聚焦姿态改为随窗口计算：机身高度最多占视口 66%，窄窗口继续等比缩小，给顶部状态和底部操作提示留出空间。调整在手部渲染与屏幕投影之前应用，物理外壳、画面和点击命中使用同一变换；普通 MP4 保持原来的姿态。连接时不再重复发送长聊天提示。
- `build` 和最终 `geometrySelfTest` 通过。几何检查包括 5 种窗口比例、9 组 ±4° 悬停姿态、5445 个显示与点击投影样本，使用实际握持旋转中心。测试图世界会话 `20260907-164102-b0786b` 通过真实 MC 点击入口，在缩小后的手机中心显示输入标记：上传 77 帧、绘制 291 次、2641 ms；3 个受管进程正常退出。
- 默认直接 Android 内核启动改用 `virtio-multitouch-pci,id=phone-touch,display=phone-display`，显示器对应 `id=phone-display`。QMP 输入批次指定同一个显示器，避免触摸按钮路由到 PS/2 鼠标。MC 当前只使用一个触点；兼容模式 `--qemu-input mouse` 保留 USB tablet/VNC 指针。
- 首轮真实触屏会话 `20260907-163614-4d7ada` 已确认 Android 识别 `QEMU Virtio MultiTouch`：`Sources: 0x00001002`、`DeviceType: touchScreen`、`INPUT_PROP_DIRECT`。`getevent` 记录 `BTN_TOUCH DOWN`、连续 `ABS_MT_POSITION_Y`、`ABS_MT_TRACKING_ID ffffffff`、`BTN_TOUCH UP`，滑动打开应用列表后没有安卓鼠标箭头。这是客体的真实触屏事件，并非仅隐藏鼠标图标。
- 最终合并验收会话 `.runtime/quicktest/20260907-164233-9797b6/`：原生 1080×1920、480 dpi，约 100 秒检测到 Android 启动完成；实际 bridge 触点上滑打开应用列表，BACK 返回桌面，HOME 保持桌面。`guest-console.log`、`touch-events.log` 保存设备分类和真实触点记录。诊断包装脚本归档为同目录 `diagnostic-script.py`。
- 已检查最终 `android-phone.png`：手机顶部和底部均在视口内，状态栏及导航键完整可见，颜色正常、没有安卓鼠标箭头，普通副手 MP4 正常。MC 上传 1 个静态帧、绘制 237 次，2148 ms 完成；`session.json` 为 passed，4 个受管进程退出码均为 0。真实安卓触点通过 bridge 协议测试，MC 内命中另由上述测试图世界验证；没有宣称完成多指或持续性能验收。
- 全量 Python 测试通过：bridge 59 项、启动器等脚本 65 项，无跳过。新增验证覆盖触点批次与坐标、追踪 ID、停止/断线/尺寸变化时释放触点，以及 DOWN/UP 结果不确定时的清理重试；画面断线后拒绝新触点，允许补发释放。

## 四边等厚黑框（同日新增）

- 固定 9:16 内屏，保留既有外壳和手持姿态。外壳前面宽 `256/448 + 0.07`、高 `1.07`；解 `(W-2b)/(H-2b)=9/16` 得到四边相同的模型空间厚度 `b≈0.0452041`。默认 1080×1920 画面完整等比显示，其他画面比例在固定内屏中留边。
- 视频渲染和触摸映射共用最终内屏矩形，黑框与额外留边拒绝新触点，已经开始的拖动仍限制在有效画面范围。连接初始化、关闭和尺寸变化均重新计算矩形；逆透视的精确边缘比较允许极小浮点误差。
- Gradle `build` 通过。几何测试新增四边实体厚度相等、默认画面比例、黑框输入拒绝、拖动边缘限制、4:3 内屏留边，以及 242 个透视后的内屏映射样本，包含全部四条精确边缘；既有窗口边距检查通过。
- 最终真实 Android/MC 会话 `.runtime/quicktest/20260907-165640-eaf699/` 通过，人工检查 `android-phone.png`：上下左右外壳黑框均清晰可见，状态栏和系统导航栏保留在内屏内。上传 2 帧、绘制 231 次、2141 ms 完成世界检查。`session.json` 为 passed，4 个受管进程退出码均为 0。此次修改未改变 QEMU 输入配置，诊断仍验证了真实触屏滑动和返回。

## 现代窄边框调整（同日新增）

- 手机专用外壳改为 9:16 基础面板加四边 `0.012` 模型单位的细边框。外壳前面宽 `0.5865`、高 `1.024`；边框较上一版 `0.0452041` 缩小约 73%。完整安卓画面保持原比例，四边仍等厚。
- 仅在安卓手机渲染作用域中调整面板宽度和四处外框尺寸；普通 MP4 保留原始值。Mixin 注入点已核对 NCPB 0.7.8-beta 实际字节码，并通过 Minecraft 真实加载验证。
- Gradle `build`、等厚窄框/触摸映射/窗口边距几何检查通过。测试图会话 `20260907-171605-6182d0` 通过 MC 内实际点击：上传 76 帧、绘制 290 次、2639 ms；普通副手 MP4 正常，3 个受管进程正常退出。
- 最终真实 Android 会话 `.runtime/quicktest/20260907-171752-afc9a1/` 通过，已查看 `android-phone.png`：边框显著变细，状态栏及导航栏完整保留，颜色和触屏正常。上传 1 帧、绘制 230 次、2142 ms 完成游戏检查，`session.json` 为 passed，4 个受管进程退出码均为 0。

## D-Bus 共享显示与操作延迟（同日新增）

Windows 的 `test-phone.cmd qemu` 现在默认使用 D-Bus；直接 Android 内核启动默认配套 virtio-vga 2D，保持原生 1080×1920、480 dpi、HWACCEL=0 和已有 BGR 兼容修正。无需安装新镜像、SDK 或新增 Python 包。`--qemu-gpu vga` 与 `--qemu-display vnc` 保留兼容选择；手工 bridge 默认仍为 VNC。

实际 Android 共享显示先在会话 `20260907-182009-1ebd76` 跑通，最终合并验收位于 `.runtime/quicktest/20260907-183014-9827e2/`：日志确认 `QEMU direct shared-map: 1080x1920, stride=4320, rgb0`。QEMU 的只读 Win32 映射由 bridge 读取，再经过 FFmpeg NV12、现有 mmap 协议和 MC 纹理。它不是 D3D/GPU 零拷贝，Android 仍使用软件渲染。

关键修正：

- D-Bus 控制与显示监听分别使用本地 AF_UNIX peer；通过真实 QEMU 子进程 PID 交接 socket，短 QMP 连接供管理器继续查询 UUID 和关机。支持共享映射及无共享句柄时的 Scanout/Update 原始像素回退。
- 同尺寸同格式的 Scanout 翻页复用 FFmpeg；仅实际布局变化才重建。原始像素已有不可变存储时直接保留，局部更新按需复制。
- 转换器每次只允许一张待输出画面，输出完成后才读取最新 surface。仅在上游保留最新帧还不够：合成 1080p 测试曾测出 FFmpeg 内部积压约 25 帧，首个变化输出延迟约 1344 ms；最终生产代码在同类合成负载下为约 98 ms。这个合成结果不能代替真实安卓结果。
- 单指触点使用持久 D-Bus MultiTouch；QEMU 11.1.1 的 End 只结束 tracking ID，bridge 另向同一 Console 发送 Mouse.Release(touch=9)，补齐 BTN_TOUCH 松开。任一步结果不确定时保留待释放状态，关闭时重试。实际来宾记录验证配对测试的 6 组 DOWN / tracking-ID END / BTN_TOUCH UP；末尾短手势的 getevent 文件可能仍有 stdio 缓冲，未将其缺失尾行当成额外验证。
- 安卓手机跳过每帧被视频覆盖的 MP4 离屏音乐界面；普通 MP4 保留原行为。手机窄框、比例和命中映射保持此前验证结果。

最终同机对照使用同一已启动 Android、同一 virtio-vga、同一分辨率和转换器，按 VNC 后 D-Bus 的固定顺序各做 3 次：HOME 后静置 7 秒，1 秒内发送 60 次 MOVE，再等待 4 秒。每张最终 NV12 做 SHA256，排除与基线相同的画面和重复提交；统计的是实际内容变化，不是显示通知次数。

| 测量项 | VNC | D-Bus 共享 Map |
| --- | --- | --- |
| 首个不同 NV12 帧，3 次（ms） | 218.94 / 239.64 / 260.89 | 162.45 / 167.17 / 155.90 |
| 首个不同帧中位值（ms） | 239.64 | 162.45 |
| 滑动第一秒不同画面数，3 次 | 8 / 8 / 8 | 12 / 12 / 12 |
| 触点调用平均往返（ms） | 10.194 | 0.632 |

测量止于 bridge 的 NV12 输出回调，不包含 MC 最终显示扫描；配对期间没有同时运行 MC。两种显示服务都已启用，每次仅连接一个测量后端。样本较少且顺序固定，不能外推为持续 30/60 FPS。当前软件渲染仍限制安卓流畅度。完整数据、逐次输入时间和去重哈希保存在 `paired-performance.json`。

配对完成后自动启动实际 MC，新建独立测试世界、主手使用安卓手机，副手普通 MP4 正常。最终 `ANDROIDPHONE_WORLD_SMOKE_OK`：上传 1 个静态帧、绘制 228 次、2139 ms 完成世界检查。已查看手机桌面和应用抽屉截图，画面比例、颜色、边框及触屏显示正常。`session.json` 为 passed，4 个受管进程退出码均为 0。MC 此项验证显示与物品使用；触点动作由同一 bridge 协议和前述后端配对测试覆盖。

最终 Python 回归通过：bridge 102 项、启动/进程脚本 76 项，无跳过。覆盖分片 D-Bus、消息边界、原生映射关闭、同布局连续 Scanout、最新帧合并、真实 1080p 转换完成等待、超时/关闭唤醒、触点及键盘释放重试，以及新 GPU 自动选择。Gradle build 通过，Java 核心 1266 个断言与手机几何检查通过；实际 MC 已加载新增的手机专用 GUI 跳过注入。

最终无 Android/MC 快速回归会话 20260907-183701-539650 同样通过：D-Bus 共享 Map、文本改变像素、640×400 → 720×400 → 640×400、两次旧连接退役及不同 mmap 文件，耗时 3.824 秒，基底 BIOS 镜像保持不变。


## 2026-09-07：VirGL / D3D11 GPU 画面

- 本机 GL capability 实测：NVIDIA RTX 5070 Ti Laptop，OpenGL 4.6 / NVIDIA 610.88，external-memory Win32、keyed mutex 和 copy-image 均可用。
- QEMU 11.1.1 + 固定哈希 MSYS2 ANGLE 2.1.r25748.890b5d8f-3，Android-x86 9.0-r2 开启 `HWACCEL=1 GRALLOC=gbm HWC=drm`；客体 `GLES: Red Hat, virgl, OpenGL ES 2.0 Mesa 19.3.5`。1080×1920 / 480 dpi 保留。没有使用 Android SDK 的运行库。
- 独立生产导入器测试 `.runtime/quicktest/20260907-232759-5eef9e/gpu-consumer.log`：连续三帧完成 D3D11 导入、GPU copy 和 DXGI 验证；首次 140.5 ms 含设备/FFM 初始化，后两帧约 1.20 / 1.03 ms。样本很小，只代表该测试的导入/缓存操作，不是端到端延迟或持续帧率。
- 驱动 GL Release `false / error=0` 已通过独立 DXGI Acquire/Release 验证处理，没有忽略失败。最初失败会话 `20260907-231633-1534b0` 保留供排查。
- 首次 GPU MC 烟雾测试 `20260907-232950-88098a` 上传 25 帧并通过，但截图仍是 Android 开机动画，且纵向倒置；随后修正 ANGLE/D3D UV，不能把该次截图当作桌面验收。
- **最终桌面与触摸验收**：`.runtime/quicktest/20260907-233819-10fbfd/`。先等待 `sys.boot_completed=1`，选择 Quickstep，注入 60 步滑动并归还每个 GPU 更新；`gpu-input.json` 记录 106 个更新通知、0 错误（不是 106 个不同画面）。`gpu-touch-events.log` 明确包含 tracking ID 开始/结束与 BTN_TOUCH DOWN/UP。
- 同次 MC 截图 `android-phone.png` 已视觉检查：桌面、Google/Gmail/Chrome 颜色、纵向文字、状态栏/导航栏和窄黑框正确，左手普通 MP4 同时显示正常。日志 `ANDROIDPHONE_GPU_FRAME` 确认 D3D11 路径，`ANDROIDPHONE_WORLD_SMOKE_OK` 上传 2 帧、绘制 3032 次。静止桌面重连首帧约等待 26 秒，属于已记录的 QEMU 初始 Update 限制；没有把重复绘制算作不同画面帧率。
- 该完整会话 QEMU、bridge、首帧探针、Minecraft 四项受管进程均正常退出。GPU 路径未创建 `.nv12` 像素文件，没有 FFmpeg 子进程。
- Python 回归：bridge 106 项、scripts 77 项通过；新增 GPU 更新租约、ACK、句柄退休和触控释放测试。Java core 与几何测试随最终构建执行。

实现边界、快速验证和严格零拷贝剩余工作见 [GPU 说明](gpu.md)。
最终交付检查：Gradle `build` 通过，Java core 1304 项断言、手机几何测试、77 项启动测试通过；JAR 为 `mod/build/libs/mcandroidphone-ncpb-adapter-0.1.0-prototype.jar`（75,342 字节），包含 GPU 导入器、DXGI 验证器与 core 句柄协议。

仓库自带的 `scripts/gpu-smoke.py` 在 `20260907-235000-cf7504` 和最终复跑 `20260907-235632-19a395` 均通过。每次实际导入三帧，并在诊断端读回 RGBA 计算 CRC；存在不同 CRC，确认获取了变化的 GPU 图像。最终复跑预热后的两帧导入/复制/同步为约 1.57 / 1.67 ms，首帧约 174.6 ms 含初始化；不作为持续帧率或触摸端到端延迟结论。所有受管进程正常退出。
