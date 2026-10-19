from __future__ import annotations

from sqlalchemy import Engine, inspect

from .models import Base

# Columns added after 0.1.0. create_all() never alters existing tables.
ADDED_COLUMNS = {
    "courses": {
        "status": "TEXT NOT NULL DEFAULT 'pending'",
        "requestedBy": "INTEGER",
        "requestedAt": "INTEGER",
        "reviewedAt": "INTEGER",
        "reviewNote": "TEXT",
    },
    "readers": {
        "nickname": "TEXT",
        "avatar": "TEXT",
    },
}


def initialize_database(engine: Engine) -> None:
    Base.metadata.create_all(engine)
    with engine.connect() as connection:
        for table, columns in ADDED_COLUMNS.items():
            existing = {column["name"] for column in inspect(connection).get_columns(table)}
            added = [name for name in columns if name not in existing]
            for name in added:
                connection.exec_driver_sql(f"ALTER TABLE {table} ADD COLUMN {name} {columns[name]}")
            if table == "courses" and "status" in added:
                # 0.1.0 reviewed lessons one by one; a course that already has a public lesson
                # was in effect approved, so it stays visible after the upgrade.
                connection.exec_driver_sql(
                    "UPDATE courses SET status = 'approved' WHERE id IN "
                    "(SELECT courseId FROM lessons WHERE publishedRevisionId IS NOT NULL)"
                )
        connection.commit()
