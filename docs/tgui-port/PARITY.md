# 路由与功能对照

基线：Listen main@a2b5cc4；来源 ListenApp.kt、MainActivity.kt、各 Screen、ViewModel。状态为计划时不表示已实现/已验收。未列出的已有行为默认保留。

| 原路由/功能 | 决定 | 新页面 | 状态 |
|---|---|---|---|
| record-home；即时识别/仅录音；选择/新建课程 | 移植 | 首页、开始课堂 | 待实现 |
| 首页最近课程/课堂 | 减掉 | 仅录制或暂停时显示当前课堂 | 待实现 |
| courses；新建/重命名/删除/课程颜色 | 移植 | 课程列表 | 待实现 |
| course/{courseId}；课堂列表/新建/改名/换课程/删除/多选 | 移植 | 课程详情 | 待实现 |
| record/{recordId}；原文/笔记/资料 | 移植 | 课堂页 | 待实现 |
| 录音开始/暂停/继续/结束/计时/前台服务/通知/异常恢复 | 移植 | 首页、课堂、宿主 | 待实现 |
| 补识别/全部识别/队列/失败/取消/录音删除 | 移植 | 课堂录音 | 待实现 |
| 原文跟随最新/搜索/选择/复制/删除 | 移植 | 原文页 | 待实现 |
| 原文记重点/重点筛选 | 减掉 | 无入口；marked 保留 | 待实现 |
| search；原文与笔记检索 | 移植 | 全局搜索 | 待实现 |
| 课表 PDF 导入 | 移植 | 课程/课表 | 基线尚未找到，核验分支 |
| 单课/全课程 WAV 导出，进度/取消 | 移植 | 课堂/课程导出 | 待实现 |
| course/{courseId}/publish | 合并 | 顶层公开发布 hub → 课程发布状态/操作 | 待实现 |
| 课程/课堂笔记资料上传 | 移植 | 课程/课堂资料 | 核验现有 Android/API 能力 |
| ai；全局会话/结果，多选、删除、TXT 导出 | 移植 | AI 会话列表 | 待实现 |
| ai/new；ai/new/{recordId} | 移植 | 新会话 | 待实现 |
| ai-conversation/{conversationId}；流式/历史/附件/重试/复制 | 移植 | tgui 聊天页 | 待实现 |
| record/{recordId}/ai-conversations | 移植 | 课堂会话列表 | 待实现 |
| record/{recordId}/ai-results | 移植 | 课堂 AI 结果 | 待实现 |
| record/{recordId}/ai-result/{resultId}；ai/result/{recordId}/{resultId} | 合并 | Markdown 结果页 | 待实现 |
| record/{recordId}/ai-result/{resultId}/ask | 移植 | 笔记追问 | 待实现 |
| 笔记方向/生成中/失败/重试/自动整理/重新生成 | 移植 | 课堂笔记与结果 | 待实现 |
| settings | 移植 | 设置 | 待实现 |
| settings/stt-service | 移植 | 语音识别服务 | 待实现 |
| settings/vad | 移植 | VAD 预设与参数 | 待实现 |
| settings/ai-service；settings/ai-service/models | 移植 | AI 服务/模型 | 待实现 |
| settings/device | 移植 | 设备绑定 | 待实现 |
| settings/cloud-sync | 移植 | 云同步 | 待实现 |
| settings/publish | 移植 | 发布服务设置 | 待实现 |
| settings/appearance | 合并 | 系统/日间/夜间 | 待实现 |
| settings/ai-prompts；settings/ai-prompts/{kind} | 移植 | 提示词列表/编辑 | 待实现 |
| settings/asr-prompt-policy；thresholds；课程提示词 | 减掉 | 无界面，值保留 | 待实现 |
| settings/ai-generation | 移植 | AI 生成设置 | 待实现 |
| settings/asr-diagnostics；history/{recordId}；分段事件/重试 | 移植 | ASR 诊断/历史/详情 | 待实现 |
| 配置向导、跳过/继续、绑定/手动配置 | 移植 | 配置向导 | 待实现 |
| 相机/图片/文件附件，来源文字，AI 诊断 | 移植 | AI 附件和上下文菜单 | 待实现 |
| 日夜切换/旋转/返回手势/进程恢复/权限拒绝 | 移植 | 全局宿主和页面 | 待实现 |

## 测试迁移

旧 Compose 测试暂时保留。切换时逐项重写 UiInteractionTest、SettingsPagesTest、FollowLatestTest、AiComposerImeTest、AiCameraCaptureTest 中所有保留功能；只对明确删去的界面覆盖记录理由，不删除数据层 marked 测试。
