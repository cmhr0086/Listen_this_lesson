from __future__ import annotations

from copy import deepcopy
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.main import create_app

API_TOKEN = "test-sync-token"


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("SYNC_API_TOKEN", API_TOKEN)
    app = create_app(f"sqlite:///{tmp_path / 'sync-test.db'}")
    with TestClient(app) as value:
        yield value


def session_payload(*, session_id: str | None = None, updated_at: int = 1_000, deleted: bool = False):
    return {
        "sessionId": session_id or str(uuid4()),
        "courseName": "高等数学",
        "name": "第一课",
        "startedAt": 900,
        "endedAt": None,
        "createdAt": 900,
        "updatedAt": updated_at,
        "deleted": deleted,
    }


def segment_payload(*, session_id: str, segment_id: str | None = None, updated_at: int = 1_100, deleted: bool = False):
    return {
        "segmentId": segment_id or str(uuid4()),
        "sessionId": session_id,
        "startTime": 910,
        "endTime": 990,
        "audioDurationMs": 80,
        "recognitionDurationMs": 30,
        "text": "原始识别文本",
        "correctedText": None,
        "correctedAt": None,
        "sourceSegmentId": None,
        "sequenceNumber": 1,
        "asrJobId": None,
        "queueDurationMs": 10,
        "uploadDurationMs": 5,
        "responseWaitDurationMs": 15,
        "totalAsrDurationMs": 30,
        "serverModel": "test-model",
        "createdAt": 990,
        "updatedAt": updated_at,
        "deleted": deleted,
    }


def sync(client: TestClient, *, device_id: str, last_sync_at: int = 0, sessions=None, segments=None):
    response = client.post(
        "/api/v1/sync",
        headers={"Authorization": f"Bearer {API_TOKEN}"},
        json={
            "deviceId": device_id,
            "lastSyncAt": last_sync_at,
            "sessions": sessions or [],
            "segments": segments or [],
        },
    )
    assert response.status_code == 200, response.text
    return response.json()


def test_sync_without_token_returns_401(client):
    response = client.post(
        "/api/v1/sync",
        json={"deviceId": str(uuid4()), "lastSyncAt": 0, "sessions": [], "segments": []},
    )

    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"


def test_sync_with_wrong_token_returns_401(client):
    response = client.post(
        "/api/v1/sync",
        headers={"Authorization": "Bearer wrong-token"},
        json={"deviceId": str(uuid4()), "lastSyncAt": 0, "sessions": [], "segments": []},
    )

    assert response.status_code == 401


def test_sync_with_correct_token_succeeds(client):
    response = client.post(
        "/api/v1/sync",
        headers={"Authorization": f"Bearer {API_TOKEN}"},
        json={"deviceId": str(uuid4()), "lastSyncAt": 0, "sessions": [], "segments": []},
    )

    assert response.status_code == 200


def test_health_remains_public(client):
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_missing_server_token_fails_closed(tmp_path, monkeypatch):
    monkeypatch.delenv("SYNC_API_TOKEN", raising=False)
    app = create_app(f"sqlite:///{tmp_path / 'missing-token.db'}")
    with TestClient(app) as value:
        response = value.post(
            "/api/v1/sync",
            headers={"Authorization": "Bearer any-client-token"},
            json={"deviceId": str(uuid4()), "lastSyncAt": 0, "sessions": [], "segments": []},
        )

    assert response.status_code == 401


def test_new_session_and_segment_upload_then_second_device_download(client):
    device_a = str(uuid4())
    device_b = str(uuid4())
    session = session_payload()
    segment = segment_payload(session_id=session["sessionId"])

    uploaded = sync(client, device_id=device_a, sessions=[session], segments=[segment])

    assert uploaded["sessionAcks"] == [{"id": session["sessionId"], "updatedAt": 1_000, "matches": True}]
    assert uploaded["segmentAcks"] == [{"id": segment["segmentId"], "updatedAt": 1_100, "matches": True}]
    assert uploaded["sessions"] == [session]
    assert uploaded["segments"] == [segment]

    downloaded = sync(client, device_id=device_b)
    assert downloaded["sessions"] == [session]
    assert downloaded["segments"] == [segment]


def test_segment_modification_and_soft_deletes_are_incremental(client):
    device = str(uuid4())
    session = session_payload()
    segment = segment_payload(session_id=session["sessionId"])
    initial = sync(client, device_id=device, sessions=[session], segments=[segment])

    changed_segment = deepcopy(segment)
    changed_segment.update(updatedAt=2_000, correctedText="纠正后的文本", correctedAt=2_000)
    changed = sync(
        client,
        device_id=device,
        last_sync_at=initial["serverTime"],
        segments=[changed_segment],
    )
    assert changed["segments"] == [changed_segment]

    deleted_session = {**session, "updatedAt": 3_000, "deleted": True}
    deleted_segment = {**changed_segment, "updatedAt": 3_100, "deleted": True}
    tombstones = sync(
        client,
        device_id=device,
        last_sync_at=changed["serverTime"],
        sessions=[deleted_session],
        segments=[deleted_segment],
    )
    assert tombstones["sessions"][0]["deleted"] is True
    assert tombstones["segments"][0]["deleted"] is True


def test_older_client_never_overwrites_newer_server_and_receives_authority(client):
    device = str(uuid4())
    session = session_payload(updated_at=5_000)
    sync(client, device_id=device, sessions=[session])

    stale = {**session, "name": "旧名称", "updatedAt": 4_000}
    response = sync(client, device_id=device, last_sync_at=10**15, sessions=[stale])

    assert response["sessionAcks"] == [{"id": session["sessionId"], "updatedAt": 5_000, "matches": False}]
    assert response["sessions"][0]["name"] == "第一课"
    assert response["sessions"][0]["updatedAt"] == 5_000


def test_newer_client_overwrites_older_server(client):
    device = str(uuid4())
    session = session_payload(updated_at=1_000)
    first = sync(client, device_id=device, sessions=[session])
    newer = {**session, "name": "更新名称", "updatedAt": 2_000}

    response = sync(client, device_id=device, last_sync_at=first["serverTime"], sessions=[newer])

    assert response["sessionAcks"][0]["matches"] is True
    assert response["sessions"][0]["name"] == "更新名称"


def test_repeating_identical_sync_is_idempotent(client):
    device = str(uuid4())
    session = session_payload()
    segment = segment_payload(session_id=session["sessionId"])
    first = sync(client, device_id=device, sessions=[session], segments=[segment])

    repeated = sync(
        client,
        device_id=device,
        last_sync_at=first["serverTime"],
        sessions=[session],
        segments=[segment],
    )
    empty_delta = sync(client, device_id=device, last_sync_at=repeated["serverTime"])

    assert repeated["sessionAcks"][0]["matches"] is True
    assert repeated["segmentAcks"][0]["matches"] is True
    assert len(repeated["sessions"]) == 1
    assert len(repeated["segments"]) == 1
    assert empty_delta["sessions"] == []
    assert empty_delta["segments"] == []


def test_unknown_segment_parent_rejects_whole_request(client):
    segment = segment_payload(session_id=str(uuid4()))
    response = client.post(
        "/api/v1/sync",
        headers={"Authorization": f"Bearer {API_TOKEN}"},
        json={
            "deviceId": str(uuid4()),
            "lastSyncAt": 0,
            "sessions": [],
            "segments": [segment],
        },
    )
    assert response.status_code == 422


def test_equal_timestamp_with_different_content_is_not_acknowledged(client):
    device = str(uuid4())
    session = session_payload(updated_at=1_000)
    sync(client, device_id=device, sessions=[session])
    divergent = {**session, "name": "同时间戳但内容不同"}

    response = sync(client, device_id=device, last_sync_at=10**15, sessions=[divergent])

    assert response["sessionAcks"][0]["matches"] is False
    assert response["sessions"][0]["name"] == "第一课"


def test_server_change_cursor_does_not_miss_late_arrival_with_old_business_timestamp(client):
    device_a = str(uuid4())
    device_b = str(uuid4())
    first = session_payload(updated_at=10_000)
    first_response = sync(client, device_id=device_a, sessions=[first])

    # This different entity arrives later but carries an older device clock.
    late = session_payload(updated_at=10)
    sync(
        client,
        device_id=device_a,
        last_sync_at=first_response["serverTime"],
        sessions=[late],
    )
    downloaded = sync(
        client,
        device_id=device_b,
        last_sync_at=first_response["serverTime"],
    )

    assert [item["sessionId"] for item in downloaded["sessions"]] == [late["sessionId"]]
