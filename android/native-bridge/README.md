# AMD64 上的 ARM64 应用转译

选用 [Digitalis](https://github.com/DigitalisX64/digitalis)，基于 AOSP Berberis，Apache-2.0。上游公开 AOSP Berberis 的现成后端是 RISC-V；Google 的 ARM64 libndk_translation 和 Intel Houdini 不能仅凭第三方下载链接就视为可自由再分发。

固定 translator 提交：`cf5842168cfbc001fe0cdc44ca340fc2525fb269`。配套文档参考 `DigitalisX64/digitalis` 提交 `3a60a56b716d1d75960fd6a0bf22b96cd1e2f0bf`。

在已准备的 LineageOS 23.2 Linux 树上，`prepare-digitalis.py ROOT ARCHIVE` 校验固定 tarball 摘要，保存原源码和两个配置文件，并仅给 AMD64 Go 产品加入 ARM64 Native Bridge 架构与源码产品配置。它不下载、发布或开启 permissive SELinux。对应 `--restore` 会检查准备后的配置是否被修改，再恢复原树，实验源码保留在备份中。

`LINEAGE_ROOT=... bash android/native-bridge/build-probe.sh` 先编译 translator 和 runner，不重新同步整套 Android，不生成发布镜像。默认 12 路，建议配合主机的 24 GiB WSL 内存和 systemd 内存限制。

后续验收必须包括：完整镜像构建；真实 ARM64 JNI 应用安装/运行；重启后仍可用；GLES/Vulkan、WebView、音视频和严格 SELinux 下的表现。Digitalis 上游使用 goldfish / ANGLE / gfxstream，我们使用 virtio / Mesa / VirGL，不能直接继承上游图形兼容声明，也不能只设置 ABI 属性就宣传 ARM64 应用已兼容。本方案只针对 ARM64，不含 32 位 ARM 转译。

当前发行镜像保持已验证的 full 基线；转译镜像通过实际验收后再进入官方目录。

## 本机编译进展（2026-09-13）

首次完整图分析触及 22 GiB 内存 / 5 GiB swap 限制，系统以 OOM 终止；不是 C++ 编译错误。对已经配置好的同一产品运行 `bounded-graph.sh`，使用 Go `GOGC=25`、`GOMEMLIMIT=18GiB` 后，构建图成功生成。此工具要求当前 `out/soong` 中已有本产品的变量和环境文件，不替代首次产品配置。

随后 `MCANDROIDPHONE_REUSE_GRAPH=true LINEAGE_ROOT=... bash android/native-bridge/build-probe.sh` 直接构建已解析的两个 Android x86_64 输出目标，544 步在约 42 秒完成，转译库与 runner 均成功。systemd 报告的极小 MemoryPeak 未覆盖该阶段全部子进程，不作为实际内存需求结论。

完整镜像装配成功（13 分 35 秒），使用原树、已有输出和 12 路；对本版本 Soong，经检查 `config.go` 后采用 `m --skip-config --config-only --no-soong-only -j 12 vm-utm-zip`，复用已确认的图但执行 Kati 和镜像构建。这是当前源码版本的恢复办法，**不要在源码/产品配置改变后直接跳过图生成**。原始 full 镜像已独立保存，构建输出不等于发布完成。

## 实际验收结果

2026-09-13，Windows AMD64 / WHPX 的干净新手机启动成功：约 96.6 秒观察到 boot completed，D-Bus / VirGL 收到 36 帧，关机为 guest-confirmed。这只是传输验收，不等于 GPU 画面已渲染。

随后用独立持久手机、virtio 2D / VNC 画面验收：经安卓安装器从只读 FAT16 USB 盘安装 `probe/` 构建的 APK，显示 **ARM64 JNI PASS: 42**。APK 唯一原生库为 `lib/arm64-v8a/libmcphone_arm64_probe.so`，ELF Machine 为 AArch64，没有 x86 库或 Java 回退。正常关机、重新启动同一手机后再次通过。测试未打开 ADB，也未改变 SELinux。截图和模板摘要见 [验收记录](../../docs/handoff-evidence/2026-09-13/digitalis/verification.json)。

实验镜像为 `mcandroidphone-android-go-amd64-digitalis-experimental-20260913.zip`，837,243,308 字节，SHA-256 `d25516b78ddf342dbe87165b2c17eb635e7c97418434ef3cc0c2a2d52799a2ed`。保留 WebView；系统盘为 2,194,210,816 字节，比原 full 增加约 31 MB。发布的是从构建输出保存的干净三盘模板，未使用已安装探针的测试手机盘。

在 0.2.1 的“选项 → 安卓镜像… → 导入”中选择该 ZIP，架构 AMD64，再设为新手机默认。基础 ARM64 JNI 已验证，但复杂应用、图形代理和媒体兼容性仍需继续测试，因此它作为实验附件提供，暂不替换官方默认下载。

验收中另外发现：浏览器可以访问 QEMU 宿主，Android 下载器却将网络判为离线；VNC 的冒号输入可变成分号。这些不是 JNI 转译失败，已记录为后续网络/键盘问题。只读 USB 安装用于绕开测试环境的下载阻塞，不修改发行镜像。
