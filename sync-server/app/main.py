from __future__ import annotations

import os
import secrets
from collections.abc import Iterator
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException, Response
from starlette.middleware.gzip import GZipMiddleware
from fastapi.security.utils import get_authorization_scheme_param
from sqlalchemy import Engine
from sqlalchemy.orm import Session

from .compression import CAPABILITIES_HEADER, GzipRequestMiddleware
from .database import create_sync_engine, initialize_database, session_provider
from .schemas import SyncRequest, SyncResponse
from .sync_service import synchronize


def create_app(database_url: str | None = None) -> FastAPI:
    engine = create_sync_engine(database_url)
    api_token = os.getenv("SYNC_API_TOKEN")

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        initialize_database(engine)
        yield
        engine.dispose()

    app = FastAPI(title="Listen Sync Server", version="1.1.0", lifespan=lifespan)
    app.state.engine = engine
    # Outermost first: inflate gzip request bodies before routing, compress large responses.
    app.add_middleware(GZipMiddleware, minimum_size=1024)
    app.add_middleware(GzipRequestMiddleware)

    def get_session() -> Iterator[Session]:
        yield from session_provider(engine)

    def require_sync_token(authorization: Annotated[str | None, Header()] = None) -> None:
        scheme, credentials = get_authorization_scheme_param(authorization)
        valid = (
            api_token is not None
            and bool(api_token)
            and scheme.lower() == "bearer"
            and secrets.compare_digest(credentials, api_token)
        )
        if not valid:
            raise HTTPException(
                status_code=401,
                detail="Invalid or missing bearer token.",
                headers={"WWW-Authenticate": "Bearer"},
            )

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/api/v1/sync", response_model=SyncResponse)
    def sync(
        request: SyncRequest,
        response: Response,
        _authorized: None = Depends(require_sync_token),
        db: Session = Depends(get_session),
    ) -> SyncResponse:
        # Advertised so clients only start gzip-compressing uploads once this server can read them.
        response.headers[CAPABILITIES_HEADER] = "gzip-request"
        try:
            return synchronize(db, request)
        except ValueError as error:
            raise HTTPException(status_code=422, detail=str(error)) from error

    return app


app = create_app()
