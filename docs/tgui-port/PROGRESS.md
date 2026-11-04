# Listen tgui 移植进度

任务日期：2026-11-05（Asia/Shanghai）。目标为完整替换；下方前几节是过程记录，最新状态见文末。

## 环境与基线

- Listen 初始 `main@a2b5cc4`、工作区干净，已建立 `codex/tgui-port`。
- tgui 初始 `codex/phase-f`、工作区干净。fetch 后从 `origin/dev/ui-lib@e40ffdf` 建立 `codex/listen-host-fixes`。切换后四个文件只有 CRLF 差异（`git diff --ignore-space-at-eol` 为空），将仅规范换行。
- 两库 `core.autocrlf=false`；tgui `.gitattributes` 已约束 `*.attheme text eol=lf`。
- SDK：`C:/Users/CMHR/AppData/Local/Android/Sdk`；平台 36、NDK 28.2.13676358、CMake 3.22.1 均已安装；两库 local.properties 已设置且不提交。
- `git lfs pull` 成功，sherpa AAR 48,847,529 字节，后续核验 ZIP/native ABI。
- JBR `D:/Tools/AndroidBuild/jbr21` 缺 `jlink.exe`，两个基线首次因 JdkImageTransform 失败。改用完整 `C:/Users/CMHR/.jdks/temurin-21.0.13+9`，正在重跑。
- Listen 未改动基线位于 `D:/Project/out/listen-tgui-baseline`，避免原脚本在配置阶段读取现有正式签名属性。未读取签名属性或密钥。后续 Debug 构建使用显式任务和签名读取门禁。
- `gh` 不在 PATH；`git push --dry-run origin HEAD:refs/heads/codex/tgui-port` 成功。PR 优先使用 GitHub 连接器。
- MuMu CLI 确认实例 0、ADB **127.0.0.1:16384**。SDK adb 已连接：Android 15、x86_64、1080×1920、480 dpi。7555 不是 CLI 所指的本任务 MuMu，不再使用；所有后续命令指定 ANDROID_SERIAL=127.0.0.1:16384 和 adb -s。
- 日志保存在 `D:/Project/out/tgui-port-logs/`，不作为成功证据，只有命令结束后才更新结果。

## 当前工作

1. 完成功能/路由清点，见 PARITY.md。
2. 接入 ui5、本地 Maven、debug 宿主；并行实现设置、AI、搜索/发布与核心课堂页面。
3. 尚未切换 launcher、尚未删除旧 UI；旧测试正逐项迁移、尚未完成设备验收。

## 第一个可构建检查点

- 完整 JDK 重跑未改动 Listen 基线：assembleDebug、testDebugUnitTest **通过**（45 秒）。基线 APK 88,976,972 字节。
- tgui 本机 attheme 曾为 CRLF，Git blob 是 LF；已规范工作区到同样 LF 并重跑 publishToMavenLocal/assembleRelease/demo assembleDebug：**通过**（10 秒）。未改库 API，没有发布新后缀。
- 原生 debug 宿主、owner/insets、课程/课堂/原文/录音、设置/向导/诊断、AI 聊天/笔记、搜索/发布/资料和课表预览均已有实现；当前仍处于逐项补齐和设备测试阶段。
- 新界面 `:app:assembleDebug :app:testDebugUnitTest` **通过**，174 项单元测试、0 失败/错误/跳过。APK 101,991,639 字节。
- 同一命令继续跑 assembleDebugAndroidTest 时发现原 UiInteractionTest 的旧调用缺 exportRecordings 参数；正在完整迁移原生测试，没有跳过该测试。
- MuMu 首次安装新 debug APK 成功；ListenHostActivity 冷启动成功（1649 ms）；crash buffer 为空。首屏夜间截图 `screenshots/home-first.png` 已目视检查。
- sherpa AAR 已确认为 ZIP，包含 x86_64 的 onnxruntime 和 sherpa JNI。尚不能据此声称录音/VAD 已验证。
- tgui 已推送 a33231f，草稿 PR：https://github.com/cmhr0086/telegram-11-ui/pull/8。
- 完整覆盖审计发现原 UI 还有建议课程快捷录音、录音保存长度/队列提示、原文分组选取等细节；正在补齐，不能把当前检查点算作完整移植。

## 继续位置

先检查正在运行的 Gradle 会话与日志，不重复启动同一构建。整合新页面后依次执行 assembleDebug/testDebugUnitTest，提交、推送、开 draft PR。截图和 connected 测试仍需实际运行。

## 第二轮原生覆盖与设备回归（进行中）

- Listen 首个检查点 ca2fac9 已推送，草稿 PR：https://github.com/cmhr0086/Listen_this_lesson/pull/5。
- `:app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest` 全部通过（13 秒，日志 listen-native-stage2b.log）；设备测试 APK 已成功安装到 MuMu。
- 补齐建议课程、课程颜色、录音安全保存时长/全局队列、原文分组选择/计数/AI操作/TXT导出、删除倒计时、AI时间线拖动多选。
- 第一批设备回归：笔记成功/失败/重试、相机 URI/取消清理、AI时间线拖动多选、附件菜单不唤起键盘通过。设置 12 项因测试替身用了 HTTP AI 地址被原有 HTTPS 校验拒绝；已修测试地址，未放宽生产校验，待重跑。
- 流式聊天测试在发送后的 Espresso 全局 idle 等待超时，测试清理也未返回；已主动终止 debug 测试进程，不能算通过。改为有界的实际 UI 手势和针对渲染状态的等待后重跑。其余发布/搜索测试另批执行。
- FollowLatestTest 已迁移为真实 Room + 原生列表的三项完整回归；正在验证。原 UI 测试逐项映射见 UI_TEST_PARITY.md。
- 当前 launcher 仍是旧界面；全量测试、截图和切换未完成。

- 第二批核心设备回归 47 项中 36 通过、11 失败。原文跟随 3 项、录音/暂停继续/队列/ASR 诊断已有真实通过记录。失败集中于设置 enabled diff、向导滚动、对话框根/点击时序及 Markwon 表格 ReplacementSpan 断言；均已定位并修正，待重跑，尚不称全通过。
- 第二批 AI/发布 11 项中 7 通过、4 失败。流式完整输出、仅气泡刷新和阅读锚点断言通过，复制后的第二次菜单定位仍失败；发布编辑和删除确认定位待修。两个 PDF 本地契约测试、搜索和资料课堂范围测试通过。
- 纯展示 helper 提取后 assembleDebug/testDebugUnitTest/assembleDebugAndroidTest 通过（19 秒）；核心检查点再跑 assembleDebug/testDebugUnitTest 通过（5 秒）。

## 原生切换与最终审计

- 纯展示 helper 提取后检查点 `5d2d27d` 已推送。随后切换 MainActivity、删除 19 个 Compose 页面 / 主题文件和全部 Compose 插件依赖。
- 最终切换首次 `assembleDebug + testDebugUnitTest + connectedDebugAndroidTest` 通过：174 单元测试、107 设备测试，0 失败 / 跳过，4 分 59 秒（connected-native-final.log）。包含新 Activity 首帧日夜色、完整返回栈、真实 20 秒以上录音、前后台、旋转与左边缘返回。
- 截图发现冷启动首页混色以及 AI / 原文返回箭头、hint 错色，已用建页前读取持久化主题修复。修复后的实际 Home / AI / 原文截图已逐张复核。
- 最终只读审计补齐原文 AI 前置失败提示、原文增量刷新；加强原旧测试的课程切换、精确保存时长、课堂身份标题；新增离线诊断 DemoData 和不存在片段空状态。当前 110 项设备测试正在运行（connected-native-audited.log），不能提前称通过。
- 全量截图将用最终 APK 重拍；系统 SAF 导出和真正杀进程恢复仍待人工操作。PDF 仅合同预览 / 创建课程，缺时段持久化，必须在最终报告保留未完成项。


## 验收实现已推送

- `319de048751f209e8c8da43cb7a84a4d05f73e2f` 已推送 codex/tgui-port，原生 launcher / 删除 Compose / 审计修复 / 完整 DemoData。
- **最终源码** `:app:assembleDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest` 全通过，5 分 15 秒，日志 connected-native-delivery.log。XML：174 单元测试与 110 设备测试，失败 / 错误 / 跳过均 0。
- APK 85,818,946 字节，较基线减少 3,158,026 字节（3.55%）。schema、data/audio/recording、所有 ViewModel、服务端和许可证 diff 为空；通知减法为之前记录的 5 行。
- tgui 最终文档提交 `0d083547b6f90c15614330d749de3355aea531e9` 已推送，assembleRelease / demo assembleDebug 再次通过（12 秒），PR #8 仍 draft / dev/ui-lib。
- 当前正在同一 APK 上重拍日夜截图；下一步系统 SAF 导出 WAV 检查及实际进程终止恢复，之后完成 REPORT / PARITY / 截图索引和文档提交。


## 最后人工验收与导航修复

- 54 个页面 / 关键查看状态已完成日夜截图共 108 张，链接与 PNG 尺寸全部核验，图像逐张 / 联系表复核；截图只使用离线 DemoData。
- 发现并修复专用搜索页吞系统 / 工具栏返回，以及课程 / 课堂 view 重建丢动态标题。代码 `a93e2c8bca7d6887493b03f121bcaf8d99ced927` 已推送，提交前 assembleDebug / testDebugUnitTest / assembleDebugAndroidTest 全通过（6 秒），补充原设备测试断言。
- 实际冷启动完整导航、后台 am kill 后原路由栈和标题恢复通过。系统麦克风拒绝不开始采集，再授权后实际录音；录制中 force-stop 后重启显示已恢复，DB INTERRUPTED、endedAt 与 WAV 57.024 秒 / 912384 帧一致。
- 单课堂 / 全课程实际 SAF 导出各两份 WAV，独立读回为 mono PCM16 16kHz、每组 3 秒 / 4 秒。外部 adb pull 的 Windows 中文目录问题通过 exec-out 原始参数读回核验；未改导出业务。
- 原 data/、audio/、recording/、所有 ViewModel、schema、服务端和许可证仍无差异；最终 APK 86,092,621 字节，较基线减少约 3.24%。
- 最后完整设备回归正在执行（connected-native-navigation-final.log）；最终结果及交付状态继续写在下节。


## 最终交付

- 最终验收源码 `fdbce41d22f8b5b2a51bb96fef7f04a532971f4c` 已推送；最后整轮 assembleDebug / testDebugUnitTest / connectedDebugAndroidTest **全部通过**（5m 14s）。XML 独立汇总：174 单元测试、110 设备测试，失败 / 错误 / 跳过全 0，日志 connected-native-accepted.log。
- 前一轮唯一失败为向导保存状态后界面返回动画尚未完成；补有界等待实际首页 / 动画结束并保留全部原断言，未改业务或删减覆盖。
- REPORT.md、PARITY.md、DECISIONS.md 记录实际人工恢复、导出校验、108 张截图和未验证边界。PDF 完整时段导入仍缺失，不能宣称全部产品要求完成。
- Listen PR #5、tgui PR #8 保持草稿，分别指向 main / dev/ui-lib；无合并或发布。最终文档 / 截图提交后再核对两分支远端 HEAD。
