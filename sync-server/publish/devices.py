from __future__ import annotations

import base64
import binascii
import json
import os
import secrets
from uuid import uuid4

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from .models import AccountConfig, Device, DevicePairing, Reader, Uploader
from .readers import reader_uploader
from .schemas import ConfigSync
from .service import ServiceError, audit, hash_token, now_ms

# A phone binds to an account without typing anything: it shows a short code, an admin reader
# (identified by WeChat) approves the code in the mini program, and the phone then collects a
# device token and the account's service settings. Binding needs an admin or an account allowed
# to upload; only admins are handed the private sync token.

PAIRING_TTL_MS = 5 * 60 * 1000
CLAIM_PREFIX = "lc_"
DEVICE_PREFIX = "ld_"
SEALED_VERSION = "v1"


class ConfigCipher:
    """AES-GCM with the key from the environment, so the database alone never reveals a key.
    The reader id is bound in, so a sealed row copied to another account will not open."""

    def __init__(self, encoded_key: str) -> None:
        try:
            key = base64.urlsafe_b64decode(encoded_key)
        except (binascii.Error, ValueError) as error:
            raise ValueError("PUBLISH_CONFIG_KEY must be base64") from error
        if len(key) != 32:
            raise ValueError("PUBLISH_CONFIG_KEY must decode to 32 bytes")
        self._aead = AESGCM(key)

    def seal(self, reader_id: int, config: dict) -> str:
        nonce = os.urandom(12)
        data = json.dumps(config, ensure_ascii=False, separators=(",", ":")).encode()
        sealed = self._aead.encrypt(nonce, data, str(reader_id).encode())
        return f"{SEALED_VERSION}:{base64.b64encode(nonce + sealed).decode()}"

    def open(self, reader_id: int, sealed: str) -> dict:
        version, _, payload = sealed.partition(":")
        if version != SEALED_VERSION:
            raise ServiceError(500, "云端配置无法读取")
        raw = base64.b64decode(payload)
        try:
            return json.loads(self._aead.decrypt(raw[:12], raw[12:], str(reader_id).encode()))
        except InvalidTag as error:
            raise ServiceError(500, "云端配置无法读取") from error


# ---- pairing ---------------------------------------------------------------------------


def _purge_expired(db: Session) -> None:
    db.execute(delete(DevicePairing).where(DevicePairing.expiresAt < now_ms()))


def create_pairing(db: Session, name: str) -> dict:
    _purge_expired(db)
    active = set(db.scalars(select(DevicePairing.code)))
    code = next(c for c in (f"{secrets.randbelow(1_000_000):06d}" for _ in iter(int, 1)) if c not in active)
    claim = CLAIM_PREFIX + secrets.token_urlsafe(32)
    at = now_ms()
    expires = at + PAIRING_TTL_MS
    db.add(DevicePairing(claimHash=hash_token(claim), code=code, name=name, createdAt=at, expiresAt=expires))
    db.commit()
    return {"code": code, "claim": claim, "expiresAt": expires}


def _waiting_pairing(db: Session, code: str) -> DevicePairing:
    pairing = db.scalar(select(DevicePairing).where(
        DevicePairing.code == code, DevicePairing.readerId.is_(None), DevicePairing.expiresAt >= now_ms()
    ))
    if pairing is None:
        raise ServiceError(404, "绑定码不对或已过期")
    return pairing


def preview_pairing(db: Session, code: str) -> dict:
    pairing = _waiting_pairing(db, code)
    return {"name": pairing.name, "createdAt": pairing.createdAt, "expiresAt": pairing.expiresAt}


def approve_pairing(db: Session, reader: Reader, code: str) -> dict:
    pairing = _waiting_pairing(db, code)
    pairing.readerId = reader.id
    audit(db, f"reader:{reader.id}", "device.approve", pairing.name)
    db.commit()
    return {"name": pairing.name}


def claim_pairing(db: Session, claim: str) -> dict:
    pairing = db.get(DevicePairing, hash_token(claim))
    if pairing is None or pairing.expiresAt < now_ms():
        raise ServiceError(410, "绑定码已过期，请重新获取")
    if pairing.readerId is None:
        return {"status": "pending"}
    token = DEVICE_PREFIX + secrets.token_urlsafe(32)
    at = now_ms()
    device = Device(
        id=str(uuid4()), readerId=pairing.readerId, name=pairing.name, tokenHash=hash_token(token), createdAt=at, lastSeenAt=at
    )
    db.add(device)
    db.delete(pairing)
    db.commit()
    return {"status": "approved", "deviceId": device.id, "deviceToken": token}


# ---- devices ---------------------------------------------------------------------------


def can_bind(reader: Reader) -> bool:
    uploader = reader_uploader(reader)
    return reader.isAdmin or (uploader is not None and uploader.status == "active")


def find(db: Session, token: str) -> Device | None:
    return db.scalar(select(Device).where(Device.tokenHash == hash_token(token)))


def authenticate(db: Session, token: str) -> Device | None:
    """A device stops working as soon as it is removed or its account loses the right to bind."""
    device = find(db, token)
    if device is None:
        return None
    reader = db.get(Reader, device.readerId)
    return device if reader is not None and can_bind(reader) else None


def authenticate_uploader(db: Session, token: str) -> Uploader | None:
    """The uploader a bound phone publishes as; None when the token is no device of an uploader."""
    device = find(db, token)
    reader = db.get(Reader, device.readerId) if device else None
    uploader = reader_uploader(reader) if reader else None
    return uploader if uploader is not None and uploader.status == "active" else None


def admin_reader(db: Session, token: str) -> Reader | None:
    """The admin behind a device token: how the web console signs in."""
    device = find(db, token)
    reader = db.get(Reader, device.readerId) if device else None
    if reader is None or not reader.isAdmin:
        return None
    device.lastSeenAt = now_ms()
    db.commit()
    return reader


def list_devices(db: Session, reader: Reader) -> list[dict]:
    rows = db.scalars(select(Device).where(Device.readerId == reader.id).order_by(Device.lastSeenAt.desc()))
    return [device_dict(device) for device in rows]


def remove(db: Session, device: Device, actor: str) -> None:
    audit(db, actor, "device.remove", device.name)
    db.delete(device)
    db.commit()


def device_dict(device: Device) -> dict:
    return {"deviceId": device.id, "name": device.name, "createdAt": device.createdAt, "lastSeenAt": device.lastSeenAt}


# ---- config ----------------------------------------------------------------------------


def sync_config(db: Session, device: Device, cipher: ConfigCipher, body: ConfigSync, sync_defaults: dict | None) -> dict:
    row = db.get(AccountConfig, device.readerId)
    stored = cipher.open(device.readerId, row.sealed) if row else {}
    before = json.dumps(stored, sort_keys=True)

    changed = body.changed.model_dump(exclude_none=True)
    stored.update(changed)
    for section, value in body.offered.model_dump(exclude_none=True).items():
        stored.setdefault(section, value)
    _link_uploader(db, device.readerId, stored)

    at = now_ms()
    device.lastSeenAt = at
    if json.dumps(stored, sort_keys=True) != before:
        sealed = cipher.seal(device.readerId, stored)
        if row is None:
            row = AccountConfig(readerId=device.readerId, sealed=sealed, version=1, updatedAt=at)
            db.add(row)
        else:
            row.sealed, row.version, row.updatedAt = sealed, row.version + 1, at
        # Which sections, never their values.
        audit(db, f"device:{device.id}", "config.update", device.name, ",".join(sorted(changed)) or None)
    db.commit()

    config = dict(stored)
    if "sync" not in config and sync_defaults and db.get(Reader, device.readerId).isAdmin:
        config["sync"] = sync_defaults
    return {"config": config, "version": row.version if row else 0, "updatedAt": row.updatedAt if row else None}


def replace_publish_token(db: Session, cipher: ConfigCipher, old_hash: str, token: str) -> None:
    """After a token is rotated, accounts holding the old one get the new one, or their phones would stop publishing."""
    for row in db.scalars(select(AccountConfig)):
        stored = cipher.open(row.readerId, row.sealed)
        held = (stored.get("publish") or {}).get("token")
        if held and hash_token(held) == old_hash:
            stored["publish"]["token"] = token
            row.sealed, row.version, row.updatedAt = cipher.seal(row.readerId, stored), row.version + 1, now_ms()
    db.commit()


def _link_uploader(db: Session, reader_id: int, stored: dict) -> None:
    # A phone from before accounts could publish holds an uploader's token. Proving it once ties
    # that uploader, and every lesson it published, to the account.
    token = (stored.get("publish") or {}).get("token")
    reader = db.get(Reader, reader_id)
    if not token or reader.uploaderId:
        return
    uploader = db.scalar(select(Uploader).where(Uploader.tokenHash == hash_token(token)))
    taken = uploader and db.scalar(select(Reader.id).where(Reader.uploaderId == uploader.id))
    if uploader is not None and not taken:
        reader.uploaderId = uploader.id
        audit(db, f"reader:{reader.id}", "uploader.linked", f"uploader:{uploader.id}")
