# Listen This Lesson：Claude（全新 Mac）接手指南

> 适用场景：Claude 在一台没有本项目开发环境、没有本机缓存、没有签名材料的 Mac 上从零接手。  
> 交接基线：GitHub `main` 分支，提交 `2873655`，正式版本 `v1.0.3`。  
> 文档日期：2026-10-08。

## 0. 给 Claude 的第一段话

```text
你正在一台全新的 Mac 上接手 Listen This Lesson。请先克隆仓库并完整阅读 CLAUDE_HANDOFF.md、CHANGELOG.md、README.md，以及与当前任务直接相关的源码。当前稳定基线是 main@2873655 / v1.0.3，Room v11。

在环境验证完成前不要修改业务代码。先完成 Git LFS、Android SDK 36、JDK、Gradle 单元测试和 sync-server pytest 基线。没有正式 keystore、真实 API Token 或线上 SSH 凭证时，不要猜测、生成替代品或尝试正式发版；Debug 构建和本地测试不需要这些材料。

项目边界必须保持：Android 是 local-first 客户端；Mac 只提供 ASR 推理；Rainyun 只同步 Session/Segment 文本。音频、Recording 和 AI 附件不进入云同步。
```

## 1. 从 GitHub 获取的基线

- 仓库：<https://github.com/cmhr0086/Listen_this_lesson>
- 最新正式版本：<https://github.com/cmhr0086/Listen_this_lesson/releases/tag/v1.0.3>
- 基线提交：`2873655 feat: prepare v1.0.3 release`
- Android applicationId：`com.cmhr.listen`
- Android 版本：`versionCode=4`、`versionName=1.0.3`
- Room：v11，存在从 1 到 11 的连续显式 Migration
- Release APK：仅 `arm64-v8a`
- v1.0.3 APK SHA-256：`a5920d70a6cd277df58e215a12179d7d80e3cb02921acb7dc7daabadf5accc06`

全新 Mac 只应从远端仓库获取项目。原 Windows 工作区中的未跟踪 `sync-server.zip` 不属于 Git 基线，也不是接手所需文件。

## 2. 先向项目所有者确认或索取的材料

这些材料不在 Git 仓库中。没有它们仍可完成大多数开发和测试，但不能完成对应的真实联调或正式发版。

| 材料 | 没有时能做什么 | 何时必须索取 |
|---|---|---|
| 正式 Android keystore、alias 与密码 | 可 Debug 构建、测试；不能正式签名 | 需要发布可覆盖安装旧版本的 APK/AAB 时 |
| Mac ASR 服务启动方式、依赖、API Key | 可用 MockWebServer 测客户端；不能真实识别 | 修改实时 ASR、队列或补识别时 |
| Rainyun HTTPS 同步地址与 `SYNC_API_TOKEN` | 可本地跑 sync-server；不能真实跨设备同步 | 做线上同步验收时 |
| Rainyun SSH/控制台权限 | 不影响本地开发 | 部署、迁移或排查生产服务时 |
| AI 服务地址、模型和 API Key | 可测试本地 UI/Mock；不能真实生成 | 修改 AI 调用或视觉能力时 |
| 一台 Android 真机 | 可先用 Android Emulator | 验证长时间麦克风、锁屏、前台服务和真实相机时 |

安全原则：

- 不要让用户把密码、Token 或 keystore 内容提交到 Git。
- 不要把 Token 写入源码、Gradle、README、日志或测试快照。
- 不要自行生成新正式 keystore；新证书无法升级已发布应用。
- 如果只收到 keystore 文件，应让所有者通过安全渠道另行提供密码。

本次 Windows 侧另行生成了交付包 `claude-mac-key-handoff-2026-10-08.tar.gz`。包内只有受密码保护的正式 JKS、SHA-256、`keystore.properties` 占位模板和迁移说明；签名密码不在包内。归档与 `storePassword/keyPassword` 必须通过不同渠道交付。

## 3. Mac 从零安装开发环境

### 3.1 必需软件

安装：

1. Git
2. Git LFS
3. Android Studio（包含合适的 JBR）
4. Android SDK Platform 36、Build Tools、Platform Tools
5. Python 3（建议 3.11 或更新的稳定版本）

如使用 Homebrew，可参考：

```bash
brew install git git-lfs python
brew install --cask android-studio
git lfs install
```

Apple Silicon 和 Intel Mac 都可以做 Android Debug 开发。创建模拟器时优先选择与宿主匹配的系统镜像；正式 APK 仍只发布 `arm64-v8a`。

### 3.2 克隆并校验仓库

```bash
git clone https://github.com/cmhr0086/Listen_this_lesson.git
cd Listen_this_lesson
git lfs pull
git checkout main
git pull --ff-only
git rev-parse --short HEAD
git status --short
```

期望基线提交为 `2873655`，工作区为空。如果远端已经有更新，不要强行回退；先阅读新提交和更新后的 `CHANGELOG.md`，再把本文档视为历史交接信息。

校验 LFS 文件：

```bash
git lfs ls-files
ls -lh app/libs/sherpa-onnx-1.13.4.aar
```

`app/libs/sherpa-onnx-1.13.4.aar` 必须是实际 AAR，而不是 Git LFS pointer 文本。

### 3.3 配置 Android SDK

第一次用 Android Studio 打开仓库根目录，让 IDE 完成 SDK 与 Gradle Sync。若命令行找不到 SDK，可在仓库根目录创建不提交的 `local.properties`：

```properties
sdk.dir=/Users/<你的用户名>/Library/Android/sdk
```

命令行可设置：

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

优先让 `./gradlew` 使用 Android Studio 自带 JBR；若终端 Java 不兼容，在本机 shell 配置正确的 `JAVA_HOME`，不要把绝对路径提交到项目。

## 4. 建立无密钥开发基线

Debug 构建、单元测试和 instrumentation 测试不需要正式签名配置。

先执行：

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

再在 Android Studio Device Manager 创建并启动 API 35/36 模拟器，然后执行：

```bash
adb devices -l
./gradlew connectedDebugAndroidTest
```

如果有多个设备：

```bash
export ANDROID_SERIAL=<adb devices 显示的序列号>
./gradlew connectedDebugAndroidTest
```

需要重点保留的设备测试：

- `ListenDatabaseMigrationTest`
- `SyncRepositoryTest`
- `RecordingDaoTest`
- `SessionDefaultNameTest`
- `AiCameraCaptureTest`
- `UiInteractionTest`

`v1.0.3` 发布前这些测试曾在 MuMu Android 15 上合并运行，共 39 项，0 失败。MuMu 是原 Windows 环境的验证设备，不是 Mac 接手的前置条件；Mac 上用 Android Emulator 建立新基线即可。相机、锁屏、麦克风和长时前台服务最终仍建议补真机验收。

## 5. 本地启动 sync-server

sync-server 是独立 FastAPI + SQLite 项目，不需要 Android 密钥或线上服务器权限。

```bash
cd sync-server
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
pip install -r requirements-dev.txt

export SYNC_DATABASE_URL='sqlite:///./listen-sync.db'
export SYNC_API_TOKEN='仅用于本机测试的随机字符串'

pytest -q
uvicorn app.main:app --host 127.0.0.1 --port 8000
```

另开终端验证：

```bash
curl http://127.0.0.1:8000/health
curl -i -X POST http://127.0.0.1:8000/api/v1/sync \
  -H 'Content-Type: application/json' \
  -d '{"deviceId":"00000000-0000-0000-0000-000000000001","lastSyncAt":0,"sessions":[],"segments":[]}'
```

第二个请求没有 Bearer Token，期望返回 HTTP 401。不要用生产 Token 做本地自动化测试。

Android 的正式云同步设置要求 HTTPS；本地端到端调试如需让模拟器访问 Mac 服务，应先设计明确的 Debug-only 方案或本地 HTTPS 入口，不要弱化正式构建的 HTTPS 校验。

## 6. 产品架构与职责边界

### Android App

- 采集 16 kHz、单声道、PCM16 音频。
- 用 sherpa-onnx/Silero VAD 做本地语音分段。
- 保存 Course、Session、Segment、AI 数据和本地 Recording。
- 调用 Mac ASR 服务识别短 WAV。
- 手动与 Rainyun 同步 Session/Segment 文本。

### Mac ASR 服务

- 当前仓库不包含其实现。
- Android 期望 `GET /health`、`POST /transcribe` 和 `GET /jobs/{jobId}`。
- 适合短 WAV Job，不适合一次上传一小时录音。
- 不保存课堂业务数据库，不执行云同步。

### Rainyun sync-server

- FastAPI + SQLite。
- 只保存 Session 和 Segment。
- 不保存客户端 `syncStatus`。
- 不同步音频、Recording、Course 实体、AI 对话或附件。
- 不调度 Mac ASR。

除非新需求明确改变架构，不要在三者之间增加新的隐式依赖。

## 7. Android 关键文件地图

### UI 与入口

> v1.3.0 起：视觉组件在 `ui/Lists.kt`（`ListGroup`/`ListRow`/`StatusPill`/`CourseBadge`，课程颜色 `CourseColors` + DataStore `course_colors` 覆盖，经 `LocalCourseColors` 提供）。课堂页在 `ui/ClassScreens.kt`（`ClassTab` 文字/笔记/问答、`SelectionActionBar`），搜索在 `ui/SearchScreen.kt`。Room v12：`records.topic`、`transcript_segments.marked`、`pending_marks` 表 + `mark_new_segments` 触发器（`ListenDatabase.TRIGGERS_CALLBACK` 在 onOpen 建立），标记入口 `CourseRepository.markMoment`（控制栏、通知 `ACTION_MARK`）。自动笔记 `AiViewModel.autoOrganizeNotes`（归档后、全部识别后触发；`splitTopic` 取首行“主题：”写入 topic）。同步新增 `topic`/`marked`，`@EncodeDefault(NEVER)` 保证旧服务端兼容；sync-server 的 `initialize_database` 启动时补列。
>
> v1.2.0 起：课堂可暂停/继续（`SttViewModel.pause/resume/endClass`，状态在 `ListeningUiState.pausedClass`/`resumingRecordId`，续录用 `elapsedOffsetMs` 保持计时连续）；归档确认只在“结束”后出现。课堂页底部控制栏为 `CaptureScreens.kt` 的 `CaptureControlBar`，多段录音汇总为 `RecordingsSummaryCard`，“全部识别”是 `RecordingViewModel.recognizeAll`。底栏高亮由 `mainDestinationForRoute` 按内容判断。排队数只取当前记录的 `AsrRuntimeSummary.inProgressCount`。
>
> v1.1.0 起：底栏为“录音 / 课程 / AI 会话 / 设置”。“录音”页（`HomeScreens.kt` 的 `RecordHomeScreen`）先开始录制，课程由 `CourseSuggester` 按星期几和时间预选，停止后 `ListenApp` 弹出归档确认并调用 `CourseRepository.moveRecord`（只改 `records.courseId`/自动名称，标记 PENDING）。课堂文字阅读模式在 `TranscriptReading.kt`（仅显示层分段，不改片段数据）；长按/拖动多选内的点按需用 `selectionAwareTap`。主题在 `ui/theme/Palettes.kt`，外观偏好存 DataStore（`theme_palette`、`dark_mode`）。

- `app/src/main/java/com/cmhr/listen/MainActivity.kt`
- `app/src/main/java/com/cmhr/listen/ui/ListenApp.kt`：Compose 导航与依赖装配
- `ui/CourseScreens.kt`：Course、Session、课堂详情、识别模式选择
- `ui/AiScreens.kt`：AI 对话、图片/文件附件和直接拍照
- `ui/SettingsScreens.kt`：STT、AI、VAD、云同步设置
- 根包下各 `*ViewModel.kt`

### 音频与 ASR

- `audio/PcmRecorder.kt`：唯一 AudioRecord 封装
- `audio/VadSegmenter.kt`：实时/离线共用 VAD，包含 EOF `finish()`
- `audio/StreamingWavRecorder.kt`：长录音流式 WAV
- `data/stt/SttApiClient.kt`：Mac ASR HTTP 客户端
- `data/stt/AsrQueueRuntime.kt`：持久队列、Job 提交/轮询、恢复、Segment 落库
- `recording/ClassroomCaptureRuntime.kt`：application-scope 麦克风所有权
- `recording/OfflineRecognitionRuntime.kt`：录音补识别与恢复
- `recording/OfflineWindowPolicy.kt`：5 分钟核心窗口和 frame/时间轴换算
- `ListeningForegroundService.kt`
- `recording/RecognitionForegroundService.kt`

### Room 与同步

- `data/course/ListenDatabase.kt`：Room Entity、DAO、v1→v11 Migration
- `data/course/CourseRepository.kt`
- `data/course/RecordNameGenerator.kt`
- `data/recording/RecordingEntities.kt`
- `data/recording/RecordingDao.kt`
- `data/recording/RecordingRepository.kt`
- `data/sync/SyncModels.kt`
- `data/sync/SyncApiClient.kt`
- `data/sync/SyncRepository.kt`
- `data/settings/AppSettingsRepository.kt`
- `data/settings/EncryptedSecretStore.kt`

### sync-server

- `sync-server/app/main.py`：路由与 Bearer Token
- `sync-server/app/models.py`：SQLAlchemy 模型
- `sync-server/app/schemas.py`：协议模型
- `sync-server/app/sync_service.py`：LWW、Ack 和增量窗口
- `sync-server/app/database.py`：WAL 与 `BEGIN IMMEDIATE`
- `sync-server/tests/test_sync.py`

## 8. Room v11 数据模型

### CourseEntity

Course 使用本地自增 `Long id`，目前不作为独立实体同步。Session 同步载荷携带 `courseName`；另一设备下载时按名称复用或创建本地 Course。

### SessionEntity

Room 历史表名是 `records`：

- `id: Long`：仅本地关系键
- `sessionId: UUID String`：跨设备主键
- `courseId`、`name`、`startedAt`、`endedAt`
- `createdAt`、`updatedAt`、`deleted`、`syncStatus`

新 Session 默认名称由 `RecordNameGenerator` 在创建时生成：`${courseName}-${MM-dd}`，使用设备本地时区。用户手动改名后，结束、重开、补识别和云同步都不能重新覆盖。

### SegmentEntity

Room 历史表名是 `transcript_segments`：

- `id: Long`：仅本地关系键
- `segmentId: UUID String`：跨设备主键
- `sessionId`：父 Session UUID
- `recordId`：父 Session 本地 Long id
- 时间轴、文本/纠正文、sequence 和 ASR 诊断字段
- `createdAt`、`updatedAt`、`deleted`、`syncStatus`

本地新增或编辑必须更新 `updatedAt` 并设为 `PENDING`。远程合并使用独立 DAO 方法写入 `SYNCED`，避免同步回声。

### RecordingEntity / RecordingChunkEntity

二者只属于 Android 本地，不进入同步协议。

Recording 状态：

```text
RECORDING → RECORDED → PROCESSING → COMPLETED
                            ↘ FAILED（可继续）
```

Chunk 状态：

```text
PLANNED | QUEUED | COMPLETED | FAILED | SUBMISSION_UNKNOWN
```

录音位于 `filesDir/recordings`，已排除 Android cloud backup 和 device transfer。

任何 Room schema 变化都必须：

1. 增加数据库版本。
2. 添加显式 Migration。
3. 更新 `ListenDatabaseMigrationTest`。
4. 不使用 destructive migration 规避问题。

## 9. 三条核心业务链路

### 9.1 实时课堂 ASR

```text
AudioRecord
→ PcmRecorder（512 samples/chunk）
→ VadSegmenter
→ CapturedPcmSegment
→ AsrQueueRuntime.persistAndEnqueue()
→ 短 WAV
→ Mac /transcribe
→ /jobs/{jobId}
→ SegmentEntity(PENDING)
```

不要为录音补识别复制另一套队列或改变实时队列的 `SUBMISSION_UNKNOWN` 保守语义。

### 9.2 仅录音 + 稍后补识别

仅录音模式绝不能调用 ASR。它把 PCM 流式写入 `<recordingId>.wav.part`，每约 5 秒同步文件并更新进度；结束时修复 WAV header、原子完成 `.wav` 并改成 `RECORDED`。

补识别：

```text
本地 WAV
→ 5 分钟核心窗口（带 VAD 上下文）
→ 共用 VadSegmenter
→ 请求前持久化稳定 Chunk 边界
→ 逐 Chunk 复用 AsrQueueRuntime
→ 原 Session 下的 SegmentEntity(PENDING)
→ 整个窗口成功后推进 processedFrames
```

失败后保留已成功 Segment。再次点击复用已保存 Chunk，不从头跑完整录音。`chunkId` 是稳定 UUID，可避免大量重复。录音、实时识别和补识别由全局 guard 互斥；Session 删除后不能继续写 Segment。

### 9.3 手动双向同步

```http
POST /api/v1/sync
Authorization: Bearer <SYNC_API_TOKEN>
```

必须保持：

- UUID 为跨设备主键。
- Unix epoch milliseconds。
- Last Write Wins，只有 `incoming.updatedAt > existing.updatedAt` 覆盖。
- 删除是 tombstone，不物理删除。
- 服务端不保存 `syncStatus`。
- `/health` 无鉴权；Token 缺失或错误返回 401。

Android 当前批量（v1.0.4 起，见 `SyncBatchLimits`）：Session 200、Segment 500，且每批按 UTF-8 约 400 KB 封顶；条数上限需保持在 SQLite 999 绑定参数以内。DAO 每次查询当前前 N 条 PENDING，按 `updatedAt, id` 排序，不使用 OFFSET。Session 先于其 Segment 上传。

Ack 只有在 `id`、本次上传的 `updatedAt` 与 `matches=true` 同时满足时才确认；DAO 还会再次检查本地 `updatedAt` 未变化，防止请求期间本地编辑被误标为 `SYNCED`。

每批成功后只确认该批。中途失败时此前批次保持成功，剩余数据保持 PENDING。持久化 `lastSyncAt` 只在所有批次及服务端增量合并都成功后更新。

服务端使用 SQLite `BEGIN IMMEDIATE`，在事务内分配单调 `serverTime` 并写 `serverChangedAt`，返回 `(lastSyncAt, serverTime]`。v1.0.4 / sync-server 1.1.0 起，增量结果不再包含本次请求中 `matches=true` 的行（客户端已持有完全相同的版本），`matches=false` 的行始终返回权威版本。服务端响应头 `X-Listen-Sync-Capabilities: gzip-request` 表示可接受 gzip 请求体，客户端只在看到该声明后才压缩上传。不要用请求到达顺序或客户端 `updatedAt` 代替服务端增量游标。

## 10. AI 拍照附件

v1.0.3 的 AI 会话加号菜单支持直接拍照：

- `AiAttachmentStore` 在 `cacheDir/ai_camera` 创建临时文件和 FileProvider URI。
- `ActivityResultContracts.TakePicture` 启动系统相机。
- 输出继续走已有图片方向校正、缩放和 JPEG 压缩。
- 临时文件处理后清理。

App 未声明 `CAMERA` 权限是有意设计：它调用外部相机 Activity 并授予 FileProvider URI 权限。除非改为 App 内置相机，不要增加无必要权限。

## 11. 正式签名与发版

普通开发不要创建 `keystore.properties`。需要正式发版时，向所有者取得原 keystore 和配置，在仓库根目录创建不提交的文件：

```properties
storeFile=/Users/<user>/secure/path/listen-this-lesson-release.jks
storePassword=...
keyAlias=listen-this-lesson
keyPassword=...
```

然后：

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew connectedDebugAndroidTest
./gradlew assembleRelease

"$ANDROID_HOME/build-tools/<version>/apksigner" verify \
  --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk

shasum -a 256 app/build/outputs/apk/release/app-release.apk
```

发版还需：

1. 更新 `versionCode`、`versionName`。
2. 更新 `CHANGELOG.md` 和 README 当前下载文件名。
3. 在模拟器和至少一台真机安装签名 Release 做 smoke test。
4. 提交代码，创建注释 tag `vX.Y.Z`。
5. 推送分支和 tag。
6. 创建 GitHub Release，上传 APK 和 `.sha256`。

没有原签名材料时必须停在 Debug/测试阶段，明确告诉所有者无法产生可升级的正式包。

## 12. Rainyun 部署边界

Docker 应只监听服务器本机，再由 Nginx/Caddy 提供 HTTPS：

```bash
docker build -t listen-sync .
docker run -d --name listen-sync \
  --restart unless-stopped \
  -p 127.0.0.1:8780:8000 \
  -e SYNC_API_TOKEN='通过环境变量注入' \
  -v /opt/listen-sync-data:/data \
  listen-sync
```

SQLite 数据卷必须持久化。当前部署应保持单 Uvicorn worker，避免 SQLite 多进程写竞争。没有 Rainyun 权限时不要声称已验证线上状态；只能报告本地服务测试结果。

## 13. 当前未实现的功能

不要误认为以下功能已经存在：

- 网页端
- 用户注册、多租户或细粒度权限
- 自动后台云同步
- 音频云同步
- Rainyun 到 Mac 的 ASR Job 队列
- 实时 ASR 失败后自动切换仅录音
- 自动后台补识别
- 自动删除录音

第一版长录音使用 PCM16 WAV，约 115 MB/小时，可靠性优先。补识别使用 `dataSync` 前台服务，长任务仍需关注 Android 系统的运行时间限制。`SUBMISSION_UNKNOWN` 当前会停住提示，不会自动重复 POST。

## 14. 已知文档债务

根目录 `README.md` 仍有以下陈旧内容：

- 安装文件名写的是 `v1.0.1`，当前实际版本为 `v1.0.3`。
- 使用教程没有完整介绍“仅录音 + 稍后补 ASR”。
- AI 会话说明没有写直接拍照。
- 云同步使用说明不够完整。

这些可以作为独立文档提交修正，不要顺便重构业务代码或同步协议。

## 15. 每次修改前后的检查清单

修改前：

- `git status --short`
- 阅读目标模块、现有测试和最近相关提交
- 判断是否影响 Room Migration、同步协议、失败恢复或密钥存储
- 说明准备保持的兼容语义

修改后：

- 本地新增/编辑是否正确更新 `updatedAt/PENDING`
- 远程合并是否仍不会产生同步回声
- Recording 是否仍未进入同步 DTO
- 是否可能一次加载全部 PENDING Segment
- 是否破坏 Session 手动名称
- 是否存在第二个 AudioRecord 或重复补识别任务
- 是否处理 Session 删除和进程中断
- 是否添加/更新相应测试
- 是否运行 unit、lint、instrumentation 和必要的服务端 pytest

交付报告至少写明：

1. 修改了什么行为。
2. 修改了哪些文件。
3. 数据库/协议是否变化。
4. 执行了哪些测试及结果。
5. 哪些真实环境因缺少凭证或设备尚未验证。

