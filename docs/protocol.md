# 本机显示协议 v1

协议和 `core` 不依赖 Minecraft、NeoForge 或 NCPB。QEMU、SDK Emulator 和测试图后端使用同一份 Python bridge / Java client 约定。音频、多设备、多人共享、主动控制屏幕旋转及共享 GPU 纹理不在 v1 范围内；原生显示尺寸变化可通过新连接处理。

## 连接

bridge 仅监听 loopback；启动时生成随机 token，写入用户指定 runtime 目录内的 `bridge.properties`。不要把 runtime 目录提交到 Git。Java 通过 `mcandroidphone.config` 指定此文件。

```properties
host=127.0.0.1
port=18765
token=<随机令牌>
```

TCP 控制流为 ASCII、TAB 分隔、LF 结尾，单行最多 8192 字节。下文用 `\t`、`\n` 表示实际制表符和换行。

```text
client -> HELLO\t1\t<token>\n
server -> WELCOME\t1\t<width>\t<height>\t<frameBytes>\t3\t<base64(UTF8 absolute path)>\tNV12\n
server -> FRAME\t<sequence>\t<slot>\t<monotonicNs>\n
client -> ACK\t<sequence>\t<slot>\n
```

文件恰好包含 3 个紧密排列的 NV12 帧槽，无文件头。槽位偏移为 `slot * frameBytes`。宽高必须为偶数，`frameBytes = width * height * 3 / 2`。Y 后面紧跟交错 UV，行跨度均为 width；颜色约定为 **BT.709 limited range**；原点为画面左上角。v1 单边上限 4096 像素。

## 槽位所有权与丢帧

1. bridge 只写未借出的槽。
2. 完整写入后，bridge 发送 FRAME，槽位开始借出。
3. Java 网络线程把该槽复制到自己的 direct ByteBuffer 池。
4. 复制完成后才 ACK；bridge 校验 sequence 和 slot 匹配后回收。
5. Java 只保存一张待显示帧；新帧替换旧帧时立即归还旧缓冲。渲染线程取得帧后必须 close。

因此 MC 渲染慢时不会形成无限帧队列，bridge 也不会在 Java 读帧途中覆盖源数据。三个槽均被借出时丢弃新的源帧。已有的三个通知仍可能排队，理论上不是严格零排队延迟。

Java 使用 Java 25 的 `FileChannel.map(..., Arena)`，连接结束时确定性关闭映射；direct 上传池独立于映射，可在渲染线程使用。此链路有一次共享内存到 direct buffer 的复制，并非端到端零拷贝。独立手机层将 direct buffer 直接上传到自己的 Y/UV 两平面纹理，并在 shader 中转换颜色。

## 输入

```text
TOUCH\tDOWN\t0.25\t0.5\n
TOUCH\tMOVE\t0.35\t0.5\n
TOUCH\tUP\t0.35\t0.5\n
KEY\tBACK\n
KEY\tHOME\n
KEY\tAPP_SWITCH\n
TEXT\t<base64(UTF8)>\n
```

坐标为当前显示内容中的有限归一化值 [0,1]；bridge 映射到实际像素范围 [0,width-1]/[0,height-1]。手机外壳的黑边不属于显示内容。按下之后的拖动/释放可夹到边缘，避免离开手机屏幕时卡住按压。

只有一个触点会话。SDK 后端注入单指触摸；QEMU 的 touchscreen 模式通过 QMP/virtio 注入真实单指触点，mouse 兼容模式通过 VNC/USB tablet 注入鼠标指针。当前协议不提供多点触控。断连必须取消按压。Java 输入队列有界，若队列满则断开连接，依靠 bridge 释放按压，不能静默丢失 UP。TEXT 传输上限为 4096 个 UTF-8 字节，当前两个真实设备后端各限每条 256 个可打印 ASCII 字符，不等同于 Android 输入法。

QEMU 后端将 BACK/HOME/APP_SWITCH 映射到独立 QMP 连接中的 `ac_back` / `ac_home` / `Alt+Tab`。具体 Android 导航行为取决于客体键位配置，不改变上面的 MC→bridge 协议。

## 生命周期

每条连接有自己的映射文件，尺寸在本次连接内固定。QEMU 的 DesktopSize 通知会触发 `BridgeServer.reconfigure(width, height)`：先同步关闭旧输入会话并释放按压，再更新尺寸并清空缓存；下一条连接得到新的 `WELCOME` 和唯一映射文件。尺寸变化不修改仍被读取的旧 mmap。相同尺寸的重连也使用新文件，FRAME 序号可以重新从 1 开始。

每个转换器持有与其尺寸代次绑定的发布回调，旧转换器的迟到帧会被忽略。关闭中的旧会话保持可追踪，确保旧 UP 在新会话 DOWN 之前释放；旧 ACK、输入及清理回调不得影响新会话。Java 按新会话重新分配缓冲，已经交给渲染线程的帧拥有自己的副本，不随旧映射销毁。

SDK Emulator 后端尚未采用此动态尺寸流程，改变显示尺寸仍需重启 bridge 并匹配参数。bridge 的认证、映射生命周期与输入接收应相互隔离，非法命令不得导致任意宿主命令执行。MC→bridge 的 token 不为 QEMU 的 VNC/QMP 端口提供认证；当前它们仅绑定本机 `127.0.0.1`。

Java 在断开后每秒重新读取配置并尝试连接，不重放旧输入。没有变化的 Android 屏幕允许长时间没有新帧；TCP 读取不以此判定故障。进程死亡会关闭 socket；进程挂起而 socket 存活的检测尚未实现。

## GPU 协议 v2（独立于 v1 像素协议）

`bridge.properties` 中 `transport=d3d11` 选择 GPU 协议：

```text
HELLO\t2\t<token>
WELCOME\t2\t<bridgePid>\tD3D11
GPUFRAME\t<sequence>\t<bridgeHandle>\t<textureWidth>\t<textureHeight>\t<y0Top:0|1>\t<x>\t<y>\t<width>\t<height>\t<monotonicNs>
GPUACK\t<sequence>
```

没有映射文件或像素缓冲。Java 仅以 PROCESS_DUP_HANDLE 权限打开已认证 bridge，通过 DuplicateHandle 取得自己的源纹理句柄；源 bridge 句柄在该更新 ACK 之前保持有效。OpenGL mutex 释放和 GPU 缓存复制完成后才 ACK。只能 ACK 当前待处理的递增序号；同一连接最多一帧在途。坐标、裁剪及尺寸严格校验，单边最多 4096；GPU 协议不要求偶数尺寸。

TOUCH/KEY/TEXT 沿用 v1 的验证与输入状态机。失去可见手机时归还待领取更新，丢弃的帧也要 ACK；已领取的帧必须先结束 GPU 使用。重连使用新 listener 和 epoch，源尺寸由每帧元数据给出。静止画面重连和 QEMU 初始更新限制见 [GPU 说明](gpu.md)。
