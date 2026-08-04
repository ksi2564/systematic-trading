from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from wallant.api import accounts, market_data, operations, research, strategies
from wallant.api.dependencies import get_actor
from wallant.config import Settings, get_settings
from wallant.persistence.database import (
    create_database_engine,
    create_session_factory,
    initialize_local_database,
)


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    settings.validate_execution_safety()
    engine = create_database_engine(settings)
    session_factory = create_session_factory(engine)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        initialize_local_database(engine, settings)
        yield
        engine.dispose()

    app = FastAPI(
        title=settings.app_name,
        version="0.1.0",
        docs_url=f"{settings.api_prefix}/docs" if not settings.is_production else None,
        redoc_url=None,
        openapi_url=f"{settings.api_prefix}/openapi.json" if not settings.is_production else None,
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.engine = engine
    app.state.session_factory = session_factory

    if not settings.is_production:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=["http://127.0.0.1:5173", "http://localhost:5173"],
            allow_credentials=False,
            allow_methods=["*"],
            allow_headers=["*"],
        )

    @app.exception_handler(ValueError)
    async def value_error_handler(_request: Request, exc: ValueError) -> JSONResponse:
        return JSONResponse(status_code=422, content={"detail": str(exc)})

    @app.get("/health", include_in_schema=False)
    def health() -> dict:
        return {
            "status": "UP",
            "execution_enabled": False,
            "broker_adapter": "disabled",
        }

    for router in (
        strategies.router,
        research.router,
        accounts.router,
        market_data.router,
        operations.router,
    ):
        app.include_router(
            router,
            prefix=settings.api_prefix,
            dependencies=[Depends(get_actor)],
        )
    return app


app = create_app()
