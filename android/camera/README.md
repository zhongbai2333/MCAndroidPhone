# 安卓游戏相机 · 2026-09-09

`MCPhoneCamera` 是前台工作的游戏相机应用。它展示 Minecraft 手机独立前后镜头，支持切换、拍照、保存至 `DCIM/MCAndroidPhone`、打开系统相册。它不访问真实摄像头/麦克风，不申请广泛存储读取权限，也不注册标准 Camera2 设备。

## 当前能用的范围

- 需要本轮更新的 Java 模组运行时，配置 `camera=true`、`cameraTransport=network`（后者为默认）。旧 local-v3.jar 尚无固定地址路由。
- 应用只连接虚拟机内部 `10.0.2.100:18765`；QEMU 将它接到当前会话的宿主回环临时端口。每台 VM 可用相同来宾地址，宿主不占用固定公共端口，不需要 Python、netcat、用户脚本或用户填写端口。
- 当前开发镜像仍需安装 APK。`android/image/Prepare.java` 和 `product.mk` 已接入源码预装模块，完整定制系统尚未构建。当前普通模组 JAR 不会擅自开启 ADB、安装 APK 或修改已有设备系统。
- 仅前台拉取，最高 10 fps、640×480。预览/编码有界，先检查 JPEG 原始尺寸再解码。镜头切换、离开 Activity 和暂停/收纳时清掉旧图；无新帧时禁用拍照。连接断开会重试，重开后可能等待数秒。
- 使用 MediaStore 的 pending 写入再发布；失败时删除本应用刚创建的记录。照片在相册可见，读取其他应用照片不在功能范围内。

## 本机实测

证据目录 `.runtime/evidence/camera-system/`：

- `apk-build.log`：SDK 36 编译、D8、APK 签名验证通过；开发 APK 16794 字节。签名为本地开发证书，非发布密钥。
- `game-app-acceptance.log`：真实 Minecraft 物品右键启动 ARM64/HVF Android。APK 安装到该次独立快照，解锁后打开，后摄预览与前摄自拍都有实际画面。
- 同一轮 `WORLD_SMOKE_OK` 为 308132 ms，收纳恢复及资源重载通过。`final-build.log` 构建/回归通过；`cleanup-audit.json` 的三个会话均 stopped，36 个原生守护/子进程无残留，独立测试 ADB 服务已关闭。
- `app-preview-live.png`、`app-front-photo.png`：实际 Android 界面；`photos/` 两张 640×480 JPEG，分别为后摄世界与前摄玩家模型。
- `media-store.txt`：两张记录 `is_pending=0`，目录均为 `DCIM/MCAndroidPhone/`。`album.png`：系统 Gallery 正常打开照片；`resumed-later.png`：返回应用后恢复前摄预览。返回初期 `returned-camera.png` 显示无帧等待状态，不能计为该时刻已恢复。
- 后续 Home/返回的第三张拍照尝试没有形成已验证照片；最后一次属性/截图读取发生在游戏自动退出后，设备离线，空的 `media-store-final.txt`/`background-resumed.png` 不算证据。
- 第一轮短窗口测试只证明正常运行/退出，APK 被锁屏挡住；其 `app-rear.png`、`app-unlocked.png` 不是相机预览。第二轮已延长 opt-in smoke 窗口并实际解锁。

## 标准 Camera2 的实际阻塞

现成 Android 16 镜像包含 AOSP `virtual_camera`，`cmd -w virtual_camera help` 可触发懒启动。实际 `enable_test_camera` 注册失败，日志明确为 `GL_EXT_YUV_target not supported`，图形驱动为 ANGLE。证据为 `virtual-lazy.txt`、`virtual-test-camera.txt`、`virtual-egl.txt`；CameraService 设备数仍为 0。

AOSP 虚拟相机可将生产端 Surface 接入 Camera2 并输出 YUV/JPEG，但当前后端未满足它的 EGL 契约。不能删除能力检查后就宣称 Camera2 已支持；后续需验证兼容图形后端或实现 CPU 帧输出路径。[AOSP 服务说明](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/camera/virtualcamera/README.md)、[服务 EGL 检查源码](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/camera/virtualcamera/VirtualCameraService.cc)。

## 开发构建

```sh
# JDK 25 + 已下载的官方 Android SDK 36；不自动下载或安装全局依赖。
java android/camera/Build.java /path/to/android-36/android.jar /path/to/build-tools/36.0.0
# 仅独立开发来宾需要下面的安装；产品预装使用 Android.bp。
adb install --no-incremental -r android/camera/build/mcphone-camera.apk
```

输出 `android/camera/build/mcphone-camera.apk`，同一 APK 可用于 ARM64/AMD64 Android 29+。只有本机 ARM64/Android 36 实测，不能推及所有设备。产品 UI 不依赖这些开发命令。

网络转发契约：[QEMU guestfwd](https://www.qemu.org/docs/master/system/qemu-manpage.html)。照片写入契约：[Android MediaStore](https://developer.android.com/training/data-storage/shared/media)。
