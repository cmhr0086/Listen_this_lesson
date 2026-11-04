# 路由与功能对照

基线：Listen main@a2b5cc4。最终验收源码 `fdbce41d22f8b5b2a51bb96fef7f04a532971f4c` 已推送；assembleDebug、174 单元测试和 110 设备测试全部通过（5m 14s），0 失败 / 错误 / 跳过，日志 connected-native-accepted.log。新增搜索返回、标题重建及配置向导完成返回断言均包含在本轮。真实人工导航、杀进程恢复、权限拒绝 / 重试和录音中断文件校验另有独立证据。下表只声明实际覆盖范围，不将本地模拟服务成功写成线上可用。

## 证据范围

| 证据 | 日志 / 报告 | 已观察结果 |
| --- | --- | --- |
| 历史完整回归（返回 / 标题修复前） | `D:\Project\out\tgui-port-logs\connected-native-delivery.log` | `Finished 110 tests on V2364A - 15`，`BUILD SUCCESSFUL in 5m 15s` |
| 结果 XML | `app/build/outputs/androidTest-results/connected/debug/TEST-V2364A - 15-_app-.xml` | tests=110、failures=0、errors=0、skipped=0；包含 Host 3、Appearance 5、AI 6、Publish 6、UI 33 项 |
| 同轮 JVM 检查 | 最终 accepted 日志中的 `:app:testDebugUnitTest` | 174 项，0 失败、0 错误、0 跳过；`app/build/test-results/testDebugUnitTest/TEST-*.xml` 汇总；纯 helper、协议、导出命名单测不替代设备端到端操作 |
| 搜索返回与重建标题修复构建 | `D:\Project\out\tgui-port-logs\search-return-build.log`、`navigation-final-build.log` | assemble、`:app:testDebugUnitTest` 与 androidTest APK 构建通过；最后一份日志 `BUILD SUCCESSFUL in 6s`。新增设备断言已在 connected-native-accepted.log 整轮通过。 |
| 修复后整轮设备回归 | `D:\Project\out\tgui-port-logs\connected-native-accepted.log` | 110 项全通过，5m 14s，0 失败 / 错误 / 跳过；包含最终返回 / 标题断言。 |
| 真实 SAF 导出与独立读回 | `D:\Project\out\tgui-port-logs\manual_export_verified.log`、`verify_exports.py`、`verified-exports/verification.json` | 实际 UI 分别选择目录并完成单课堂 / 全课程导出；独立读回 4 个 WAV，每组 3 秒和 4 秒，全部为单声道 PCM16、16 kHz，`SAF_EXPORT_PASS`。 |
| 真实导航与后台进程终止恢复 | `D:\Project\out\tgui-port-logs\manual_navigation.log` | 冷启动 → 课程 → 课堂 → 原文 → 搜索 → 返回通过；按系统 Home 后执行 `am kill` 并确认 PID 消失，重新启动恢复课堂和原课程列表返回栈、真实标题，`PROCESS_RESTORE_PASS`。 |
| 权限拒绝与重新授予 | `D:\Project\out\tgui-port-logs\manual_permission_final.log`、`manual-current.png` | 麦克风拒绝、通知允许后显示需要麦克风权限错误且无录音；再次经系统 UI 授予麦克风后真实仅录音，截图显示 00:48 / 已安全保存 00:45。 |
| 真实录音中断恢复 | `D:\Project\out\tgui-port-logs\manual_recovery_final.log` | 对实际录音进程 force-stop 后重启 `recordings/3`，页面显示已恢复；DB 为 INTERRUPTED 且 endedAt 非空。WAV 912384 帧、16 kHz、1824812 字节，与 durationMs=57024 一致，`RECOVERY_PASS`；原文 0 段，仅录音未发 ASR。 |

下表“通过”指具体测试方法在上述已完成整轮回归中通过，不能扩展为该功能所有边界已验证；后来补充的返回 / 标题断言也已在最终 accepted 整轮通过。人工导出证据另列，不计入自动化测试数。“未覆盖”继续保留没有相应断言或人工证据的部分；模拟服务结果不代表生产服务可用性。早期 A/B 日志中的失败已经修复并被该整轮回归覆盖，不再作为当前状态；历史记录见文末。

测试简称：`UI` = `com.cmhr.listen.ui.UiInteractionTest`；`SP` = `ui.SettingsPagesTest`；`SN` = `ui.SettingsNativeTest`；`Follow` = `ui.FollowLatestTest`；`AI` = `tgui.AiNativeTest`；`Pub` = `tgui.PublishNativeTest`。其余写完整类名。方法名与当前测试源码对应。

## 核心功能

| 原路由 / 功能 | 决定 | 当前原生实现 | 对应测试 | 已验证范围 / 仍需验证 |
| --- | --- | --- | --- | --- |
| `record-home`；即时识别 / 仅录音；选择或新建课程 | 移植 | `HomeFragment` 首页建议课程、直接开始课堂、切换课程；宿主创建课堂并启动原 VM | `UI.homeStartsAClassWithTheSuggestedCourseWithoutNavigating`、`idleCapturePanelOffersBothModesAndStartsTheChosenOne`、`newCourseActionShowsItsNameAndCreatesTheCourse` | 所列原生用例全部通过，含建议课程直接仅录音、新建课程、结束后返回首页切换课程并在新课程再次启动；未以真实 ASR 服务验证即时识别。 |
| 首页最近课程 / 课堂 | 减掉 | 空闲首页不列最近课堂；录制 / 暂停时显示当前课堂 | `UI.homeStartsAClassWithTheSuggestedCourseWithoutNavigating`、`pausedClassResumesFromTheControlBar` | 开始 / 暂停对应路径通过；空闲首页无最近列表经过源码与截图检查，未另设覆盖所有录制状态的视觉断言。 |
| `courses`；新建 / 改名 / 删除 / 课程颜色 | 移植 | `CoursesFragment` 列表、长按操作、多选删除；8 色与原存储索引保留 | `UI.coursesTabShowsClassCountAndOpensTheCourse`、`newCourseActionShowsItsNameAndCreatesTheCourse`；单测 `ClassPresentationTest.courseColorsAreStableAndOverridable` | 列表数量、打开课程与新建保存通过；改名、课程多选删除、8 色逐项点击未覆盖；课程颜色纯规则单测不替代设备颜色选择验收。 |
| `course/{courseId}`；课堂列表 / 新建 / 改名 / 换课程 / 删除 / 多选 | 移植 | `CourseFragment`；课堂长按操作和选择 ActionMode；沿用原 CourseViewModel；数据标题缓存后用于 view 重建 | `UI.coursesTabShowsClassCountAndOpensTheCourse`、`coursePickerConfirmsTheChosenCourse`、`persistentDeleteRequiresTwoSecondConfirmation`；`HostNativeTest.notificationEntryDoesNotOverrideSavedRouteOnRecreate` 新增标题断言 | 进入课程、课堂换课程及两秒删除门禁通过；后来修复标题在返回栈重建时回退为“课堂”，构建 / 单测通过，新增标题设备断言已在最终整轮通过；人工杀进程恢复已确认真实标题。课堂列表所有批处理分支未覆盖。 |
| `record/{recordId}`；原文 / 笔记 / 资料 | 移植 | `ClassroomFragment` 显眼入口进入原文、结果、资料和录音页；数据标题缓存后用于 view 重建 | `UI.transcriptMenuContainsAllRetainedActions`、`recordingsStayBehindOneSummaryAndOfferRecognizeAll`；`Pub.classMaterialsFilterOtherLessonsAndUploadToTheCorrectLesson`；`HostNativeTest.notificationEntryDoesNotOverrideSavedRouteOnRecreate` 新增标题断言 | 原文菜单、录音摘要和课堂资料本地模拟服务路径通过；课堂标题同样补齐重建缓存，构建 / 单测通过，新增设备断言已在最终整轮通过；人工导航 / 返回已通过。笔记生成通过范围见 AI 行。 |
| 录音开始 / 暂停 / 继续 / 结束 / 安全保存时长 | 移植 | 首页与课堂复用状态展示；宿主调用既有 SttViewModel / 服务 | `UI.recordOnlyPanelShowsSavedLengthAndStops`、`capturingPanelOffersPauseAndEnd`、`pausedClassResumesFromTheControlBar`、`controlBarOffersContinueForAFinishedClass`、`controlBarWhileCapturingShowsPauseAndEnd`；`HostNativeTest.recordOnlySurvivesHomeAndPauseResumeThenSavesAudio` | 所列 UI 用例及 Host 后台录音用例通过，含真实音频文件、安全保存、暂停 / 继续 / 结束、多 WAV、合计至少 20 秒；不等于真实语音识别质量或整夜后台稳定性验收。 |
| 即时识别语音 / 排队状态；另一课堂录制时阻止开始 | 移植 | `captureStatus` 显示模式、计时、语音与队列；当前课堂名称和禁用开始入口 | `UI.realtimePanelShowsSpeechAndQueue`、`capturePanelIsBlockedWhileAnotherRecordCaptures` | 两项通过；语音阳性 / 队列是确定性状态夹具，另一课堂互斥为实际仅录音操作，不把夹具当实测 VAD。 |
| 前台服务 / 通知操作 / 异常恢复 | 保留原服务并接原生路由 | `ListenHostActivity` 权限、通知 Intent、暂停 / 继续 / 结束；原录音恢复逻辑未换实现 | `RecordingRecoveryTest.captureKilledWithoutStopBecomesRecognizableAndEndsTheSession`、`interruptedRecognitionIsPausedNotFailed`；`UI.interruptedRecordingIsReadyToRecognizeWithNeutralNote`；`HostNativeTest.recordOnlySurvivesHomeAndPauseResumeThenSavesAudio`、`notificationEntryDoesNotOverrideSavedRouteOnRecreate`；人工导航 / 权限 / 恢复日志 | 既有 Host 三项及恢复六项通过，含实际采集、后台、通知计时、暂停续录、多 WAV 和恢复夹具。新增人工实测：录音中 force-stop 后页面显示已恢复，DB INTERRUPTED / endedAt 非空，WAV 帧数与 57.024 秒时长一致；麦克风拒绝无采集，重新授予后实际录音。通知“结束”仅验证动作存在，未断言点击完成；不把 force-stop 单例扩展为所有系统回收原因或整夜稳定性。 |
| 补识别 / 全部识别 / 队列 / 失败 / 暂停 / 录音删除 | 移植 | `RecordingsFragment` 段数与总时长摘要、暂停百分比、继续 / 重试、队列、错误、删除 | `UI.recordingsStayBehindOneSummaryAndOfferRecognizeAll`、`interruptedRecordingIsReadyToRecognizeWithNeutralNote`、`pausedRecordingOffersResumeAndDelete` | 所列用例通过，含本地夹具任务状态变化；暂停项验证百分比与继续 / 删除菜单，实际录音删除由 RecordingRecoveryTest.deletingOneRecordingRemovesItsAudioButNotActiveOnes 验证底层保护，不等于该菜单删除全链路已覆盖。 |
| 原文跟随最新；超长末段和增长 | 移植 | `TranscriptFragment` RecyclerListView、末段增长跟随、上拖暂停和显式恢复 | `Follow.scrollToBottomReachesTheEndOfAParagraphTallerThanTheScreen`、`followingStaysAtTheBottomWhileTheLastParagraphGrows`、`draggingUpStopsFollowingUntilTheUserReturns`；`AI.transcriptAppendDoesNotRebindUnchangedBubbles` | 三项通过；真实原生页面 + Room 修订增长，覆盖超屏末段到底、追加跟随、上拖阅读位置保持与显式恢复。新增 AI 原文追加测试也通过，确认未变气泡不重绑、无全量刷新。 |
| 原文搜索 / 长按选择 / 全选 / 整段选择 / 复制 / 删除 / AI / TXT | 移植 | `TranscriptFragment` 原生搜索、气泡菜单、ActionMode、范围选择与原数据操作 | `UI.longPressSelectsTranscriptWithoutChangingText`、`longPressSelectionKeepsTheLineSelectedAfterRelease`、`selectionTopBarCountsAndSelectsAll`、`transcriptMenuContainsAllRetainedActions`；`selectionActionBarCopiesWithoutChangingMarkedData`、`paragraphHeaderSelectsAndDeselectsTheWholeParagraph`、`emptySelectionCannotCopyOrAskAi`；`AI.transcriptAiMissingConfigurationShowsErrorWithoutReplayingItOnReopen` | 所列选择、复制、整段、空选择、菜单用例全部通过；新增缺配置 AI 前置错误用例通过（不发请求、重开不重放旧错误、重复操作仍提示）。原文页文本搜索、TXT 导出文件与选段 AI 成功回复的完整端到端流程仍未全覆盖。 |
| 原文记重点 / 重点筛选 | 减掉界面，保留数据 | 不提供标记操作或重点过滤；`marked` schema、查询与同步未删除 | `Pub.searchFiltersNotesHighlightsMatchesAndOpensClassroom`；`UI.selectionActionBarCopiesWithoutChangingMarkedData`；原 `MarksAndSearchTest` | 无重点筛选、复制不改变 marked 断言及原 MarksAndSearchTest 五项全部通过；仅删界面入口，保留原数据层标记、查询、计数和同步行为。 |
| `search`；原文与笔记检索 | 移植 | `SearchFragment` 本地 Room 查询、全部 / 文字 / 笔记、按课堂分组、主题高亮、课堂跳转；专用搜索页收起请求转为返回父页 | `Pub.searchFiltersNotesHighlightsMatchesAndOpensClassroom`；原 `MarksAndSearchTest.searchFindsCorrectedTextNotesAndLiteralWildcards` | 原生输入、笔记范围、真实高亮 span、课堂跳转 / 返回搜索、空结果，以及 Room 查询均已通过。人工发现系统 / 工具栏 Back 被展开搜索框吞掉后已修复；原用例补上实际 Home → 搜索 → 系统 Back → Home，再次搜索 → 工具栏 Back → Home，构建 / 单测通过，新增设备断言已在最终整轮通过；人工导航 / 返回已通过。 |
| 单课堂 / 全课程 WAV 导出、进度 / 取消 | 移植 | `CoreFragments.exportRecordings` 沿用 RecordingExporter，选目录、显示进度、关闭对话框取消 Job | `RecordingExporterTest.namesSortInTeachingOrder`、`classesRecordedInPartsGetPartNumbers`、`unsafeCharactersAreReplaced`；人工 `manual_export_verified.log` | 导出命名单测通过；真实 UI 经 SAF 分别完成单课堂 / 整课程导出，独立读回 4 个 WAV，每组 3 秒和 4 秒，全部单声道 PCM16、16 kHz，校验通过。取消分支及大批量导出仍未验证；测试只用 debug 演示音频。 |

## 发布、资料与课表

| 原路由 / 功能 | 决定 | 当前原生实现 | 对应测试 | 已验证范围 / 仍需验证 |
| --- | --- | --- | --- | --- |
| `course/{courseId}/publish` | 合并为顶层入口 | 首页“公开发布” → `PublishHubFragment` → `CoursePublishFragment`；课程 / 课堂页不再散放发布按钮 | `Pub.publicationHubShowsCachedStatusAndCourseEditsRemainLocal`；纯状态 `PublishStatusTest`、`PublishSearchPortTest.publicClassStateDistinguishesRequestedWithdrawalFromHiddenLocalState` | Pub 整项通过：顶层入口、缓存公开状态、未绑定提示、学校编辑写回原 DataStore；真实上传、审核与撤回未调用且未验收。 |
| 课程笔记 / 资料上传、列表、下载查看、删除 | 接已有服务合同补原生入口 | 基线 Android 无专用资料 UI；`MaterialsFragment` / `MaterialService` 复用现有 admin materials API，原始文件上传、FileProvider 查看与删除门禁 | `Pub.courseMaterialsUploadViewAndDeleteUseOnlyMockServer`；`PublishSearchPortTest.materialsUseRawFileBodyBoundAuthAndLessonScope`、`materialListDeleteAndPermissionFailureUseExistingRoutes`、`downloadsUseSafeGeneratedNameAndRespectSizeLimit` | Pub 整项通过：本地 mock 刷新、原始文件上传、下载、只读 FileProvider 外部查看 Intent、两秒门禁后的删除请求及资料消失；协议 / 安全文件名单测通过。未访问真实资料服务。 |
| 单课堂笔记 / 资料归属 | 接已有服务合同补原生入口 | 查询现有发布 lessonId，过滤其它课堂和课程级资料；上传携带该 lessonId | `Pub.classMaterialsFilterOtherLessonsAndUploadToTheCorrectLesson` | Pub 整项通过：本地 MockWebServer 验证 sessionId → lessonId、过滤其它归属、上传 category / lessonId；是真实原生 UI + 模拟服务验证，不是生产服务可用性证明。 |
| 课表 PDF 导入 | 基线缺项；受 UI-only 范围限制的部分实现 | `TimetableFragment` 选择 PDF，调用只读核验到的 `origin/feature/timetable-pdf` 合同，预览教师 / 周次 / 节次 / 教室，选择课程后调用原 CourseViewModel 创建课程 | `Pub.timetableSelectsPdfShowsSlotsAndCreatesSelectedCourse`、`unavailableTimetableEndpointShowsAnHonestFailure`；`PublishSearchPortTest.timetableUsesExistingRawPdfContractAndKeepsPreviewDetails` | 预览 / 选课创建与 404 明确失败两项均通过，仅本地 mock。**基线 Android 根本没有课表路由、导入流程或时段 schema；main 服务端没有该 endpoint。当前没有周次 / 节次持久化，不得新增 schema 来伪装完成 UI 移植，也不得称完整课表导入完成。** |

## AI

| 原路由 / 功能 | 决定 | 当前原生实现 | 对应测试 | 已验证范围 / 仍需验证 |
| --- | --- | --- | --- | --- |
| `ai`；全局会话 / 结果，多选、删除、TXT 导出 | 移植 | `AiListFragment` 混合时间线、过滤、长按拖动选择、ActionMode 与原 ViewModel 导出 / 删除 | `AI.timelineLongPressDragSelectsAndUnselectsRange` | 时间线拖动扩展 / 缩小选择通过；批量删除和导出文件结果没有完整端到端覆盖。 |
| `ai/new`；`ai/new/{recordId}` | 移植 | `AiChatFragment` 原生输入框，保留全局 / 课堂上下文入口 | `AI.streamingKeepsHistoryPositionAndCopiesFullMarkdownAfterReopen`；`AiComposerImeTest.attachmentMenuDoesNotFocusInputAndCanBeReopened` | 本地 mock 流式新会话完整链路、附件菜单 / 输入焦点用例通过；课堂新会话入口已有实现，不能把全局会话测试扩展为每种上下文组合均已验收。 |
| `ai-conversation/{conversationId}`；流式 / 历史 / 重试 / 复制 | 移植 | 原生 RecyclerListView / TextBubbleCell；稳定消息标识、末项增量更新、历史位置与完整原文菜单 | `AI.streamingKeepsHistoryPositionAndCopiesFullMarkdownAfterReopen` | 流式局部更新、阅读历史不跳走、关闭重开及完整 Markdown 复制用例通过；没有真实 AI 服务调用。 |
| `record/{recordId}/ai-conversations` | 移植 | 宿主映射课堂限定 `AiListFragment(recordId)` | `AI.timelineLongPressDragSelectsAndUnselectsRange` 仅覆盖时间线选择机制 | 时间线选择机制通过；课堂限定列表与会话路由组合仍无独立端到端用例。 |
| `record/{recordId}/ai-results` | 移植 | `AiResultsFragment`，课堂结果与会话复用时间线 | `AI.noteGenerationSuccessFailureAndRetryUseOriginalSource` | 本地 mock 笔记生成成功、进入结果、失败提示与重新生成恢复均通过；结果筛选所有组合未逐项覆盖。 |
| `record/{recordId}/ai-result/{resultId}`；`ai/result/{recordId}/{resultId}` | 合并 | `AiResultFragment` / `AiChatFragment` 原生气泡；完整结果可进入 `AiMarkdownFragment` | `UI.markdownRendererAcceptsHeadingTableTaskListAndStrikethrough`；`AI.streamingKeepsHistoryPositionAndCopiesFullMarkdownAfterReopen` | Markdown 标题 / 表格 / 任务列表 / 删除线，以及流式完整正文复制测试均通过；原 Markdown 规范化 helper 和原文均保留。 |
| `record/{recordId}/ai-result/{resultId}/ask` | 移植 | 结果页追问模式与原来源上下文 | `AI.noteGenerationSuccessFailureAndRetryUseOriginalSource` 关联来源检查 | 实现保留；笔记生成 / 重试来源快照不变已通过，但独立笔记追问请求 / 回复尚无专用端到端用例。 |
| 笔记方向 / 生成中 / 失败 / 重试 / 自动整理 / 重新生成 | 移植 | `AiNoteGenerationFragment` 选择方向与附件；结果错误 / 重试；宿主监听课堂结束及批量识别完成，调用原自动整理 | `AI.noteGenerationSuccessFailureAndRetryUseOriginalSource` | 本地 mock 方向选择、成功、500 失败、重新生成重试成功及来源快照不变全部通过；自动整理在课堂结束 / 批量识别完成时的实际生成结果未独立验收。 |
| 相机 / 图片 / 文本文件附件 | 移植 | 原 FileProvider、AiAttachmentStore；图片 / 拍照 / 文本选择，限制与取消清理 | `AI.cameraAttachmentForwardsProviderUriAndCancelCleansTemporaryFile`；`AiComposerImeTest.attachmentMenuDoesNotFocusInputAndCanBeReopened`；原 `AiCameraCaptureTest.cameraTargetUsesFileProviderAndCapturedPhotoUsesExistingImagePipeline` | 原生相机 Intent / FileProvider URI / 取消清理、附件菜单可重开且不抢焦点、原相机图片流水线测试全部通过；真实相机拍照和全部文件格式仍未验收。 |
| 来源文字 / 提示词 / AI 请求诊断 / 纠错应用 | 移植 | 聊天顶部菜单、`AiMarkdownFragment`、附件页、`AiCorrectionFragment` 使用原 VM 数据 | `AI.noteGenerationSuccessFailureAndRetryUseOriginalSource` 仅关联来源链路 | 生成和重试来源快照检查通过；请求诊断详情、全部附件查看和纠错应用操作未全覆盖。 |

## 设置、向导与宿主

| 原路由 / 功能 | 决定 | 当前原生实现 | 对应测试 | 已验证范围 / 仍需验证 |
| --- | --- | --- | --- | --- |
| `settings` | 移植 | `SettingsFragment` 原生分组列表 | `SN.browsingServicePagesDoesNotCallRemoteServicesOrRevealSecrets`、`allTenPromptKindsRemainReachable` | 服务页浏览无请求 / 不暴露合成凭据、十种提示词入口可达通过；不将页面浏览推广为每个服务操作成功。 |
| `settings/stt-service` | 移植至 `settings/stt` | 识别服务 / 地址 / Key / 检查；保留原 SettingsViewModel | `SP.editSheetOnlyConfirmsAChangedValueAndTrimsIt`、`failedSpeechCheckStaysOnThePage` | 输入校验、去首尾空白保存、本地 mock 403 失败状态及留在本页通过；未访问真实识别服务。 |
| `settings/vad` | 移植 | VAD 预设、十项参数和合法性校验 | `UI.lastVadChoiceRemainsReachableAndSelectable`；`SN.vadPresetsAndNumericValidationKeepAllParametersAvailable` | 最后预设可达、预设切换、语音阈值拒绝无效值 / 保存有效值、全部十项参数入口和默认恢复通过；不宣称实测所有参数对识别效果的影响。 |
| `settings/ai-service`；`settings/ai-service/models` | 移植至 `settings/ai`、`settings/models` | AI 服务、模型查询与选择 | `SN.browsingServicePagesDoesNotCallRemoteServicesOrRevealSecrets`；`SP.reasoningOptionsFollowTheProvider` | 安全浏览和 provider 思考参数联动通过；模型获取 / 选择完整服务交互尚无这轮专用端到端验证。 |
| `settings/device` | 移植 | 绑定二维码、状态和解绑，使用原绑定 API | `SN.browsingServicePagesDoesNotCallRemoteServicesOrRevealSecrets` | 无请求页面浏览通过；未执行真实设备绑定 / 解绑。 |
| `settings/cloud-sync` | 移植至 `settings/sync` | 状态、配置、手动同步入口，保留原同步逻辑 | `UI.settingsOverviewIncludesManualCloudSyncEntry`；`SN.browsingServicePagesDoesNotCallRemoteServicesOrRevealSecrets` | 总览同步入口、直接服务页无请求浏览通过；SyncRepositoryTest 14 项本地仓库规则通过，不等于真实云同步服务可用。 |
| `settings/publish` | 移植 | 发布服务设置、学校等原字段 | `SN.browsingServicePagesDoesNotCallRemoteServicesOrRevealSecrets`；`Pub.publicationHubShowsCachedStatusAndCourseEditsRemainLocal` | 无请求浏览、学校编辑写回通过；真实发布服务未调用。 |
| `settings/appearance` | 合并为系统 / 日间 / 夜间 | `AppearanceModels` 保留旧存储 enum；原生 Theme 应用、ThemeDescription 更新；删除配色 / 字体入口 | `SN.appearanceOnlyExposesThreeModesAndKeepsStoredPalette`；新 `AppearanceNativeTest.dayAndNightUpdateActualBackgroundAndTextOnTheOpenPage`、`followSystemAppliesTheCurrentConfigurationToVisibleColors`、`everyLegacyAppearanceValueStillRoundTripsThroughTheRepository`、`coldStartHomeUsesSavedDayModeDespiteCachedDarkTheme`、`coldStartHomeUsesSavedNightModeDespiteCachedLightTheme`；`PalettesTest` | SN 三模式入口和旧 palette 保留、Appearance 5 项全部通过：已打开页背景 / 行 / 文字日夜变色、跟随系统、全部旧值仓库往返、新 Activity 在相反 tgui 缓存下的日 / 夜首页首帧与稳定色。另有纯 enum 名称 / 默认值 / ARGB 兼容单测；缓存模拟不等于杀进程实测。 |
| `settings/ai-prompts`；`settings/ai-prompts/{kind}` | 移植至 `settings/prompts`、`settings/prompt/{kind}` | 原生编辑、未修改禁用保存、放弃确认、恢复默认 | `SP.promptEditorAsksBeforeDroppingEdits`；`SN.allTenPromptKindsRemainReachable`、`promptSavePersistsAndSingleRestoreReturnsToDefault` | 编辑放弃确认、十类入口、单项保存 / 恢复默认均通过。 |
| `settings/asr-prompt-policy`；thresholds；课程提示词 | 减掉界面，保留值 | 不显示专业词提示配置，不改底层 ASR prompt 设置和云端数据 | `SN.removedProfessionalPromptEntryDoesNotResetStoredValue` | 入口移除而已存 mode 不重置用例通过；其它底层 prompt 配置保持原实现。 |
| `settings/ai-generation` | 移植至 `settings/generation` | 输出上限、温度、思考模式 / effort、默认恢复 | `SN.generationLimitsRejectInvalidValuesAndPersistValidChange`、`SP.reasoningOptionsFollowTheProvider` | 非法输出长度拒绝、有效值与温度保存、默认恢复、provider 思考模式 / effort 联动全部通过。 |
| `settings/asr-diagnostics`；history / 分段事件 / 重试 | 移植至 `settings/asr`、`settings/asr-history/{recordId}`、`settings/asr-segment/{recordId}/{segmentId}` | 原诊断仓库、健康检查手动刷新、15 条预览 / 全部历史、阶段耗时与事件 | `UI.currentRecordDiagnosticsShowLifecycleAndHealthRefreshIsManual`、`diagnosticsPreviewShowsFifteenThenOpensCurrentRecordHistory`、`diagnosticsHistoryContainsTheSixteenthEntry`、`processingWithoutValidServerAnchorShowsEstimatingInsteadOfDeviceUptime`、`smoothElapsedTextRefreshesAtTenthsOfASecond`、`missingDiagnosticShowsEmptyStateAfterLoading` | 手动健康检查、15 条预览 / 第 16 条历史、无有效时间锚点估算、平滑刷新及不存在的诊断片段加载后显示空状态全部通过；分段事件所有分支 / 重试成功链路未全覆盖。 |
| 配置向导、跳过 / 继续、绑定 / 手动配置 | 移植 | `SetupFragment` 原生分步配置，沿用 SetupState | `SN.setupCanSkipServicesAndFinishWithoutNetworkRequests` | 手动向导跳过服务、上一步、完成 SetupState 且无网络请求的完整用例通过；真实微信绑定流程未调用。 |
| 日夜切换 / 旋转 / 返回手势 / 进程恢复 / 权限拒绝 | 移植宿主 | `ListenHostActivity` 生命周期、路由保存、系统配置回调、权限申请；原生 ActionBarLayout | `UI.nativeComposerAndContentStayInsideTheViewport`、`AiComposerImeTest.attachmentMenuDoesNotFocusInputAndCanBeReopened`；`AppearanceNativeTest`；`HostNativeTest.rotationKeepsContentInsideSystemBarsAndLeftEdgeSwipeReturns`、`notificationEntryDoesNotOverrideSavedRouteOnRecreate`；人工 `manual_navigation.log`、`manual_permission_final.log` | 既有边界、附件焦点、Appearance 5 项、Host 旋转 / 左边缘返回 / recreate 栈通过。人工 Home → am kill 确认 PID 消失 → 重启恢复课堂、原课程列表返回栈与真实标题通过；麦克风拒绝后无采集、系统 UI 再授予后真实仅录音通过。新增自动化标题 / 双 Back 断言也已在最终整轮通过；实机软键盘全部组合、通知拒绝及各类系统回收场景仍未全覆盖。 |

## 迁移边界

旧 Compose 测试的保留功能已迁移为原生 Activity / Room / Espresso / UiAutomator 覆盖，并在交付轮 110 项设备测试中通过。完整旧断言映射见 `UI_TEST_PARITY.md`；旧三项 palette 断言的退役 / 迁移理由见 `APPEARANCE-TESTS.md`。Compose 页面已移除，纯 helper 独立保留；标记数据层、录音恢复、相机附件流水线、数据库迁移和同步仓库测试仍保留并通过本轮测试。

资料、课表、AI 和服务设置的网络验证只使用本地 MockWebServer。资料课程 / 课堂上传归属、下载查看、删除已有完整本地通过证据；课表仅通过分支合同模拟下的预览和选课创建。没有真实发布、上传、同步、绑定或 AI / ASR 服务验收。PDF 的时段持久化缺项来自基线不存在该模型，不能因为 UI 渲染完成便称功能齐备；本次不跨范围新增 schema。

## 历史回归记录

- `native-core-first.log`：47 项，36 通过、11 失败。
- `native-ai-publish-second.log`：11 项，7 通过、4 失败。
- `connected-native-final.log`：107 项全部通过。
- 新增 3 项覆盖后的前次 110 项回归有 1 项首页测试未先返回首页造成失败；修正导航后，交付轮 `connected-native-delivery.log` 110 项、0 失败、0 错误、0 跳过。历史失败不再当作当前未修复问题，也不抹去上表仍无断言的功能边界。
- 人工恢复首轮 `manual_recovery.log` 因 UiAutomator XML 未刷新而断言失败；随后刷新页面证据，并以截图、DB、独立 WAV 校验完成 `manual_recovery_final.log`。该旧断言失败不记录为产品恢复失败，也不省略其验证方式修正。

- 最后导航修复轮 connected-native-navigation-final.log：110 项中 109 通过、1 项配置向导结束后旧界面可见性断言赶在动画结束前执行。增加实际首页 / 动画结束的有界等待，保留原断言；connected-native-accepted.log 110 项全部通过。
