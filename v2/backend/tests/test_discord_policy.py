import asyncio
from unittest.mock import AsyncMock

import discord

from wallant.alerts.discord_gateway import (
    DiscordCommand,
    DiscordCommandContext,
    DiscordCommandPolicy,
    DiscordGatewayBot,
)
from wallant.config import Settings


def policy() -> DiscordCommandPolicy:
    return DiscordCommandPolicy(
        Settings(
            discord_guild_id=10,
            discord_channel_id=20,
            discord_allowed_user_ids=(30,),
        )
    )


def test_허용된_서버_채널_사용자의_정지명령만_허용한다() -> None:
    allowed, _reason = policy().authorize(
        DiscordCommand.PAUSE_ALL,
        DiscordCommandContext(guild_id=10, channel_id=20, user_id=30),
    )
    assert allowed


def test_다른_사용자의_명령은_거부한다() -> None:
    allowed, reason = policy().authorize(
        DiscordCommand.STATUS,
        DiscordCommandContext(guild_id=10, channel_id=20, user_id=999),
    )
    assert not allowed
    assert "사용자" in reason


def test_discord에는_재개_명령이_정의되어_있지_않다() -> None:
    assert {command.value for command in DiscordCommand} == {
        "STATUS",
        "PAUSE_ACCOUNT",
        "PAUSE_ALL",
    }


def test_gateway_명령을_허용된_서버에_등록한다() -> None:
    class Actions:
        async def status(self) -> str:
            return "정상"

        async def pause_account(self, account_id: str, reason: str, actor: str) -> str:
            return "정지"

        async def pause_all(self, reason: str, actor: str) -> str:
            return "전체 정지"

        async def audit_denied(self, command: str, actor: str, reason: str) -> None:
            return None

    async def verify() -> None:
        bot = DiscordGatewayBot(
            Settings(
                discord_guild_id=10,
                discord_channel_id=20,
                discord_allowed_user_ids=(30,),
            ),
            Actions(),
        )
        bot.tree.sync = AsyncMock(return_value=[])
        await bot.setup_hook()
        commands = {
            command.name for command in bot.tree.get_commands(guild=discord.Object(id=10))
        }
        await bot.close()
        assert commands == {
            "wallant-status",
            "wallant-pause-account",
            "wallant-pause-all",
        }

    asyncio.run(verify())
