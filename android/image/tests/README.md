# Go 构建与应用兼容性检查

## 不需要 Android 源码的检查

```sh
java android/image/tests/PrepareSelfTest.java
java android/image/tests/PrepareCompactSelfTest.java
python3 android/image/tests/test_filter_webview.py
bash -n android/image/build-go.sh
```

`fixtures` 保存 2026-09-09 读取的 LineageOS 23.2 原始产品配置，来源和内容 SHA-256 见 `fixtures/sources.json`，保留上游许可声明。C++/Blueprint 锚点由测试生成最小文本夹具，用于检测准备流程的幂等性和失败前不写入，不代表 C++/Soong 编译。

测试用 GNU Make 实际求值两个根产品的品牌、语言和编译配置，以及 Go/非 Go 下的可选包选择。它不会完整模拟 Android 的产品继承系统；Linux 构建仍须通过 `VerifyGoConfig` 对 `get_build_var` 的实际输出检查。

## 真实 Android 应用检查

`apps.json` 固定两个上游公开 APK 及 SHA-256。只用于开发测试，不预装进发布镜像。不需要账号、Play 服务或联网玩法。

先按文件中的发布链接下载 APK 并检查 SHA-256，再使用已编译的测试类与待测完整 JAR：

```sh
# 使用项目的 JDK 25；测试 APK 不会被打入 JAR。
javac -cp CANDIDATE.jar -d TEST_CLASSES \
  src/test/java/com/zhongbai233/mcandroidphone/core/TrimmedAndroidTest.java \
  src/test/java/com/zhongbai233/mcandroidphone/core/AppCompatibilityTest.java
java -Xmx256m -cp CANDIDATE.jar:TEST_CLASSES \
  com.zhongbai233.mcandroidphone.core.AppCompatibilityTest \
  TEST_CONFIG.properties NEW_EVIDENCE_DIR /path/to/adb calculator.apk game2048.apk go
```

Windows classpath 使用分号。可选的最后一个参数指定已有的独立测试 game 目录，以复用解压缓存；每次仍创建新的手机 UUID。配置文件应指向**待测系统对应的独立、已初始化、开发 ADB 可用的整套磁盘种子**；不能将当前普通镜像的旧用户盘用于新 Go 系统验收。用默认本机镜像建立对照时，最后一个参数改为 `baseline`。`go` 模式要求实际来宾 `ro.config.low_ram=true` 且 `ro.product.device` 以 `_go` 结尾，普通镜像无法冒充 Go 通过。

检查执行 APK 哈希验证、安装、启动、计算器 `12+34=46`、固定分辨率下的游戏 24 次滑动、后台返回，并记录属性、截图和内存。每次创建新手机 UUID，显示尺寸修改仅在测试手机内。游戏是否正常合并、得分及恢复状态仍须审阅截图；程序明确输出 `GAME_CAPTURES_REQUIRE_REVIEW`，不能把画面变化或进程存活当成可玩性/FPS 结论。

这套样本不覆盖 WebView 游戏、3D/Vulkan、32 位原生 APK、Google Play 服务、反作弊或标准 Camera2。除本机 Mac ARM64 基线外，其他平台和真正 Go 镜像尚待验收。

## 精简配置与磁盘配对

compact 保留 WebView；minimal 取消预装 WebView 和 Jelly，因此不运行 WebView 成功验收。配置差异和实际镜像结果见 [精简镜像记录](../../../docs/android-go-compact.md)。

AMD64 VirtIO 镜像把数据加密密钥保存在系统盘的 metadata 分区（当前 vda4），数据在 vdb。只把新系统模板与旧数据盘混用，会因缺少密钥而启动失败。优先建立全新、配套的测试设备；若为应用回归制作隔离迁移种子，必须保留配套 metadata，并证明新系统其余内容不变。这样的含测试密钥/数据的盘绝不能进入分发归档。
