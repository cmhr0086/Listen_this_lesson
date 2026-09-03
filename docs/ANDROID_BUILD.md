# Android 客户端构建

本文档只介绍 Listen This Lesson Android 客户端的本地构建、测试和签名。STT 服务端部署请参阅 [STT 服务端构建](SERVER_BUILD.md)。

## 环境要求

- Android Studio，或可用的 JDK 17 及以上版本
- Android SDK 36 与对应 Build Tools
- Git LFS
- 用于设备测试的 Android 模拟器或设备

项目最低支持 Android 8.0（API 26）。Debug 同时包含 `arm64-v8a` 和 `x86_64`，便于真机及 Android Studio 模拟器调试；Release 只包含 `arm64-v8a`。

## 获取源码

```bash
git clone https://github.com/cmhr0086/Listen_this_lesson.git
cd Listen_this_lesson
git lfs pull
```

使用 Android Studio 打开仓库根目录并等待 Gradle Sync 完成。`app/libs/sherpa-onnx-1.13.4.aar` 由 Git LFS 管理，未执行 `git lfs pull` 时无法得到完整依赖。

## Debug 构建

Windows：

```powershell
.\gradlew.bat assembleDebug
```

macOS 或 Linux：

```bash
./gradlew assembleDebug
```

Debug APK 输出到 `app/build/outputs/apk/debug/`。普通开发构建不需要正式签名配置。

## 测试

单元测试：

```powershell
.\gradlew.bat testDebugUnitTest
```

连接 Android 设备或启动模拟器后执行设备测试：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

完整检查与构建：

```powershell
.\gradlew.bat clean build
```

## Release 签名

正式构建从仓库根目录、未纳入 Git 的 `keystore.properties` 读取签名信息：

```properties
storeFile=C:/absolute/path/to/listen-this-lesson-release.jks
storePassword=本机密钥库密码
keyAlias=listen-this-lesson
keyPassword=本机签名密码
```

配置完成后执行：

```powershell
.\gradlew.bat assembleRelease
```

Release APK 输出到 `app/build/outputs/apk/release/`。缺少或不完整的签名配置会使正式发布任务明确失败，避免误发布未签名安装包。

发布前应使用 Android SDK Build Tools 中的 `zipalign` 和 `apksigner` 检查 APK，并确认压缩包中的原生库目录只有 `lib/arm64-v8a/`。

## 敏感文件

以下内容只应保存在开发者本机，不得提交：

- `local.properties`
- `keystore.properties`
- `*.jks`、`*.keystore`
- 真实 STT 或 AI API Key
- 构建生成的 APK 与临时附件

正式签名密钥决定后续版本的升级身份。请将密钥库和密码分别备份到安全位置；密钥丢失后将无法用新证书覆盖安装已发布版本。
