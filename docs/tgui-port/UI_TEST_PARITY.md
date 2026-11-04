# UiInteractionTest 原生测试覆盖映射

原文件实际包含 **33 个 `@Test` 方法**。新文件保留/替代为 32 个测试；只删除独属于“专业词提示”的编辑器测试。没有 `@Ignore`、假设跳过或用失败吞掉异常的处理。下表描述覆盖迁移；执行结果另见 REPORT，不以源码存在代替通过。

| 原测试 | 新测试 / 保留行为 |
| --- | --- |
| floatingComposerOverlaysFullHeightContent | nativeComposerAndContentStayInsideTheViewport：检查真正 ChatActivityEnterView 的底部、横向边界及键盘打开后输入栏可见。旧 Compose 全高覆盖层布局被用户指定的原生聊天布局替代；仍检查输入栏不越界及可操作 |
| extendedFabShowsActionNameAndHandlesClick | newCourseActionShowsItsNameAndCreatesTheCourse：真实新建操作、输入保存、Room 课程记录和列表入口 |
| idleCapturePanelOffersBothModesAndStartsTheChosenOne | 同名：实际录音方式选择、麦克风权限、RECORD_ONLY 启动状态 |
| recordOnlyPanelShowsSavedLengthAndStops | 同名：真实保存时长增长、模式显示、停止后保留实际录音记录 |
| capturingPanelOffersPauseAndEnd | 同名：真实暂停与结束、课堂结束时间写入 |
| pausedClassResumesFromTheControlBar | 同名：真实暂停/继续、相同课堂编号；共享显示函数验证 754 秒精确格式 |
| controlBarOffersContinueForAFinishedClass | 同名：已结束课堂重新录音、相同课堂及 RECORD_ONLY 模式 |
| controlBarWhileCapturingShowsPauseAndEnd | 同名：实际暂停/结束控件；共享 captureStatus 校验实时识别和队列数格式 |
| realtimePanelShowsSpeechAndQueue | 同名：页面实际使用的共享 captureStatus 对确定的语音/队列状态输出模式、语音状态和数量。模拟输入仅用于确定性格式断言，不声称模拟器真实麦克风产生 VAD-positive 结果 |
| capturePanelIsBlockedWhileAnotherRecordCaptures | 同名：真实另一课堂正在录音时，不可开始新录音，显示当前课堂名称，活动课堂编号不变 |
| homeStartsAClassWithTheSuggestedCourseWithoutNavigating | 同名：Room 中上周同一时段记录产生建议课程；首页直接开启并保存到该课程 |
| coursesTabShowsClassCountAndOpensTheCourse | 同名：真实七节/空课程数量及课程导航 |
| coursePickerConfirmsTheChosenCourse | 同名：选择另一课程后实际课堂归属更新 |
| recordingsCollapseIntoOneSummaryWithRecognizeAll | recordingsStayBehindOneSummaryAndOfferRecognizeAll：课堂保留单一录音摘要入口，进入录音详情后显示四段/总时长/待识别数，实际触发批量识别。展开动作改为独立页面导航 |
| interruptedRecordingIsReadyToRecognizeWithNeutralNote | 同名：合成 WAV + INTERRUPTED 记录，安全时长说明、开始识别实际进入处理管线 |
| pausedRecordingOffersResumeAndDelete | 同名：真实 PAUSED 记录的 50% 进度、继续识别和删除菜单 |
| currentRecordDiagnosticsShowLifecycleAndHealthRefreshIsManual | 同名：Room 队列 + 回环模拟服务真实阻塞一次提交和一次轮询，显示 1/1/2/1 和 3/3 并发；进入不请求 health，手动刷新请求恰好一次 |
| processingWithoutValidServerAnchorShowsEstimatingInsteadOfDeviceUptime | 同名：单调时钟锚点为空的 PROCESSING 记录显示估算中，不用开机时长兜底 |
| diagnosticsPreviewShowsFifteenThenOpensCurrentRecordHistory | 同名：16 条 Room 数据，预览只显示15条，跳转正确课堂历史后可见第16条 |
| diagnosticsHistoryContainsTheSixteenthEntry | 同名：历史滚动到最早条目、打开详情验证实际片段编号 |
| smoothElapsedTextRefreshesAtTenthsOfASecond | 同名：活动片段真实单调时钟计时，不等待整秒即可观察显示更新 |
| horizontalChoiceSelectorScrollsAndSelectsLastOption | lastVadChoiceRemainsReachableAndSelectable：原生 VAD 列表最后一个选项可到达、点击后实际持久状态 NOISY。旧通用横滑组件不再使用 |
| courseAsrPromptDialogUsesScrollableHorizontalChoices | **删除**：用户明确移除专业词提示及开关，底层值保留；SettingsNativeTest 单独验证入口删除不会清空值 |
| longPressInDragSelectionListKeepsTheLineSelectedAfterRelease | longPressSelectionKeepsTheLineSelectedAfterRelease：原生长按→多选、松开后仍选中、点击第二气泡追加选择 |
| selectionTopBarCountsAndSelectsAll | 同名：已选计数2→3及三条气泡均选中 |
| selectionActionBarCopiesAndMarksSelectedText | selectionActionBarCopiesWithoutChangingMarkedData：真实剪贴板文本、源段落不变；仅移除用户要求删掉的“标为重点”操作断言，同时断言该操作不存在且旧字段未改变 |
| longPressSelectsTranscriptWithoutChangingText | 同名：实际气泡被选中，Room 原始文本不变 |
| paragraphHeaderSelectsTheWholeParagraphWhileSelecting | paragraphHeaderSelectsAndDeselectsTheWholeParagraph：整段选择三条，再取消整段回到空选择 |
| emptySelectionDisablesTheActions | emptySelectionCannotCopyOrAskAi：空选择时复制和问 AI 不可触发（禁用或退出选择模式后隐藏） |
| recordMenuContainsAllRequestedActions | transcriptMenuContainsAllRetainedActions：导出 TXT/选择片段存在，专业词提示已删，笔记仍在独立课堂入口 |
| markdownRendererAcceptsHeadingTableTaskListAndStrikethrough | 同名：真正 Markwon TextView 为 Spanned，标题/任务列表/删除线内容完整，Markdown 标记已渲染；表格由 TableRowSpan 绘制，使用其公开单元格 Layout API 验证两行四个单元格的实际文字与布局高度，不能用 TextView.text 查找被 ReplacementSpan 替换的表格内容 |
| settingsOverviewIncludesManualCloudSyncEntry | 同名：三个服务及自动笔记入口、开发者关闭时无诊断，开启后诊断出现 |
| persistentDeleteRequiresTwoSecondConfirmation | 同名：真实待删段落，倒计时按钮禁用且数据未删，等待后确认并验证数据库实际少一条 |

测试只使用 `com.cmhr.listen.debug`、独立命名的测试课程、合成静音 WAV 和设备本进程的 MockWebServer。测试结束后停止录音/补识别，删除本测试创建的数据及队列音频。没有真实课堂数据、真实外部 AI/ASR/同步/发布调用。
