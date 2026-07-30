from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import Protocol

import discord
from discord import app_commands

from wallant.config import Settings


class DiscordCommand(StrEnum):
    STATUS = "STATUS"
    PAUSE_ACCOUNT = "PAUSE_ACCOUNT"
    PAUSE_ALL = "PAUSE_ALL"


@dataclass(frozen=True, slots=True)
class DiscordCommandContext:
    guild_id: int | None
    channel_id: int | None
    user_id: int


class DiscordGatewayActions(Protocol):
    async def status(self) -> str: ...

    async def pause_account(self, account_id: str, reason: str, actor: str) -> str: ...

    async def pause_all(self, reason: str, actor: str) -> str: ...

    async def audit_denied(self, command: str, actor: str, reason: str) -> None: ...


class DiscordCommandPolicy:
    ALLOWED = {
        DiscordCommand.STATUS,
        DiscordCommand.PAUSE_ACCOUNT,
        DiscordCommand.PAUSE_ALL,
    }

    def __init__(self, settings: Settings) -> None:
        self.guild_id = settings.discord_guild_id
        self.channel_id = settings.discord_channel_id
        self.allowed_users = set(settings.discord_allowed_user_ids)

    def authorize(
        self,
        command: DiscordCommand,
        context: DiscordCommandContext,
    ) -> tuple[bool, str]:
        if command not in self.ALLOWED:
            return False, "허용되지 않은 명령입니다."
        if context.guild_id != self.guild_id:
            return False, "허용되지 않은 Discord 서버입니다."
        if context.channel_id != self.channel_id:
            return False, "허용되지 않은 Discord 채널입니다."
        if context.user_id not in self.allowed_users:
            return False, "허용되지 않은 Discord 사용자입니다."
        return True, "허용"


class DiscordGatewayBot(discord.Client):
    """공개 수신 엔드포인트 없이 outbound Gateway WebSocket만 사용한다."""

    def __init__(self, settings: Settings, actions: DiscordGatewayActions) -> None:
        super().__init__(intents=discord.Intents.none())
        self.settings = settings
        self.actions = actions
        self.policy = DiscordCommandPolicy(settings)
        self.tree = app_commands.CommandTree(self)

    async def setup_hook(self) -> None:
        self.tree.add_command(
            app_commands.Command(
                name="wallant-status",
                description="Wall-Ant 계좌와 안전 상태를 조회합니다.",
                callback=self._status,
            )
        )
        self.tree.add_command(
            app_commands.Command(
                name="wallant-pause-account",
                description="지정 계좌를 정지합니다. Discord에서는 재개할 수 없습니다.",
                callback=self._pause_account,
            )
        )
        self.tree.add_command(
            app_commands.Command(
                name="wallant-pause-all",
                description="모든 계좌를 긴급 정지합니다.",
                callback=self._pause_all,
            )
        )
        guild = discord.Object(id=self.settings.discord_guild_id)
        self.tree.copy_global_to(guild=guild)
        await self.tree.sync(guild=guild)

    async def _status(self, interaction: discord.Interaction) -> None:
        if not await self._authorized(DiscordCommand.STATUS, interaction):
            return
        await interaction.response.send_message(await self.actions.status(), ephemeral=True)

    @app_commands.describe(account_id="정지할 Wall-Ant 계좌 ID", reason="정지 이유")
    async def _pause_account(
        self,
        interaction: discord.Interaction,
        account_id: str,
        reason: str,
    ) -> None:
        if not await self._authorized(DiscordCommand.PAUSE_ACCOUNT, interaction):
            return
        actor = f"discord:{interaction.user.id}"
        message = await self.actions.pause_account(account_id, reason, actor)
        await interaction.response.send_message(message, ephemeral=True)

    @app_commands.describe(reason="전체 정지 이유")
    async def _pause_all(self, interaction: discord.Interaction, reason: str) -> None:
        if not await self._authorized(DiscordCommand.PAUSE_ALL, interaction):
            return
        actor = f"discord:{interaction.user.id}"
        message = await self.actions.pause_all(reason, actor)
        await interaction.response.send_message(message, ephemeral=True)

    async def _authorized(
        self,
        command: DiscordCommand,
        interaction: discord.Interaction,
    ) -> bool:
        context = DiscordCommandContext(
            guild_id=interaction.guild_id,
            channel_id=interaction.channel_id,
            user_id=interaction.user.id,
        )
        allowed, reason = self.policy.authorize(command, context)
        if allowed:
            return True
        actor = f"discord:{interaction.user.id}"
        await self.actions.audit_denied(command.value, actor, reason)
        await interaction.response.send_message(reason, ephemeral=True)
        return False

    async def notify(self, message: str) -> None:
        channel = self.get_channel(self.settings.discord_channel_id or 0)
        if not isinstance(channel, discord.abc.Messageable):
            raise RuntimeError("Discord 알림 채널을 찾을 수 없습니다.")
        await channel.send(message)


def run_discord_gateway(settings: Settings, actions: DiscordGatewayActions) -> None:
    if not settings.discord_enabled or not settings.discord_bot_token:
        raise RuntimeError("Discord Gateway가 활성화되지 않았습니다.")
    DiscordGatewayBot(settings, actions).run(settings.discord_bot_token, log_handler=None)
