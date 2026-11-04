# 移植决定

1. 保留旧 Compose launcher，首先新增只在 debug manifest 注册的 ListenHostActivity；完整功能、测试和截图完成后才切换 MainActivity。
2. 用现有 ViewModel/Room/录音运行时，宿主实现 LifecycleOwner、ViewModelStoreOwner。选文件、相机和权限走 Activity 回调；不在 createView 注册数据监听。
3. 首页以当前课堂（仅录制/暂停时）与课程、AI 会话、公开发布、设置组成；公开发布集中到顶层 hub，课程/课堂显著显示笔记与资料入口。
4. 专业词提示及记重点只删除新 UI 入口，不改存储、同步字段或用户已有值；外观仅系统/日间/夜间。
5. Debug 构建不读取正式签名配置。未改动基线在独立 detached worktree 运行，缺 jlink 的 JBR 改为现有完整 Temurin 21。此为构建安全与环境修正，不改业务。
6. tgui 采用官方本库 ui5（e40ffdf）。只有实际缺陷才修改其代码和发布独立后缀；不覆盖 ui5。
7. 功能存在性以 a2b5cc4 源码为准。课表 PDF 与资料上传若基线缺失，必须核验已有分支/API，不能把占位页或未验证接口当作完成。
8. 所有设备验证仅用 SDK adb、MuMu 16384 和 com.cmhr.listen.debug。DemoData 与本地模拟服务用于测试；不接触正式包或真实课堂。
