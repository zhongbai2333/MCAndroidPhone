# 安卓镜像管理

在主菜单或游戏内进入“选项”，点击底部“安卓镜像…”。耗时操作在后台进行，页面显示下载、校验和解压进度，可取消；官方镜像中断后可继续下载。

- 官方镜像：两种架构的 Android Go full，保留 WebView。0.2.1 默认 zstd；0.2.0 的 XZ 下载仍可使用。
- 新手机默认：每种架构分别保存。选择已安装的镜像后点击“设为新手机默认”。仅新建手机使用该默认；旧手机绑定自己的系统、数据盘和启动变量，移除模板也不删除手机数据。
- 本地导入：拖入 ZIP 或目录，也可粘贴路径。支持 `manifest.properties` 描述的三盘镜像包，以及包含 `vda.qcow2`、`vdb.qcow2`、`efi_vars.fd` 的干净目录。无清单目录默认 720×1280，EFI 自动区分 raw/qcow2；其他尺寸需清单指定。
- 运行中的安卓需要先关机才能管理镜像。删除有确认，并禁止删除当前默认镜像；缓存目录之外的手机文件不会被遍历删除。

自定义镜像必须适配目前 QEMU 的 virtio/UEFI 启动和设备布局。普通真机 ROM、OTA ZIP 或单独 GSI 不是可直接启动的系统盘。导入会复制模板并检查大小、SHA-256、架构、磁盘独立性及配置白名单，不执行镜像包提供的宿主程序或命令。不得把旧加密 userdata 随意与新的系统元数据混合。

本地 ZIP 的 `manifest.properties` 与官方镜像描述格式相同，但不需要 `download.*` 字段；导入忽略其中的网络地址。三个条目分别映射 `config.disk`、`config.dataDisk` 和 `config.firmwareVars`，每个条目声明 `path`、`size`、`sha256`；压缩条目另加 `archivePath`、`compression=xz|zstd`、`storedSize`、`storedSha256`。磁盘格式用各角色的 `config.*Format=raw|qcow2`。只有系统相关设置可导入，不允许 QEMU 路径、启动脚本或任意参数。

发行工具 `PackageAndroidImages` 支持 `.xz` 和 `.zst`，并自动在 ZIP 内写入清单，发布包可直接导入。新包仍保留下载 ZIP 的整包摘要和每个解压后磁盘的摘要。

zstd 使用固定 `zstd-jni 1.5.7-16`，私有类加载器隔离，JAR SHA-256 校验，解压窗口最多 128 MiB。原生解码库覆盖六种规划宿主架构；这不代表这些宿主的 QEMU/GPU 路径均已验收。

2026-09-13 Windows 全盘解压加 SHA-256 实测（从最终 Mod codec 读取，不写输出盘）：AMD64 2,970 ms / 2,163,605,504 字节，ARM64 2,614 ms / 1,915,813,888 字节。真实首次安装还包括网络、磁盘写入和原生依赖安装；这不是 Android 冷启动耗时。

AMD64 的 Digitalis 实验镜像已通过纯 ARM64 JNI 应用及重启后的运行验证，可从同一 Release 下载带 `digitalis-experimental` 的 ZIP 导入。基础官方默认镜像仍不含转译层；实验版尚不代表复杂游戏/GLES/Vulkan 兼容。见 [转译集成](../android/native-bridge/README.md)。
