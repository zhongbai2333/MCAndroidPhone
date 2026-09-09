# 独立手机 Mod

产物：`build/libs/mcandroidphone-0.1.0-prototype.jar`。需要 Minecraft 26.1.2、NeoForge 26.1.2.76 和 Java 25。无需 NCPB、NetMusic、SceneEditor；客户端和服务器都安装手机 Mod。

升级时移除旧 `mcandroidphone-ncpb-adapter-*.jar`，换成本次独立产物。物品 ID `mcandroidphone:android_phone` 保持不变，无需重新创建世界。桥接仍只连接本机设备，不自动把安卓画面共享给其他玩家。

## 快速测试

```bat
test-phone.cmd pattern --world-smoke
test-phone.cmd qemu
test-phone.cmd qemu --qemu-gpu virgl
```

第一条无需 Android，自动新建临时世界，检查独立 Mod 加载、实际物品使用、NV12 更新、投影触控、移入背包后恢复及资源包重载。后两条分别打开普通 QEMU 和实验 GPU 模式。启动器不再扫描或构建其他 Mod。

## 操作与收纳

- 从“安卓手机”创造分类取手机，或 `/give @s mcandroidphone:android_phone`。
- 主手右键由 JAR 开机、连接并聚焦；已连接时右键只打开触控界面。
- 左键点击/拖动发送单指触屏；右键返回，Home 回桌面，End 打开最近任务。
- Esc 退出聚焦；切换快捷栏、移到副手或收进背包释放触点，保留设备会话和画面。拿回主手直接显示，不必再右键连接。
- F8 或 `/androidphone disconnect` 断开画面，安卓继续运行。`/androidphone poweroff` 关闭自有运行环境；离开世界/关闭游戏也会清理进程和显示资源；把手机移出自己的库存超过约 2 秒会断开。

`/androidphone status` 查看状态，`/androidphone runtime` 查看启动状态和日志；其他命令为 `connect`、`back`、`home`、`recent`、`text <文字>`。Backspace 当前表示安卓返回；文字输入能力取决于后端。

## 实现范围

手机独立维护物品身份、库存追踪、薄边框模型、手持渲染、70 度第一人称投影和逆透视触点。NV12 两平面与 GPU RGBA 路径使用自己的纹理与 shader。动态画面缓存跨收纳和资源包重载存活，断线后按连接 epoch 清理。

仍只支持一个本机安卓实例、主手聚焦和单指触屏。音频、多指、多人共享与 Iris shaderpack 兼容尚未完成。后端与启动管理已迁入 Java，玩家无需 Python；普通 JAR 使用外部依赖，平台内置 JAR 可自动解压启动，手机磁盘默认按物品持久保存。配置见 [运行环境](runtime.md)，包与摄像头通道的证据见 [本机功能验收](local-features.md)。GPU 模式仍有一次 GPU 缓存复制，参见 [GPU 边界](gpu.md)。
