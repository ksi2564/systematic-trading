from __future__ import annotations

import base64
import json
import os
from dataclasses import dataclass

from cryptography.hazmat.primitives.ciphers.aead import AESGCM


@dataclass(frozen=True, slots=True)
class EncryptedCredential:
    nonce: bytes
    ciphertext: bytes
    key_version: int = 1


class CredentialCipher:
    def __init__(self, encoded_master_key: str, *, key_version: int = 1) -> None:
        try:
            key = base64.urlsafe_b64decode(encoded_master_key.encode("ascii"))
        except Exception as exc:
            raise ValueError("자격증명 마스터 키는 URL-safe base64여야 합니다.") from exc
        if len(key) != 32:
            raise ValueError("AES-256-GCM 마스터 키는 32바이트여야 합니다.")
        self._aes = AESGCM(key)
        self.key_version = key_version

    def encrypt(self, account_id: str, payload: dict[str, str]) -> EncryptedCredential:
        nonce = os.urandom(12)
        plaintext = json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")
        aad = self._aad(account_id, self.key_version)
        ciphertext = self._aes.encrypt(nonce, plaintext, aad)
        return EncryptedCredential(nonce=nonce, ciphertext=ciphertext, key_version=self.key_version)

    def decrypt(self, account_id: str, encrypted: EncryptedCredential) -> dict[str, str]:
        aad = self._aad(account_id, encrypted.key_version)
        plaintext = self._aes.decrypt(encrypted.nonce, encrypted.ciphertext, aad)
        value = json.loads(plaintext.decode("utf-8"))
        if not isinstance(value, dict) or not all(
            isinstance(key, str) and isinstance(item, str) for key, item in value.items()
        ):
            raise ValueError("복호화된 자격증명 형식이 올바르지 않습니다.")
        return value

    @staticmethod
    def generate_key() -> str:
        return base64.urlsafe_b64encode(AESGCM.generate_key(bit_length=256)).decode("ascii")

    @staticmethod
    def _aad(account_id: str, key_version: int) -> bytes:
        return f"wallant:v2:credential:{account_id}:v{key_version}".encode()
