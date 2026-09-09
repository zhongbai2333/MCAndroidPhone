# Go 方向优化与应用基线（2026-09-09）

本轮推进源码构建配置与可复用应用验收，没有在当前普通 Android 镜像上设置一个 Go 属性后冒充新系统。**未生成定制 Go 镜像，完整包仍为上一轮已验证的 726.37 MiB；500 MiB 目标未达成。**

## 上游核验

[jqssun/android-lineage-qemu](https://github.com/jqssun/android-lineage-qemu/releases/tag/v2026.08.22) 最新公开发布 v2026.08.22 的 10 个资产是普通 ARM64/x86_64 系统、OTA、boot、recovery，没有 Go 预编译镜像。上游脚本实际构建普通产品，不能把 `_go` 产品定义的存在当成已有可下载镜像。

读取并核对了 LineageOS 23.2 两个 Go 产品、Go defaults、vendor/lineage mini 继承链和 Soong dexpreopt 选择逻辑。来源与 SHA-256 在 `.runtime/evidence/go-optimization/upstream/source-lock*.json`；纳入离线回归的原始配置见 `android/image/tests/fixtures/sources.json`。

## 已实现的源码改动

1. **修正根产品配置。** 过去把定制产品作为最后一个子产品继承，无法覆盖根产品已明确设置的品牌等标量；语言列表也可能与 `languages_full.mk` 合并。现在直接在两个 Go 根产品末尾 include 定制配置，源头替换全语言继承，将源码资源的目标语言收敛为中英文，继续保留全部字体与输入能力。已有预编译 APK 内的多语言资源不会因此被重新裁切，不能据此直接计算体积收益。
2. **采用有取舍的预编译策略。** 普通预装 APK 默认 `verify`，SystemUI 和 Go Launcher 继续在 speed 名单，system server 保留 speed-profile。保留原始 DEX、ART/JIT/AOT，不全局关闭 dexpreopt。不把上游已有的去调试 ART、精简 Java 调试信息算作本轮新增收益。
3. **有范围的预装裁剪。** Backgrounds、BasicDreams、EasterEgg、PrintRecommendationService、vim 在原始包声明处按目标产品条件移除；非 Go 产品不变。没有再尝试删除上次失败的 AVF/CompOS，没有删除 WebView、APEX、HAL、中文字体或输入法。
4. **构建前检查最终解析结果。** `build-go.sh` 只选择 Go 目标，从 `get_build_var` 输出检查实际品牌、语言、用户构建类型、预编译、低内存属性、必需模块和已移除模块。记录真实 repo manifest 及本地 overlay/camera 文件哈希，再开始镜像构建。变量读取失败也会中止。

[AOSP ART 配置](https://source.android.com/docs/core/runtime/configure)支持这些编译筛选项，同时明确系统体积、数据分区、首启和性能的取舍。配置文本通过不代表最终镜像更小或游戏更快。

## 本机验证

- `PrepareSelfTest` 使用原始上游产品片段、GNU Make 和故障夹具验证：两个根产品设置生效、Go/非 Go 包选择、dry-run 不写入、重复执行不变化、上游锚点变动/本地 overlay 修改在写入前失败。
- 解析结果检查器拒绝缺少 WebView、资源语言扩张、品牌未覆盖、编译策略不对、裁掉的组件重新出现、userdebug 等偏差。
- 已加入独立 `go-overlay` CI 作业；远程 CI 未触发。C++/Blueprint 锚点是最小文本夹具，**不是 Soong、SELinux 或完整 Android 编译通过**。

## 普通应用与小游戏基线

两个 APK 均来自原作者 GitHub Releases，下载后与发布元数据 SHA-256 一致，版本锁定在 `android/image/tests/apps.json`。只安装进独立测试手机，不预装到分发 JAR。

| 样本 | 本机实际结果 |
| --- | --- |
| [Fossify Calculator 1.4.0](https://github.com/FossifyOrg/Calculator/releases/tag/1.4.0) | 原始发布 APK 安装、启动、点击计算 `12+34=46` 通过 |
| [2048 Open Fun Game 1.16.2](https://github.com/andstatus/game2048/releases/tag/1.16.2-release) | 原始发布 APK 安装、显示棋盘、24 次滑动通过；人工核验得分 148、步数 25、出现 32 方块；后台返回保留得分和进度 |

基线使用上一轮完整 v5 JAR 内的 Android 16 ARM64 / HVF、独立已初始化种子及软件图形路径。2048 的一次内存快照 TOTAL PSS 为 160,637 KiB（约 157 MiB），不是峰值或完整内存预算。没有测帧率、持续运行稳定性或 3D 游戏。

新增 `AppCompatibilityTest` 可对同一组锁定 APK 重跑安装、计算、24 次滑动与后台恢复，保存截图及来宾属性。`go` 模式额外要求真实来宾的 low-RAM 属性和 `_go` 产品名，普通镜像不能冒充 Go。游戏部分明确要求人工审阅截图，不将进程存活或图片哈希变化自动判成可玩。使用方式见 [测试说明](../android/image/tests/README.md)。

自动化最终复验也已完成：计算结果为 46；人工审阅 `automated-final/game-after.png` 与 `game-resumed.png`，两张均为得分 144、步数 24，包含 32 方块且棋盘一致。首次自动化运行的返回截图抓到了启动过渡画面，因此没有计为恢复通过；增加滑动动画和返回后的等待时间后重新运行，才得到上述可确认结果。证据位于 `.runtime/evidence/go-optimization/automated-final/`，JSON 仍保留 `requires-visual-review`，此次人工审阅结论记录在本文。

最终 `compileTestJava --offline` 与离线配置回归通过；清理审计返回 `CLEANUP_OK sessions=3`，三个测试会话记录的宿主子进程均已退出。宿主进程清理不等同于所有关机方式的数据持久性验收。

**上述是普通镜像的比较基线，不是 Go 镜像验收。** Go 下仍须重跑同一组测试，再补 WebView 游戏、轻量 3D、音视频和目标 APK 的原生 ABI 兼容性。

## 尚需的构建条件

当前机器是 macOS，完整 LineageOS 构建所需的 Linux 源码树与依赖未准备；本轮开始时本机可用磁盘约 26 GiB，不能承载既定的完整 Android 源码构建流程。因此没有下载数百 GiB 源码、触发远端编译或替换现有手机系统。

Go 配置已可在准备好的 Linux checkout 上按 `android/image/build-go.sh` 执行。编译产物出来后必须重新检查安装文件清单、完整 JAR 体积、首次启动、计算器/小游戏、环境服务、相机和关机，不预报一个未经验证的 500 MiB 成果。
