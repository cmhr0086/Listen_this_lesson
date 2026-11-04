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
