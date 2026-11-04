from __future__ import annotations

import json
import shutil
import urllib.error
import urllib.request
from pathlib import Path

from . import materials

# What the admin console's storage page shows. Sizes are read off the disk each time; the sync
# service reports its own, since this service has no access to the private sync database.


def _size(path: Path) -> int:
    return path.stat().st_size if path.exists() else 0


def sync_stats(base_url: str | None, token: str | None) -> dict | None:
    """None when the sync service is unset, down, or older than this endpoint."""
    if not (base_url and token):
        return None
    request = urllib.request.Request(base_url.rstrip("/") + "/api/v1/stats", headers={"Authorization": f"Bearer {token}"})
    try:
        with urllib.request.urlopen(request, timeout=8) as response:
            return json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, ValueError):
        return None


def report(database_path: str, sync_url: str | None, sync_token: str | None) -> dict:
    db = Path(database_path)
    backups = sorted(db.parent.glob("backup-*.db"))
    material_files = [p for p in materials.directory().glob("*") if p.is_file()]
    sync = sync_stats(sync_url, sync_token)
    parts = [
        {"key": "publish", "label": "公开数据库", "bytes": sum(_size(p) for p in (db, db.with_name(db.name + "-wal"), db.with_name(db.name + "-shm")))},
        {"key": "materials", "label": "课程资料文件", "bytes": sum(_size(p) for p in material_files), "count": len(material_files)},
        {"key": "backups", "label": "部署前备份", "bytes": sum(_size(p) for p in backups), "count": len(backups)},
    ]
    if sync is not None:
        parts.insert(0, {"key": "sync", "label": "云同步数据库", "bytes": sync["dbBytes"]})
    disk = shutil.disk_usage(db.parent)
    return {
        "totalBytes": sum(p["bytes"] for p in parts),
        "parts": parts,
        "disk": {"totalBytes": disk.total, "usedBytes": disk.used, "freeBytes": disk.free},
        "sync": sync,
    }
