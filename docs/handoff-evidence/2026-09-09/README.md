# 交接证据摘要

本目录只携带独立开发测试手机的应用截图、哈希与本机回归结果摘要。没有 APK、用户盘、ADB 密钥或整套 `.runtime`。普通镜像与 Go 镜像严格区分。

## 普通 Android 应用基线

来源为 Mac M4 上 `.runtime/evidence/go-optimization/automated-final/`，测试使用普通 Android 16 ARM64 和 v5 Mac 内置候选。两个原作者 APK 的版本、来源与哈希见 [apps.json](../../../android/image/tests/apps.json)。

- [计算器截图](calculator-result.png)：`12+34=46`，自动化通过 UI XML 断言。
- [2048 滑动后](game-after.png)：人工审阅得分 144、步数 24、出现 32 方块。
- [2048 后台返回](game-resumed.png)：人工审阅棋盘、得分及步数均与前图一致。

固定 24 次滑动不保证每次相同得分，因为棋盘有随机性。自动化输出保留 `GAME_CAPTURES_REQUIRE_REVIEW`；以上游戏结论来自截图审阅，没有 FPS、长时间稳定性、3D 游戏或 Go 镜像验收结论。首次自动化返回截图曾抓到启动过渡画面，增加等待后重新运行得到本目录最终图片。

`files.json` 为复制时计算的字节数和 SHA-256。测试会话均已停止，原始证据根目录清理审计为 `CLEANUP_OK sessions=3`；进程退出不等于所有关机策略都能持久保存数据。

## 上传前本机回归

2026-09-09 在同一 Mac、Zulu JDK 25、已有 Gradle 缓存下执行：

```text
sh gradlew build runtimeSelfTest --offline
BUILD SUCCESSFUL in 13s
15 actionable tasks: 9 executed, 6 up-to-date

java android/image/tests/PrepareSelfTest.java
GO_PREPARE_OK dry-run, idempotence, both root products evaluated, non-Go packages preserved, drift and dirty overlay rejected before writes

git diff --check
exit 0（test-phone.cmd 有 Git 行尾转换提示，无 diff 错误）
```

生命周期覆盖正常连接/重连、自有进程树清理、取消/失败和 JVM SIGKILL 后清理。此结果不代表 Windows 实机、远端 CI、完整 Android 编译或完全无缓存构建已经通过。后续 CI 以该交接分支的 GitHub Actions 实际状态为准。
