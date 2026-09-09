# 游戏环境联动：第一阶段 · 2026-09-08

本阶段已实现 Minecraft 客户端的位置/姿态采集、独立环境通道、跨语言协议，以及定制 Android 的原生接收服务和 HAL 特化脚本。真实 Android 验证使用明确的开发测试入口；默认运行配置仍关闭环境通道。

后续已增加持久化存储、Mac ARM64 内置运行包以及游戏画面到 Android 的 JPEG 通道，见 [本机功能验收](local-features.md)。以下表格保留第一阶段当时的范围；系统 Camera HAL 和定制镜像仍未完成。

## 已验证与待验证

| 层次 | 当前证据 |
| --- | --- |
| 游戏采集 | 真实 Minecraft 新世界，手机右键连接；鼠标悬停倾斜及边框拖动到 -90°，采集玩家眼部位置和手机相对姿态 |
| Java 通道 | 最新值覆盖，不排队积压；20 Hz 上限；超过 500 ms 未更新或游戏暂停/离开世界时不可用；关闭不阻塞游戏线程 |
| Android API | `LocationManager` 测试 provider 和 `SensorManager` 加速度/陀螺仪监听器收到真实回调；不是仅检查发送日志 |
| 游戏到 Android | 485 个接收包、868 个传感器回调、434 个定位回调；竖屏约 `(0,9.81,0)`，横屏约 `(-9.81,0,0)`；收纳恢复和资源重载继续通过 |
| Java/C++ | 实际编译原生接收器，通过二进制交叉解码、确认序号、500 ms 过期和畸形长度拒绝测试 |
| 正式 HAL | 已实现正常模式下的定位和五类传感器接入改动；在上游源码片段上验证特化脚本的检查、应用和幂等性，**尚未完成 Android/Soong 编译或定制镜像启动** |
| 未在本阶段完成 | 摄像头、ARM 转译、持久用户盘、六个平台的内置系统 JAR；`platforms.json` 是构建矩阵计划，不是已发布产物 |

当前 stock LineageOS `user` 镜像把 `/dev/vport6p1` 设为 root-only，且没有本项目的接收服务。因此真实 Android API 测试通过 QEMU 用户网络访问宿主回环测试连接，使用显式 `--inject-test`。它不证明正式 VirtIO 字节流、SELinux 策略、GNSS HAL 和 Sensors HAL 已在来宾中通过验证。系统的普通应用自动旋转也没有作为本次通过项；截图中的锁屏保持原来的显示方向。

用户已说明 Windows 11 是迁移到 macOS 前稳定使用的原平台。本次新增环境通道和新的 Go 镜像仍需在该机器回归。

## 数据模型

- Minecraft 坐标是 `(东,上,南)`，姿态使用设备到 ENU `(东,北,上)` 的单位四元数，顺序 `x,y,z,w`。
- 环境采样当前取玩家眼部位置，加上玩家视角和手机的展示旋转/倾斜。摄像头另用手机模型的实际变换求前/后镜头位置、朝向并独立渲染；其镜头偏移尚未用于 GPS/运动传感器采样。见 [本机摄像头验收](local-features.md)。
- 定位是虚拟世界映射：原点经纬度 `(0,0)`，1 方块 = 1 米，海平面 `y=63`；只在主世界且纬度不超过 ±85° 时提供有效定位。经度归一化，原始维度和 XYZ 始终保留。它不会自动生成现实地图服务中的 Minecraft 地形。
- 加速度为设备坐标下的比力，单位 m/s²，包括约 9.80665 的静止重力读数；陀螺仪单位 rad/s。
- 气压是 `1013.25 * exp(-(y-63)/8434.5)` hPa 的游戏模型；光照按 0–15 亮度映射到 0–10000 lux；虚拟磁场以世界北向为基准。它们不是现实环境测量。
- 换维度、暂停、采样间隔大于 500 ms、位移大于 32 方块会重置速度/加速度历史。收纳时跟随持有者，失去物品所有权后立即把数据标记为不可用。

## 通道与正常系统接入

`ManagedRuntime` 在 `environment=true` 时保留一个宿主回环端口，通过 QEMU socket chardev 连接名为 `com.mcandroidphone.environment` 的 `virtserialport`。该连接独立于 QMP、画面和触摸。接收器先发送协议 magic/version，再逐包确认 sequence；读写有界，断线可重新握手。

原生 `mcphone-environmentd` 按端口名称查找设备节点，把收到的状态原子写入 `/data/vendor/mcandroidphone/environment.bin`。文件带 **Android 本地 BOOTTIME 接收时间**，HAL 在读取时检查新鲜度；不会拿宿主的时钟直接当 Android 传感器时间。原生服务拥有专用 SELinux 域，节点不改成全员可写。定位和传感器 HAL 仅获得状态文件读取权限。

`android/image/Prepare.java` 为上游的 Sensors AIDL 示例 HAL 接入加速度、陀螺仪、磁场、气压和光照；为 GNSS 示例服务覆盖定位回调。普通运行模式不需要 mock-location、ADB 或 SensorService 注入模式。上游示例的 NMEA 输出被停用，原始卫星测量、批量定位等示例接口还需后续裁剪/一致性验证，当前不宣称完整 GNSS 合规。

## 线协议 v1

所有整数和 IEEE754 double 均为大端。先发 `uint32 payloadLength`，范围 192–512，再发 payload：

| 字段 | 编码 |
| --- | --- |
| magic / version | uint32 `0x4d435045` / uint32 `1` |
| sequence / hostElapsedNanos | 非负 int64 / 非负 int64，相对本次采样器开始时间 |
| available / locationValid / discontinuity | 三个严格的 0/1 字节 |
| dimension | uint32 字节数 + 1–128 个 ASCII 标识符字节 |
| 数值 | 20 个 double：XYZ、纬经高、四元数 xyzw、加速度 xyz、陀螺仪 xyz、气压、光照、水平速度、航向 |

payload 必须恰好为 `191 + dimensionLength` 字节。握手是两个 uint32（magic/version），每包回复原 sequence 的 int64。宿主可以重发同序号的不可用状态，接收器拒绝倒序。不允许 NaN、Infinity、非法维度或非单位四元数。

## 复现

Java/游戏回归：

```sh
java scripts/Dev.java quick
sh gradlew build
sh test-phone.sh qemu --environment-smoke --warmup 80 --set adbPort=15555
```

最后一条要求已有可启动的本地 Android 镜像；不会自动下载或安装系统。测试结束会退出游戏，只在新建世界执行。

开发接收器使用 Google D8 编译为 DEX；`R8_JAR` 需指向本地官方编译器（本轮为 9.4.17）。它不打进模组 JAR，也不是正式系统服务：

```sh
R8_JAR=/absolute/path/r8.jar sh android/tools/build-probe.sh
adb push android/tools/build/environment-probe.jar /data/local/tmp/mcphone-environment-probe.jar
# 测试快照内授权；先记录原 appops 与定位开关状态，结束时恢复。
adb shell appops set com.android.shell android:mock_location allow
adb shell cmd location set-location-enabled true
# PORT 从本次会话 qemu-command.json 的 phone-env chardev 读取。
adb shell 'CLASSPATH=/data/local/tmp/mcphone-environment-probe.jar app_process /system/bin com.zhongbai233.mcandroidphone.guest.EnvironmentProbe --tcp 10.0.2.2 PORT --inject-test'
```

接收器最多运行 120 秒，结束时移除本次定位 provider 并恢复 SensorService 正常模式；操作者仍需恢复原定位开关和 mock-location appops。被强制终止时也需执行 `dumpsys sensorservice enable`。仅对独立测试虚拟机使用这个入口。

原生协议测试（macOS/Linux，有 C++17 编译器）：

```sh
mkdir -p android/tools/build
clang++ -std=c++17 -Wall -Wextra -Werror android/image/guest/environmentd.cpp -o android/tools/build/environmentd
clang++ -std=c++17 -Wall -Wextra -Werror android/tools/inspect-state.cpp -o android/tools/build/inspect-state
java -cp build/classes/java/main:build/classes/java/test com.zhongbai233.mcandroidphone.core.EnvironmentNativeSelfTest android/tools/build/environmentd android/tools/build/inspect-state
```

## 本轮证据

- `.runtime/evidence/game-environment/android-probe.log`：独立真实 Android，2016 帧、4032 传感器回调、2016 定位回调。
- `.runtime/evidence/game-environment/minecraft-android-probe.log`：实际游戏采样与 Android 回调，含横屏和不可用状态。
- `.runtime/evidence/game-environment/minecraft-environment.log`：悬停、边框旋转、收纳和重载通过；约 94,998 ms 完成游戏验收。
- `.runtime/evidence/bdc4280d-b97d-4edb-9016-ab2da14abf50/`：游戏截图，已目视核对。
- `.runtime/evidence/game-environment/{build.log,portable-tests.log}`：最终构建与运行时回归。

参考：[Sensors AIDL HAL](https://source.android.com/docs/core/interaction/sensors/sensors-aidl-hal)、[LineageOS VirtIO 双架构 Go 目标](https://lineageos.github.io/lineage_wiki/libvirt-qemu.html)。
