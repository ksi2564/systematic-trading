from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from verify_c0_gate import (
    C0_B_CAPTURE_ITEMS,
    C0_B_COLLECTION_APPROVAL_ITEM,
    C0_B_COLLECTION_SCOPE,
    C0_B_ITEM_IDS,
    C0_B_PENDING_STATES,
    GateError,
    _artifact,
    _decision_sections,
    _protected_c2_tree_digest,
    _validate_c2_source_diff,
    _validate_safe_evidence_text,
    verify_repository,
)


class VerifyC0GateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source_root = Path(__file__).resolve().parents[3]
        cls.source_c0 = (
            cls.source_root / "docs/v2-cutover/C0_DECISIONS.md"
        ).read_text(encoding="utf-8")
        cls.source_trace = (
            cls.source_root / "docs/v2-cutover/DELIVERY_TRACE.md"
        ).read_text(encoding="utf-8")
        cls.source_board = (
            cls.source_root / "docs/v2-cutover/review-board/app.js"
        ).read_text(encoding="utf-8")

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        (self.root / "docs/v2-cutover").mkdir(parents=True)
        self.c0 = self.source_c0
        self.trace = self.source_trace
        self.board = self.source_board
        self._write_documents()

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def _write_documents(self) -> None:
        (self.root / "docs/v2-cutover/C0_DECISIONS.md").write_text(
            self.c0, encoding="utf-8"
        )
        (self.root / "docs/v2-cutover/DELIVERY_TRACE.md").write_text(
            self.trace, encoding="utf-8"
        )
        board_path = self.root / "docs/v2-cutover/review-board/app.js"
        board_path.parent.mkdir(parents=True, exist_ok=True)
        board_path.write_text(self.board, encoding="utf-8")

    def _approve_decision(self, decision_id: str, choice_index: int = 0) -> None:
        choice = _decision_sections(self.c0)[decision_id][1][choice_index]
        pattern = re.compile(
            rf"(^## {re.escape(decision_id)}\. .*?^결정 상태: )`승인 대기`$",
            flags=re.MULTILINE | re.DOTALL,
        )
        self.c0, replacements = pattern.subn(r"\1`조건부 승인`", self.c0, count=1)
        self.assertEqual(replacements, 1)
        pending_row = f"| {decision_id} | - | 승인 대기 | - | - | - |"
        approved_row = (
            f"| {decision_id} | {choice} | 조건부 승인 | iny | "
            "2026-08-05T23:10:00+09:00 | "
            f"문서={'a' * 40}; 코드={'b' * 40} |"
        )
        self.assertIn(pending_row, self.c0)
        self.c0 = self.c0.replace(pending_row, approved_row, 1)
        approved_count = sum(
            1
            for state, _ in _decision_sections(self.c0).values()
            if state == "조건부 승인"
        )
        board_state = "조건부 승인" if approved_count == 10 else "부분 검토"
        self.board, board_replacements = re.subn(
            r'(c0A: ")[^"]+(",)',
            rf"\g<1>{board_state}\g<2>",
            self.board,
            count=1,
        )
        self.assertEqual(board_replacements, 1)

    def _approve_all(self) -> None:
        for number in range(1, 11):
            self._approve_decision(f"D-{number:02d}")

    def _set_trace_status(
        self, gate: str, state: str, *, sync_board: bool = True
    ) -> None:
        lines = self.trace.splitlines()
        changed = 0
        for index, line in enumerate(lines):
            if not line.startswith("|"):
                continue
            cells = line.split("|")
            if len(cells) == 11 and cells[1].strip() == gate:
                cells[8] = f" {state} "
                lines[index] = "|".join(cells)
                changed += 1
        self.assertEqual(changed, 1)
        self.trace = "\n".join(lines) + ("\n" if self.trace.endswith("\n") else "")
        if sync_board and gate in {"D-03", "C2 미래 화면", "C2 종합"}:
            board_lines = self.board.splitlines()
            board_changes = 0
            for index, line in enumerate(board_lines):
                values = re.findall(r'"([^"\\]*)"', line)
                if len(values) != 7 or values[0] != gate:
                    continue
                suffix = f'", "{values[5]}", "{values[6]}"],'
                self.assertTrue(line.endswith(suffix))
                board_lines[index] = line[: -len(suffix)] + (
                    f'", "{state}", "{values[6]}"],'
                )
                board_changes += 1
            self.assertEqual(board_changes, 1)
            self.board = "\n".join(board_lines) + (
                "\n" if self.board.endswith("\n") else ""
            )

    def _set_trace_approval(self, gate: str, approval: str) -> None:
        lines = self.trace.splitlines()
        changed = 0
        for index, line in enumerate(lines):
            if not line.startswith("|"):
                continue
            cells = line.split("|")
            if len(cells) == 11 and cells[1].strip() == gate:
                cells[9] = f" {approval} "
                lines[index] = "|".join(cells)
                changed += 1
        self.assertEqual(changed, 1)
        self.trace = "\n".join(lines) + ("\n" if self.trace.endswith("\n") else "")

    def _set_c2_state(self, state: str) -> None:
        self.c0, c0_replacements = re.subn(
            r'C2 개발 상태: `[^`]+`',
            f'C2 개발 상태: `{state}`',
            self.c0,
            count=1,
        )
        self.assertEqual(c0_replacements, 1)
        self.board, board_replacements = re.subn(
            r'(c2: ")[^"]+(",)',
            rf"\g<1>{state}\g<2>",
            self.board,
            count=1,
        )
        self.assertEqual(board_replacements, 1)

    def _write_json_artifact(
        self, relative_path: str, payload: dict[str, object]
    ) -> str:
        path = self.root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        encoded = (json.dumps(payload, ensure_ascii=False, sort_keys=True) + "\n").encode()
        path.write_bytes(encoded)
        return f"{relative_path}#sha256={hashlib.sha256(encoded).hexdigest()}"

    def _approve_c0_b_collection(
        self,
        *,
        scope: str = C0_B_COLLECTION_SCOPE,
        owner: str = "iny",
        timestamp: str = "2026-08-05T23:20:00+09:00",
    ) -> None:
        pending = f"| {C0_B_COLLECTION_APPROVAL_ITEM} | - | - | 승인 대기 | - | - |"
        approved = (
            f"| {C0_B_COLLECTION_APPROVAL_ITEM} | {scope} | - | 수집 승인 | "
            f"{owner} | {timestamp} |"
        )
        self.assertIn(pending, self.c0)
        self.c0 = self.c0.replace(pending, approved, 1)

    def _complete_c0_b(
        self,
        *,
        approve_collection: bool = True,
        captured_at: str = "2026-08-05T23:30:00+09:00",
    ) -> tuple[str, str]:
        if approve_collection:
            self._approve_c0_b_collection()
        snapshot_items: list[dict[str, object]] = []
        diff_items: list[dict[str, object]] = []
        for item in C0_B_CAPTURE_ITEMS:
            pending = C0_B_PENDING_STATES[item]
            pending_row = f"| {item} | - | - | {pending} | - | - |"
            item_id = C0_B_ITEM_IDS[item]
            capture_source = self._write_json_artifact(
                f"docs/v2-cutover/evidence/c0b/items/{item_id}.json",
                {
                    "schema_version": 1,
                    "kind": "c0b_capture",
                    "id": item_id,
                    "captured_at": captured_at,
                    "collector": "read-only-test-fixture",
                    "sanitized": True,
                    "data": {
                        "summary": "sanitized read-only fixture capture",
                        "facts": [
                            {"name": "fixture state", "value": "captured and redacted"}
                        ],
                    },
                },
            )
            capture_sha = capture_source.rsplit("=", 1)[1]
            diff_details_source = self._write_json_artifact(
                f"docs/v2-cutover/evidence/c0b/diff-items/{item_id}.json",
                {
                    "schema_version": 1,
                    "kind": "c0b_diff_detail",
                    "id": item_id,
                    "captured_at": captured_at,
                    "collector": "read-only-test-fixture",
                    "sanitized": True,
                    "result": "NO_DIFF",
                    "summary": "sanitized fixture values match",
                },
            )
            diff_sha = diff_details_source.rsplit("=", 1)[1]
            if item == "Java 실행 코드 SHA":
                captured = (
                    f"git={'c' * 40}; 항목={item_id}; sha256={capture_sha}"
                )
            elif item == "승인 당시 요구사항·명세 문서/v2 코드 SHA":
                captured = (
                    f"문서={'a' * 40}; 코드={'b' * 40}; "
                    f"항목={item_id}; sha256={capture_sha}"
                )
            else:
                captured = f"항목={item_id}; sha256={capture_sha}"
            complete_row = (
                f"| {item} | {captured} | 결과=NO_DIFF; sha256={diff_sha} | "
                f"확인 완료 | iny | {captured_at} |"
            )
            self.assertIn(pending_row, self.c0)
            self.c0 = self.c0.replace(pending_row, complete_row, 1)
            snapshot_items.append(
                {
                    "id": item_id,
                    "label": item,
                    "source": capture_source,
                    "captured_at": captured_at,
                    "sha256": capture_sha,
                }
            )
            diff_items.append(
                {
                    "id": item_id,
                    "result": "NO_DIFF",
                    "details_source": diff_details_source,
                    "details_sha256": diff_sha,
                }
            )

        snapshot_ref = self._write_json_artifact(
            "docs/v2-cutover/evidence/c0b/snapshot.json",
            {
                "schema_version": 1,
                "kind": "c0b_snapshot_manifest",
                "captured_at": "2026-08-05T23:31:00+09:00",
                "document_sha": "a" * 40,
                "code_sha": "b" * 40,
                "items": snapshot_items,
            },
        )
        snapshot_digest = snapshot_ref.rsplit("=", 1)[1]
        diff_ref = self._write_json_artifact(
            "docs/v2-cutover/evidence/c0b/diff.json",
            {
                "schema_version": 1,
                "kind": "c0b_diff",
                "snapshot_manifest_sha256": snapshot_digest,
                "compared_at": "2026-08-05T23:32:00+09:00",
                "result": "NO_DIFF",
                "items": diff_items,
            },
        )
        final_pending = "| C0-B 최종 bundle | - | - | 재승인 대기 | - | - |"
        final_complete = (
            f"| C0-B 최종 bundle | {snapshot_ref} | {diff_ref} | 재승인 완료 | "
            "iny | 2026-08-05T23:40:00+09:00 |"
        )
        self.assertIn(final_pending, self.c0)
        self.c0 = self.c0.replace(final_pending, final_complete, 1)
        return snapshot_ref, diff_ref

    def _verify(self) -> None:
        self._write_documents()
        verify_repository(self.root, allow_non_git=True)

    def test_current_pending_record_passes(self) -> None:
        self._verify()

    def test_partial_c0_a_with_complete_metadata_passes_while_c2_is_unstarted(self) -> None:
        self._approve_decision("D-01")
        self._verify()

    def test_approved_d02_data_semantics_choice_passes_while_c2_is_unstarted(self) -> None:
        self._approve_decision("D-02", choice_index=2)
        self._verify()

    def test_approved_d06_improvement_difference_choice_passes_while_c2_is_unstarted(self) -> None:
        self._approve_decision("D-06", choice_index=2)
        self._verify()

    def test_conditional_approval_missing_owner_fails(self) -> None:
        self._approve_decision("D-01")
        self.c0 = self.c0.replace(
            "| D-01 | A 승인 | 조건부 승인 | iny |",
            "| D-01 | A 승인 | 조건부 승인 | - |",
            1,
        )
        with self.assertRaisesRegex(GateError, "approval owner is required"):
            self._verify()

    def test_invalid_or_non_kst_approval_time_fails(self) -> None:
        self._approve_decision("D-01")
        self.c0 = self.c0.replace(
            "2026-08-05T23:10:00+09:00", "2026-02-30T23:10:00Z", 1
        )
        with self.assertRaisesRegex(GateError, "invalid ISO 8601 timestamp"):
            self._verify()

    def test_section_and_record_state_mismatch_fails(self) -> None:
        self._approve_decision("D-01")
        self.c0 = self.c0.replace(
            "결정 상태: `조건부 승인`", "결정 상태: `승인 대기`", 1
        )
        with self.assertRaisesRegex(GateError, "do not match"):
            self._verify()

    def test_c0_b_cannot_complete_before_all_c0_a_decisions(self) -> None:
        pending = (
            "| Java 실행 코드 SHA | - | - | 캡처 대기 | - | - |"
        )
        complete = (
            f"| Java 실행 코드 SHA | git={'c' * 40}; "
            f"항목=java_code_sha; sha256={'a' * 64} | "
            f"결과=NO_DIFF; sha256={'b' * 64} | 확인 완료 | iny | "
            "2026-08-05T23:30:00+09:00 |"
        )
        self.c0 = self.c0.replace(pending, complete, 1)
        with self.assertRaisesRegex(GateError, "cannot complete before all C0-A"):
            self._verify()

    def test_c0_b_collection_approval_requires_complete_c0_a(self) -> None:
        self._approve_c0_b_collection()
        with self.assertRaisesRegex(GateError, "requires complete C0-A decisions"):
            self._verify()

    def test_c0_b_collection_approval_requires_scope_owner_and_kst_time(self) -> None:
        self._approve_all()
        approved_c0 = self.c0
        cases = (
            (
                {
                    "scope": (
                        "대상=12개; 권한=읽기 전용; 정제=필수; "
                        "저장위치=docs/v2-cutover/evidence/c0b/; "
                        "원문저장=금지; 보존=Git 이력"
                    )
                },
                "scope is incomplete or malformed",
            ),
            (
                {
                    "scope": (
                        "대상=12개; 접근방법=GitHub Actions PROD SSH로 운영 호스트 "
                        "shell 조회·DB SELECT; 권한=읽기 전용; 정제=필수; "
                        "원문저장=금지; 보존=Git 이력"
                    )
                },
                "scope is incomplete or malformed",
            ),
            ({"owner": "-"}, "approval owner is required"),
            ({"timestamp": "2026-08-05T14:20:00Z"}, r"must use the \+09:00 offset"),
            (
                {"timestamp": "2026-08-05T23:00:00+09:00"},
                "cannot predate C0-A approval",
            ),
        )
        for kwargs, expected_error in cases:
            with self.subTest(kwargs=kwargs):
                self.c0 = approved_c0
                self._approve_c0_b_collection(**kwargs)
                with self.assertRaisesRegex(GateError, expected_error):
                    self._verify()

        pending_row = (
            f"| {C0_B_COLLECTION_APPROVAL_ITEM} | - | - | 승인 대기 | - | - |"
        )
        invalid_rows = (
            (
                (
                    f"| {C0_B_COLLECTION_APPROVAL_ITEM} | {C0_B_COLLECTION_SCOPE} | - | "
                    "승인 대기 | - | - |"
                ),
                "pending row must contain only placeholders",
            ),
            (
                (
                    f"| {C0_B_COLLECTION_APPROVAL_ITEM} | {C0_B_COLLECTION_SCOPE} | - | "
                    "확인 완료 | iny | 2026-08-05T23:20:00+09:00 |"
                ),
                "unsupported state",
            ),
            (
                (
                    f"| {C0_B_COLLECTION_APPROVAL_ITEM} | {C0_B_COLLECTION_SCOPE} | "
                    "결과=NO_DIFF | 수집 승인 | iny | 2026-08-05T23:20:00+09:00 |"
                ),
                "difference must be a placeholder",
            ),
        )
        for invalid_row, expected_error in invalid_rows:
            with self.subTest(invalid_row=invalid_row):
                self.c0 = approved_c0.replace(pending_row, invalid_row, 1)
                with self.assertRaisesRegex(GateError, expected_error):
                    self._verify()

    def test_c0_b_capture_requires_prior_collection_approval(self) -> None:
        self._approve_all()
        self._complete_c0_b(approve_collection=False)
        with self.assertRaisesRegex(GateError, "requires prior collection approval"):
            self._verify()

    def test_c0_b_capture_cannot_predate_collection_approval(self) -> None:
        self._approve_all()
        self._approve_c0_b_collection(timestamp="2026-08-05T23:25:00+09:00")
        self._complete_c0_b(
            approve_collection=False,
            captured_at="2026-08-05T23:20:00+09:00",
        )
        with self.assertRaisesRegex(GateError, "cannot predate collection approval"):
            self._verify()

    def test_c2_progress_without_c0_b_reapproval_fails(self) -> None:
        self._set_c2_state("진행 중")
        self._set_trace_status("D-03", "부분")
        self._set_trace_status("C2 종합", "부분")
        with self.assertRaisesRegex(GateError, "requires complete C0-A and C0-B"):
            self._verify()

    def test_complete_c0_records_allow_c2_progress(self) -> None:
        self._approve_all()
        self._complete_c0_b()
        self._set_c2_state("진행 중")
        self._set_trace_status("D-03", "부분")
        self._set_trace_status("C2 종합", "부분")
        self._verify()

    def test_snapshot_checksum_mismatch_fails(self) -> None:
        self._approve_all()
        snapshot_ref, _ = self._complete_c0_b()
        digest = snapshot_ref.rsplit("=", 1)[1]
        replacement = ("0" if digest[0] != "0" else "1") + digest[1:]
        self.c0 = self.c0.replace(
            snapshot_ref, snapshot_ref[: -len(digest)] + replacement, 1
        )
        with self.assertRaisesRegex(GateError, "SHA-256 does not match"):
            self._verify()

    def test_diff_artifact_requires_an_explicit_result(self) -> None:
        self._approve_all()
        _, diff_ref = self._complete_c0_b()
        diff_path = self.root / diff_ref.split("#", 1)[0]
        invalid_payload = json.loads(diff_path.read_text(encoding="utf-8"))
        invalid_payload["result"] = "UNKNOWN"
        invalid_ref = self._write_json_artifact(
            "docs/v2-cutover/evidence/c0b/invalid-diff.json", invalid_payload
        )
        self.c0 = self.c0.replace(diff_ref, invalid_ref, 1)
        with self.assertRaisesRegex(GateError, "result must be NO_DIFF or DIFF"):
            self._verify()

    def test_artifact_path_traversal_fails(self) -> None:
        self._approve_all()
        snapshot_ref, _ = self._complete_c0_b()
        digest = snapshot_ref.rsplit("=", 1)[1]
        self.c0 = self.c0.replace(
            snapshot_ref,
            f"docs/v2-cutover/evidence/c0b/../snapshot.json#sha256={digest}",
            1,
        )
        with self.assertRaisesRegex(GateError, "stay inside the repository"):
            self._verify()

    def test_trace_progress_must_match_the_c2_marker(self) -> None:
        original_trace = self.trace
        original_board = self.board
        self._set_trace_status("C2 미래 화면", "부분", sync_board=False)
        with self.assertRaisesRegex(GateError, "marked unstarted"):
            self._verify()

        c2_approval_gates = (
            "D-03",
            "D-04",
            "D-05~06",
            "C2 미래 화면",
            "C2 종합",
        )
        for gate in c2_approval_gates:
            with self.subTest(artifact="delivery trace", gate=gate):
                self.trace = original_trace
                self.board = original_board
                self._set_trace_approval(gate, "C0-A 승인")
                with self.assertRaisesRegex(
                    GateError, "must require C0-B result reapproval"
                ):
                    self._verify()

        for gate in c2_approval_gates:
            with self.subTest(artifact="planning board", gate=gate):
                self.trace = original_trace
                board_lines = original_board.splitlines()
                board_changes = 0
                for index, line in enumerate(board_lines):
                    if line.lstrip().startswith(f'["{gate}",'):
                        self.assertIn('"② 결과 재승인"],', line)
                        board_lines[index] = line.replace(
                            '"② 결과 재승인"],',
                            '"C0-A 승인"],',
                            1,
                        )
                        board_changes += 1
                self.assertEqual(board_changes, 1)
                self.board = "\n".join(board_lines) + (
                    "\n" if original_board.endswith("\n") else ""
                )
                with self.assertRaisesRegex(GateError, "must show result reapproval"):
                    self._verify()

    def test_pending_rows_reject_partial_values(self) -> None:
        self.c0 = self.c0.replace(
            "| D-01 | - | 승인 대기 | - | - | - |",
            "| D-01 | A 승인 | 승인 대기 | - | - | - |",
            1,
        )
        with self.assertRaisesRegex(GateError, "only placeholders"):
            self._verify()

    def test_zero_width_placeholder_fails(self) -> None:
        self.c0 = self.c0.replace(
            "| D-01 | - | 승인 대기 | - | - | - |",
            "| D-01 | -\u200b | 승인 대기 | - | - | - |",
            1,
        )
        with self.assertRaisesRegex(GateError, "zero-width"):
            self._verify()

    def test_board_replies_must_match_the_documented_choices(self) -> None:
        self.board = self.board.replace('reply: "A 승인"', 'reply: "다른 승인"', 1)
        with self.assertRaisesRegex(GateError, "planning board replies must exactly match"):
            self._verify()

    def test_note_required_choice_cannot_be_recorded_as_approval(self) -> None:
        self._approve_decision("D-01", choice_index=1)
        with self.assertRaisesRegex(GateError, "must be resolved and reapproved"):
            self._verify()

    def test_nonrecommended_concrete_alternative_requires_spec_revision(self) -> None:
        self._approve_decision("D-04", choice_index=1)
        with self.assertRaisesRegex(GateError, "must be resolved and reapproved"):
            self._verify()

    def test_board_cannot_remove_a_required_note_to_make_a_request_approvable(self) -> None:
        self.board = self.board.replace(
            ', noteLabel: "원하는 최종 기준"',
            "",
            1,
        )
        with self.assertRaisesRegex(GateError, "static option format"):
            self._verify()

    def test_snapshot_manifest_requires_every_c0_b_item(self) -> None:
        self._approve_all()
        snapshot_ref, _ = self._complete_c0_b()
        snapshot_path = self.root / snapshot_ref.split("#", 1)[0]
        invalid_payload = json.loads(snapshot_path.read_text(encoding="utf-8"))
        invalid_payload["items"] = invalid_payload["items"][:-1]
        invalid_ref = self._write_json_artifact(
            "docs/v2-cutover/evidence/c0b/incomplete-snapshot.json",
            invalid_payload,
        )
        self.c0 = self.c0.replace(snapshot_ref, invalid_ref, 1)
        with self.assertRaisesRegex(GateError, "all C0-B item records are required"):
            self._verify()

    def test_diff_must_reference_the_exact_snapshot_digest(self) -> None:
        self._approve_all()
        _, diff_ref = self._complete_c0_b()
        diff_path = self.root / diff_ref.split("#", 1)[0]
        invalid_payload = json.loads(diff_path.read_text(encoding="utf-8"))
        invalid_payload["snapshot_manifest_sha256"] = "0" * 64
        invalid_ref = self._write_json_artifact(
            "docs/v2-cutover/evidence/c0b/wrong-snapshot-diff.json",
            invalid_payload,
        )
        self.c0 = self.c0.replace(diff_ref, invalid_ref, 1)
        with self.assertRaisesRegex(GateError, "snapshot manifest digest does not match"):
            self._verify()

    def test_snapshot_item_requires_a_real_sanitized_source_artifact(self) -> None:
        self._approve_all()
        snapshot_ref, _ = self._complete_c0_b()
        snapshot_path = self.root / snapshot_ref.split("#", 1)[0]
        invalid_payload = json.loads(snapshot_path.read_text(encoding="utf-8"))
        first_item = invalid_payload["items"][0]
        first_item["source"] = (
            "docs/v2-cutover/evidence/c0b/items/missing.json"
            f"#sha256={first_item['sha256']}"
        )
        invalid_ref = self._write_json_artifact(
            "docs/v2-cutover/evidence/c0b/missing-source-snapshot.json",
            invalid_payload,
        )
        self.c0 = self.c0.replace(snapshot_ref, invalid_ref, 1)
        with self.assertRaisesRegex(GateError, "artifact file does not exist"):
            self._verify()

    def test_diff_item_requires_a_real_sanitized_details_artifact(self) -> None:
        self._approve_all()
        _, diff_ref = self._complete_c0_b()
        diff_path = self.root / diff_ref.split("#", 1)[0]
        invalid_payload = json.loads(diff_path.read_text(encoding="utf-8"))
        first_item = invalid_payload["items"][0]
        first_item["details_source"] = (
            "docs/v2-cutover/evidence/c0b/diff-items/missing.json"
            f"#sha256={first_item['details_sha256']}"
        )
        invalid_ref = self._write_json_artifact(
            "docs/v2-cutover/evidence/c0b/missing-details-diff.json",
            invalid_payload,
        )
        self.c0 = self.c0.replace(diff_ref, invalid_ref, 1)
        with self.assertRaisesRegex(GateError, "artifact file does not exist"):
            self._verify()

    def test_sanitized_evidence_rejects_credentials_and_unmasked_identifiers(
        self,
    ) -> None:
        for unsafe_value in (
            "Bearer abcdefghijklmnopqrstuvwxyz",
            "AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE",
            "KIS_APP_SECRET=S3cure!7",
            "authorization_header=S3cure!7",
            "accessToken=S3cure!7",
            "refreshToken=S3cure!7",
            "idToken=S3cure!7",
            "token_value=S3cure!7",
            "cookie_value=S3cure!7",
            "password_hash=S3cure!7",
            "appkey=S3cure!7",
            "appsecret=S3cure!7",
            "pwd=S3cure!7",
            "account 12345678901",
            "account 1234-5678-01",
            "1234 5678 01",
            "12-345-678-901",
            "user@example.com",
        ):
            with self.subTest(unsafe_value=unsafe_value), self.assertRaisesRegex(
                GateError, "possible credential or unmasked identifier"
            ):
                _validate_safe_evidence_text(unsafe_value, "fixture")

    def test_sanitized_evidence_allows_required_iso_market_dates_and_times(self) -> None:
        for safe_value in (
            "market date 2026-08-05",
            "captured at 2026-08-05T23:30:00+09:00",
            "window 2026-07-29 - 2026-08-05",
        ):
            with self.subTest(safe_value=safe_value):
                self.assertEqual(
                    _validate_safe_evidence_text(safe_value, "fixture"), safe_value
                )

    def test_c0_b_shas_must_match_the_latest_c0_a_approval(self) -> None:
        self._approve_all()
        self._complete_c0_b()
        self.c0 = self.c0.replace(
            f"문서={'a' * 40}; 코드={'b' * 40}",
            f"문서={'d' * 40}; 코드={'e' * 40}",
            10,
        )
        with self.assertRaisesRegex(GateError, "latest C0-A approval"):
            self._verify()

    def test_complete_c0_a_requires_one_final_document_and_code_sha(self) -> None:
        self._approve_all()
        choice = _decision_sections(self.c0)["D-10"][1][0]
        old_row = (
            f"| D-10 | {choice} | 조건부 승인 | iny | "
            "2026-08-05T23:10:00+09:00 | "
            f"문서={'a' * 40}; 코드={'b' * 40} |"
        )
        new_row = (
            f"| D-10 | {choice} | 조건부 승인 | iny | "
            "2026-08-05T23:11:00+09:00 | "
            f"문서={'d' * 40}; 코드={'e' * 40} |"
        )
        self.assertIn(old_row, self.c0)
        self.c0 = self.c0.replace(old_row, new_row, 1)
        with self.assertRaisesRegex(GateError, "every decision to use the same final"):
            self._verify()

    def test_c0_b_completion_cannot_predate_c0_a(self) -> None:
        self._approve_all()
        self._complete_c0_b()
        self.c0 = self.c0.replace(
            "2026-08-05T23:30:00+09:00",
            "2026-08-05T23:00:00+09:00",
            1,
        )
        with self.assertRaisesRegex(GateError, "cannot predate C0-A approval"):
            self._verify()

    def test_final_reapproval_cannot_predate_the_diff(self) -> None:
        self._approve_all()
        self._complete_c0_b()
        self.c0 = self.c0.replace(
            "2026-08-05T23:40:00+09:00",
            "2026-08-05T23:31:00+09:00",
            1,
        )
        with self.assertRaisesRegex(GateError, "cannot predate the completed diff"):
            self._verify()

    def test_duplicate_c0_a_table_header_fails(self) -> None:
        duplicate = (
            "\n| 결정 | 선택 | 상태 | 승인자 | 승인 시각 | 기준 문서/코드 SHA |\n"
            "| --- | --- | --- | --- | --- | --- |\n"
            "| D-01 | - | 승인 대기 | - | - | - |\n"
        )
        self.c0 = self.c0.replace("\n## C0-B", duplicate + "\n## C0-B", 1)
        with self.assertRaisesRegex(GateError, "exactly one exact table header"):
            self._verify()

    def test_fenced_c0_a_table_is_not_accepted_as_a_visible_record(self) -> None:
        self.c0 = self.c0.replace(
            "| 결정 | 선택 | 상태 | 승인자 | 승인 시각 | 기준 문서/코드 SHA |",
            "```text\n| 결정 | 선택 | 상태 | 승인자 | 승인 시각 | 기준 문서/코드 SHA |",
            1,
        )
        self.c0 = self.c0.replace(
            "| D-10 | - | 승인 대기 | - | - | - |",
            "| D-10 | - | 승인 대기 | - | - | - |\n```",
            1,
        )
        with self.assertRaisesRegex(GateError, "cannot be fenced or commented out"):
            self._verify()

    def test_midline_html_comment_cannot_hide_the_c0_a_table(self) -> None:
        header = "| 결정 | 선택 | 상태 | 승인자 | 승인 시각 | 기준 문서/코드 SHA |"
        self.c0 = self.c0.replace(header, f"visible-prefix<!--\n{header}", 1)
        self.c0 = self.c0.replace(
            "| D-10 | - | 승인 대기 | - | - | - |",
            "| D-10 | - | 승인 대기 | - | - | - |\nhidden-suffix-->",
            1,
        )
        with self.assertRaisesRegex(GateError, "commented out"):
            self._verify()

    def test_hidden_html_wrapper_cannot_hide_the_c0_a_table(self) -> None:
        header = "| 결정 | 선택 | 상태 | 승인자 | 승인 시각 | 기준 문서/코드 SHA |"
        self.c0 = self.c0.replace(header, f'<div hidden>\n{header}', 1)
        self.c0 = self.c0.replace(
            "| D-10 | - | 승인 대기 | - | - | - |",
            "| D-10 | - | 승인 대기 | - | - | - |\n</div>",
            1,
        )
        with self.assertRaisesRegex(GateError, "wrapped in HTML"):
            self._verify()

    def test_comment_opened_before_the_c0_section_cannot_hide_records(self) -> None:
        self.c0 = self.c0.replace(
            "## C0-A 제품 결정 기록",
            "<!--\n## C0-A 제품 결정 기록",
            1,
        )
        with self.assertRaisesRegex(GateError, "cannot be commented out"):
            self._verify()

    def test_fence_opened_before_the_c0_section_cannot_hide_records(self) -> None:
        self.c0 = self.c0.replace(
            "## C0-A 제품 결정 기록",
            "```text\n## C0-A 제품 결정 기록",
            1,
        )
        with self.assertRaisesRegex(GateError, "cannot be fenced"):
            self._verify()

    def test_any_hidden_html_wrapper_cannot_hide_c0_records(self) -> None:
        self.c0 = self.c0.replace(
            "## C0-A 제품 결정 기록",
            '<article hidden>\n## C0-A 제품 결정 기록',
            1,
        )
        with self.assertRaisesRegex(GateError, "wrapped in HTML"):
            self._verify()

    def test_multiline_html_start_tag_cannot_hide_c0_records(self) -> None:
        for opener in ('<article\nhidden>', '<div\nstyle="display:none">', '<dialog\nopen="false">'):
            with self.subTest(opener=opener):
                original = self.c0
                self.c0 = self.c0.replace(
                    "## C0-A 제품 결정 기록",
                    f"{opener}\n## C0-A 제품 결정 기록",
                    1,
                )
                with self.assertRaisesRegex(GateError, "wrapped in HTML"):
                    self._verify()
                self.c0 = original

    def test_commonmark_raw_block_cannot_hide_c0_records(self) -> None:
        for opener in ("<![CDATA[", "<?audit", "<!DOCTYPE html", "<![IGNORE["):
            with self.subTest(opener=opener):
                original = self.c0
                self.c0 = self.c0.replace(
                    "## C0-A 제품 결정 기록",
                    f"{opener}\n## C0-A 제품 결정 기록",
                    1,
                )
                with self.assertRaisesRegex(GateError, "wrapped in HTML"):
                    self._verify()
                self.c0 = original

    def test_board_reply_must_be_a_static_string_value(self) -> None:
        self.board = self.board.replace(
            'reply: "A 승인"',
            'reply: /* reply: "A 승인" */ "위조 승인"',
            1,
        )
        with self.assertRaisesRegex(GateError, "JavaScript comments"):
            self._verify()

    def test_board_trace_status_must_be_a_static_string_value(self) -> None:
        self.board = self.board.replace(
            '"미구현", "② 결과 재승인"],',
            '"미구현" && forgedTraceStatus, "② 결과 재승인"],',
            1,
        )
        with self.assertRaisesRegex(GateError, "static seven-string format"):
            self._verify()

    def test_artifact_json_rejects_duplicate_keys_before_sanitization(self) -> None:
        relative_path = "docs/v2-cutover/evidence/c0b/items/duplicate.json"
        artifact_path = self.root / relative_path
        artifact_path.parent.mkdir(parents=True, exist_ok=True)
        payload = (
            b'{"collector":"Bearer REAL-SECRET-MUST-NOT-LEAK",'
            b'"collector":"read-only-test-fixture"}\n'
        )
        artifact_path.write_bytes(payload)
        reference = f"{relative_path}#sha256={hashlib.sha256(payload).hexdigest()}"
        with self.assertRaisesRegex(GateError, "duplicate key: collector"):
            _artifact(self.root, reference, "duplicate fixture")

    def test_c2_complete_is_rejected_until_an_exit_gate_exists(self) -> None:
        self._set_c2_state("완료")
        with self.assertRaisesRegex(GateError, "comprehensive exit gate"):
            self._verify()

    def test_board_state_must_match_the_c0_record(self) -> None:
        self.board = self.board.replace('c0A: "승인 대기"', 'c0A: "부분 검토"', 1)
        with self.assertRaisesRegex(GateError, "C0-A state does not match"):
            self._verify()

    def test_board_c2_trace_status_must_match_the_canonical_trace(self) -> None:
        lines = self.board.splitlines()
        changed = 0
        for index, line in enumerate(lines):
            if line.lstrip().startswith('["C2 종합",'):
                self.assertIn('", "미구현", "② 결과 재승인"],', line)
                lines[index] = line.replace(
                    '", "미구현", "② 결과 재승인"],',
                    '", "부분", "② 결과 재승인"],',
                    1,
                )
                changed += 1
        self.assertEqual(changed, 1)
        self.board = "\n".join(lines) + (
            "\n" if self.board.endswith("\n") else ""
        )
        with self.assertRaisesRegex(GateError, "do not match the delivery trace"):
            self._verify()

    def test_protected_c2_source_change_requires_the_c2_progress_marker(self) -> None:
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        subprocess.run(
            ["git", "config", "user.email", "c0-gate@example.invalid"],
            cwd=self.root,
            check=True,
        )
        subprocess.run(
            ["git", "config", "user.name", "C0 Gate Test"],
            cwd=self.root,
            check=True,
        )
        source = self.root / "v2/backend/src/foundation.py"
        source.parent.mkdir(parents=True)
        source.write_text("FOUNDATION = True\n", encoding="utf-8")
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        subprocess.run(
            ["git", "commit", "-q", "-m", "foundation"],
            cwd=self.root,
            check=True,
        )
        baseline = _protected_c2_tree_digest(self.root)
        source.write_text("FOUNDATION = False\n", encoding="utf-8")
        with self.assertRaisesRegex(GateError, "protected C2 source tree changed"):
            _validate_c2_source_diff(self.root, "미시작", baseline)
        _validate_c2_source_diff(self.root, "진행 중", baseline)

    def test_new_c2_runner_outside_src_is_still_protected(self) -> None:
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        baseline = _protected_c2_tree_digest(self.root)
        runner = self.root / "v2/backend/c2_shadow_runner.py"
        runner.parent.mkdir(parents=True, exist_ok=True)
        runner.write_text("RUNNER_ENABLED = True\n", encoding="utf-8")
        with self.assertRaisesRegex(GateError, "protected C2 source tree changed"):
            _validate_c2_source_diff(self.root, "미시작", baseline)

    def test_ignored_c2_runner_is_rejected(self) -> None:
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        (self.root / ".gitignore").write_text("v2/backend/data/\n", encoding="utf-8")
        runner_root = self.root / "v2/backend/data"
        runner_root.mkdir(parents=True, exist_ok=True)
        for runner_name in (
            "c2_shadow_runner.py",
            "c2_shadow_runner",
            "c2_shadow_runner.rb",
            "c2_shadow_runner.bin",
        ):
            (runner_root / runner_name).write_text(
                "RUNNER_ENABLED = True\n", encoding="utf-8"
            )
        with self.assertRaisesRegex(GateError, "not an approved generated artifact"):
            _protected_c2_tree_digest(self.root)

    def test_git_info_exclude_cannot_hide_unknown_c2_source(self) -> None:
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        exclude_path = self.root / ".git/info/exclude"
        with exclude_path.open("a", encoding="utf-8") as exclude_file:
            exclude_file.write("\nv2/backend/hidden_runner.py\n")
        runner = self.root / "v2/backend/hidden_runner.py"
        runner.parent.mkdir(parents=True, exist_ok=True)
        runner.write_text("RUNNER_ENABLED = True\n", encoding="utf-8")
        with self.assertRaisesRegex(GateError, "not an approved generated artifact"):
            _protected_c2_tree_digest(self.root)

    def test_ignored_noncode_data_does_not_change_the_source_tree_digest(self) -> None:
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        (self.root / ".gitignore").write_text("v2/backend/data/\n", encoding="utf-8")
        baseline = _protected_c2_tree_digest(self.root)
        data_file = self.root / "v2/backend/data/state.parquet"
        data_file.parent.mkdir(parents=True, exist_ok=True)
        data_file.write_bytes(b"sanitized-fixture-data")
        self.assertEqual(_protected_c2_tree_digest(self.root), baseline)

    def test_preapproval_exclusion_near_miss_is_protected(self) -> None:
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        baseline = _protected_c2_tree_digest(self.root)
        near_miss = self.root / "v2/infra/tests/verify_c0_gate.py.bak"
        near_miss.parent.mkdir(parents=True, exist_ok=True)
        near_miss.write_text("BYPASS = True\n", encoding="utf-8")
        with self.assertRaisesRegex(GateError, "protected C2 source tree changed"):
            _validate_c2_source_diff(self.root, "미시작", baseline)

    def test_protected_source_executable_bit_is_part_of_the_tree_digest(self) -> None:
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        source = self.root / "v2/backend/source_marker.txt"
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text("foundation\n", encoding="utf-8")
        source.chmod(0o644)
        baseline = _protected_c2_tree_digest(self.root)
        source.chmod(0o755)
        with self.assertRaisesRegex(GateError, "protected C2 source tree changed"):
            _validate_c2_source_diff(self.root, "미시작", baseline)

    def test_non_git_source_gate_fails_closed_without_test_override(self) -> None:
        with self.assertRaisesRegex(GateError, "requires a Git worktree"):
            _validate_c2_source_diff(self.root, "미시작")


if __name__ == "__main__":
    unittest.main()
