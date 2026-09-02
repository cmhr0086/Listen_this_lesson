# Listen_this_lesson

一个面向课堂场景的 Android 语音识别与 AI 辅助整理应用。应用持续采集课堂音频，使用本地 Silero VAD 完成自然语音分段，再将 WAV 片段发送到远程 Qwen3-ASR 服务。识别结果可按课程和课堂记录保存，并支持使用兼容 OpenAI 接口的 AI 服务进行总结、笔记整理、ASR 纠错、快速回答和对话。

## 当前功能

- Kotlin、Jetpack Compose 与 Material 3 界面
- AudioRecord 持续录音：16 kHz、单声道、PCM 16-bit
- sherpa-onnx / Silero VAD 本地语音分段
- FIFO 远程 ASR 请求队列
- Course → Record → TranscriptSegment 的 Room 持久化结构
- 每门课程独立的 ASR Context
- OpenAI-compatible / DeepSeek AI 服务配置
- AI 结果、课堂问答、片段多选、TXT 导出与照片补充
- VAD 参数、预设和 AI 场景提示词运行时配置
- API Key 使用 Android Keystore 加密保存

## 环境要求

- Android Studio（包含 JDK 17 或更高版本）
- Android SDK 36
- minSdk 26
- Git LFS
- arm64-v8a Android 设备或模拟器

项目当前只打包 `arm64-v8a`，并随 APK 离线提供 Silero VAD 模型。Android 端的 sherpa-onnx 仅用于 VAD，语音识别仍由远程 Qwen3-ASR 服务完成。

## 获取与构建

```bash
git clone https://github.com/cmhr0086/Listen_this_lesson.git
cd Listen_this_lesson
git lfs pull
```

使用 Android Studio 打开项目，等待 Gradle Sync 完成。普通开发构建不需要正式签名文件：

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest
```

Android Studio 会在未存在时创建本机专用的 `local.properties`。该文件可能包含 SDK 路径等本机信息，已被 Git 忽略，不应提交。

## 正式发布签名

正式 Release 使用仓库根目录下、未纳入 Git 的 `keystore.properties`。文件包含以下字段：

```properties
storeFile=C:/Users/your-name/.android/listen-this-lesson-release.jks
storePassword=本机密钥库密码
keyAlias=listen-this-lesson
keyPassword=本机签名密码
```

配置完成后执行：

```powershell
.\gradlew.bat clean build
```

签名密钥决定 Android 后续版本的升级身份。请将 `.jks` 和对应密码保存在安全的离线位置；丢失后无法用新密钥覆盖安装已有版本。密钥、密码、API Key、`local.properties` 和 `keystore.properties` 均不得提交到仓库。

正式版本及校验文件可从仓库的 [GitHub Releases](https://github.com/cmhr0086/Listen_this_lesson/releases) 下载。

## 应用配置

首次启动后在“设置”中配置：

1. STT 服务器地址与 API Key。
2. AI 服务地址、模型与 API Key（如需 AI 功能）。
3. 开发者模式下可调整 VAD 参数、预设与 AI 场景提示词。

默认 STT 地址用于私有网络测试。请保证 Android 设备能够访问所配置的服务；不要把真实 API Key 写入源码、日志或提交到 Git。

## 数据与隐私

- 课程、课堂记录、转写和 AI 结果保存在应用本地数据库。
- 原始识别文本不会被 AI 输出覆盖。
- 当前不长期保存原始课堂音频。
- AI 补充照片保存在应用私有目录，并在相关数据删除时清理。
- API Key 不存入 Room，也不会导出到 TXT。

## 许可

当前仓库暂未提供开源许可证。未经明确授权，不代表授予复制、修改或再分发本项目代码的权利。
