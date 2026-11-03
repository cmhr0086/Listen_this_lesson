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
from .schemas import ConfigSync
from .service import ServiceError, audit, create_uploader, hash_token, now_ms

# A phone binds to an account without typing anything: it shows a short code, an admin reader
# (identified by WeChat) approves the code in the mini program, and the phone then collects a
# device token and the account's service settings. Binding is limited to admin readers because
# the settings hold the private sync token.

PAIRING_TTL_MS = 5 * 60 * 1000
CLAIM_PREFIX = "lc_"
DEVICE_PREFIX = "ld_"
PROVISIONED_UPLOADER_NAME = "听这节课"
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


def authenticate(db: Session, token: str) -> Device | None:
    """A device stops working as soon as it is removed or its account stops being an admin."""
    device = db.scalar(select(Device).where(Device.tokenHash == hash_token(token)))
    if device is None:
        return None
    reader = db.get(Reader, device.readerId)
    return device if reader is not None and reader.isAdmin else None


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
    _provision_uploader(db, stored)

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
    if "sync" not in config and sync_defaults:
        config["sync"] = sync_defaults
    return {"config": config, "version": row.version if row else 0, "updatedAt": row.updatedAt if row else None}


def _provision_uploader(db: Session, stored: dict) -> None:
    # Only when the server has no uploader at all. Otherwise a phone already holds that uploader's
    # token, and a new one would cut that phone off from the lessons it published once it synced.
    if "publish" in stored or db.scalar(select(Uploader.id).limit(1)) is not None:
        return
    _uploader, token = create_uploader(db, PROVISIONED_UPLOADER_NAME)
    stored["publish"] = {"token": token, "schoolName": ""}
