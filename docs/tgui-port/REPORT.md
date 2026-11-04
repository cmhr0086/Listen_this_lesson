# Listen 原生 tgui 移植报告

日期：2026-11-05，Windows / Asia/Shanghai。Listen 基线 `a2b5cc4`，分支 `codex/tgui-port`。此报告严格区分真实设备操作、本地模拟服务和未验证项。

## 结论

原有 Android Compose 界面已替换为原生 tgui，MainActivity 类名、Listen 名称和图标保留。**不能宣称所有产品要求全部完成**：课表 PDF 仅实现现有分支合同的预览及创建所选课程，缺少时段持久化；基线 Android 没有该模型，main 服务也没有对应 endpoint，在本次不改 schema / 服务端的约束下保留为明确缺项。

最终验证：验收实现提交 `fdbce41` 的三项 Gradle 任务全部通过：174 单元测试、110 MuMu 设备测试，0 失败 / 错误 / 跳过；总耗时 5m 14s。全套最终截图及人工操作见下节。

## 实现与减法

- 课程 / 课堂管理、建议课程快捷开始、录音开始 / 暂停 / 继续 / 结束、前台通知、恢复、录音与补识别队列、WAV 导出接回原 ViewModel / runtime。
- 原文使用原生气泡、分组、跟随最新、搜索、长按 / 拖动多选、复制、删除、TXT 导出及选段 AI。普通追加不再整组重绑；超长段和阅读锚点有设备回归。
- AI 会话列表使用 DialogCell / DialogItem；会话使用 ChatFragmentBase / TextBubbleCell / ChatActivityEnterView，保持流式、仅当前回答更新、上滑不强拉到底、长按复制、附件、来源、历史和重试。完整 Markdown 用 Markwon，包含表格 / 代码 / 列表，不用气泡截断代替全文。
- 生成笔记、成功 / 失败 / 重试 / 重新生成、追问、纠错查看与应用入口保留。设置、十类提示词、生成参数、云同步 / 设备绑定 / 向导、VAD / ASR 诊断均迁移。
- 公开发布改为首页顶层 hub，集中进入各课程状态；课程 / 课堂的笔记和资料入口直接可见。资料适配现有 API，上传 / 查看 / 删除 / 课堂归属使用本地 MockWebServer 验证。
- 只移除专业词提示及开关、记重点入口与通知按钮、空闲首页最近课程、额外配色界面。旧值、marked 字段和同步、外观枚举及 ARGB 存储兼容保留。
- 删除 19 个旧 Compose 页面 / 主题文件、Compose 插件 / 依赖；纯展示 helper 移到独立文件，旧业务数据代码不重写。

逐行功能、测试与不足见 [PARITY.md](PARITY.md)，取舍见 [DECISIONS.md](DECISIONS.md)，AI 细节见 [AI.md](AI.md)，旧测试映射见 [UI_TEST_PARITY.md](UI_TEST_PARITY.md) 和 [APPEARANCE-TESTS.md](APPEARANCE-TESTS.md)。

## 构建与设备证据

统一命令环境：

```powershell
$env:JAVA_HOME='C:\Users\CMHR\.jdks\temurin-21.0.13+9'
$env:ANDROID_HOME='C:\Users\CMHR\AppData\Local\Android\Sdk'
$env:ANDROID_SERIAL='127.0.0.1:16384'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.timeout_msec=90000'
```

| 检查 | 实际结果 |
| --- | --- |
| 未修改基线 assembleDebug / testDebugUnitTest | 通过；独立 detached worktree；原 JBR 缺 jlink，换完整 Temurin 后通过 |
| 最终 assembleDebug | 通过；验收实现 fdbce41，connected-native-accepted.log |
| 最终 testDebugUnitTest | 174 项，0 失败 / 错误 / 跳过；XML 汇总核验 |
| 最终 connectedDebugAndroidTest | 110 项，0 失败 / 跳过；5m 14s；connected-native-accepted.log |
| git grep androidx.compose -- app/src/main | 无结果；退出码 1 代表未匹配 |
| App / 根 Gradle / version catalog Compose 配置 | 无插件或依赖 |
| git diff a2b5cc4 -- app/schemas | 空 |
| data/、audio/、recording/、ViewModel | 相对基线无差异 |
| ListeningForegroundService | 只删除通知“标记重点”按钮及 PendingIntent，共 5 行；处理逻辑和录音生命周期不变 |
| tgui assembleRelease / demo assembleDebug | 通过，12 秒；日志 tgui-final-build.log |
| 崩溃检查 | 最终 logcat crash buffer 未出现 com.cmhr.listen.debug；缓冲区包含历史 UiAutomator 工具进程 SIGSEGV，未把它们当作 Listen 崩溃或声称缓冲区全空；crash-final.log |

MuMu 实例 0，SDK adb **127.0.0.1:16384**，Android 15 / API 35，x86_64，1080×1920，480 dpi，手机竖屏。只操作 `com.cmhr.listen.debug`；正式包 / USB 真机未操作。AGP 的 connected 测试按 ANDROID_SERIAL 选择目标；测试结束会卸载 debug 包，截图 / 手工操作前重新安装。所有截图来自重新生成的 DemoData；没有公开真实课堂或凭据。

过程中的失败保留在本地日志：原 JBR 环境失败；首轮原生测试定位/等待方式修正；第一次全量 105 项中 2 项断言失败（debug 包名、表格 span 遍历顺序），修正后 107 项全通过；补强首页选择器测试曾漏返回首页，110 项中 1 项失败，补真实返回步骤后再全量重跑。没有删除、跳过或弱化失败用例。返回 / 标题修复后的 110 项曾有 1 项配置向导断言抢在返回动画结束前执行（SettingsNativeTest:135）；增加有界等待真实首页及动画结束，保留全部原断言，再整轮重跑通过。所有测试结果以最终一行为准。

本地日志目录 `D:/Project/out/tgui-port-logs/`，完整测试 XML / HTML 在 app/build 下，未把原始日志推到公开库；本报告和测试源码记录可复核的范围。

## 实际操作与截图

- 实际启动主入口 → 课程 → 课堂 → 原文 → 输入 Sy 搜索 → 返回原文 / 课堂通过；搜索系统返回吞键问题修复后，重新走通该路径。日志 manual_navigation.log。
- 退到桌面后 am kill debug 包，确认旧 PID 消失；重新启动 MainActivity 恢复课堂，再返回原课程及课程列表，标题与列表均正确。首次实测发现课程标题回退默认值，现缓存动态标题并在 createView 绑定，新增重建断言。
- 系统麦克风弹窗点“不允许”后，首页显示需要权限错误、未开始录音；再次点仅录音并在系统弹窗授权后开始真实 AudioRecord。通知权限实际通过系统弹窗授予。日志 manual_permission_final.log。
- 录音中主动 force-stop debug 进程，重启后显示“录音意外中断，已保留到 00:57”；独立读取 debug 数据库验证 INTERRUPTED、课堂 endedAt 非空，WAV 为 912,384 帧 / 16 kHz / 1,824,812 字节 / 57.024 秒，帧数与数据库一致。原文 0 段，本次仅录音，无 ASR / VAD 阳性结论。日志 manual_recovery_final.log。录制期间计时频繁更新使外部 uiautomator dump 曾读到旧 XML；最终用实际截图、数据库及文件内容交叉核验，没有据此修改业务或弱化设备测试。
- 单课堂“导出录音”和整课程“导出全部录音”均实际打开系统 SAF、选择目录、授权写入。两组各有 2 份 WAV，独立二进制读回均为单声道 PCM16 / 16 kHz，每组 3 秒与 4 秒，大小 96,044 / 128,044 字节。日志 manual_export_verified.log。Windows adb pull 对含空格中文目录失败后改为 exec-out 原始参数读回；设备导出文件本身完整。

设备测试还验证：真实 AudioRecord 累积超过 20 秒、后台至少 3 秒继续计时、通知前台状态、暂停后继续产生独立 WAV、结束课堂写入时间；没有由此宣称真实语音质量 / VAD 阳性。AI 全链路通过本地 SSE 模拟：提问、PENDING 到 SUCCESS、仅末行刷新、上滑锚点不变、长按复制完整回答、全文表格 / 代码、退出重进历史；笔记成功、500 失败和重试也只走本地模拟服务。

共 **54 个页面 / 关键查看状态、108 张日夜截图**。拍摄于 MuMu 1080×1920 / 480 dpi，全部为 debug DemoData；已逐张 / 联系表复核，非真实服务内容。完整长列表首屏外的内容以测试和功能检查为准。

| 页面 / 查看状态 | 日间 | 夜间 |
| --- | --- | --- |
| home | [日间](screenshots/home-day.png) | [夜间](screenshots/home-night.png) |
| courses | [日间](screenshots/courses-day.png) | [夜间](screenshots/courses-night.png) |
| course | [日间](screenshots/course-day.png) | [夜间](screenshots/course-night.png) |
| classroom | [日间](screenshots/classroom-day.png) | [夜间](screenshots/classroom-night.png) |
| transcript | [日间](screenshots/transcript-day.png) | [夜间](screenshots/transcript-night.png) |
| recordings | [日间](screenshots/recordings-day.png) | [夜间](screenshots/recordings-night.png) |
| search | [日间](screenshots/search-day.png) | [夜间](screenshots/search-night.png) |
| publish-hub | [日间](screenshots/publish-hub-day.png) | [夜间](screenshots/publish-hub-night.png) |
| publish-course | [日间](screenshots/publish-course-day.png) | [夜间](screenshots/publish-course-night.png) |
| course-materials | [日间](screenshots/course-materials-day.png) | [夜间](screenshots/course-materials-night.png) |
| class-materials | [日间](screenshots/class-materials-day.png) | [夜间](screenshots/class-materials-night.png) |
| timetable | [日间](screenshots/timetable-day.png) | [夜间](screenshots/timetable-night.png) |
| ai-list | [日间](screenshots/ai-list-day.png) | [夜间](screenshots/ai-list-night.png) |
| ai-new | [日间](screenshots/ai-new-day.png) | [夜间](screenshots/ai-new-night.png) |
| ai-class-new | [日间](screenshots/ai-class-new-day.png) | [夜间](screenshots/ai-class-new-night.png) |
| ai-chat | [日间](screenshots/ai-chat-day.png) | [夜间](screenshots/ai-chat-night.png) |
| ai-class-history | [日间](screenshots/ai-class-history-day.png) | [夜间](screenshots/ai-class-history-night.png) |
| ai-results | [日间](screenshots/ai-results-day.png) | [夜间](screenshots/ai-results-night.png) |
| ai-note | [日间](screenshots/ai-note-day.png) | [夜间](screenshots/ai-note-night.png) |
| ai-note-followup | [日间](screenshots/ai-note-followup-day.png) | [夜间](screenshots/ai-note-followup-night.png) |
| ai-note-generate | [日间](screenshots/ai-note-generate-day.png) | [夜间](screenshots/ai-note-generate-night.png) |
| settings | [日间](screenshots/settings-day.png) | [夜间](screenshots/settings-night.png) |
| settings-stt | [日间](screenshots/settings-stt-day.png) | [夜间](screenshots/settings-stt-night.png) |
| settings-ai | [日间](screenshots/settings-ai-day.png) | [夜间](screenshots/settings-ai-night.png) |
| settings-models | [日间](screenshots/settings-models-day.png) | [夜间](screenshots/settings-models-night.png) |
| settings-sync | [日间](screenshots/settings-sync-day.png) | [夜间](screenshots/settings-sync-night.png) |
| settings-publish | [日间](screenshots/settings-publish-day.png) | [夜间](screenshots/settings-publish-night.png) |
| settings-device | [日间](screenshots/settings-device-day.png) | [夜间](screenshots/settings-device-night.png) |
| settings-appearance | [日间](screenshots/settings-appearance-day.png) | [夜间](screenshots/settings-appearance-night.png) |
| settings-vad | [日间](screenshots/settings-vad-day.png) | [夜间](screenshots/settings-vad-night.png) |
| settings-prompts | [日间](screenshots/settings-prompts-day.png) | [夜间](screenshots/settings-prompts-night.png) |
| settings-generation | [日间](screenshots/settings-generation-day.png) | [夜间](screenshots/settings-generation-night.png) |
| settings-asr | [日间](screenshots/settings-asr-day.png) | [夜间](screenshots/settings-asr-night.png) |
| settings-asr-history | [日间](screenshots/settings-asr-history-day.png) | [夜间](screenshots/settings-asr-history-night.png) |
| settings-asr-segment | [日间](screenshots/settings-asr-segment-day.png) | [夜间](screenshots/settings-asr-segment-night.png) |
| setup | [日间](screenshots/setup-day.png) | [夜间](screenshots/setup-night.png) |
| prompt-notes | [日间](screenshots/prompt-notes-day.png) | [夜间](screenshots/prompt-notes-night.png) |
| prompt-style_detailed | [日间](screenshots/prompt-style_detailed-day.png) | [夜间](screenshots/prompt-style_detailed-night.png) |
| prompt-style_outline | [日间](screenshots/prompt-style_outline-day.png) | [夜间](screenshots/prompt-style_outline-night.png) |
| prompt-style_framework | [日间](screenshots/prompt-style_framework-day.png) | [夜间](screenshots/prompt-style_framework-night.png) |
| prompt-style_qa | [日间](screenshots/prompt-style_qa-day.png) | [夜间](screenshots/prompt-style_qa-night.png) |
| prompt-correct | [日间](screenshots/prompt-correct-day.png) | [夜间](screenshots/prompt-correct-night.png) |
| prompt-quick | [日间](screenshots/prompt-quick-day.png) | [夜间](screenshots/prompt-quick-night.png) |
| prompt-class_chat | [日间](screenshots/prompt-class_chat-day.png) | [夜间](screenshots/prompt-class_chat-night.png) |
| prompt-image | [日间](screenshots/prompt-image-day.png) | [夜间](screenshots/prompt-image-night.png) |
| prompt-general | [日间](screenshots/prompt-general-day.png) | [夜间](screenshots/prompt-general-night.png) |
| ai-markdown | [日间](screenshots/ai-markdown-day.png) | [夜间](screenshots/ai-markdown-night.png) |
| ai-source | [日间](screenshots/ai-source-day.png) | [夜间](screenshots/ai-source-night.png) |
| ai-saved-prompt | [日间](screenshots/ai-saved-prompt-day.png) | [夜间](screenshots/ai-saved-prompt-night.png) |
| ai-correction | [日间](screenshots/ai-correction-day.png) | [夜间](screenshots/ai-correction-night.png) |
| ai-attachment | [日间](screenshots/ai-attachment-day.png) | [夜间](screenshots/ai-attachment-night.png) |
| setup-stt | [日间](screenshots/setup-stt-day.png) | [夜间](screenshots/setup-stt-night.png) |
| setup-ai | [日间](screenshots/setup-ai-day.png) | [夜间](screenshots/setup-ai-night.png) |
| setup-ready | [日间](screenshots/setup-ready-day.png) | [夜间](screenshots/setup-ready-night.png) |

## 体积、依赖与分支

基线 APK：88,976,972 字节；并存阶段：101,991,639 字节；最终原生 APK：86,092,621 字节，较基线减少 2,884,351 字节（约 3.24%）。

tgui 使用 `org.telegram.ui:tgui:11.4.2-ui5`，本地 Maven；库代码来源 `e40ffdf75f18953c27b0e591e57f02ac9c35f394`。主题文件规范为 Git blob 的 LF 后重新构建，库 API / 资源内容无变更，不另发版本后缀。tgui 分支仅有宿主接入与验收记录。

- Listen 验收实现提交：`fdbce41d22f8b5b2a51bb96fef7f04a532971f4c`；草稿 PR：[Listen #5](https://github.com/cmhr0086/Listen_this_lesson/pull/5)，目标 main。
- tgui 文档提交：`0d083547b6f90c15614330d749de3355aea531e9`；草稿 PR：[tgui #8](https://github.com/cmhr0086/telegram-11-ui/pull/8)，目标 dev/ui-lib。
- 验收代码及 tgui 文档分支已推送，git ls-remote 与上述 SHA 一致；最终报告 / 截图提交后再次核对远端 HEAD。报告与截图可能在验收实现提交之后单独提交，最终文档 HEAD 以草稿 PR 分支为准。没有合并 / 自动合并 / tag / Release / 版本号修改 / force push。推送成功，因此不生成 bundle。

## 已知缺项与未验证

- PDF：完整课表时段导入未完成；当前 main 服务未提供已核验的分支 endpoint，404 会显示明确失败，不能把 mock 成功写成线上可用。
- 文件选择器 / 相机期间进程被销毁后，不续接旧 lambda；返回成功时提示重新选择 / 拍照。权限中的录音请求保存标识符；普通页面恢复保持路由栈，临时正文查看器回到可恢复父页。尚未覆盖所有外部 Activity 被系统回收的组合。
- 全部真机（Mate 9、主力机）、真实麦克风与 VAD、arm64 原生库、真实 ASR、真实 AI、真实同步 / 发布 / 绑定 / 上传、长时间录音均未验证。MuMu 实际录音文件存在不等于真实语音识别可用。
- Android 15 以外版本（含 Android 16 返回策略）、平板、复杂 RTL、超大字体、真实相机应用兼容和长时后台限制未验证。
- 笔记追问中的折叠回答可同时显示气泡“查看全文”文本和全文按钮，内容可达；后续可精简重复文案。
- 日夜截图覆盖页面与关键查看器，不等于每个网络状态、每种文件格式、所有批量删除和导出取消竞态都已穷举。更细的未覆盖分支见 PARITY。

## 许可与合并前验收

tgui 为 GPL v2 or later，Listen 标称 Apache-2.0；组合分发的许可证方案由项目所有者决定。LICENSE、THIRD_PARTY_NOTICES.md 未修改，本任务不发布正式签名包。

明早建议按下列顺序验收：

1. 查看两个草稿 PR、PARITY 缺项及日夜截图；先决定许可和课表缺项范围，再决定是否合并，保持草稿直至决定。
2. 在自有测试设备、独立测试数据下复查录音开始 / 后台 / 暂停 / 继续 / 结束、异常重启、导出文件与权限拒绝；补齐 Mate 9 / 主力机、arm64 和长时后台。
3. 使用项目所有者批准的测试环境及账号验证真实 ASR / AI / 绑定 / 同步 / 发布 / 资料；本任务没有代替这些检查调用生产服务。
4. 检查 Android 16 返回策略、外部选文件 / 拍照重建、字体与可访问性。核验远端更新后的 main 差异与冲突，再独立执行合并前构建；本任务未合并 main 的后续服务端提交。
