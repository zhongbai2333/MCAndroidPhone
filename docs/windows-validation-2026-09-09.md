# Windows 接续验收 · 2026-09-09

基线为 `codex/windows-wsl-handoff-20260909` 的 `3a400f95815c1416fd8d246a8cfeeb5221ad0842`，开始时 Windows 工作区干净。本轮直接使用已有 Windows 原生依赖和 Android-x86 9.0-r2 比较基线，所有 Android 测试均为独立快照；没有覆盖原配置、手机数据或 Mac 产物。以下修复和记录目前保留在本地，未提交/推送。

交接基线的 [GitHub Actions 34341702052](https://github.com/zhongbai2333/MCAndroidPhone/actions/runs/34341702052) 已全部成功；这是 `3a400f9` 的结果，不代表本轮未推送修复已通过远端 CI。旧文档的 NeoForge 502 阻碍不能继续当作当前分支 CI 状态。

## Windows 原生结果

Windows 11 AMD64，Ryzen 9 9955HX（16 核/32 线程），物理内存 33,483,370,496 字节，Zulu Java 25.0.1。复用 `.runtime/qemu/bin` 的 QEMU 11.1.1、`.runtime/angle/bin`、已有 FFmpeg 和 Android-x86 ISO/内核。

| 验证 | 本轮结果 |
| --- | --- |
| Java `quick` | 通过；包含 AF_UNIX socket sharing、D-Bus 协议、QMP/RFB、CPU 帧租约、存储和共享底盘、摄像头/环境通道 |
| Windows 生命周期 | 正常退出、重复启动、断开重连、取消、失败、阻塞 stdin、强杀父 JVM 后的子孙回收通过 |
| 完整构建 | `gradlew.bat --offline --no-configuration-cache build runtimeSelfTest` 通过 |
| 打包 | PackageRuntimeSelfTest / PackageRuntimeXZSelfTest 通过；可重现字节、缓存身份、Unicode、XZ 完整性与错误镜像拒绝 |
| 真实 BIOS / D-Bus | Java guardian 拉起 QEMU，经共享 surface、FFmpeg 输出 720×400 NV12，随后退出 |
| 真实 BIOS / VNC | Windows 低位动态端口环境下输出 720×400；PATH 仅保留 JDK/Windows，未使用 Python |
| Minecraft 诊断图 | 悬停倾斜约 ±2.5°、外缘拖转 -90°、投影触控、背包恢复、资源重载通过；横屏握持截图已目视检查 |
| Minecraft / Android / D3D11 | 1080×1920 真实 Android，**CPU 像素复制 0 次、GPU 缓存复制 1 次**；背包恢复保持 connection/epoch，资源重载通过 |
| 真实 Android / Java CPU D-Bus | 触屏选择 Quickstep 并进入桌面、打开 Gallery、BACK 返回、滑动打开应用抽屉、输入 `Calc` 过滤 Calculator，截图确认实际响应 |
| 静态画面重连 | CPU 视图 epoch 从 5 变为 6；收到重连帧且仍是原桌面，随后输入继续有效 |

GPU 世界约 67.8 秒完成，截图是 Android 首次桌面选择界面；不能将它描述成新版 LineageOS/Go 桌面。诊断帧计数不是帧率基准。[GPU 游戏截图](handoff-evidence/2026-09-09/windows/android-gpu-world.png)；[Android 实际文本输入](handoff-evidence/2026-09-09/windows/android-input.png)。

最终普通模组 JAR：`build/libs/mcandroidphone-0.1.0-prototype.jar`，**429,223 字节**，SHA-256 `44fae4a5de2a36da9425f1513bdf22bff3444875908ff40477bacd10bc4239e8`。91 个 Java class 均与本机编译结果一致；Python 文件和 bridge.zip 均为 0。它未内置 Android/QEMU，不能拿这个小 JAR 的体积证明完整平台包已低于 500 MiB。

## 本轮修复

1. 本机 IPv4 动态端口范围是 1024–15000。旧实现反复申请系统临时端口并要求大于等于 5900，100 次后仍可能报 `No free local port`，且 D-Bus 也错误地申请无用的 VNC 端口。现在仅 VNC 在 5900–65535 显式探测可绑定 IPv4 端口，QMP 使用系统分配的 IPv4 端口；不修改系统端口设置。新增真实回环连接回归。
2. Java FFM 返回后另行调用 WSAGetLastError 会丢失错误码，本机实际出现 `Winsock error 0`，导致空闲 D-Bus 连接被关闭。改为在原调用内用 `captureCallState("WSAGetLastError")` 捕获；新增真实空闲读超时及超时后继续读写回归。修复后 BIOS、长时间静态 Android 和 GPU 世界通过。
3. 运行时失败在该会话 `failure.log` 保存完整异常链，便于区分启动、协议与回收错误。
4. WSL GCC 的 `-Werror=misleading-indentation` 暴露 environmentd / inspect-state 多语句同一行问题，已拆分语句；严格编译及 Java/C++ 协议实测通过。相同编译和原生测试已加入 Linux CI 步骤，待推送执行。

## WSL 结果与 Go 构建条件

Ubuntu-24.04 / WSL2 / AMD64；JDK 25.0.4、GNU Make 4.3、GCC、QEMU 8.2.2 和 FFmpeg 已有。独立 checkout 位于 `/home/zhongbai233/src/MCAndroidPhone-handoff-20260909`，包含本轮六个源码/测试修复；其 origin 当前是 Windows 本地工程路径。Windows 和 WSL 不共享 build 输出。

- Linux Java quick、正常/取消/失败/强杀生命周期均通过。
- `PrepareSelfTest.java` 通过：两个 Go 产品的 Make 解析、补丁幂等、漂移拒绝和非 Go 包保留；build-go.sh 语法通过。
- environmentd / inspect-state 经 GCC C++17 `-Wall -Wextra -Werror` 编译，Java/C++ 握手、数据、陈旧状态拒绝、错误包清理通过。这是 Linux 主机协议测试，未编译 Android HAL/SELinux 产品。
- Linux Java guardian 拉起 QEMU / TCG / VNC BIOS，收到 720×400，退出通过。当前普通用户无 `/dev/kvm` 写权限，auto 选择 TCG；未改变用户权限或启动 KVM Android 测试。
- WSL 的 13 个记录的自有 PID 均无残留。Windows 的 64 个记录的自有 PID 均无残留；含最后 VNC 复验的核对见本机 `process-audit.json`，存活列表为空。

WSL 当前可见约 **15 GiB RAM + 4 GiB swap**。VHDX 位于 `E:/WSL/Ubuntu-24.04`；E 盘实际空闲 732,457,263,104 字节（约 682 GiB）。WSL 内 df 显示约 948 GiB 可用是虚拟盘容量，不能替代宿主空闲。C 盘仅约 37 GiB、D 盘约 180 GiB。

本机尚无 LineageOS 源码树，`repo` / Git LFS 未安装。没有启动完整源码同步、Android 构建或后台下载；没有修改 `.wslconfig`、内存限制或 swap。宿主 32 GB、WSL 15 GiB 低于原交接的 64 GB 构建基准，应先确定低并行/内存方案再执行 AMD64 Go，不能直接使用全部 32 线程。磁盘有准备独立源码树的空间。

仍按原交接顺序：准备 LineageOS 23.2 及两个 VirtIO 设备树，保存锁定 manifest，优先 AMD64 Go，完成真实启动和普通 APP 验收后再 ARM64。新 Go 与新 userdata、默认关机结果、Windows 双盘/共享底盘持久化、原生 APK ABI、WebView、音视频、HAL/SELinux、相机和六平台完整离线包仍需分别验收。此次 Android 9 快照和合成存储测试不能替代这些项目。

## 本机证据与复现

日志集中在 `.runtime/evidence/windows-handoff-20260909/`：`quick-fixed.log`、`build-final.log`、`bios-dbus-capture-error.log`、`bios-vnc-no-python.log`、`pattern-world.log`、`android-gpu-world.log`、`android-cpu.log`、`package*.log`、`wsl-quick.log`、`wsl-bios.log`、`go-prepare.log`、`native-environment.log`、`artifact-audit.json`、`process-audit.json`、`wsl-process-audit.json`。首轮失败日志保留。

CPU Android 操作输入、响应和截图在同目录 `android-cpu/commands`、`results`；会话最终 `STOPPED`。两份游戏证据分别是 `.runtime/evidence/b40d2264-69a1-4376-add4-3016812b041a/`（诊断图）与 `.runtime/evidence/d30350b0-2b9e-4230-ae31-154b2a040b3d/`（Android GPU）。完整日志和运行时文件仍不纳入 Git。

```powershell
$env:JAVA_HOME='D:\Program Files\Zulu\zulu-25'
.\test-phone.cmd quick
.\gradlew.bat --offline --no-configuration-cache build runtimeSelfTest
.\test-phone.cmd pattern --world-smoke
.\test-phone.cmd qemu --gpu virgl --world-smoke --warmup 60
.\test-phone.cmd runtime-smoke --set backend=qemu --set bios=true --set input=mouse --set 'ffmpeg=E:/Program Files/ffmpeg/bin/ffmpeg.exe'
```
