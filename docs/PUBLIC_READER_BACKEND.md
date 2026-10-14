# 公开阅读端后端设计（草案）

状态：B1（后端代码与测试）已在分支 `feature/publish-server` 实现，尚未部署。依据：S3 需求文档（仅参考）、现有 `sync-server/`（FastAPI + SQLite）。

## 1. 总体结构

```
Android ──(个人同步 Token)──▶ listen-sync     /api/v1/sync         sync.db      （不动）
Android ──(上传者 Token)────▶ listen-publish  /api/publish/v1/*    publish.db
管理员  ──(管理员 Token)────▶ listen-publish  /api/admin/v1/*, /admin
小程序/网页 ──(匿名, 只读)──▶ listen-publish  /api/public/v1/*
```

- **新开一个服务 `listen-publish`**：和同步服务在同一个仓库目录（`sync-server/` 下新增 `publish/` 包），共用一个镜像，但启动入口、容器、数据库文件都分开。
  - 为什么分开：公开接口要面向匿名用户，私人同步库根本不在这个进程里，就不可能因为查询写错把私人数据读到公开结果里。
  - 公开流量或审核功能出问题，也不会影响同步服务。
- 技术栈沿用现有的：FastAPI、SQLAlchemy 2、SQLite（WAL 模式，单 worker）、pytest。规模按 20 人以下设计，不引入 PostgreSQL，也不引入搜索引擎。
- 部署：新增容器 `listen-publish`，只监听 `127.0.0.1:8781`，数据卷挂在 `/opt/listen-publish-data`，前面由现有的反向代理提供 HTTPS。

## 2. 核心概念

| 概念 | 说明 |
|---|---|
| 上传者 uploader | 管理员人工开通。每人一个上传 Token（服务端只存哈希），附带一个公开昵称 |
| 课程 course | 由「学校 + 学期 + 课程名 + 教师」四项共同确定，四项规范化后唯一 |
| 公开课堂 lesson | 由「上传者 + Android 的 sessionId」确定。同一节课再次提交，就是这节课的新版本 |
| 版本 revision | 每次提交生成一个**不可修改的快照**，包括标题、主题、时间、片段和所选笔记。审核的对象是版本 |
| 公开版本 | 每节课的 `publishedRevisionId`。公众端读取、搜索、导出时只用这个版本 |

### 版本状态

```
提交 ──▶ pending ──批准──▶ approved（成为公开版本，旧公开版本变为 superseded）
            │
            ├──驳回──▶ rejected（附驳回理由；有旧公开版本时继续显示旧版）
            └──同一节课又提交了新版本──▶ superseded（审核队列里每节课最多保留一个待审版本）
```

课堂另有一个状态：`active` 或 `taken_down`。管理员可以下架或恢复。生产者只能“申请撤回”，不能自己直接下架。

## 3. 数据表（publish.db）

```text
uploaders        id, displayName, tokenHash, tokenHint(后4位), status(active|disabled), createdAt
courses          id, schoolName, term, courseName, teacherName, normKey UNIQUE, createdAt
lessons          id(UUID, 公开ID), uploaderId, sessionId, courseId, publishedRevisionId?,
                 status(active|taken_down), withdrawRequestedAt?, withdrawReason?,
                 createdAt, updatedAt            UNIQUE(uploaderId, sessionId)
revisions        id, lessonId, number, status, clientSubmissionId UNIQUE, contentHash,
                 courseId,  -- 提交时选的课程；批准后同步到 lessons.courseId
                 title, topic?, startedAt, endedAt?,
                 submittedAt, reviewedAt?, reviewNote?
revision_segments revisionId, seq, startTime, endTime, text, correctedText?, marked
revision_notes   revisionId, position, noteId, title, content, format(plain|markdown)
audit_log        id, at, actor(uploader:<id>|admin), action, target, detail
```

- 版本中的片段和笔记都是**复制进来的快照**。Android 之后再怎么改本地记录、怎么个人同步，都不会影响已经提交或已经公开的版本。
- 提交时只接收公开需要的字段。不接收音频、ASR 诊断（asrJobId、各项耗时、模型名）、AI 提示词、推理过程，也不接收 deviceId。
- 课程的 `normKey` 生成方式：四项分别做 NFKC 规范化、去掉首尾空白、压缩连续空白、大小写折叠，再拼接。同名课程只要教师或学期不同，就算不同课程。

## 4. 接口

### 4.1 公开只读 `/api/public/v1`（匿名，只有 GET 请求）

| 接口 | 返回 |
|---|---|
| `GET /courses?school=&term=` | 课程列表：四项信息、公开课堂数、最近一节的时间。只统计有公开版本、且状态为 active 的课堂 |
| `GET /courses/{courseId}/lessons?cursor=` | 课堂列表（按开始时间倒序）：标题、主题、日期、时长、上传者昵称、笔记数、重点数 |
| `GET /lessons/{lessonId}` | 课堂详情：课程信息、标题、主题、时间、`segments[]`（`displayText` 优先取纠正后的文字，`marked`）、`notes[]`、公开版本更新时间 |
| `GET /search?q=&courseId=&cursor=` | 搜索命中：lessonId、课程和课堂标题、命中类型（`segment` 或 `note`）、定位信息（片段 seq 或笔记 noteId 加偏移）、带前后文的摘要 |
| `GET /lessons/{lessonId}/export.txt?parts=transcript,notes` | UTF-8 编码的 TXT，网页和小程序用同一份格式 |

- 下架的课堂、没有公开版本的课堂，在所有入口都返回 404。分页用 cursor，只按条数分页，不靠“没返回”来判断数据已被删除。
- 搜索第一版直接对**当前公开版本**的片段和笔记做 `LIKE` 查询。规模在千节课以内都够用；以后数据量大了，再换成 FTS5 trigram 索引。
  - `LIKE` 不受“中文至少 3 个字”的限制，搜两个字也能命中。
- **TXT 由服务端生成**：网页直接下载；小程序用 `wx.downloadFile` 下载，再交给保存或转发，两端格式自然一致。
- 响应带 `ETag` 和较短的 `Cache-Control`。网页跨域访问用 CORS 白名单控制；小程序不受 CORS 限制，但请求的域名要在小程序后台配置。

### 4.2 上传 `/api/publish/v1`（上传者 Bearer Token）

| 接口 | 说明 |
|---|---|
| `GET /me` | 返回昵称和资格状态，Android 的“测试连接”用这个 |
| `GET /courses?q=` | 已有课程列表，供选择，减少同一门课被填成几种写法 |
| `POST /submissions` | 提交一个版本（格式见下），返回 `lessonId`、`revision`、`status=pending` |
| `GET /submissions?sessionId=` | 这节课各个版本的状态和驳回理由，Android 课堂页据此显示“待审核 / 已公开 / 已驳回” |
| `POST /lessons/{lessonId}/withdraw-request` | 申请撤回，需写理由，由管理员处理 |

```jsonc
// POST /submissions   （gzip 可选，沿用现有压缩中间件）
{
  "clientSubmissionId": "uuid",          // 幂等：网络重试不会生成重复版本
  "course": { "schoolName": "", "term": "", "courseName": "", "teacherName": "" },
  "session": { "sessionId": "uuid", "name": "", "topic": null, "startedAt": 0, "endedAt": 0 },
  "segments": [ { "startTime": 0, "endTime": 0, "text": "", "correctedText": null, "marked": false } ],
  "notes": [ { "noteId": "<sessionId>:<aiResultId>", "title": "", "content": "", "format": "markdown" } ]
}
```

- 校验规则：
  - 上传者必须是 active 状态；
  - 字段必须完整；
  - 请求体解压后不超过 5 MB，片段不超过 5000 条，单份笔记不超过 20 万字；
  - 每个上传者每小时最多提交 30 次。
- 提交时，如果同一节课已经有一个待审核版本，旧的那个改成 `superseded`，审核队列里每节课只保留最新一版。
- 如果和当前公开版本的 `contentHash` 完全相同，直接返回“内容无变化”，不进入审核队列。

### 4.3 管理 `/api/admin/v1` 和 `/admin` 页面（管理员 Token，从环境变量读取）

| 接口 | 说明 |
|---|---|
| `GET /revisions?status=pending` | 审核队列 |
| `GET /revisions/{id}` | 版本全文，同时附上当前公开版本，方便对比 |
| `POST /revisions/{id}/approve`、`/reject {note}` | 批准或驳回，写入审核日志 |
| `POST /lessons/{id}/takedown`、`/restore` | 下架或恢复，同时处理撤回申请 |
| `POST /uploaders` | 开通上传者，**Token 只在这时显示一次** |
| `POST /uploaders/{id}/disable`、`/rotate-token` | 停用上传者、重置 Token |

- `/admin` 是一个服务端自带的单页（HTML 加 fetch），按手机屏幕设计，管理员可以在手机上审核。读者在网页和小程序里都看不到它。

## 5. Android 侧需要配合的改动（之后做，版本号 1.4.x）

- 设置里新增「公开发布」：服务地址、上传者 Token、测试连接，入口和个人同步分开。
- 课堂页新增「公开发布」流程：
  1. 确认课程四项（按本地课程记住上次填的内容）；
  2. 勾选要公开的已完成笔记；
  3. 预览；
  4. 提交；
  5. 之后在课堂页显示审核状态和驳回理由。
- `noteId` 用 `sessionId:aiResultId` 拼成。sessionId 本身是 UUID，所以全局唯一，Android 端不需要新建表。

## 6. 分期

| 阶段 | 内容 | 完成标准 |
|---|---|---|
| B1 | publish 服务：表结构、上传接口、管理接口、公开接口（含搜索和 TXT）、`/admin` 页面、pytest | 用测试数据跑通“提交 → 待审 → 批准 → 匿名可读 → 改版仍显示旧版 → 批准新版 → 下架”；私人同步库与公开库互不访问 |
| B2 | 部署：容器、HTTPS、备份（每天 `sqlite3 .backup`） | 从公网匿名访问 `/api/public/v1/courses` 成功 |
| A1 | Android 公开发布流程 | 用 1 节真实课堂跑通整个闭环 |
| M1 | 小程序阅读端（重新设计） | 真机能看目录和课堂详情，能搜索、复制，能导出 TXT |

## 7. 需要确认的决定

1. **小程序要用的域名**：小程序的 `request` 和 `downloadFile` 只能访问后台配置过的 HTTPS 域名。服务器如果在中国大陆，这个域名还必须完成 ICP 备案。现在同步服务用的是哪个域名？有没有备案？
2. **学期格式**：可以自由填写（规范化后比对），也可以让用户选“学年 + 学期”（例如 `2026-2027 第一学期`）。建议用选择的方式，避免同一学期出现几种写法。
3. **哪些信息公开**：教师姓名和上传者昵称会出现在公开页面上，这样可以接受吗？
4. **小程序主体与类目**：小程序注册主体是个人还是组织？个人主体能选的服务类目有限，“展示用户上传的内容”可能需要特定类目或额外的内容安全审核。这一点需要按微信当前规则核实，后端可以预留接入内容安全检测的位置。
