# 更新日志

## 1.0.0 — 2026-09-02

Listen This Lesson 的首个正式版本。

### 主要功能

- 使用 AudioRecord 持续采集 16 kHz、单声道、PCM 16-bit 课堂音频。
- 使用 sherpa-onnx / Silero VAD 在设备端自然分段，ASR 请求期间继续监听。
- 通过 FIFO 队列调用远程 Qwen3-ASR，并按课程、课堂记录和识别片段保存结果。
- 支持课程级 ASR Context，以及关闭、自动和始终使用三种 Prompt 策略。
- 支持 OpenAI-compatible 与 DeepSeek AI 服务，可执行总结、笔记整理、ASR 纠错、快速回答和课堂对话。
- 支持 AI Markdown、流式回复、照片补充、片段多选、TXT 导出和前台监听通知。
- 支持运行时调整并持久化 VAD 参数、预设、AI 提示词和生成参数。

### 修复与体验

- 修复 ASR Prompt 相关页面因 Compose 编译与运行依赖不一致导致的闪退。
- ASR、DeepSeek 思考和推理强度选项统一为可横向滑动布局。
- AI 回复与固定结果卡片使用完整内容宽度。
- 新增适配 Android Adaptive Icon 和主题图标的“书本＋声波”应用图标。

### 平台说明

- 最低系统版本 Android 8.0（API 26）。
- 当前安装包仅支持 `arm64-v8a`。
- Android 端的 sherpa-onnx 只用于 VAD，所有语音识别仍由远程 Qwen3-ASR 完成。
