from __future__ import annotations

import os
from collections.abc import Iterator

from sqlalchemy import Engine, create_engine, event
from sqlalchemy.orm import Session

from .models import Base


def create_sync_engine(database_url: str | None = None) -> Engine:
    url = database_url or os.getenv("SYNC_DATABASE_URL", "sqlite:////data/listen-sync.db")
    engine = create_engine(
        url,
        connect_args={"check_same_thread": False, "timeout": 30},
    )

    @event.listens_for(engine, "connect")
    def configure_sqlite(dbapi_connection, _connection_record) -> None:
        dbapi_connection.isolation_level = None
        cursor = dbapi_connection.cursor()
        cursor.execute("PRAGMA foreign_keys=ON")
        cursor.execute("PRAGMA journal_mode=WAL")
        cursor.execute("PRAGMA busy_timeout=30000")
        cursor.close()

    @event.listens_for(engine, "begin")
    def begin_immediate(connection) -> None:
        # Serializes writers before the sync cutoff is allocated, so the
        # returned (lastSyncAt, serverTime] window cannot miss a committed row.
        connection.exec_driver_sql("BEGIN IMMEDIATE")

    return engine


def initialize_database(engine: Engine) -> None:
    Base.metadata.create_all(engine)


def session_provider(engine: Engine) -> Iterator[Session]:
    with Session(engine) as session:
        yield session
