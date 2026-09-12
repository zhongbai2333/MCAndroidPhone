# Windows AMD64 Android Go 精简镜像 — 2026-09-13

本轮目标是 Android 镜像分发归档 **小于 500,000,000 字节**，不包含 QEMU/ANGLE/FFmpeg/Mod 原生依赖。不要用 500 MiB、虚拟磁盘容量或未打包分区大小替代这个门槛。

## 最终产物

**v10 minimal 三盘镜像归档为 497,765,444 字节（497.77 MB / 474.71 MiB），严格小于 500,000,000 字节。** 相比上一轮 v5 的 803,320,927 字节减少约 38.0%。这是 Android 镜像包，不是完整离线 JAR；QEMU、ANGLE、FFmpeg 和 Mod 需另计。

本机产物：`.runtime/packages/lineage-go-amd64-minimal-v10-images-xz.zip`。SHA-256：`e75d86ebf2dd29f24bbc5ff24e01d3e4ebd71313e7eff9d4a3e92e26b63c4d40`。ZIP CRC 和严格字节门槛检查通过，三盘输入及压缩文件的完整大小/哈希见 [package.json](handoff-evidence/2026-09-13/windows-go-under500/package.json)。该归档及磁盘不上传 Git，源码和小型验收证据可同步。

采用已获用户允许的后备方案：取消预装 WebView 和 Jelly。保留 WebView 的 v7 在现有解码内存限制内仍有 574,181,304 字节的系统盘 XZ，未达到目标；这不是对所有可能的未来优化作不可能性结论。`full` 仍为默认构建配置，`compact` 提供保留 WebView 的折中配置。

## 最终验收

- **真实构建**：WSL Ubuntu 24.04，LineageOS 23.2 AMD64 Go user，12 路；v10 增量构建耗时 29 分 18 秒，2392 步成功，临时构建 swap 已清理。WSL 数据仍位于 E 盘。
- **磁盘与解压**：`qemu-img check` 无错误。成品 Mod 内置 Java codec 在 `-Xmx256m` 下解压写出 1,504,706,560 字节，与原始系统盘 SHA-256 一致，耗时 28,552 ms。仍用 48 MiB XZ 字典和既有 64 MiB 解码内存限制。[解码记录](handoff-evidence/2026-09-13/windows-go-under500/java-decode.txt)
- **全新手机**：Windows QEMU 11.1.1、WHPX、SandyBridge、4 vCPU / 4096 MiB、VirGL。运行时就绪 5.03 秒、首传输帧 78.66 秒、Android 启动完成 85.58 秒，正常关机 `guest-confirmed`。此测试排空 GPU 帧租约，不渲染画面；实际 Minecraft 验收另列。[启动记录](handoff-evidence/2026-09-13/windows-go-under500/fresh-boot.json)
- **来宾契约**：`sys.boot_completed=1`、Go low-RAM 为 true、EGL 为 ANGLE；WebView feature、预装 WebView 和 Jelly 均不存在。[来宾查询](handoff-evidence/2026-09-13/windows-go-under500/guest-contract.json)
- **普通应用与输入**：生产 `PhoneConnection` 触摸通道，计算器 `12+34=46`；2048 实际 24 次滑动使分数 136→400、步数 24→48，截图中棋盘发生合并，切走再进入后棋盘和分数保留。[应用记录](handoff-evidence/2026-09-13/windows-go-under500/apps.json)、[计算器](handoff-evidence/2026-09-13/windows-go-under500/calculator.png)、[滑动前](handoff-evidence/2026-09-13/windows-go-under500/game-before.png)、[滑动后](handoff-evidence/2026-09-13/windows-go-under500/game-after.png)、[恢复](handoff-evidence/2026-09-13/windows-go-under500/game-resumed.png)
- **Minecraft 实际渲染**：构建和世界 smoke 成功，截图确认欢迎页正常显示；收起再拿出保持同一连接和 epoch，无需再次右键；资源重载保持缓存。D3D11 共享纹理路径 CPU 像素复制 0 次、GPU 缓存复制 1 次。物品使用到首次 GPU 帧约 54 秒；主动等待 120 秒完成截图和恢复检查。[运行记录](handoff-evidence/2026-09-13/windows-go-under500/minecraft.txt)、[交互画面](handoff-evidence/2026-09-13/windows-go-under500/android-phone-focused.png)、[恢复后画面](handoff-evidence/2026-09-13/windows-go-under500/phone.png)

85.58 秒不包含上述 XZ 解码；这些是单次正确性测量，宿主缓存/并行磁盘工作没有严格控制，不据此承诺冷启动加速或 FPS。普通应用样例通过不代表所有应用兼容，ARM 原生应用转译和其他五种宿主的实测也不在本轮结果中。

## 构建配置

`build-go.sh` 第二个参数可选 `full`（默认）、`compact`、`minimal`。后两者目前仅支持 AMD64 Go；ARM64 仍使用原完整配置。

```sh
LINEAGE_ROOT=/path/to/lineage bash android/image/build-go.sh amd64 compact
# 仅在需要取消预装 WebView 时：
LINEAGE_ROOT=/path/to/lineage bash android/image/build-go.sh amd64 minimal
```

构建显式导出 `MCANDROIDPHONE_IMAGE_PROFILE`，最终产品变量会记录并校验。`PrepareCompact` 的上游补丁始终有产品/配置条件，普通 full 和其他产品保留原行为；已有手机不会因重新构建而自动迁移。

`compact` 保留 WebView，只移除其 64 位系统不会加载的 x86 JNI 库，APK 由原有 `default_dev_cert` 的 Soong 导入流程重新签名与对齐。资源、64 位 Chromium、DEX 和 WebView 加载支持库保留。不是从成品系统里删除库后跳过签名校验。

图形保留 VirtIO VirGL、VirtIO Vulkan 驱动和 SwiftShader 回退，取消实体 Intel GPU 直通驱动及 Mesa 的第二套软件光栅化器。取消 CompOS 产品继承、相关 system server 依赖和虚拟机功能声明，来宾不声明支持再次运行虚拟机。system 分区仍保留缩小后的 `com.android.virt.apex`（v10 为 1,331,200 字节），不能把取消继承写成整个 APEX 已删除。ThemePicker 不预装。

System server 恢复 speed-profile；普通应用保留原始 DEX/JIT，不再要求 SystemUI/Launcher 的整应用 speed 预编译。这可能延长首次启动或降低初期流畅度，必须测量。APEX 关闭内层压缩，让外层镜像压缩器直接压缩完整载荷，功能与签名流程不变，但安装后的分区可能更大。

`minimal` 额外取消预装 WebView 及依赖它的 Jelly 浏览器，并用产品权限 XML 的 `unavailable-feature` 取消 `android.software.webview`，避免 SystemServer 启动没有默认提供者的 WebView 服务。应用内网页、部分登录页和混合应用会受影响；不是“完整兼容”版本，也未实现安装单个 WebView APK 后自动恢复框架能力；需要网页兼容性时应选择 compact 镜像。用户已授权仅在保留兼容性无法达到体积目标时采用这一后备方案。

## 验证边界

已有测试通过：原 Go 准备回归；WebView 原始输入不变、保留资源、仅删除预期 JNI/签名条目和架构拒绝；GNU Make 对 full/compact/minimal 及其他产品的实际求值；补丁幂等与锚点漂移前不写入。

最终 v10 的归档、启动与应用结果见上文；WebView 验收仅适用于保留它的 v7。不能仅凭源码配置和分项压缩加和宣称达标。分项预算证据在 Linux 工程 `.runtime/evidence/under500-20260912/`；分组分别压缩会失去组间共享字典，因此其加和不是最终镜像大小。

## 已测中间结果（2026-09-13）

| 候选 | 系统盘 XZ 字节 | 解码兼容性 / 结果 |
| --- | ---: | --- |
| 原 v5，全功能配置 | 803,311,332 | 已验证的比较基线 |
| v7 compact，48 MiB 字典 | 574,181,304 | 保留 WebView，沿用现有解码内存上限 |
| v7 compact，128 MiB 字典 | 549,207,080 | 仅体积实验；超过现有 Java 解码器 64 MiB 内存上限，未采用 |
| v8 minimal，48 MiB 字典 | 499,720,176 | 首轮体积实验；还残留依赖 WebView 的 Jelly，最终候选继续移除该入口 |

这些行是系统盘 XZ，不是三盘归档或完整 JAR。最终三盘归档必须单独校验严格的 500,000,000 字节门槛。

v7 compact 全新配套磁盘在 Windows WHPX / SandyBridge / 4 vCPU / 4 GiB / CPU 图形路径完成 Android 启动，串口完成标记 32,337 ms，关机 `guest-confirmed`。5,297 ms 的首传输帧包含固件阶段，不能称为 Android 桌面出现时间；也不能与之前 VirGL 的 GPU 帧计时直接比较。配套 metadata 的隔离应用测试中，WebView 的 JavaScript `12+34=46` 回调及 Canvas 像素检查通过，截图确认实际网页显示；生产触摸通道计算器结果为 46。此轮 2048 自动滑动误落在菜单上，未计为游戏玩法通过，v10 已修正菜单状态检查并通过实际玩法重测。

测试故障也保留记录：新 v7 系统盘与旧加密用户盘混配时，vold 在空白 metadata 分区找不到密钥，无法挂载数据；该结果不是系统源码裁剪失败。改用全新配套盘后启动成功。应用回归另外制作隔离盘，只迁移旧测试设备的 32 MiB metadata，逐字节确认新系统其余内容不变；该盘含测试密钥，绝不用于分发。另一次应用虚拟机在与 Android 构建并行时无法分配 4 GiB 内存，错开构建后启动成功。
v9 故障诊断：系统盘 XZ 达到 497,700,440 字节，但因启动循环被淘汰，不能分发。完整串口 Logcat 明确记录 `WebViewUpdateServiceImpl2` 构造函数抛出 `No available by default WebView Provider`，SystemServer 随之退出；不是已经确认的 VirGL 驱动缺失。v10 同步取消 WebView feature 声明，配置校验要求该声明与 minimal 预装选择一致。原始 virtconsole 默认只输出 crash 缓冲区，完整诊断另用临时 GRUB 参数选择 all 缓冲区，不改分发盘。

另更正分区审计：`com.android.virt.apex` 的来宾路径是 /system/apex，在 system-as-root 镜像中须检查 /system/apex/com.android.virt.apex；它仍存在，但已缩小至 1,331,200 字节。仅检查 system_ext/apex 下同名文件的“未找到”不能证明该组件已被移除。最终记录以真实安装路径及来宾查询为准。
完整日志中的直接错误位于 v9-all-log-probe 会话，WebViewUpdateServiceImpl2.java:125 构造失败。v10 使用系统现有 unavailable-feature 机制：SystemConfig 读取各分区后统一移除该 feature，SystemServer 在 hasSystemFeature 检查处跳过服务启动。未修改 Android framework 的异常处理，也未降低 SELinux。

## 压缩复现

在已准备好的 Linux 构建主机执行 `build-go.sh amd64 minimal`，取该次构建的 `VirtualMachine/UTM/LineageOS_Go_on_x86_64.utm/Data/` 三盘。不要使用测试手机目录中的磁盘。系统模板保持稀疏 QCOW2，下面只改变分发表示，不改变虚拟磁盘容量：

```sh
xz -T4 --memlimit-compress=8GiB --no-adjust --x86 \
  --lzma2=preset=6,dict=48MiB -c vda.qcow2 > vda.qcow2.xz
java -Xmx256m scripts/VerifyImageXZ.java BASE_MOD.jar \
  vda.qcow2.xz vda.qcow2 NEW_DECODED.qcow2
```

`VerifyImageXZ` 必须在项目目录调用并传入实际文件路径；输出须是新路径。三盘镜像 ZIP 对 XZ 条目用 STORED、空白数据盘/EFI 用 Deflate，并附原始/压缩文件 SHA-256；对完成后的整个 ZIP 检查 CRC 和 `<500000000`，不使用压缩中间文件大小作最终结论。完整离线 Mod 可继续使用现有 `CompressRuntime --amd64-image-compact` 与 `PackageRuntime --xz-dir` 流程，玩家端的首次解压由内置 Java codec 处理。
## 在本机快速试用

`v10` 的干净三盘模板位于 `.runtime/images/lineage-go-amd64-20260913-minimal-v10/`。在项目目录运行：

```powershell
$env:JAVA_HOME='D:\Program Files\Zulu\zulu-25'
.\test-phone.cmd qemu --runtime-config .runtime/go-windows-minimal.properties
```

拿取新的手机物品进入 v10；旧手机不会自动迁移。原 `.runtime/go-windows.properties` 仍保留 v5，`.runtime/go-windows-compact.properties` 指向上一轮 v6 内部 zlib 压缩实验，与本轮 Android `compact` 产品配置不是同一概念。

应用验收使用隔离测试盘，只迁移旧测试设备的 metadata 密钥并配对原用户盘，逐字节确认新 v10 系统的其余区域不变。它不是分发模板；全新手机及 Minecraft 测试使用从干净 v10 三盘初始化的另一套数据。一次临时应用脚本误指向已淘汰的 v9，核对配对报告后重新制作 v10 测试盘并复验；该错误没有改变最终归档和全新启动测试输入。

## 本轮收口与后续优先级

用户指出围绕体积门槛的投入已过多，本轮在 v10 达到镜像归档目标并完成验收后停止继续压缩。建议保留 WebView 的版本作为主线，minimal 仅作体积优先的可选实验；本机默认仍是已验证的 v5，没有把精简版切换为默认。

后续优先改善启动等待、运行状态恢复和交互流畅度。继续删兼容组件来跨越人为体积门槛，收益不足以抵消兼容性和维护成本。此次没有为恢复 WebView 承诺简单 APK 补装路径，也没有开展新一轮性能改动。
