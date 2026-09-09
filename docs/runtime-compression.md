# 内置镜像 XZ 分发与系统模块裁剪实验（2026-09-09）

本轮优先降低完整下载包，保持已验证的 Android 系统内容不变。第一轮裁剪文件清单仍见 [镜像裁剪记录](android-image-trimming.md)。

## 已实现

- `RuntimeBundle` 支持 schema 2 的 XZ 条目，继续读取 schema 1 普通 ZIP 包。磁盘路径和解压后的内容不变，QEMU 直接读取校验完成的 qcow2，不读取 XZ。
- 首次安装由 Java 流式解压，核对压缩数据和原始文件的长度/SHA-256；完成后原子发布缓存。取消或失败会清理临时目录。复用缓存仍检查解压后的完整性。
- 固定使用 [XZ for Java 1.12](https://tukaani.org/xz/java.html)，约 165 KiB，0BSD 许可。官方 1.12 修复了 1.10/1.11 的编码问题。解码器作为私有嵌套资源加载，不把 `org.tukaani.xz` 类注入 Minecraft 公共模块路径，也不依赖玩家安装 Python、XZ 或系统解码器。
- 解码器内存请求上限为 64 MiB。本轮选择 32 MiB 字典，输出文件另受清单大小和总安装空间限制。这是解码器上限，不代表整个 JVM 或 Android 虚拟机的内存上限。
- 打包器验证预压缩内容解码后与 staging 原件逐字节哈希一致，使用 ZIP STORED 条目避免对 XZ 再压缩。相同输入产生稳定的包和清单。

运行时解码器源与许可：[tukaani-project/xz-java](https://github.com/tukaani-project/xz-java)。固定 Maven Central 依赖 `org.tukaani:xz:1.12`，嵌入 JAR 的 SHA-256 为 `3e158a87bd73d8afb4b6e8239c013b7d049c48563f45860ce99cd2e448cf4a6b`，加载时再次验证。

## 构建方式

正常用户无需执行以下操作。开发者先构建 mod，然后针对大镜像生成预压缩文件，目录与 staging 的相对路径一致：

```sh
./gradlew build
mkdir -p compression/images
xz -T2 --lzma2=preset=6,dict=32MiB -c STAGE/images/disk-vda.qcow2 \
  > compression/images/disk-vda.qcow2.xz
java scripts/PackageRuntime.java build/libs/mcandroidphone-0.1.0-prototype.jar \
  STAGE macos-arm64 NEW-PACKAGE.jar --xz-dir compression
java scripts/InspectBundle.java NEW-PACKAGE.jar
```

XZ 命令仅用于开发时编码；另提供 `BundleCompression.encode(OutputStream)` 供 Java 构建工具和回归夹具使用。没有传 `--xz-dir` 时继续生成原格式。不能拿其他版本镜像的 `.xz` 替代当前 staging 文件，打包器会拒绝不匹配的内容。

`InspectBundle` 分别报告 ZIP 有效载荷字节和安装后的总文件大小，避免把 `.xz` 大小误报为用户磁盘占用。压缩减少下载包大小，不减少解压后的共享系统底盘大小，也没有承诺相同的首次安装速度。

## 验证

`CompressedBundleSelfTest` 已加入 Gradle check：实际私有解码器往返、缓存篡改、压缩/解压长度与哈希、损坏/截断/尾随垃圾、64 MiB 解码内存上限、取消后无残余目录、schema 兼容均通过。

`PackageRuntimeXZSelfTest` 使用构建后的真实 mod JAR，验证压缩包确定性、STORED 条目和错误预压缩镜像拒绝；旧版 `PackageRuntimeSelfTest` 同时通过。压缩打包检查已加入 mod CI 工作流，远程 CI 本轮未触发。

本机 ARM64 / HVF 的 `android-v2` 直接使用 800 MiB 完整包，自动解压后两轮 Android 16 启动、APK 安装、WebView、来宾 NAT、相机测试图传输与新增照片、重启标记、共享底盘完整性、正常关机均通过。两轮包含相机等验收操作分别约 115.8 秒和 59.4 秒；第一轮包含首次解压，这不是纯 Android 开机时间。第二轮复用了已校验缓存。

## 未采用的系统模块裁剪

进一步审阅了 `com.android.virt`（约 90.6 MiB）、`com.android.compos`（约 4.3 MiB）、各自预编译服务文件及 vendor 中的 AVF 功能声明。[AOSP 官方说明](https://source.android.com/docs/core/virtualization/usecases)将 CompOS 定义为可选的隔离编译组件，并说明失败时可以回退到开机编译。但源码产品层面可选，不代表可从任意预编译镜像直接删除。

独立实验同步删除上述 APEX、对应预编译文件和 `android.software.virtualization_framework` 声明。文件系统和 qcow2 检查通过，但真实启动中 Zygote 每约 5 秒退出/重启，未完成 Android 启动，ADB 日志抓取也未成功。尚未取得具体 Java 异常，因此不将根因断言为某个确定的缺失类。

此候选**未通过、未打包、未设为默认**。记录清单 `android/image/trim/lineage-arm64-no-avf-v2.json` 已标为 REJECTED，裁剪工具拒绝直接使用。其压缩任务已停止，残留标为 `.incomplete`。最终包保留 AVF/CompOS；进一步删减应在源码产品、classpath 和预编译配置上一起调整并重新构建，当前 Mac 没有完整 Linux Android 构建条件。

证据根目录：`.runtime/evidence/image-compression/`。原始镜像、上一版包、已使用的手机数据与默认运行配置均未替换。

## 最终包体积

| 产物 | 完整 JAR 字节 | MiB |
| --- | ---: | ---: |
| 初始 v3 包 | 1,159,575,082 | 1105.86 |
| 第一轮裁剪 ZIP 包 | 1,104,623,075 | 1053.45 |
| XZ 8 MiB 字典包（两轮 Android 已通过） | 839,003,802 | 800.14 |
| 最终 XZ 32 MiB 字典包 | **812,151,403** | **774.53** |

最终包相对上一版减少 **278.92 MiB / 26.48%**，相对初始 1.08 GiB 包减少 **331.33 MiB / 29.96%**，通过 780 MiB 包体积门槛。系统内容与上一轮通过验收的裁剪镜像一致，没有采用失败的无 AVF 候选。

产物：`.runtime/packages/mcandroidphone-macos-arm64-trim-xz-v3-test.jar`。SHA-256：`762cbfa40e0d84d1601749eb324f67a4b94621930061d26e306fd077286b280b`。仍属于本机 Mac ARM64 测试包；其他五个平台没有在本轮完成真机验收，干净用户盘的首次配置和默认正常关机缺口也没有因压缩而自动解决。

源码版本核验：官方 `v1.12` tag 解引用为 `107a519fac1e6789101ad9c234afe3dc407be7f5`。最终包在 `-Xmx256m` 宿主测试 JVM 中首次解压和虚拟机就绪耗时 **50,448 ms**；不含后续 Android 首次配置时间，也不代表 QEMU 来宾只占用 256 MiB。

最终 32 MiB 字典包的冷启动串口日志明确出现 `sys.boot_completed=1`。首次截图按帧数抽样留下了较早的启动动画帧，不能据此声称已看到初始化界面；验收工具现改为保留最新帧并在结束时强制保存，完整画面复验记录以 `factory-final-view` 为准。

`factory-final-view/verified-setup-screen.png` 已人工核验为真实的 “Welcome to LineageOS / Start” 首次配置界面，尚未走完配置向导。此轮干净用户盘默认关机仍返回 `forced-timeout`；上面两轮正常关机通过的范围是已初始化用户盘与 `power-key` 配置。最终清理审计为 `CLEANUP_OK sessions=5`，所有测试会话记录的子进程与包装进程均已退出。

复用同一已解压缓存、另建一台全新测试手机时，`runtimeReadyMs` 为 **4,338 ms**，再次校验后直接启动，没有再次解压。两次均是虚拟机就绪耗时，不是首次配置向导完成时间。

后续 ARM64 BCJ、原生依赖压缩及 QEMU 固件清理的实测见 [500 MiB 体积预算](runtime-size-budget.md)。本页 v3 产物保留用于对照。
