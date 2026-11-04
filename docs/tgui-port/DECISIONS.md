# 移植决定

1. 保留旧 Compose launcher，首先新增只在 debug manifest 注册的 ListenHostActivity；完整功能、测试和截图完成后才切换 MainActivity。
2. 用现有 ViewModel/Room/录音运行时，宿主实现 LifecycleOwner、ViewModelStoreOwner。选文件、相机和权限走 Activity 回调；不在 createView 注册数据监听。
3. 首页以当前课堂（仅录制/暂停时）与课程、AI 会话、公开发布、设置组成；公开发布集中到顶层 hub，课程/课堂显著显示笔记与资料入口。
4. 专业词提示及记重点只删除新 UI 入口，不改存储、同步字段或用户已有值；外观仅系统/日间/夜间。
5. Debug 构建不读取正式签名配置。未改动基线在独立 detached worktree 运行，缺 jlink 的 JBR 改为现有完整 Temurin 21。此为构建安全与环境修正，不改业务。
6. tgui 采用官方本库 ui5（e40ffdf）。只有实际缺陷才修改其代码和发布独立后缀；不覆盖 ui5。
7. 功能存在性以 a2b5cc4 源码为准。课表 PDF 与资料上传若基线缺失，必须核验已有分支/API，不能把占位页或未验证接口当作完成。
8. 所有设备验证仅用 SDK adb、MuMu 16384 和 com.cmhr.listen.debug。DemoData 与本地模拟服务用于测试；不接触正式包或真实课堂。

9. 录音服务仅删除通知中的“标记重点”按钮和对应 PendingIntent（5 行）；ACTION_MARK 处理、录音生命周期及数据字段保留。通知结束后自动笔记改由宿主观察真实录制状态统一触发，页面不重复触发。
10. 原文保留长段到底、增长跟随、拖动暂停、手动到底恢复以及显式恢复；选中模式支持分组和拖动范围。删除沿用 2 秒不可确认保护。
11. 旧页面中的纯展示规则原样移到独立 Kotlin 文件，设置枚举名称和存储值保留；这为最后删除 Compose 做准备，当前不提前切换 launcher。

12. 全保留功能和旧 UI 测试迁移后，MainActivity 改为原生 ListenHostActivity 子类，稳定类名与桌面 / 通知入口保留；删除 19 个 Compose 页面 / 主题文件及插件、依赖。旧纯展示 helper 和外观存储 enum 保留，不迁移用户数据。
13. 首次建页前只预读持久化 appearance.darkMode（IO dispatcher），再应用主题；不能用 SettingsUiState 的合成 SYSTEM 默认值覆盖用户设置。代价是首次读取等待已落盘的小型 DataStore；本机冷启动耗时另记录。后续订阅原 Repository，避免双重来源竞态。
14. 保存标识符路由栈，批量挂回再一次 rebuild。临时正文 / 附件页重建回到最近可恢复父页，不序列化长内容。待权限的录音请求保存 id / mode / newClass；文件选择 / 相机期间若宿主被销毁，返回时明确提示重选，不宣称跨进程自动续接这些临时回调。
15. targetSdk 36 的宿主继续使用 tgui 的 Activity 返回栈，Manifest 显式 opt out predictive-back，避免 Android 16 忽略旧 onBackPressed。依据 [Android 16 官方行为变更](https://developer.android.com/about/versions/16/behavior-changes-16#predictive-back)；MuMu 只验证 Android 15，16 仍需实机 / 模拟器补测。
16. 资料入口直接位于课程 / 课堂页，服务适配器只在 UI 层复用已有 materials 合同。课表 PDF 的 Android 基线没有完整模型或路由，现有 main 服务也无该 endpoint；只实现既有 feature/timetable-pdf 合同的预览和选课建课程，不新增 schema / 时段持久化，不声称完整课表导入完成。
17. 原文选段 AI 的前置错误只响应本页发起的操作，忙时明确提示；不在重新进入原文时重放旧错误。普通 Room 原文追加不再重绑所有旧气泡，只有选中态真正改变时更新关联行。
18. Debug DemoData 增加一条明确标为离线演示的已完成 ASR 耗时记录，用于详情截图；无 jobId、待处理状态、WAV 或重试时间，不启动网络工作。不存在的诊断片段显示“未找到片段”，不永久显示正在读取。
19. 图片全部使用 debug DemoData；空资料 / 未绑定 / 模型未配置截图代表这些真实状态，不把它们当成功联网证据。真实服务和真机验证保留为所有者的合并前清单。
20. 专用搜索页保持搜索框展开，但系统 Back 与工具栏返回都先进入 tgui 的搜索收起回调，不能仅返回 false 吞掉操作。当前片段位于栈顶且未开始结束时，收起请求调用 finishFragment 后返回 false；保留课堂返回搜索时的查询状态，避免非栈顶或销毁阶段误退。原搜索设备测试补充两条真实返回首页路径，不直接调用 finishFragment 替代点击。
21. CourseFragment / ClassroomFragment 的数据可能早于 view 重建到达；ListenListFragment 缓存最新 displayTitle，setPageTitle 同时更新缓存与现有 ActionBar，createView 使用缓存值。避免已载入课程 / 课堂名称在返回栈恢复后被默认“课堂”覆盖；Host 重建测试增加实际标题断言。修复代码 a93e2c8bca7d6887493b03f121bcaf8d99ced927 已推送并通过 assemble / JVM 单测，最终全量 110 项已在 connected-native-accepted.log 通过，新增断言已取得独立通过证据。人工冷启动导航与后台 am kill 后重启已验证原返回栈和真实标题恢复。
22. WAV 导出验收增加真实 SAF UI 与独立文件读回：单课堂、整课程各导出两段 debug 演示音频，共 4 个 WAV，每组分别 3 秒和 4 秒，单声道 PCM16、16 kHz，验证日志为 manual_export_verified.log。该证据覆盖选择目录和实际文件产出，不能扩展为取消 / 大批量导出已验证。
23. 人工权限与恢复验证独立于 Activity recreate 和合成夹具：麦克风拒绝 / 通知允许后必须显示权限错误且不采集，再经系统 UI 授予麦克风后实际仅录音。实际采集时 force-stop，重启检查已恢复提示、DB INTERRUPTED 与 endedAt、WAV 帧数 / 采样率 / 文件长度 / 时长一致；本次为 912384 帧、16 kHz、1824812 字节、57.024 秒，原文 0 段且无 ASR。依据 manual_permission_final.log、manual-current.png、manual_recovery_final.log；不扩展为识别质量、全部进程回收场景或整夜稳定性验收。旧恢复脚本使用未刷新的 UiAutomator XML 所致失败，以刷新后的页面、DB 和 WAV 独立证据修正，保留历史而不误报产品缺陷。

24. 配置向导先保存 SetupState.DONE，再执行原生返回动画；测试必须有界等待真实首页且动画结束，才检查旧页消失。保留旧页不可见与网络请求数不变两个断言，并增加实际首页断言，避免把数据写入完成当作视图过渡完成；不改生产代码。
