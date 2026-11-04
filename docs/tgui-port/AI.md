# AI 界面移植

实现：`app/src/main/java/com/cmhr/listen/tgui/AiFragments.kt`。
所有 AI 调用仍由原 `AiViewModel` 和 repository 处理；未改数据库、AI 请求、凭据、附件文件格式或录音实现。

## 页面与原能力

| 页面 | 新实现 | 保留能力 |
| --- | --- | --- |
| AI 时间线、课堂 AI 记录 | `AiListFragment` / `AiResultsFragment` | `DialogCell + DialogItem`、按日期分组、全部/课堂/自由提问筛选、长按拖动范围选择/取消、边缘自动滚动、ActionBar 多选计数、全选、删除、TXT 导出、混合笔记/会话时间线 |
| 新对话、课堂新对话、历史对话 | `AiChatFragment` | 原会话创建/冻结课堂原文/选定片段 API、上下文字数、课堂与自由问答、流式历史、错误、思考过程、本轮上下文 |
| 结果、笔记追问 | `AiResultFragment` | 结果流式状态、失败、重新生成、来源上下文、提示词、请求诊断、原结果追问会话、附件、笔记折叠上下文 |
| 生成笔记 | `AiNoteGenerationFragment` | 四种原有整理方向，记忆上次方向、图片/拍照/文本附件、生成中/失败/创建后打开结果 |
| 纠错结果 | `AiCorrectionFragment` | 逐段原文、纠正文、修改理由、确认后应用全部纠正；保留原文仍由原 ViewModel 保证 |
| 完整内容 | `AiMarkdownFragment` | Markwon 的列表、表格、任务清单、删除线、HTML、代码和软换行；全文选择、复制、原文/渲染切换 |
| 已保存附件 | `AiAttachmentFragment` | 内部查看图片和原文本；不上传、不改附件数据 |

## 聊天实现决定

- 使用 `ChatFragmentBase`、`TextBubbleCell`、`TextBubbleMessage`、`ChatActivityEnterView`；提问为发出气泡，回答为收到气泡。
- 输入栏附件按钮使用中文选择菜单，图片/文本走宿主文档选择器，拍照走 FileProvider + 宿主拍照回调。沿用每次 3 个附件、文本合计 20000 字符校验，退出时清理尚未发送附件。
- 长按调用 `MessageContextMenu`，支持复制、全文、思考过程、附件、本轮上下文。普通会话的重新提问填回原问题供用户确认；结果重新生成调用已有 `retryResult`。
- 每轮使用稳定消息 ID；流式正文变化仅 `notifyItemChanged` 对应消息，新增消息仅插入；禁用 change animation。读取历史上滑时关闭跟随，回到底部或点“回到最新”恢复。
- 简单 Markdown 直接传 Markwon spans 给气泡；表格、长回答和折叠笔记提供有明确“查看全文”的预览和按钮，全部原文由单独 Markwon 页面读取，不通过预览文本重建。完整页不设置行数上限，并可切换原文检查代码/表格内容。
- 禁用库内语音/视频/贴纸/GIF发送、定时发送长按菜单，不增加 Listen 之外的聊天功能。
- 生命周期数据监听在 `onFragmentCreate` 创建，视图重建只重新绑定；销毁时取消 UI 收集，后台 AI 工作仍归 ViewModel。
- 宿主监听录制真正开始后再结束的状态变化，统一调用原自动笔记入口；暂停和恢复不提前生成，通知里的“结束”也走同一收尾路径。自动笔记开关、原文长度校验与任务去重仍由原 ViewModel 处理。
- 日夜颜色通过 `ChatFragmentBase` 与各辅助页面的 `getThemeDescriptions` 登记。

## 验证状态

本文件记录实现及取舍，不声称完成模拟器验收。当前子任务未运行构建或模拟器（由主任务统一调度，以免同时占用 Gradle/ADB）。最终证据以 `REPORT.md` 的实际命令、测试与截图为准。必须仍验证流式上滑不回底、复制和历史恢复、附件选择/取消、笔记成功/失败/重试、长表格/代码全文、每个页面日夜截图。

未调用真实 AI；真实服务、真实相机和真机未验证。

## 原生测试覆盖映射

以下为已编写的断言，执行结果由主任务统一填入报告，不把测试存在当作通过。

| 测试 | 具体断言 |
| --- | --- |
| `AiComposerImeTest.attachmentMenuDoesNotFocusInputAndCanBeReopened` | 输入栏初始不聚焦；原生消息容器可见；附件菜单图片/文本/拍照入口可见且不聚焦输入框；取消后消失；同一按钮可以再次打开。原 Compose 的二次点击关闭菜单改成原生模态菜单的取消，保留关闭/重开覆盖 |
| `AiNativeTest.streamingKeepsHistoryPositionAndCopiesFullMarkdownAfterReopen` | 真 UI 输入并发送；原客户端消费本地 SSE；数据库先有非空 PENDING 草稿再 SUCCESS；上滑后锚点和像素偏移不变；流式期间没有全 adapter 刷新且只刷新末尾回答；长按复制的是完整回答；Markwon 存在真实表格 span；长回答末尾代码/标记未丢失；原文切换内容完全相等；退出再进入仍显示保存历史 |
| `AiNativeTest.noteGenerationSuccessFailureAndRetryUseOriginalSource` | 原笔记方向选择、生成成功、来源快照、500 失败的界面与数据库状态、中文菜单重新生成成功且原来源快照不变 |
| `AiNativeTest.cameraAttachmentForwardsProviderUriAndCancelCleansTemporaryFile` | 输入栏拍照转发 debug FileProvider URI、读写授权及 ClipData；模拟相机取消后临时文件不可读取；未产生 AI 请求 |
| `AiNativeTest.timelineLongPressDragSelectsAndUnselectsRange` | 真实长按拖动选择连续三项，再拖动取消；ActionBar 计数与 ViewModel 选择集合一致 |
| 原 `AiCameraCaptureTest` | 保持不变：相机 FileProvider、图片解析、尺寸、清理的数据层验证 |

本地 AI 测试使用测试专有 HTTPS 域名标识与虚构凭据；测试内 OkHttp interceptor 只接受该标识并将请求重写到 `MockWebServer` 回环地址。通过测试反射注入客户端，不更改生产 HTTPS 校验或数据接口，不打印请求头。测试始终检查包名为 `com.cmhr.listen.debug`。

首轮流式测试停在发送后的 Espresso 全局空闲等待，清理阶段的 ActivityScenario 空闲等待也未返回；主任务终止该轮，不能据此记为通过。测试现改为 UiAutomator 手势与有时限的主线程检查，等待实际消息/adapter 回调而非全局空闲；锚点、偏移、局部刷新、全文复制、表格和历史恢复断言不变。重新执行结果见主报告。

通知保留课堂入口、计时、录制状态和“结束”，移除“标记重点”按钮。该改动仅删通知按钮及其 PendingIntent 构造，原 ACTION_MARK 处理、录音存储和恢复逻辑没有改动，符合移除标记入口而保留历史数据的范围。
