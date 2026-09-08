# Android frame bridge

This helper transfers raw NV12 frames and input commands between the Minecraft
client and a **separately running** stock QEMU guest or Android SDK Emulator. Its `pattern`
backend is an interactive diagnostic picture, not an Android implementation.

The main Gradle build includes this package as `mcandroidphone/runtime/bridge.zip` in the mod JAR. `managed.py` and `_launch/` start and own QEMU/bridge/FFmpeg on Windows; normal game use needs no external scripts. Native executables and Android media remain external. See [runtime configuration](../docs/runtime.md). The manual commands below are diagnostic entry points.

Python 3.10+ is sufficient for pattern mode. From this directory:

```powershell
python -m mcandroid_bridge --backend pattern --runtime ..\runtime --width 1080 --height 1920 --fps 30
```

Install the optional emulator dependencies into a private environment:

```powershell
python -m venv --copies .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

Connect to an already running emulator that exposes authenticated gRPC on
`127.0.0.1:8554`. The Android SDK emulator supports `-grpc 8554 -grpc-use-token`
for legacy local bearer authentication. Its discovery INI under
`$env:LOCALAPPDATA\Temp\avd\running\pid_*.ini` contains `grpc.token`.
Choose the actual running instance's INI; do not publish this file or its token.

```powershell
.\.venv\Scripts\python.exe -m mcandroid_bridge --backend emulator --runtime ..\runtime --width 1080 --height 1920 --fps 30 --grpc-discovery-file 'C:\path\to\pid_1234.ini' --ffmpeg 'C:\path\to\ffmpeg.exe'
```

`--grpc-token-file` also accepts a plain bearer token or a pre-generated JWT.
This prototype does not mint or refresh JWTs. Both bridge sockets and the gRPC
client are fixed to `127.0.0.1`; there is no remote-host argument. An empty token
is rejected. The Android Emulator process must itself bind gRPC to loopback.

The dimensions must exactly match the emulator's native display. For the
existing `Medium_Tablet` AVD tested on this machine, use `--width 2560 --height
1600`. Screenshot requests deliberately omit width/height, so there is **no
silent resize**. Keep orientation fixed for a session; a dimension change fails
with an explicit error. `--fps` caps submitted frames, and does not imply that
the Android guest or renderer reaches that rate. Static screens may emit no new
frames. One latest source frame is retained for a client that connects later.

The conversion path is `gRPC RGB888 -> persistent FFmpeg -> NV12 BT.709 limited
-> mmap -> client's owned buffer`. This uses copies and CPU color conversion.
There is no MP4 container, video encoding, or zero-copy claim. AOSP's proto
comment describes RGB screenshot rows as bottom-up, but a captured frame from
the installed Android Emulator 37.1.11.0 was visually verified to be top-down.
Top-down is therefore the default. Use `--rgb-bottom-up` for implementations
that need FFmpeg `vflip`; image coordinates sent to Android remain top-left.

Controls support one finger (down/move/up), Android BACK/HOME/APP_SWITCH, and
text. Protocol text is UTF-8, up to 4096 bytes. The current emulator `sendKey`
implementation only translates printable ASCII; this backend rejects unsupported
characters and limits each text command to 256 characters. Chinese/IME input,
multi-touch, sound, rotation renegotiation and guest lifecycle management are
future work.

## Protocol and ownership

`runtime/bridge.properties` contains `host`, `port`, and a newly generated
`token`; `--port 0` chooses an available port and records it there. The protocol
is newline-terminated ASCII with tab-separated fields and an 8192-byte line
limit. Paths and text use standard Base64 over UTF-8. One client is admitted;
additional clients receive `ERROR\tbusy`.

```text
HELLO  1  <token>
WELCOME  1  <width>  <height>  <frameBytes>  3  <base64 absolute mmap path>  NV12
FRAME  <sequence>  <slot>  <monotonic_ns>
ACK  <sequence>  <slot>
TOUCH  DOWN|MOVE|UP  <u from 0 to 1>  <v from 0 to 1>
KEY  BACK|HOME|APP_SWITCH
TEXT  <base64 UTF-8>
```

Spaces above denote **tabs**. The mmap file contains exactly three tightly packed
frames, no header. For NV12, Y occupies `width*height` bytes followed by
interleaved U,V at half horizontal/vertical resolution. Coordinates and normalized
output frames use the top-left origin. The server finishes writing a free slot
before sending FRAME, and never rewrites it until the matching sequence/slot ACK
arrives. The client must copy into its own buffer **before ACK** and must not
render from a released mapping. If all slots are leased, source frames are
dropped; the queue never grows. Invalid/stale ACKs or malformed inputs close the
connection. Disconnect sends UP for any active contact, closes the mapping and
attempts cleanup. Every connection gets a UUID filename, so Windows mappings
held by an old client cannot be overwritten during reconnect. Failed Windows
deletions are retried while the server remains running; a crashed process may
leave an inert `frames-*.nv12` file for later manual cleanup.

## Validation

```powershell
python -m unittest discover -s tests -v
python -m mcandroid_bridge.smoke --runtime ..\runtime --frames 12 --exercise-input --output ..\runtime\last-frame.nv12
```

The smoke client reads the same mmap protocol as Java and acknowledges only an
owned copy. `--exercise-input` changes Android navigation state and injects one
short touch gesture; omit it for frame-only capture. Frame-only capture on a
static Android screen may time out because upstream sends only changed frames.
Use `--frames 1` for a static screenshot.

Tests cover slot immutability/backpressure, stale ACK rejection, independent
reader mappings, input validation, touch release on disconnect, authentication,
one-client admission, fresh reconnect paths, and interactive pattern behavior.
When FFmpeg is installed, a native RGB-to-NV12 test checks persistent output
before stdin EOF, frame boundaries, vertical orientation and BT.709 luma values.

On this machine, a live smoke against Android Emulator 37.1.11.0, Android 35
x86_64 `Medium_Tablet`, received 12 native 2560x1600 frames (6 distinct) in
3.693 seconds while issuing HOME, APP_SWITCH, BACK and a touch gesture. This
validates the actual screenshot, conversion, mmap and input path, and is **not
a frame-rate benchmark**. The existing AVD was launched read-only; the bridge
did not install Android or modify its configuration. Synthetic 1080x1920 mode
and the tablet test must not be described as measured Android 1080p30 playback.

The independent Java `BridgeSmoke` client also received one native 2560x1600
frame from the corrected top-down live bridge, verified an owned direct NV12
buffer and completed successfully. All 13 Python tests passed with the pinned
dependencies and installed FFmpeg. These are transport/backend checks; they do
not establish Minecraft rendering performance or an in-game play test.

## Protocol sources and pinned dependencies

The stock QEMU backend uses Python's standard library plus FFmpeg, without
Android SDK or gRPC. The Windows launcher defaults to D-Bus direct display and
selects virtio-vga 2D for direct Android kernel boot, retaining `HWACCEL=0`;
the standalone bridge keeps `--qemu-display vnc` as its compatibility default.
For QEMU started with `-display dbus,p2p=on,gl=off`, attach with:

```powershell
python -m mcandroid_bridge --backend qemu --qemu-display dbus --qemu-pid 12345 --qmp-port 4444 --runtime ..\runtime --ffmpeg 'C:\path\to\ffmpeg.exe'
```

Use the actual QEMU executable's PID, not its managed wrapper. The launcher
obtains and verifies this PID automatically for Windows socket sharing.
D-Bus uses Win32.Map for shareable display surfaces. When no shared handle is
available, it automatically receives raw pixel Scanout/Update messages instead.
Android-x86 9 with the default virtio-vga 2D profile has been verified to provide
a mapped 1080x1920 surface. The explicit `--qemu-gpu vga` launcher compatibility
profile references VRAM without a shared handle in high-color Android mode and
therefore uses raw pixel updates; its BIOS surface can use Map. Both paths bypass VNC and its idle polling backoff, feeding
native pixels into persistent FFmpeg without Python RGB24 reordering. CPU
conversion, copies and texture uploads remain; D3D/GPU zero-copy is not implemented.
The GPU option configures QEMU only; it is not a bridge CLI option. Manual QEMU
launches can use `-vga none -device virtio-vga,id=phone-display,xres=1080,yres=1920,max_outputs=1,edid=on`.

The compatibility command remains `--backend qemu --qemu-display vnc
--vnc-port PORT --qmp-port PORT`. It receives native RFB frames and DesktopSize
notifications. Both display paths retire the old bridge session and mmap on
resize; Java reconnects with the new dimensions and ignores delayed old frames.
`--qemu-input touchscreen` sends one real touch contact through persistent D-Bus
(or QMP for the VNC backend) to the
virtio-multitouch device bound to display `phone-display`; the launcher selects
this for direct Android kernel boot. Standalone bridge defaults to `mouse`,
using USB-tablet input through D-Bus Mouse or RFB according to display mode.
ASCII text uses D-Bus Keyboard or RFB keys. Navigation uses short QMP
transactions, leaving the monitor available for shutdown. D-Bus touch release
also clears BTN_TOUCH on the same console after retiring its tracking ID. See QEMU's
[D-Bus display interfaces](https://www.qemu.org/docs/master/interop/dbus-display.html).
Actual performance and real QEMU text/resize and
Android desktop/MC evidence is recorded in [validation](../docs/validation.md).
Use [the QEMU launcher guide](../docs/qemu.md) for runtime and image setup.

The small dynamically constructed protobuf descriptor in
`mcandroid_bridge/emulator_wire.py` preserves the field numbers and service
paths verified in the [AOSP emulator controller protocol](https://android.googlesource.com/platform/prebuilts/android-emulator/+/master/linux-x86_64/lib/emulator_controller.proto).
It avoids vendoring the full SDK schema or requiring a compiler at startup.
The API is marked experimental upstream. The bridge uses only `getScreenshot`,
`streamScreenshot`, `sendTouch`, and `sendKey`.

Optional dependencies are pinned to published
[grpcio 1.78.0](https://pypi.org/project/grpcio/1.78.0/) and
[protobuf 6.33.5](https://pypi.org/project/protobuf/6.33.5/).

## 实验 GPU 共享纹理路径

`test-phone.cmd qemu --qemu-gpu virgl` 选择 Android VirGL、ANGLE 和 D3D11 共享纹理；手工 bridge 使用 `--qemu-display d3d11 --qemu-pid PID --qmp-port PORT`。这条新增路径不经过 FFmpeg、NV12 或像素 mmap，Java 按 GPU 协议 v2 消费句柄；上文有关 NV12/PBO/FFmpeg 的流程仍适用于默认 CPU 与兼容后端。MC 保留一次 GPU 缓存复制，尚非严格零拷贝。默认 auto 未改变。快速测试、依赖和静止画面重连限制见 [GPU 说明](../docs/gpu.md)。
