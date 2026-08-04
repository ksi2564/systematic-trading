from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="WALLANT_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    environment: str = "local"
    build_sha: str = "development"
    app_name: str = "Wall-Ant Trading v2"
    api_prefix: str = "/api/v2"
    database_url: str = "sqlite+pysqlite:///./wallant-v2.db"
    parquet_root: Path = Path("./data/market")
    execution_enabled: bool = False
    broker_adapter: str = "disabled"
    credential_master_key: str | None = Field(default=None, repr=False)
    allowed_access_emails: tuple[str, ...] = ()
    discord_enabled: bool = False
    discord_bot_token: str | None = Field(default=None, repr=False)
    discord_guild_id: int | None = None
    discord_channel_id: int | None = None
    discord_allowed_user_ids: tuple[int, ...] = ()

    @property
    def is_production(self) -> bool:
        return self.environment.lower() == "production"

    def validate_execution_safety(self) -> None:
        if self.execution_enabled:
            raise RuntimeError("v2 MVP에서는 실제 주문 실행을 활성화할 수 없습니다.")
        if self.broker_adapter.strip().lower() != "disabled":
            raise RuntimeError("v2 MVP에서는 비활성 브로커 어댑터만 사용할 수 있습니다.")
        if self.is_production and self.database_url.startswith("sqlite"):
            raise RuntimeError("운영 환경에서는 SQLite를 사용할 수 없습니다.")
        if self.is_production and not self.allowed_access_emails:
            raise RuntimeError("운영 환경에는 Cloudflare Access 이메일 허용 목록이 필요합니다.")
        if self.is_production and (
            len(self.build_sha) != 40
            or any(character not in "0123456789abcdef" for character in self.build_sha)
        ):
            raise RuntimeError("운영 환경에는 소문자 40자 Git build SHA가 필요합니다.")
        if self.discord_enabled and (
            not self.discord_bot_token
            or not self.discord_guild_id
            or not self.discord_channel_id
            or not self.discord_allowed_user_ids
        ):
            raise RuntimeError("Discord Gateway 활성화에는 토큰과 서버·채널·사용자 허용 목록이 필요합니다.")


@lru_cache
def get_settings() -> Settings:
    settings = Settings()
    settings.validate_execution_safety()
    return settings
