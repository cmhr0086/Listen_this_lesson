from __future__ import annotations

import json
import secrets
import urllib.error
import urllib.parse
import urllib.request

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from .models import Reader, ReaderSession, ReaderTimetable
from .service import ServiceError, audit, hash_token, now_ms

# Readers sign in with wx.login. Notes stay public; transcripts are shown only to readers on the
# admin-approved allowlist, which is global (all courses) and in practice holds one or two people.

SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000
SESSION_PREFIX = "lr_"
CODE2SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session"


def code_to_openid(appid: str, secret: str, code: str) -> str:
    """Exchanges a wx.login code for the user's openid; the session_key is not kept."""
    query = urllib.parse.urlencode({"appid": appid, "secret": secret, "js_code": code, "grant_type": "authorization_code"})
    try:
        with urllib.request.urlopen(f"{CODE2SESSION_URL}?{query}", timeout=10) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, ValueError) as error:
        raise ServiceError(502, "微信登录服务暂时不可用，请稍后再试") from error
    openid = payload.get("openid")
    if not openid:
        # 40029 invalid code, 40163 code already used, 45011 rate limited.
        raise ServiceError(401, f"微信登录失败（{payload.get('errcode', '未知错误')}），请重新进入小程序")
    return openid


def sign_in(db: Session, openid: str) -> tuple[Reader, str, int]:
    at = now_ms()
    reader = db.scalar(select(Reader).where(Reader.openid == openid))
    if reader is None:
        reader = Reader(openid=openid, transcriptAccess="none", createdAt=at, lastSeenAt=at)
        db.add(reader)
        db.flush()
    reader.lastSeenAt = at
    db.execute(delete(ReaderSession).where(ReaderSession.expiresAt < at))
    token = SESSION_PREFIX + secrets.token_urlsafe(32)
    expires = at + SESSION_TTL_MS
    db.add(ReaderSession(tokenHash=hash_token(token), readerId=reader.id, createdAt=at, expiresAt=expires))
    db.commit()
    return reader, token, expires


def authenticate(db: Session, token: str) -> Reader | None:
    session = db.get(ReaderSession, hash_token(token))
    if session is None or session.expiresAt < now_ms():
        return None
    return db.get(Reader, session.readerId)


def can_read_transcripts(reader: Reader | None) -> bool:
    return reader is not None and reader.transcriptAccess == "approved"


def request_access(db: Session, reader: Reader, note: str | None) -> Reader:
    if reader.transcriptAccess not in ("approved", "pending"):
        reader.transcriptAccess = "pending"
        reader.requestedAt = now_ms()
    reader.requestNote = note
    audit(db, f"reader:{reader.id}", "reader.request_transcripts", str(reader.id), note)
    db.commit()
    return reader


def decide(db: Session, reader: Reader, approved: bool) -> Reader:
    reader.transcriptAccess = "approved" if approved else "revoked"
    reader.reviewedAt = now_ms()
    audit(db, "admin", "reader.approve" if approved else "reader.revoke", str(reader.id))
    db.commit()
    return reader


def reader_dict(reader: Reader) -> dict:
    return {"readerId": reader.id, "transcriptAccess": reader.transcriptAccess, "requestedAt": reader.requestedAt}


def admin_reader_dict(reader: Reader) -> dict:
    return {
        **reader_dict(reader),
        # Enough to tell people apart in the admin list without showing the whole openid.
        "openidHint": reader.openid[:4] + "…" + reader.openid[-4:],
        "requestNote": reader.requestNote,
        "reviewedAt": reader.reviewedAt,
        "createdAt": reader.createdAt,
        "lastSeenAt": reader.lastSeenAt,
    }


# ---- timetable sync --------------------------------------------------------------------

MAX_TIMETABLE_BYTES = 256 * 1024


def timetable(db: Session, reader: Reader) -> dict:
    row = db.get(ReaderTimetable, reader.id)
    if row is None:
        return {"timetable": None, "updatedAt": None}
    return {"timetable": json.loads(row.data), "updatedAt": row.updatedAt}


def save_timetable(db: Session, reader: Reader, data: dict, updated_at: int) -> dict:
    """Last write wins by the phone's change time; an older copy gets the stored one back (409)."""
    encoded = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    if len(encoded.encode()) > MAX_TIMETABLE_BYTES:
        raise ServiceError(413, "课表太大，无法同步")
    row = db.get(ReaderTimetable, reader.id)
    if row is not None and row.updatedAt > updated_at:
        raise TimetableConflict(timetable(db, reader))
    if row is None:
        row = ReaderTimetable(readerId=reader.id, data=encoded, updatedAt=updated_at, savedAt=now_ms())
        db.add(row)
    else:
        row.data, row.updatedAt, row.savedAt = encoded, updated_at, now_ms()
    db.commit()
    return {"updatedAt": updated_at}


class TimetableConflict(Exception):
    def __init__(self, current: dict) -> None:
        super().__init__("timetable changed elsewhere")
        self.current = current
