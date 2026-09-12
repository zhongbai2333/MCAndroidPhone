# VirtIO 源码输入 · 2026-09-09

`virtio-dependencies.json` 记录两个 Go 设备树及递归 `lineage.dependencies` 的 16 个仓库、路径、分支和提交。本轮所有依赖均有 `lineage-23.2`，未使用 23.1/23.0 回退。上游 Repo manifest 初始提交为 `705406eb22c0efc833a7821ca198e3f0c79b4115`；该文件只锁定 VirtIO 附加依赖，不能替代完整源码同步完成后的 `repo manifest -r`。

当前独立 WSL 源码目录是 `/home/zhongbai233/android/lineage-23.2-mcphone`。其 `.repo/local_manifests/mcandroidphone-virtio.xml` 使用这些 SHA 和对应 upstream 分支，并设置 clone-depth=1。源码来自 LineageOS/AOSP 原服务器。

依赖包按 [AOSP 开发要求](https://source.android.com/docs/setup/start/requirements) 和 [上游 QEMU 构建脚本](https://github.com/jqssun/android-lineage-qemu/blob/54fc5dc82fa05778be15c1200240be53f707a542/build.sh) 准备；没有执行上游的全局 Git 配置、force-sync 或品牌 sed。普通模组运行仍不需要 Python，Repo/Python 属于 Android 系统开发依赖。
