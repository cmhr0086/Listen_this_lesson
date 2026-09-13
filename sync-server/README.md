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
- `lastSyncAt/serverTime` 是服务端变更游标。内部 `serverChangedAt` 与 SQLite `BEGIN IMMEDIATE` 事务共同保证 `(lastSyncAt, serverTime]` 增量窗口不会遗漏并发提交。

## 测试

```bash
pytest -q
```
