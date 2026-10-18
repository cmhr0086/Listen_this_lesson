# Listen Sync Server

独立的 FastAPI + SQLite 手动同步服务。只同步课堂 Session 与文本 Segment，不包含账号、网页端、音频或后台任务。

## 本地运行

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements-dev.txt
export SYNC_DATABASE_URL=sqlite:///./listen-sync.db
export SYNC_API_TOKEN='replace-with-a-long-random-token'
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

健康检查 `GET /health` 无需鉴权。同步接口 `POST /api/v1/sync` 必须携带：

```http
Authorization: Bearer <SYNC_API_TOKEN>
```

`SYNC_API_TOKEN` 未配置、请求未携带 Token 或 Token 错误时，服务端统一返回 HTTP 401。Token 只从环境变量读取，不要写入镜像或代码仓库。

## Rainyun / Docker 部署

```bash
docker build -t listen-sync .
docker run -d --name listen-sync \
  --restart unless-stopped \
  -p 127.0.0.1:8780:8000 \
  -e SYNC_API_TOKEN='replace-with-a-long-random-token' \
  -v /opt/listen-sync-data:/data \
  listen-sync
```

容器只绑定服务器本机 `127.0.0.1:8780`，请通过 Nginx 或 Caddy 反向代理并提供 HTTPS 后再填入 Android App，不要在 Rainyun 防火墙中直接开放 8780。SQLite 数据文件必须挂载到持久卷；容器固定使用单个 Uvicorn worker，避免 SQLite 多进程写入竞争。

当前 Bearer Token 是公网部署前的单 Token 最小鉴权，不是用户注册或多租户权限系统。建议使用密码管理器生成并保存足够长的随机 Token，并通过 Rainyun 环境变量注入。

## 协议与冲突规则

- 跨设备主键为 UUID 字符串。
- 所有时间戳为 Unix epoch milliseconds。
- `updatedAt` 只用于 Last Write Wins：仅当 incoming 严格大于 existing 时覆盖。
- 删除是 `deleted=true` tombstone，不执行物理删除。
- `syncStatus` 只存在于 Android 本地数据库。
- App 1.3.0 起新增可选字段：Session 的 `topic`（课堂主题）与 Segment 的 `marked`（重点）。旧客户端不发送它们仍可同步；客户端未设置时也不发送，因此 1.3.0 客户端可以连接旧服务端，只是不同步重点与主题。已有数据库在服务启动时由 `initialize_database` 自动补列，无需手动迁移。
- `lastSyncAt/serverTime` 是服务端变更游标。内部 `serverChangedAt` 与 SQLite `BEGIN IMMEDIATE` 事务共同保证 `(lastSyncAt, serverTime]` 增量窗口不会遗漏并发提交。

## 测试

```bash
pytest -q
```

## 公开发布服务（listen-publish）

同一镜像里的第二个服务，入口 `publish.main:app`，使用独立的数据库文件，从不打开个人同步库。设计见 [docs/PUBLIC_READER_BACKEND.md](../docs/PUBLIC_READER_BACKEND.md)。

| 前缀 | 鉴权 | 用途 |
|---|---|---|
| `/api/public/v1` | 无；读者 Token 可选 | 课程、课堂、详情、搜索、TXT 导出，只返回已批准且未下架的版本；原文只给获准读者。小程序读者在这里登录 |
| `/api/publish/v1` | 上传者 Token | Android 提交版本、查询审核状态、申请撤回 |
| `/api/admin/v1`、`/admin` | `PUBLISH_ADMIN_TOKEN` | 审核、下架/恢复、开通上传者；`/admin` 是适配手机的审核页 |

```bash
docker run -d --name listen-publish \
  --restart unless-stopped \
  -p 127.0.0.1:8781:8000 \
  -e PUBLISH_ADMIN_TOKEN='replace-with-a-long-random-token' \
  -e PUBLISH_CORS_ORIGINS='https://reader.example.com' \
  -v /opt/listen-publish-data:/data \
  listen-sync \
  uvicorn publish.main:app --host 0.0.0.0 --port 8000 --workers 1
```

- `PUBLISH_ADMIN_TOKEN` 未配置时管理接口一律 401。上传者 Token 在 `/admin` 开通或重置时只显示一次，服务端只存 SHA-256。
- `WECHAT_APPID`、`WECHAT_SECRET` 是小程序的 AppID 和 AppSecret，用于读者登录（`POST /api/public/v1/auth/wechat`）。未配置时登录返回 503，读者仍可匿名看笔记。课堂原文只给在 `/admin`「原文权限」里允许的读者。AppSecret 和管理员 Token 一样只放在 env 文件里。
- `PUBLISH_CORS_ORIGINS` 仅网页阅读端需要（逗号分隔）；小程序不受 CORS 限制，但要在小程序后台配置 request / downloadFile 合法域名。
- `PUBLISH_TIMEZONE` 控制 TXT 中的时间显示，默认 `Asia/Shanghai`。
