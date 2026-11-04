# Listen tgui 移植报告（进行中）

截至 2026-11-05 第一个检查点：**未达到完成标准**。保留 Compose launcher，新入口为 debug 专用 `com.cmhr.listen.tgui.ListenHostActivity`。本报告随实测更新，不代表最终验收。

## 实际证据

| 检查 | 结果 |
|---|---|
| 未改动基线 assembleDebug + testDebugUnitTest | 完整 Temurin 21：通过；首次缺 jlink 的 JBR 构建失败是环境问题 |
| tgui ui5 发布/库 release AAR/demo debug 构建 | LF 主题资源下通过；未构建 Listen 正式签名包 |
| 新界面 assembleDebug + testDebugUnitTest | 通过，174 tests / 0 failures / 0 errors / 0 skipped |
| assembleDebugAndroidTest | 原生测试 APK 编译通过；设备回归仍在修复，不等于全通过 |
| connectedDebugAndroidTest | 尚未运行通过 |
| MuMu debug 安装、原生入口冷启动 | 成功，首屏实测；此阶段 crash buffer 为空 |
| 无 Compose / launcher 切换 | 未完成，按要求保留旧界面 |
| 所有页面日夜截图 | 未完成，只有首屏夜间图 |
| Room schema / 数据与录音逻辑 | 当前无修改；最终仍需 git diff 审计 |

环境：Windows、Temurin 21.0.13+9、SDK 36、NDK 28.2.13676358、CMake 3.22.1。MuMu serial 127.0.0.1:16384，Android 15 / x86_64 / 1080×1920 / 480 dpi，手机竖屏。SDK adb 全程显式指定设备，未操作真机/正式包。

## 功能与取舍

功能清单见 [PARITY.md](PARITY.md)，决定见 [DECISIONS.md](DECISIONS.md)。AI、设置、发布/资料的实现说明和测试映射分别见 [AI.md](AI.md)、[SETTINGS.md](SETTINGS.md)、[PUBLISH-SEARCH.md](PUBLISH-SEARCH.md)。它们的代码清单不等于设备验证结果。

新 UI 删除专业词入口、原文记重点、空闲时最近课程、额外外观主题；原值和同步字段保留。公开发布已放在首页独立入口，课程/课堂有显著资料入口。AI 问答使用 ChatFragmentBase/TextBubbleCell/ChatActivityEnterView，复杂 Markdown 提供完整 Markwon 页面。

课表 PDF 解析接口只在 feature/timetable-pdf 分支出现，main 不具备该端点；现有 Android 没有课表时段存储。本次仅用已有接口合同实现导入预览及课程创建，完整时段持久化未完成，不能宣称完整课表可用。资料接口复用既有服务，实测只准用本地 MockWebServer。

## 体积和库

- 基线 debug APK：88,976,972 字节。
- 首个原生并存 APK：101,991,639 字节，增加 13,014,667 字节；Compose 尚未移除。
- 使用 tgui ui5 源提交 e40ffdf75f18953c27b0e591e57f02ac9c35f394，无源码修复。
- tgui 文档提交 a33231f4a098a47b135d57ab5fdac28c12575104；[草稿 PR #8](https://github.com/cmhr0086/telegram-11-ui/pull/8)。Listen 首个检查点 ca2fac9c33d82700b80d10cc62a64c35c8e90b87；[草稿 PR #5](https://github.com/cmhr0086/Listen_this_lesson/pull/5)。

## 未验证

所有真机（Mate 9、主力机）、真实麦克风/VAD、arm64 运行、真实 ASR、真实 AI、真实同步/发布、长时间录音、MuMu 未覆盖的 Android 版本均未验证。当前也未完成录音全流程、后台/旋转/杀进程恢复、AI 流式本地模拟及全部页面截图。

## 许可与合并前

tgui 是 GPL v2 or later，Listen 为 Apache-2.0；组合分发和仓库许可处理必须由所有者决定。未修改 LICENSE/THIRD_PARTY_NOTICES.md，不合并、不打 tag、不发 Release、不改版本号。

明早验收：先读 PROGRESS，再核对所有 PARITY 行；运行三项 Gradle gate；按任务路径在 MuMu 验证录音/AI/返回/主题/恢复并检查 crash log；完成每页日夜截图；确认 Compose 已完全移除、数据/schema 不变；检查两草稿 PR 和远端 SHA；最后由所有者决定许可与合并。
