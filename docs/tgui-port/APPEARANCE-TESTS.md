# 外观测试迁移

此次任务明确删除配色方案选择和字体选择，保留「跟随系统 / 日间 / 夜间」。现有 `ThemePalette` 仍被设置仓库和 ViewModel 引用，不能把删除入口误做成重置用户数据；`AppearanceModels.kt` 保留旧 enum 名称、顺序、默认值、标签和 ARGB 元数据，完全不依赖 Compose。

## 旧断言逐条去向

| 原 `PalettesTest` 用例 | 原断言 | 处理与原因 |
| --- | --- | --- |
| `pendingStatusColorIsTheSameInEveryPalette` | 五套 Compose Material3 palette 的 tertiaryContainer 相同 | 退役：五套配色方案已明确删除，原生 tgui 使用统一主题资源。继续保留该断言会要求删除后的 Compose ColorScheme 生产逻辑常驻；本项不声称验证新的状态行颜色。 |
| `palettesDifferInTheirAccent` | 五套 Compose palette 的 primary 互不相同 | 退役：自选配色方案入口明确删除，不再要求生成五种实际主题。保留旧 palette 名称、默认值和色值兼容测试，保证历史设置仍可读取。 |
| `darkAndLightSchemesUseDifferentSurfaces` | Compose 浅色与深色 surface 不同 | 迁移：`AppearanceNativeTest.dayAndNightUpdateActualBackgroundAndTextOnTheOpenPage` 直接检查真实原生页面、选项行背景和文字颜色变化，检查日间恢复原色；不再以已停用的 ColorScheme 工厂输出代替实际界面。 |

旧 `PalettesTest` 没有字体断言，因此没有以“减掉字体选择”为由删除其他测试。

## 保留的数据兼容覆盖

`PalettesTest` 现在不再导入或调用 Compose：

- `legacyPaletteNamesKeepTheirMeaningAndOrder`：TEAL / BLUE / GREEN / PURPLE / ORANGE / DYNAMIC 序列不变，各旧字符串可以 `valueOf` 还原，`ThemePalette.Default` 与 `AppearanceSettings()` 仍为 TEAL。
- `retainedPaletteMetadataUsesTheOriginalArgbValues`：原六项中文标签和精确 ARGB 值保持不变。
- `persistedDarkModeNamesAndDefaultRemainCompatible`：SYSTEM / LIGHT / DARK 旧存储名称、顺序与默认 SYSTEM 不变。

原生 `everyLegacyAppearanceValueStillRoundTripsThroughTheRepository` 通过真实 `AppSettingsRepository` 写入并从另一仓库实例读取全部 palette/mode，确认切换 mode 不清掉已保存 palette。只操作 debug 测试包的外观设置，最后恢复原值。

## 新的原生主题覆盖

`AppearanceNativeTest` 使用现有 `NativeSettingsFixture`，不修改公共 fixture，不触发真实服务请求：

- 日间 → 夜间 → 日间：点击真实选项，读取现有页面 View 的 ColorDrawable 与 TextCheckCell 中 TextView.currentTextColor；背景和文本必须匹配当前主题资源且实际改变，亮度方向正确，回切后恢复原色。要求现有页面实例保持，避免重新进入页面掩盖刷新问题。
- 相反的强制模式 → 跟随系统：依据真实 Configuration 的夜间标志，验证已有页面背景、行背景和文字确实切到系统模式，不仅断言保存了 SYSTEM。
- 颜色和主题状态连续稳定至少 200 ms 才取样，避免把主题切换动画中的中间帧当作最终结果。

新增两项冷启动边界：先持久化 LIGHT/DARK 并故意设置相反 tgui 缓存，直接创建 Home Activity，在首次可绘制帧和稳定状态读取实际 ActionBar / 背景 / 行 / 文本颜色。此法不冒充真正杀进程；实际 force-stop 冷启动截图另记录。五项已在 107 项完整设备回归中通过，最终验收版本结果见 REPORT.md。
