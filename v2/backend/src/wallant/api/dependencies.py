from __future__ import annotations

from collections.abc import Generator

from fastapi import Header, HTTPException, Request, status
from sqlalchemy.orm import Session

from wallant.config import Settings


def get_session(request: Request) -> Generator[Session, None, None]:
    session = request.app.state.session_factory()
    try:
        yield session
    finally:
        session.close()


def get_actor(
    request: Request,
    cloudflare_email: str | None = Header(
        default=None,
        alias="Cf-Access-Authenticated-User-Email",
    ),
    local_actor: str | None = Header(default=None, alias="X-Wallant-Actor"),
) -> str:
    settings: Settings = request.app.state.settings
    if not settings.is_production:
        return local_actor or cloudflare_email or "local-user"
    if not cloudflare_email:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Cloudflare Access 인증 헤더가 없습니다.",
        )
    normalized = cloudflare_email.strip().lower()
    allowed = {email.strip().lower() for email in settings.allowed_access_emails}
    if allowed and normalized not in allowed:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="허용되지 않은 Cloudflare Access 사용자입니다.",
        )
    return normalized
