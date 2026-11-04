# 设置、向导与诊断移植清单

实现：`app/src/main/java/com/cmhr/listen/tgui/SettingsFragments.kt`。仅替换界面；所有保存、绑定、同步、服务检测与诊断重试都调用现有 `SettingsViewModel` / `SttViewModel`。没有更改数据库、录音、网络接口或已保存配置。

| 原页面 / 功能 | 新路由 | 实现范围 |
| --- | --- | --- |
| 设置总览 | `settings` | 服务入口、自动笔记、开发者开关、版本 |
| 语音识别服务 | `settings/stt` | 两种服务商、地址、兼容接口模型、密钥新增/清除、连接检测及结果 |
| AI 服务 | `settings/ai` | 两种服务商、地址、模型、密钥新增/清除、连接检测、自动笔记 |
| AI 模型列表 | `settings/models` | 获取与错误状态、现有模型、选择、搜索、手动输入 |
| 云同步 | `settings/sync` | 地址、令牌、停用、同步进度、上传/收到计数、上次同步、失败信息 |
| 发布设置 | `settings/publish` | 学校、设备授权状态、上传者、令牌新增/清除、权限检测；跳转顶层发布 |
| 设备绑定 | `settings/device` | 获取码、等待、取消、应用配置、失败重试、手动同步、解除绑定 |
| 首次配置向导 | `setup` | 绑定/手动、识别服务、AI、汇总四步；跳过、返回、测试失败继续、完成 |
| 外观 | `settings/appearance` | 只保留跟随系统、日间、夜间；不修改旧主题色值 |
| VAD 参数 | `settings/vad` | 四个预设、阈值与全部八个时长参数、范围校验、恢复默认 |
| AI 提示词 | `settings/prompts` | 十个已有提示词类型及全部恢复默认 |
| AI 提示词编辑 | `settings/prompt/{kind}` | 多行编辑、非空校验、未保存放弃确认、查看完整文本/复制、单项恢复默认 |
| AI 生成参数 | `settings/generation` | 输出长度、两类温度、深度思考、推理强度、恢复默认 |
| ASR 诊断 | `settings/asr` | 当前课堂采集/VAD、队列/并发/背压、24小时统计、服务快照手动刷新、最近15段、全部待确认重试 |
| ASR 全部历史 | `settings/asr-history/{recordId}` | 指定课堂全部片段 |
| ASR 片段详情 | `settings/asr-segment/{recordId}/{segmentId}` | 完整编号、状态、单调时钟时长、阶段耗时、时间点、尝试次数、错误、网络事件、单项确认重试 |
| 专业词提示及开关 | 删除界面 | 不调用相关保存 API，保留所有旧值；诊断页不重新提供该设置入口 |
| 主题色 | 删除界面 | 不调用主题色保存 API，保留旧值 |

## 界面实现

- 使用公共 `ListenListFragment` 的 tgui `HeaderCell`、`TextCell`、`TextDetailSettingsCell`、`TextCheckCell`、`ShadowSectionCell`；诊断片段使用 `DialogCell`。
- 监听只在 `onReady()`（由 `onFragmentCreate()` 调用）创建，由公共 fragment scope 在销毁时取消；`createView()` 不添加监听。
- 列表主题登记继承公共实现；编辑框额外登记 tgui dialog text/hint 主题色。数字输入保留原值并在原输入框显示校验错误。
- 密钥输入使用密码模式，不从存储读取或回填密钥；只显示“已保存/未填写”。
- 文案以中文为主；模型名称、用户配置 URL、网络异常类型作为用户/诊断数据原样显示，不使用 tgui 英文默认按钮。
- 保留 VAD 现有存储与生效规则，不更改任何切段默认值。

## 取舍与验证边界

- 进入设置页不自动发起连接检测或模型列表请求；“测试连接”和“获取模型列表”保留，保存设置仍走 ViewModel 原来的自动检测行为。这避免仅浏览页面就请求真实服务，并保留全部可执行操作。
- 长提示词使用可滚动原生编辑对话框，全文查看使用 tgui 可滚动消息对话框，避免摘要截断导致内容丢失。
- 诊断保留全部数据项和重试操作，用列表数字替换概率/并发装饰条。删除的专业词提示不在新诊断页暴露其配置。
- 本文件记录源码覆盖，不宣称模拟器验证。编译、实际页面日夜截图、旋转/返回、MockWebServer、绑定/同步/发布隔离测试由主任务统一执行并写入 `REPORT.md`。
- 未进行真实 AI、ASR、绑定、云同步或发布调用；这些真实服务均不在本轮授权验证范围内。

## 原设置测试逐项映射

`SettingsPagesTest.kt` 已移除 Compose 测试依赖，使用 ActivityScenario + Espresso 原生 EditText 输入 + UiAutomator；各断言读取真实 fragment 和保存后的 repository 状态，不用伪造可变 UI state。

| 原测试 / 断言 | 原生替代 |
| --- | --- |
| `editSheetOnlyConfirmsAChangedValueAndTrimsIt`：未改值不可保存，编辑后可保存，修剪空格，关闭编辑层 | 保留同名测试；真实服务地址编辑、按钮 enabled 状态、repository 保存值、EditText 消失 |
| `failedSpeechCheckStaysOnThePage`：失败状态和 403 详情、地址及清除密钥入口可见 | 保留同名测试；MockWebServer `/health` 成功、鉴权 403，通过实际 ViewModel 得到失败；重进后仍显示失败 |
| `promptEditorAsksBeforeDroppingEdits`：未编辑不能保存、编辑后能保存、退出确认、确认前不完成、放弃后完成 | 保留同名测试；验证对话框、取消与继续编辑、放弃后编辑框关闭，且 repository 原文未变 |
| `reasoningOptionsFollowTheProvider`：其他服务不显示思考模式，DeepSeek 关闭时推理强度禁用，开启后可用 | 保留同名测试；直接写入真实服务设置（本地地址），断言原生设置行的 enabled 状态。旧长解释文案按精简文案要求删除，替代断言是对应控件确实不存在 |
| `asrPromptPageListsEachCourseAndEditsIt` | 按用户减法删除；专业词提示页面与开关不移植。新增测试验证入口不存在且保存的 `ALWAYS` 值未被重置 |

新增 `SettingsNativeTest.kt`：三种外观切换并保留旧主题色、十种提示词路由、提示词保存与恢复、生成参数范围及保存、四种 VAD 预设入口与全部九项参数、向导跳过/回退/完成、浏览服务页面不触发请求或显示密钥。

测试服务器只监听设备进程内的回环地址；测试启动前强制校验应用 ID 是 `com.cmhr.listen.debug` 并使演示设备保持未绑定。仅写入明确的合成测试凭据，不读取凭据明文。测试需要 debug-only 回环 HTTP 地址许可，由主任务集成。上述测试为已编写待运行，实际通过数以主任务的测试报告为准。
