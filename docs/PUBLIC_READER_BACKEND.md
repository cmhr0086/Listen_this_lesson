# 公开阅读端后端设计

状态：已上线（`https://sync.vmnox.com`，服务端 0.2.1，Android 1.4.0）。审核从最初的“逐个版本审核”改为**按课程审核**，下文按现行设计描述。依据：S3 需求文档（仅参考）、现有 `sync-server/`（FastAPI + SQLite）。

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
| 课程 course | 由「学校 + 学期 + 课程名 + 教师」四项共同确定，四项规范化后唯一。**审核的对象是课程** |
| 公开课堂 lesson | 由「上传者 + Android 的 sessionId」确定。同一节课再次提交，就是这节课的新版本 |
| 版本 revision | 每次提交生成一个**不可修改的快照**，包括标题、主题、时间、片段和笔记。提交后立即成为这节课的公开版本 |
| 公开版本 | 每节课的 `publishedRevisionId`。公众端读取、搜索、导出时只用这个版本 |

### 课程状态

```
第一次上传到这门课 ──▶ pending ──批准──▶ approved ──下架──▶ taken_down ──恢复──▶ approved
                         │
                         └──驳回(附理由)──▶ rejected ──上传者重新申请──▶ pending（保留上次理由，后台标“重新申请”）
                                               └──管理员直接改为批准──▶ approved
```

- 读者能看到一节课，必须同时满足：课堂 `active`、有公开版本、所属课程 `approved`。
- 课程批准后，这门课已上传和以后上传的课堂都直接上线，不再逐节审核；同一节课再提交，新版本直接替换旧版本（旧版本记为 `superseded`）。
- 课堂另有一个状态：`active` 或 `taken_down`。管理员可以下架或恢复单节课。上传者只能“申请撤回”，不能自己直接下架。
- 课程名或教师变了，四项组合就是另一门课程，需要重新审核。

## 3. 数据表（publish.db）

```text
uploaders        id, displayName, tokenHash, tokenHint(后4位), status(active|disabled), createdAt
courses          id, schoolName, term, courseName, teacherName, normKey UNIQUE, createdAt,
                 status(pending|approved|rejected|taken_down), requestedBy?, requestedAt?,
                 reviewedAt?, reviewNote?
lessons          id(UUID, 公开ID), uploaderId, sessionId, courseId, publishedRevisionId?,
                 status(active|taken_down), withdrawRequestedAt?, withdrawReason?,
                 createdAt, updatedAt            UNIQUE(uploaderId, sessionId)
revisions        id, lessonId, number, status, clientSubmissionId UNIQUE, contentHash,
                 courseId,
                 title, topic?, startedAt, endedAt?,
                 submittedAt, reviewedAt?, reviewNote?
revision_segments revisionId, seq, startTime, endTime, text, correctedText?, marked
revision_notes   revisionId, position, noteId, title, content, format(plain|markdown)
audit_log        id, at, actor(uploader:<id>|admin), action, target, detail
```

- 版本中的片段和笔记都是**复制进来的快照**。Android 之后再怎么改本地记录、怎么个人同步，都不会影响已经提交或已经公开的版本。
- 提交时只接收公开需要的字段。不接收音频、ASR 诊断（asrJobId、各项耗时、模型名）、AI 提示词、推理过程，也不接收 deviceId。
- 0.1.0 → 0.2.0 迁移：启动时补齐 `courses` 新列；已有公开课堂的课程直接记为 `approved`。
- 课程的 `normKey` 生成方式：四项分别做 NFKC 规范化、去掉首尾空白、压缩连续空白、大小写折叠，再拼接。同名课程只要教师或学期不同，就算不同课程。

## 4. 接口

### 4.1 公开 `/api/public/v1`（内容只读；读者登录可选）

| 接口 | 返回 |
|---|---|
| `GET /courses?school=&term=` | 课程列表（只含已批准的课程）：四项信息、公开课堂数、最近一节的时间 |
| `GET /courses/{courseId}/lessons?cursor=` | 课堂列表（按开始时间倒序）：标题、主题、日期、时长、上传者昵称、笔记数、重点数 |
| `GET /lessons/{lessonId}` | 课堂详情：课程信息、标题、主题、时间、`notes[]`、公开版本更新时间、`segmentCount`。`segments[]`（`displayText` 优先取纠正后的文字，`marked`）只对获准读者填写，其他人拿到空数组和 `transcriptHidden: true` |
| `GET /search?q=&courseId=&cursor=` | 搜索命中：lessonId、课程和课堂标题、命中类型（`segment` 或 `note`）、定位信息（片段 seq 或笔记 noteId 加偏移）、带前后文的摘要。只有获准读者能搜到 `segment` |
| `GET /lessons/{lessonId}/export.txt?parts=transcript,notes` | UTF-8 编码的 TXT，网页和小程序用同一份格式。不带 `parts` 时给笔记，获准读者再加原文；未获准却要 `transcript` 返回 403 |
| `POST /auth/wechat {code}` | 小程序 `wx.login` 的 code 换 openid（服务端调用 `jscode2session`，不保存 session_key），返回 30 天有效的读者 Token（`lr_…`）和 `transcriptAccess` |
| `GET /me` | 当前读者的 `transcriptAccess`：`none`、`pending`、`approved`、`revoked`；Token 无效或过期返回 401 |
| `POST /me/transcript-access {note?}` | 申请查看原文，可留一句话让管理员认出是谁 |
| `PUT /me/profile {nickname?, avatar?}` | 读者在小程序里用微信头像、昵称填写能力设置的昵称（≤20 字）和头像（已压缩的 JPEG/PNG data URL，≤96 KB），管理员列表里显示 |
| `GET /me/timetable`、`PUT /me/timetable {timetable, updatedAt}` | 读者的课程表，随微信账号同步。按手机上的修改时间 `updatedAt` 后写者胜；上传的比服务器旧时返回 409 并带回服务器那份 |

- **原文只给获准读者**：笔记对所有人公开；课堂原文只给管理员允许的读者（按 openid，全局生效，不分课程）。读者在请求头里带 `Authorization: Bearer lr_…`；Token 缺失、无效或过期都按匿名处理，只给笔记。与读者有关的响应带 `Cache-Control: private, no-cache` 和 `Vary: Authorization`：共享缓存不会把原文给了别人，刚获准的读者下一次打开就能看到原文（内容没变时由 ETag 回 304）。
- 下架的课堂、没有公开版本的课堂、课程未批准的课堂，在所有入口都返回 404。分页用 cursor，只按条数分页，不靠“没返回”来判断数据已被删除。
- 搜索第一版直接对**当前公开版本**的片段和笔记做 `LIKE` 查询。规模在千节课以内都够用；以后数据量大了，再换成 FTS5 trigram 索引。
  - `LIKE` 不受“中文至少 3 个字”的限制，搜两个字也能命中。
- **TXT 由服务端生成**：网页直接下载；小程序用 `wx.downloadFile` 下载，再交给保存或转发，两端格式自然一致。
- 响应带 `ETag` 和较短的 `Cache-Control`。网页跨域访问用 CORS 白名单控制；小程序不受 CORS 限制，但请求的域名要在小程序后台配置。

### 4.2 上传 `/api/publish/v1`（上传者 Bearer Token）

| 接口 | 说明 |
|---|---|
| `GET /me` | 返回昵称和资格状态，Android 的“测试连接”用这个 |
| `GET /courses?q=` | 已有课程列表，供选择，减少同一门课被填成几种写法 |
| `POST /submissions` | 提交一节课（格式见下），返回 `lessonId`、`revision`、`status`（`published` 或 `unchanged`）、`courseId`、`courseStatus`、`courseReviewNote` |
| `GET /submissions?sessionId=` | 这节课各个版本的记录 |
| `GET /courses/{courseId}` | 课程审核状态、驳回理由、课堂数，Android 课程发布页据此显示状态 |
| `GET /courses/{courseId}/lessons` | 这个上传者在这门课里的课堂：状态、版本号、是否申请撤回 |
| `POST /courses/{courseId}/request-review` | 被驳回后重新申请（只有申请人或在该课程有课堂的上传者可以） |
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
  - 请求体不超过 8 MB，片段不超过 5000 条，笔记不超过 50 份、每份不超过 20 万字；
  - 每个上传者每小时最多提交 30 次。
- 第一次有课堂上传到某门课时创建课程，状态为 `pending`，记下申请人和时间。
- 如果和当前公开版本的 `contentHash` 完全相同，直接返回 `unchanged`，不生成新版本。

### 4.3 管理 `/api/admin/v1` 和 `/admin` 页面（管理员 Token 从环境变量读取；管理员读者的 Token 也可以）

| 接口 | 说明 |
|---|---|
| `GET /summary` | 待审课程数、待批原文申请数、撤回申请数（后台标签上的数字） |
| `GET /courses?status=pending\|all\|approved\|rejected\|taken_down` | 课程列表；待审队列按申请时间先后排，`reRequested` 标出重新申请的课程 |
| `GET /courses/{id}` | 课程信息和全部课堂 |
| `POST /courses/{id}/approve`、`/reject {note}`、`/takedown {note}` | 批准、驳回（理由显示给上传者）、下架整门课，写入审核日志 |
| `DELETE /courses/{id}` | 删除已下架或已驳回的课程及其全部课堂和版本，不能撤销；上传者再次发布时作为新课程重新审核 |
| `GET /lessons/{id}` | 课堂全文（原文和笔记），用于预览 |
| `GET /lessons?withdrawRequested=true` | 撤回申请 |
| `POST /lessons/{id}/takedown`、`/restore` | 下架或恢复单节课，下架同时了结撤回申请 |
| `GET /uploaders`、`POST /uploaders` | 上传者列表（含负责的课程、最近上传时间）；开通上传者，**Token 只在这时显示一次** |
| `POST /uploaders/{id}/disable`、`/enable`、`/rotate-token` | 停用、启用上传者、更换 Token |
| `GET /readers?status=` | 小程序读者：openid 只露首尾、申请留言、原文权限、最近登录 |
| `POST /readers/{id}/approve`、`/revoke` | 允许或收回查看原文 |
| `POST /readers/{id}/make-admin`、`/remove-admin` | 把小程序账号设为（或取消）管理员：它的读者 Token 也能调用整个管理接口，小程序「设置 › 管理」里审核；管理员可看全部原文 |
| `GET /audit` | 最近 100 条操作日志 |

- `/admin` 是服务端自带的单页（HTML 加 fetch），电脑上左右分栏，手机上列表和详情分屏，底部固定审核按钮。管理员 Token 只存在浏览器标签页里。读者在网页和小程序里都看不到它。

## 5. Android 端（1.4.0）

- 发布地址内置为 `https://sync.vmnox.com`。“设置 → 公开发布”只填上传 Token（Android Keystore 加密保存）和学校（所有课程共用）。
- 发布以**整门课**为单位，入口在课程页的“公开发布”：
  - 课程名默认用本机名称，可改；每门课只需填教师；学期按每节课的上课日期推算（8 月至次年 1 月为第一学期，2 至 7 月为第二学期）。一门本地课程跨学期时，会对应多门公开课程。
  - 一个按钮按时间顺序上传所有新课堂和有改动的课堂（用提交内容的指纹判断是否改动），显示进度；中途失败就停下，已传的不受影响。
  - 状态卡显示课程审核状态；被驳回时显示理由和“重新申请”。每节课显示上课中、没有文字、未上传、有改动、已上传/已公开、已下架、撤回中。
  - 每节课可以关掉公开；已经公开的会自动提交撤回申请。
- 每节课上传课堂文字（含纠正后的文字和重点标记）和最新一份整节课的“课堂笔记”，其他 AI 结果不上传。
- `noteId` 用 `sessionId:aiResultId` 拼成。sessionId 本身是 UUID，所以全局唯一，Android 端不需要新建表。

## 6. 分期

| 阶段 | 内容 | 完成标准 |
|---|---|---|
| B1 ✅ | publish 服务：表结构、上传接口、管理接口、公开接口（含搜索和 TXT）、`/admin` 页面、pytest | 用测试数据跑通“上传 → 课程待审 → 批准 → 匿名可读 → 更新直接替换 → 下架”；私人同步库与公开库互不访问 |
| B2 ✅ | 部署：容器、HTTPS（服务器磁盘太小，不做定时备份） | 从公网匿名访问 `/api/public/v1/courses` 成功 |
| A1 ✅ | Android 按课程公开发布（1.4.0） | 用 1 节真实课堂跑通整个闭环 |
| M1 | 小程序阅读端（重新设计） | 真机能看目录和课堂详情，能搜索、复制，能导出 TXT |

## 7. 已定与待定

1. **域名**（已定）：`sync.vmnox.com`，已完成 ICP 备案和微信认证，可配置为小程序的 request / downloadFile 合法域名。
2. **学期格式**（已定）：Android 按上课日期自动生成 `2026-2027 第一学期` 这种写法，用户不填。
3. **公开信息**：教师姓名和上传者名称会出现在公开页面上，后台新建上传者时有提示。
4. **小程序主体与类目**（待定）：小程序注册主体是个人还是组织？个人主体能选的服务类目有限，“展示用户上传的内容”可能需要特定类目或额外的内容安全审核。这一点需要按微信当前规则核实，后端可以预留接入内容安全检测的位置。
