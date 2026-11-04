# 搜索、发布、资料与课表子任务

## 已实现的界面

- `SearchFragment` 使用原生 tgui ActionBar 搜索栏和 DialogCell 列表，200 ms 防抖，保留全部／文字／笔记范围、按课堂分组、时间排序、命中附近摘要及进入课堂。已删除“只看重点”筛选和重点标识，底层 marked 未改。观察在 fragment 生命周期内注册，createView 重建只重建搜索视图；继承列表主题描述并登记搜索文本、提示和光标颜色。
- 命中高亮使用 `DialogItem.highlightedWords`，共用 `ListenRow` 增加可选关键词并在 DiffUtil 内容比较中登记；日夜切换登记 `windowBackgroundWhiteBlueText4`。没有自行覆盖 tgui 的列表绘制。
- `PublishHubFragment` 为顶层公开发布入口，列出每门本地课程的缓存审核状态。进入总览不会联网。`CoursePublishFragment` 保留学校、公开课程名、教师编辑，学期状态、刷新、重新申请、同步进度和失败信息、逐课堂排除／撤回操作。课程和课堂页无需放发布入口。
- `MaterialsFragment(courseId, recordId?)` 提供整门课程／单节课堂的笔记上传、资料上传、刷新、文件打开、长按删除。列表可显示所属课堂，课程级入口可切换学期。上传和删除仍要求服务端管理员权限。选文件后读原始字节，客户端限制 20 MB；文件打开通过应用自己的 FileProvider 临时授权，不向外部查看器泄露凭据。
- `TimetableFragment` 提供 PDF 选择、解析预览、课程多选、显示周次／单双周／节次／教室／未解析行，并通过原 `CourseViewModel.createCourse` 创建课程，跳过同名课程。

## 已有接口证据和限制

资料上传并非基线 Android 既有功能。`docs/PRODUCT_BACKLOG.md` 中该项为待定／部分完成，`CHANGELOG.md` 描述的是小程序管理页。当前服务端已有完整资料接口，因此新增的 `tgui/MaterialService.kt` 只是界面侧适配器；未修改 data、Room schema 或服务端。

当前 `sync-server/publish/main.py` 与 `materials.py` 只读审查确认的合同：

| 操作 | 已有 API |
| --- | --- |
| 列表 | GET `/api/admin/v1/courses/{courseId}/materials` |
| 上传 | POST 同一路径，原始文件 body，`fileName`、`category=note/material`、可选 `lessonId` query |
| 下载 | GET `/api/admin/v1/materials/{materialId}/file` |
| 删除 | DELETE `/api/admin/v1/materials/{materialId}` |

鉴权依赖现有 `AppSettingsRepository.readPublishCredential`，服务端 `devices.admin_reader` 接受管理员绑定设备凭证。无凭据、无远端课程映射时不会联网。刷新／上传／下载／删除仅由用户动作触发。单节课堂依赖已上传课堂的 sessionId→lessonId 映射；未上传时显示明确失败，不替换为整门课上传。

课表合同来自只读 `origin/feature/timetable-pdf` 分支的 `sync-server/publish/main.py`、`timetable_pdf.py`：POST `/api/public/v1/tools/timetable-pdf`，原始 PDF body，2 MB 上限，返回课程、教师、slots、maxWeek、maxPeriod、skipped。**当前 main 服务端没有此 endpoint，基线 Android 也没有课表存储模型。** 按主任务集成决定，只增加预览和课程创建，不增加时间表 schema；完整周次／时段持久化并未实现。因此不能把“完整课表 PDF 导入”标为已完成，不能以 MockWebServer 通过代替真实服务可用性。404 显示“当前服务尚未提供课表导入”。没有请求真实服务。

## 验证交接

新增 `PublishSearchPortTest`：搜索分组／摘要、撤回状态、资料 raw body／鉴权／课堂归属参数、列表／删除／权限错误、下载文件名路径隔离、体积检查、课表解析合同／404／无效 PDF 不发请求。网络测试仅使用本机 MockWebServer。

新增 `app/src/androidTest/java/com/cmhr/listen/tgui/PublishNativeTest.kt`，通过实际原生 Activity、Espresso 输入／文件选择返回、UiAutomator 点击验证以下六条路径（由主任务运行，本文不预报通过）：

| 用例 | 实际断言 |
| --- | --- |
| 搜索 | 输入静矩、切到笔记、native DialogCell 实际 layout 含主题高亮 span、打开课堂、返回保持范围、无结果提示、无重点筛选 |
| 发布 | 总览缓存公开状态、未绑定提示、学校编辑写回原 DataStore；全程不发送发布请求 |
| 课程资料 | 刷新、文件选择、原始文件上传与 note 参数、下载、外部查看器收到临时 FileProvider 只读 URI 而非凭据、删除二次确认 |
| 单节课堂资料 | sessionId 对应正确 lessonId、过滤其它课堂／整门课文件、material 上传携带正确归属 |
| 课表成功 | PDF 选择、原始 body、预览教师与周次节次、创建所选课程，结束清理测试课程 |
| 课表失败 | 本地服务返回 404，显示服务尚未提供，不伪造成功或允许创建课程 |

旧覆盖映射：基线 `ui/UiInteractionTest.kt` 没有独立搜索／发布／资料／课表 UI 用例，因此本文件补充集成覆盖，没有删除或替换该文件任何断言。旧 `MarksAndSearchTest.searchFindsCorrectedTextNotesAndLiteralWildcards` 是 Room 查询测试，须继续保留；新的 native 用例补充真实输入／过滤／跳转。旧 `ui/PublishStatusTest` 为纯状态规则测试，主任务保留／移动其 helper；本文件的发布 UI 用例补充点击与保存，不替代生命周期状态规则。只有已明确减掉的重点筛选／标识不进入新界面。

仪器测试依赖 Espresso Intents、UiAutomator、MockWebServer；debug 网络安全配置只允许本机 mock 地址。资料和课表 Fragment 接受内部构造参数注入测试服务，生产默认地址／API 没有改变。所有选择文件均为 cache/materials 中的演示内容，没有真实课堂 PDF 或用户文件。

本子任务未运行 Gradle、模拟器或提交／推送，由主任务统一编译与验证。尚待实际结果：编译、单元测试、模拟器搜索输入与恢复、发布页状态、所有页日夜截图、资料 Android 文件选择与外部查看器流程、课表本地模拟服务联调。真实发布、上传、绑定服务均禁止在本任务中验证。

集成需求：宿主注册 `timetable`、`materials/{courseId}`、`materials/{courseId}/{recordId}`；FileProvider 增加 cache/materials 路径；课程和课堂页放显眼的笔记与资料入口；顶层入口使用 `publish`。本子任务仅创建自身 tgui 文件与测试／本说明，没有修改其它代理负责的源文件。
