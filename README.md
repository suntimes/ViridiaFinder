# 维里迪亚寻宝 Viridia Finder 0.4.0

Minecraft 1.20.4 / Fabric / Java 17，纯客户端。安装对应 Fabric API，退出游戏后将发布 JAR 放入 mods，删除旧版同名模组 JAR。无需服务端安装。详细说明见《醒来先看-0.4.0.txt》。

## 使用

- F7：扫描、高亮、地图一起开关。开启后等待约两秒观测加载范围，自动规划海路；关闭同时停用移动辅助。
- F8：查看候选和箱子，手动确认或标记领取，重新规划海面巡查，打开轮次与移动辅助设置。
- `/viridiafinder`：主命令，使用 Tab 查看实际子命令；`/viridiafinder 新一轮` 为手动备用。不要使用服务器占用的 `/vf`。
- 主手持维里迪亚之眼手动左键：读取中文或英文系统提示，更新领取数和预计刷新时间，满足条件才归档并切换本地轮次。
- 右上角“巡查 N/169”显示本轮真实已巡查数量，候选全部加载且完整扫描后才计入；人工领取标记也计入已巡查。

## 实现与边界

保留原169候选数据库。仅扫描客户端实际加载的区域，只记录 Chest/Trapped Chest，未加载与未发现严格分开。普通箱子不自动升级为本轮宝藏。

读取本地 Xaero 地表缓存生成水域快照后打包，A* 水域寻路加多候选覆盖选站。四格网格采用保守水域判定，未知和陆地不可穿越，仅保留最大的连通海域。规划不会自动移动玩家，也不会强制加载区块。开始位置在岸上时先提示海面入口。历史地形不能证明当前实际通行。

移动辅助默认关闭：连续激流使用正常蓄力/释放，自动向前游泳通过普通前进输入与疾跑请求实现；不自动转向，不改变速度或强制姿态。详细暂停条件和限制见随附说明。

轮次识别基于手动之眼查询的系统提示，依赖公开两小时周期和 NPC 估算，存在余量与备用手动操作，不能视为读取服务器轮次编号。

## 构建与验证

使用 Gradle 8.7 和 JDK 17：`gradle build`。首次构建需要下载依赖。Loom 1.6.12，Fabric Loader 0.15.11，Fabric API 0.97.2+1.20.4，Yarn 1.20.4+build.3。

`check` 调用 `verifySurvey` 可执行验证程序，通过 Fabric Knot 加载真实 Minecraft 类和混入；未采用 JUnit 测试发现。报告生成于 build/verification。测试结果和未进行的实机测试见《测试报告.md》。

`tools/import_xaero_water.py` 用于读取本地 Xaero 地表区域并生成水域资源，仅支持已检查的格式版本。`tools/render_water_route.py` 用于生成路线预览。运行脚本时先查看参数；导入后的资源需替换 src/main/resources/assets/viridiafinder/isles-water.bin 并重新构建。没有实时 Xaero 依赖。

## 资料

- https://monumenta.wiki.gg/wiki/Tov
- https://monumenta.wiki.gg/wiki/Eye_of_Viridia
- https://monumenta.wiki.gg/wiki/Getting_Started

来源文档中的服务器许可未覆盖本模组的全部辅助功能，不声明其已获服务器许可。

## 0.4.0 下载文件

- [安装 JAR](releases/v0.4.0/viridia-finder-0.4.0.jar)
- [完整源码 ZIP](releases/v0.4.0/viridia-finder-0.4.0-source.zip)
- [海面路线预览](releases/v0.4.0/viridia-finder-0.4.0-sea-route.png)

程序验证通过99项，尚未连接服务器实测。详细说明见本仓库使用说明和测试报告。
