"""Validate the C0 approval record before any C2 progress is claimed.

This verifies record structure and sequencing only. It cannot authenticate that
the named owner actually approved the decision.
"""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
import unicodedata
from datetime import datetime, timedelta
from pathlib import Path, PurePosixPath


class GateError(ValueError):
    """Raised when the C0 documentation gate is incomplete or malformed."""


C0_A_HEADER = ["결정", "선택", "상태", "승인자", "승인 시각", "기준 문서/코드 SHA"]
C0_B_HEADER = ["확인 항목", "캡처 값/checksum", "C0-A와 차이", "상태", "승인자", "승인 시각"]
TRACE_HEADER = [
    "결정·게이트",
    "정본 요구사항",
    "기능·API",
    "화면",
    "QA ID·검증 범위",
    "실행 SHA·run/artifact",
    "증적 링크",
    "상태",
    "다음 필수 승인",
]
C0_B_ITEMS = [
    "Java 실행 코드 SHA",
    "effective 런타임 설정",
    "DB 전략 on/off·파라미터",
    "최신/바로 전 EOD 상태",
    "주문 모드·소유권·수량 설정",
    "production provider·조회 계약/version",
    "가격 종류·시장 기준일·observed/available 시각 의미",
    "KIS base 실제 기준일·VIX spot·MA200 세션/adjustment",
    "신선도·재시도·deadline 최종값",
    "operational 비교 시간창·필드별 값 허용 기준·반올림",
    "승인 당시 요구사항·명세 문서/v2 코드 SHA",
    "C0-A 대비 diff 요약",
    "C0-B 최종 bundle",
]
C0_B_ITEM_IDS = {
    "Java 실행 코드 SHA": "java_code_sha",
    "effective 런타임 설정": "effective_runtime_config",
    "DB 전략 on/off·파라미터": "db_strategy_state",
    "최신/바로 전 EOD 상태": "eod_state_pair",
    "주문 모드·소유권·수량 설정": "order_mode_ownership_quantity",
    "production provider·조회 계약/version": "production_provider_contract",
    "가격 종류·시장 기준일·observed/available 시각 의미": "price_market_time_semantics",
    "KIS base 실제 기준일·VIX spot·MA200 세션/adjustment": "kis_vix_ma200_semantics",
    "신선도·재시도·deadline 최종값": "freshness_retry_deadline",
    "operational 비교 시간창·필드별 값 허용 기준·반올림": "operational_comparison_tolerances",
    "승인 당시 요구사항·명세 문서/v2 코드 SHA": "approval_document_code_sha",
    "C0-A 대비 diff 요약": "c0a_diff_summary",
}
C0_B_PENDING_STATES = {
    **{item: "캡처 대기" for item in C0_B_ITEMS[:9]},
    C0_B_ITEMS[9]: "승인 대기",
    C0_B_ITEMS[10]: "캡처 대기",
    C0_B_ITEMS[11]: "재확인 대기",
    C0_B_ITEMS[12]: "재승인 대기",
}
C0_A_APPROVABLE_CHOICES = {
    "D-01": ("A 승인",),
    "D-02": ("A 승인",),
    "D-03": ("권장안 승인",),
    "D-04": ("A 승인",),
    "D-05": ("A 승인",),
    "D-06": ("A 승인",),
    "D-07": ("향후 QA 범위·보존 기준 동의",),
    "D-08": ("A 승인",),
    "D-09": ("A 승인",),
    "D-10": ("A를 미래 실전 명세로 승인",),
}
GIT_SHA_PATTERN = re.compile(r"[0-9a-f]{40}")
SHA_CELL_PATTERN = re.compile(r"문서=([0-9a-f]{40}); 코드=([0-9a-f]{40})")
CAPTURE_CELL_PATTERN = re.compile(
    r"항목=([a-z0-9_]+); sha256=([0-9a-f]{64})"
)
JAVA_CAPTURE_CELL_PATTERN = re.compile(
    r"git=([0-9a-f]{40}); 항목=(java_code_sha); sha256=([0-9a-f]{64})"
)
APPROVAL_CAPTURE_CELL_PATTERN = re.compile(
    r"문서=([0-9a-f]{40}); 코드=([0-9a-f]{40}); "
    r"항목=(approval_document_code_sha); sha256=([0-9a-f]{64})"
)
DIFF_CELL_PATTERN = re.compile(r"결과=(NO_DIFF|DIFF); sha256=([0-9a-f]{64})")
ARTIFACT_REF_PATTERN = re.compile(
    r"([A-Za-z0-9._/-]+)#sha256=([0-9a-f]{64})"
)
KST_OFFSET = timedelta(hours=9)
C2_SOURCE_BASELINE_TREE_SHA256 = (
    "e0334d51b87d4c9a1a406ee3b908427606247c64317c61565cbfdfa8b894e8b6"
)
C2_PROTECTED_PATHS = (
    "v2/backend",
    "v2/frontend",
    "v2/infra",
)
C2_PREAPPROVAL_EXCLUSIONS = (
    "v2/infra/tests/documentation_contract_test.sh",
    "v2/infra/tests/test_verify_c0_gate.py",
    "v2/infra/tests/verify_c0_gate.py",
)
C2_IGNORED_GENERATED_PREFIXES = (
    "v2/backend/.pytest_cache/",
    "v2/backend/.ruff_cache/",
    "v2/backend/.venv/",
    "v2/backend/htmlcov/",
    "v2/frontend/coverage/",
    "v2/frontend/dist/",
    "v2/frontend/node_modules/",
)
C2_IGNORED_VARIABLE_PREFIXES = (
    "v2/backend/data/",
    "v2/frontend/artifacts/",
    "v2/frontend/test-results/",
)
C2_IGNORED_GENERATED_EXACT = {"v2/backend/.coverage"}
C2_IGNORED_DATA_SUFFIXES = {
    ".arrow",
    ".csv",
    ".db",
    ".feather",
    ".json",
    ".jsonl",
    ".log",
    ".parquet",
    ".sqlite",
    ".sqlite3",
    ".tsv",
}
C2_IGNORED_QA_SUFFIXES = {
    ".css",
    ".html",
    ".js",
    ".json",
    ".log",
    ".md",
    ".png",
    ".svg",
    ".ttf",
    ".txt",
    ".webm",
    ".xml",
    ".zip",
}


def _clean_cell(value: str, context: str) -> str:
    if "\\|" in value:
        raise GateError(f"{context}: escaped table pipes are not allowed")
    for character in value:
        if unicodedata.category(character) in {"Cc", "Cf"}:
            raise GateError(f"{context}: control or zero-width characters are not allowed")
    normalized = unicodedata.normalize("NFKC", value)
    if normalized != value:
        raise GateError(f"{context}: compatibility Unicode characters are not allowed")
    return value.strip()


def _assert_visible_markdown_records(
    text: str,
    markers: tuple[str, ...],
    context: str,
) -> None:
    if "<!--" in text or "-->" in text:
        raise GateError(f"{context}: record markers cannot be commented out")
    marker_set = set(markers)
    visible_markers: set[str] = set()
    fence_character: str | None = None
    fence_length = 0
    for line in text.splitlines():
        fence_match = re.match(r"^\s{0,3}(`{3,}|~{3,})(.*)$", line)
        if fence_match:
            fence = fence_match.group(1)
            trailing = fence_match.group(2)
            if fence_character is None:
                fence_character = fence[0]
                fence_length = len(fence)
            elif (
                fence[0] == fence_character
                and len(fence) >= fence_length
                and not trailing.strip()
            ):
                fence_character = None
                fence_length = 0
            continue

        without_inline_code = re.sub(r"`[^`]*`", "", line)
        if "<" in without_inline_code:
            raise GateError(f"{context}: record markers cannot be wrapped in HTML")
        matching_markers = {
            marker
            for marker in marker_set
            if line == marker or (marker.endswith(".") and line.startswith(marker))
        }
        if matching_markers:
            if fence_character is not None:
                raise GateError(f"{context}: record markers cannot be fenced")
            visible_markers.update(matching_markers)
    if fence_character is not None:
        raise GateError(f"{context}: unclosed Markdown fence is not allowed")
    missing = marker_set - visible_markers
    if missing:
        raise GateError(f"{context}: required visible record marker is missing")


def _split_table_row(line: str, expected_columns: int, context: str) -> list[str]:
    if not line.startswith("|") or not line.endswith("|"):
        raise GateError(f"{context}: malformed Markdown table row")
    if "\\|" in line:
        raise GateError(f"{context}: escaped table pipes are not allowed")
    cells = line[1:-1].split("|")
    if len(cells) != expected_columns:
        raise GateError(
            f"{context}: expected {expected_columns} columns, found {len(cells)}"
        )
    return [_clean_cell(cell, context) for cell in cells]


def _section(text: str, heading: str) -> str:
    marker = f"## {heading}"
    starts = [match.start() for match in re.finditer(rf"(?m)^{re.escape(marker)}$", text)]
    if len(starts) != 1:
        raise GateError(f"expected exactly one section: {heading}")
    start = starts[0] + len(marker)
    next_heading = re.search(r"(?m)^## ", text[start:])
    end = start + next_heading.start() if next_heading else len(text)
    return text[start:end]


def _table(section: str, expected_header: list[str], context: str) -> list[list[str]]:
    hidden_html = re.compile(
        r"<\s*/?\s*(?:div|details|section|table|tbody|thead|tr|td|th|template|p|span|script)\b",
        re.IGNORECASE,
    )
    if re.search(r"```|~~~|<!--|-->", section) or hidden_html.search(section):
        raise GateError(
            f"{context}: record tables cannot be fenced or commented out or wrapped in HTML"
        )
    lines = section.splitlines()
    header_indices: list[int] = []
    for index, line in enumerate(lines):
        if not line.startswith("|"):
            continue
        try:
            cells = _split_table_row(line, len(expected_header), context)
        except GateError:
            continue
        if cells == expected_header:
            header_indices.append(index)
    if len(header_indices) != 1:
        raise GateError(f"{context}: expected exactly one exact table header")
    header_index = header_indices[0]
    if header_index + 1 >= len(lines):
        raise GateError(f"{context}: missing table separator")
    separator = _split_table_row(
        lines[header_index + 1], len(expected_header), f"{context} separator"
    )
    if any(not re.fullmatch(r":?-{3,}:?", cell) for cell in separator):
        raise GateError(f"{context}: invalid table separator")

    rows: list[list[str]] = []
    for line in lines[header_index + 2 :]:
        if not line.startswith("|"):
            break
        rows.append(_split_table_row(line, len(expected_header), context))
    if not rows:
        raise GateError(f"{context}: table has no records")
    return rows


def _parse_kst_timestamp(value: str, context: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as error:
        raise GateError(f"{context}: invalid ISO 8601 timestamp") from error
    if parsed.tzinfo is None or parsed.utcoffset() != KST_OFFSET:
        raise GateError(f"{context}: timestamp must use the +09:00 offset")
    if not value.endswith("+09:00"):
        raise GateError(f"{context}: timestamp must end with +09:00")
    return parsed


def _validate_owner(value: str, context: str) -> None:
    if not value or value == "-" or value.isspace():
        raise GateError(f"{context}: approval owner is required")
    _clean_cell(value, context)


def _decision_sections(c0_text: str) -> dict[str, tuple[str, list[str]]]:
    decisions: dict[str, tuple[str, list[str]]] = {}
    for number in range(1, 11):
        decision_id = f"D-{number:02d}"
        heading_matches = list(
            re.finditer(rf"(?m)^## {decision_id}\. .+$", c0_text)
        )
        if len(heading_matches) != 1:
            raise GateError(f"expected exactly one decision section: {decision_id}")
        heading_match = heading_matches[0]
        start = heading_match.end()
        next_heading = re.search(r"(?m)^## ", c0_text[start:])
        end = start + next_heading.start() if next_heading else len(c0_text)
        body = c0_text[start:end]
        statuses = re.findall(r"(?m)^결정 상태: `([^`]+)`$", body)
        if len(statuses) != 1:
            raise GateError(f"{decision_id}: expected exactly one decision status")
        choices_lines = re.findall(r"(?m)^선택: (.+)$", body)
        if len(choices_lines) != 1:
            raise GateError(f"{decision_id}: expected exactly one choices line")
        choices = re.findall(r"`\[ \] ([^`]+)`", choices_lines[0])
        if len(choices) < 2:
            raise GateError(f"{decision_id}: at least two choices are required")
        if len(set(choices)) != len(choices):
            raise GateError(f"{decision_id}: duplicate choices are not allowed")
        decisions[decision_id] = (statuses[0], choices)
    return decisions


def _validate_c0_a(
    c0_text: str,
    decisions: dict[str, tuple[str, list[str]]],
) -> tuple[bool, datetime | None, tuple[str, str] | None]:
    rows = _table(
        _section(c0_text, "C0-A 제품 결정 기록"),
        C0_A_HEADER,
        "C0-A decision record",
    )
    expected_ids = [f"D-{number:02d}" for number in range(1, 11)]
    if [row[0] for row in rows] != expected_ids:
        raise GateError("C0-A decision rows must contain D-01 through D-10 exactly once")

    all_approved = True
    latest_approval: datetime | None = None
    latest_approval_sha: tuple[str, str] | None = None
    approval_shas: set[tuple[str, str]] = set()
    for row in rows:
        decision_id, selection, state, owner, timestamp, sha_cell = row
        narrative_state, choices = decisions[decision_id]
        if state not in {"승인 대기", "조건부 승인"}:
            raise GateError(f"{decision_id}: unsupported C0-A state: {state}")
        if narrative_state != state:
            raise GateError(
                f"{decision_id}: section state and C0-A record state do not match"
            )
        if state == "승인 대기":
            all_approved = False
            if [selection, owner, timestamp, sha_cell] != ["-", "-", "-", "-"]:
                raise GateError(f"{decision_id}: pending rows must contain only placeholders")
            continue

        if selection not in choices:
            raise GateError(f"{decision_id}: recorded selection is not an exact listed choice")
        if selection not in C0_A_APPROVABLE_CHOICES[decision_id]:
            raise GateError(
                f"{decision_id}: note-required requests must be resolved and reapproved first"
            )
        _validate_owner(owner, f"{decision_id} owner")
        approval_time = _parse_kst_timestamp(timestamp, f"{decision_id} approval time")
        sha_match = SHA_CELL_PATTERN.fullmatch(sha_cell)
        if not sha_match:
            raise GateError(
                f"{decision_id}: SHA cell must contain full document and code git SHAs"
            )
        approval_sha = (sha_match.group(1), sha_match.group(2))
        approval_shas.add(approval_sha)
        if latest_approval is None or approval_time > latest_approval:
            latest_approval = approval_time
            latest_approval_sha = approval_sha
        elif approval_time == latest_approval and latest_approval_sha != approval_sha:
            raise GateError(
                "latest C0-A approvals at the same time must use the same document/code SHAs"
            )
    if all_approved and len(approval_shas) != 1:
        raise GateError(
            "complete C0-A requires every decision to use the same final document/code SHAs"
        )
    return all_approved, latest_approval, latest_approval_sha


def _validate_board_choices(
    board_text: str, decisions: dict[str, tuple[str, list[str]]]
) -> None:
    if re.search(r"(?m)/\*|\*/|^\s*//", board_text):
        raise GateError("planning board contract cannot contain JavaScript comments")
    decision_source = board_text.split("const screens =", 1)[0]
    matches = list(re.finditer(r'(?m)^\s*id: "(D-[0-9]{2})",$', decision_source))
    expected_ids = [f"D-{number:02d}" for number in range(1, 11)]
    if [match.group(1) for match in matches] != expected_ids:
        raise GateError("planning board must contain D-01 through D-10 exactly once")
    for index, match in enumerate(matches):
        decision_id = match.group(1)
        end = matches[index + 1].start() if index + 1 < len(matches) else len(decision_source)
        chunk = decision_source[match.start() : end]
        option_lines = [
            line for line in chunk.splitlines() if line.lstrip().startswith('{ name: "')
        ]
        option_pattern = re.compile(
            r'^\s*\{ name: "([^"\\]*)", reply: "([^"\\]*)"'
            r'(?:, noteLabel: "([^"\\]*)", notePlaceholder: "([^"\\]*)")?,'
            r' detail: "([^"\\]*)", consequence: "([^"\\]*)" \},$'
        )
        parsed_options = [option_pattern.fullmatch(line) for line in option_lines]
        if not option_lines or any(match is None for match in parsed_options):
            raise GateError(
                f"{decision_id}: planning board choices must use the static option format"
            )
        option_matches = [match for match in parsed_options if match is not None]
        replies = [match.group(2) for match in option_matches]
        choices = decisions[decision_id][1]
        if replies != choices:
            raise GateError(
                f"{decision_id}: planning board replies must exactly match C0 choices"
            )
        note_required = {
            match.group(2) for match in option_matches if match.group(3) is not None
        }
        if not note_required.issubset(set(replies)):
            raise GateError(f"{decision_id}: malformed note-required planning choices")
        expected_note_required = set(choices) - set(
            C0_A_APPROVABLE_CHOICES[decision_id]
        )
        if note_required != expected_note_required:
            raise GateError(
                f"{decision_id}: note-required planning choices must match the approval contract"
            )


def _validate_board_state(
    board_text: str,
    c0_a_complete: bool,
    approved_decisions: int,
    c2_state: str,
) -> None:
    pattern = re.compile(
        r'const planningState = Object\.freeze\(\{\s*'
        r'c0A: "([^"]+)",\s*'
        r'c2: "([^"]+)",\s*'
        r'\}\);'
    )
    matches = pattern.findall(board_text)
    if len(matches) != 1:
        raise GateError("planning board must declare exactly one planningState")
    board_c0_a, board_c2 = matches[0]
    expected_c0_a = (
        "조건부 승인"
        if c0_a_complete
        else "부분 검토"
        if approved_decisions
        else "승인 대기"
    )
    if board_c0_a != expected_c0_a:
        raise GateError("planning board C0-A state does not match the decision record")
    if board_c2 != c2_state:
        raise GateError("planning board C2 state does not match the C2 marker")


def _board_trace_statuses(
    board_text: str, gates: tuple[str, ...]
) -> dict[str, str]:
    blocks = re.findall(
        r"(?ms)^const traceRows = \[\n(.*?)^\];$",
        board_text,
    )
    if len(blocks) != 1:
        raise GateError("planning board must declare exactly one traceRows block")
    statuses: dict[str, str] = {}
    row_pattern = re.compile(
        r'^\s*\["([^"\\]*)", "([^"\\]*)", "([^"\\]*)", '
        r'"([^"\\]*)", "([^"\\]*)", "([^"\\]*)", "([^"\\]*)"\],$'
    )
    for line in blocks[0].splitlines():
        if not line.strip():
            continue
        row_match = row_pattern.fullmatch(line)
        if row_match is None:
            raise GateError("planning board trace rows must use the static seven-string format")
        values = list(row_match.groups())
        if values[0] not in gates:
            continue
        if values[0] in statuses:
            raise GateError(f"planning board contains duplicate {values[0]} trace rows")
        statuses[values[0]] = values[5]
    if set(statuses) != set(gates):
        raise GateError("planning board is missing a required C2 trace row")
    return statuses


def _protected_c2_tree_digest(repository_root: Path) -> str:
    ignored = subprocess.run(
        [
            "git",
            "-C",
            str(repository_root),
            "ls-files",
            "--others",
            "--ignored",
            "--exclude-standard",
            "-z",
            "--",
            *C2_PROTECTED_PATHS,
        ],
        check=False,
        capture_output=True,
    )
    if ignored.returncode != 0:
        raise GateError("failed to enumerate ignored C2 paths")
    try:
        ignored_paths = sorted(
            value.decode("utf-8")
            for value in ignored.stdout.split(b"\0")
            if value
        )
    except UnicodeDecodeError as error:
        raise GateError("ignored C2 paths must use UTF-8 names") from error
    unsafe_ignored: list[str] = []
    for relative_name in ignored_paths:
        relative_path = PurePosixPath(relative_name)
        if relative_path.is_absolute() or ".." in relative_path.parts:
            unsafe_ignored.append(relative_name)
            continue
        if relative_name in C2_IGNORED_GENERATED_EXACT:
            continue
        if "__pycache__" in relative_path.parts:
            continue
        if relative_name.startswith(C2_IGNORED_GENERATED_PREFIXES):
            continue
        if relative_name.startswith(C2_IGNORED_VARIABLE_PREFIXES):
            candidate = repository_root.joinpath(*relative_path.parts)
            suffix = relative_path.suffix.lower()
            backend_data = relative_name.startswith("v2/backend/data/")
            suffix_allowed = (
                suffix in C2_IGNORED_DATA_SUFFIXES
                if backend_data
                else suffix in C2_IGNORED_QA_SUFFIXES
            )
            generated_report_code = suffix in {".css", ".js"}
            report_code_allowed = not generated_report_code or "html" in relative_path.parts
            executable = candidate.is_file() and bool(candidate.stat().st_mode & 0o111)
            if not suffix_allowed or not report_code_allowed or executable:
                unsafe_ignored.append(relative_name)
            continue
        unsafe_ignored.append(relative_name)
    if unsafe_ignored:
        preview = ", ".join(unsafe_ignored[:5])
        if len(unsafe_ignored) > 5:
            preview += f", and {len(unsafe_ignored) - 5} more"
        raise GateError(
            "ignored C2 path is not an approved generated artifact: " + preview
        )

    listed = subprocess.run(
        [
            "git",
            "-C",
            str(repository_root),
            "ls-files",
            "--cached",
            "--others",
            "--exclude-standard",
            "-z",
            "--",
            *C2_PROTECTED_PATHS,
        ],
        check=False,
        capture_output=True,
    )
    if listed.returncode != 0:
        raise GateError("failed to enumerate protected C2 source paths")
    try:
        relative_paths = {
            value.decode("utf-8")
            for value in listed.stdout.split(b"\0")
            if value
        }
    except UnicodeDecodeError as error:
        raise GateError("protected C2 source paths must use UTF-8 names") from error

    tree_digest = hashlib.sha256()
    exclusions = set(C2_PREAPPROVAL_EXCLUSIONS)
    for relative_name in sorted(relative_paths):
        if relative_name in exclusions:
            continue
        relative_path = PurePosixPath(relative_name)
        if relative_path.is_absolute() or ".." in relative_path.parts:
            raise GateError("protected C2 source path escapes the repository")
        candidate = repository_root.joinpath(*relative_path.parts)
        if candidate.is_symlink():
            raise GateError(f"protected C2 source cannot be a symlink: {relative_name}")
        if candidate.is_file():
            mode = "executable" if candidate.stat().st_mode & 0o111 else "regular"
            content_digest = hashlib.sha256(candidate.read_bytes()).hexdigest()
        elif candidate.exists():
            raise GateError(f"protected C2 path is not a regular file: {relative_name}")
        else:
            mode = "missing"
            content_digest = "-"
        tree_digest.update(relative_name.encode("utf-8"))
        tree_digest.update(b"\0")
        tree_digest.update(mode.encode("ascii"))
        tree_digest.update(b"\0")
        tree_digest.update(content_digest.encode("ascii"))
        tree_digest.update(b"\0")
    return tree_digest.hexdigest()


def _validate_c2_source_diff(
    repository_root: Path,
    c2_state: str,
    baseline_digest: str = C2_SOURCE_BASELINE_TREE_SHA256,
    *,
    allow_non_git: bool = False,
) -> None:
    inside_worktree = subprocess.run(
        ["git", "-C", str(repository_root), "rev-parse", "--is-inside-work-tree"],
        check=False,
        capture_output=True,
        text=True,
    )
    if inside_worktree.returncode != 0:
        if allow_non_git:
            return
        raise GateError("C2 source gate requires a Git worktree")
    if not re.fullmatch(r"[0-9a-f]{64}", baseline_digest):
        raise GateError("C2 source baseline tree digest is malformed")
    current_digest = _protected_c2_tree_digest(repository_root)
    if c2_state == "미시작" and current_digest != baseline_digest:
        raise GateError(
            "protected C2 source tree changed while C2 is unstarted: "
            f"expected {baseline_digest}, found {current_digest}"
        )


def _unwrap_code(value: str) -> str:
    if value.startswith("`") or value.endswith("`"):
        if not (value.startswith("`") and value.endswith("`") and value.count("`") == 2):
            raise GateError("artifact reference has malformed Markdown code delimiters")
        return value[1:-1]
    return value


def _artifact(
    repository_root: Path,
    reference: str,
    context: str,
) -> tuple[Path, dict[str, object], str]:
    raw_reference = _unwrap_code(reference)
    match = ARTIFACT_REF_PATTERN.fullmatch(raw_reference)
    if not match:
        raise GateError(f"{context}: invalid artifact path/checksum reference")
    relative_path = PurePosixPath(match.group(1))
    if relative_path.is_absolute() or ".." in relative_path.parts:
        raise GateError(f"{context}: artifact path must stay inside the repository")
    if relative_path.parts[:4] != ("docs", "v2-cutover", "evidence", "c0b"):
        raise GateError(f"{context}: artifact must be under docs/v2-cutover/evidence/c0b")
    if relative_path.suffix != ".json":
        raise GateError(f"{context}: artifact must be a JSON file")

    candidate = repository_root.joinpath(*relative_path.parts)
    current = repository_root
    for part in relative_path.parts:
        current = current / part
        if current.is_symlink():
            raise GateError(f"{context}: symlink artifacts are not allowed")
    if not candidate.is_file():
        raise GateError(f"{context}: artifact file does not exist")
    try:
        candidate.resolve().relative_to(repository_root.resolve())
    except ValueError as error:
        raise GateError(f"{context}: artifact resolves outside the repository") from error

    payload = candidate.read_bytes()
    digest = hashlib.sha256(payload).hexdigest()
    if digest != match.group(2):
        raise GateError(f"{context}: artifact SHA-256 does not match the file")
    try:
        source_text = payload.decode("utf-8")
        parsed = json.loads(source_text, object_pairs_hook=_strict_json_object)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise GateError(f"{context}: artifact must contain valid JSON") from error
    if not isinstance(parsed, dict):
        raise GateError(f"{context}: artifact JSON root must be an object")
    if not parsed:
        raise GateError(f"{context}: artifact JSON object must not be empty")
    return candidate, parsed, digest


def _strict_json_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise GateError(f"artifact JSON contains duplicate key: {key}")
        result[key] = value
    return result


def _exact_json_keys(payload: dict[str, object], expected: set[str], context: str) -> None:
    if set(payload) != expected:
        raise GateError(f"{context}: JSON keys must be exactly {sorted(expected)}")


def _json_string(payload: dict[str, object], key: str, context: str) -> str:
    value = payload.get(key)
    if not isinstance(value, str) or not value or value == "-":
        raise GateError(f"{context}: {key} must be a non-placeholder string")
    return value


def _validate_safe_evidence_text(value: object, context: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise GateError(f"{context}: nonempty text is required")
    cleaned = _clean_cell(value, context)
    if len(cleaned) > 500:
        raise GateError(f"{context}: text exceeds 500 characters")
    forbidden_patterns = (
        (
            r"(?i)(?:^|[^A-Za-z0-9])(?:authorization(?:[_ -]?header)?|bearer|"
            r"password(?:[_ -]?hash)?|passwd|pwd|secret|token(?:[_ -]?value)?|"
            r"cookie(?:[_ -]?value)?|app[_ -]?key|app[_ -]?secret|api[_ -]?key|"
            r"private[_ -]?key|client[_ -]?secret|access[_ -]?key[_ -]?id|"
            r"(?:access|refresh|id|auth)[_ -]?token)"
            r"(?:[^A-Za-z0-9]|$)"
        ),
        r"(?i)(?:^|[^A-Za-z0-9])(?:pwd|pass|password|passwd|secret|token|cookie)\s*[:=]",
        r"(?i)\b[A-Z0-9]+_(?:APP_)?(?:SECRET|TOKEN|PASSWORD|PASSWD|PWD|API_KEY|ACCESS_KEY_ID)\b",
        r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b",
        r"(?i)\b(?:api|private|client)[_ -]?(?:key|secret)\b",
        r"-----BEGIN [A-Z ]+-----",
        r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}",
        r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b",
        r"(?i)\b[0-9a-f]{24,63}\b",
        r"(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{32,}(?![A-Za-z0-9_-])",
    )
    if any(re.search(pattern, cleaned) for pattern in forbidden_patterns):
        raise GateError(f"{context}: possible credential or unmasked identifier")
    numeric_scan = re.sub(r"(?<!\d)\d{4}-\d{2}-\d{2}(?!\d)", "", cleaned)
    identifier_patterns = (
        r"(?<!\d)\d{8,16}(?!\d)",
        r"(?<!\d)\d(?:[ -]*\d){7,15}(?![ -]*\d)",
    )
    if any(re.search(pattern, numeric_scan) for pattern in identifier_patterns):
        raise GateError(f"{context}: possible credential or unmasked identifier")
    return cleaned


def _validate_sanitized_payload(
    payload: dict[str, object],
    expected_id: str,
    expected_time: datetime,
    expected_kind: str,
    context: str,
    expected_result: str | None = None,
) -> None:
    common_keys = {
        "schema_version",
        "kind",
        "id",
        "captured_at",
        "collector",
        "sanitized",
    }
    expected_keys = (
        common_keys | {"data"}
        if expected_kind == "c0b_capture"
        else common_keys | {"result", "summary"}
    )
    _exact_json_keys(payload, expected_keys, context)
    if type(payload["schema_version"]) is not int or payload["schema_version"] != 1:
        raise GateError(f"{context}: schema_version must be integer 1")
    if payload["kind"] != expected_kind:
        raise GateError(f"{context}: kind must be {expected_kind}")
    if payload["id"] != expected_id:
        raise GateError(f"{context}: item ID does not match")
    captured_at = _parse_kst_timestamp(
        _json_string(payload, "captured_at", context),
        f"{context} captured_at",
    )
    if captured_at != expected_time:
        raise GateError(f"{context}: captured_at does not match the C0-B table")
    _validate_safe_evidence_text(payload.get("collector"), f"{context} collector")
    if payload["sanitized"] is not True:
        raise GateError(f"{context}: sanitized must be true")
    if expected_kind == "c0b_capture":
        data = payload["data"]
        if not isinstance(data, dict):
            raise GateError(f"{context}: data must be a sanitized object")
        _exact_json_keys(data, {"summary", "facts"}, f"{context} data")
        _validate_safe_evidence_text(data.get("summary"), f"{context} data summary")
        facts = data.get("facts")
        if not isinstance(facts, list) or not 1 <= len(facts) <= 50:
            raise GateError(f"{context}: data facts must contain 1 through 50 entries")
        for index, fact in enumerate(facts):
            fact_context = f"{context} fact {index + 1}"
            if not isinstance(fact, dict):
                raise GateError(f"{fact_context}: fact must be an object")
            _exact_json_keys(fact, {"name", "value"}, fact_context)
            _validate_safe_evidence_text(fact.get("name"), f"{fact_context} name")
            _validate_safe_evidence_text(fact.get("value"), f"{fact_context} value")
    else:
        if payload["result"] != expected_result:
            raise GateError(f"{context}: result does not match the C0-B table")
        _validate_safe_evidence_text(payload.get("summary"), f"{context} summary")


def _validate_item_artifact(
    repository_root: Path,
    reference: str,
    subdirectory: str,
    expected_id: str,
    expected_time: datetime,
    expected_kind: str,
    context: str,
    expected_result: str | None = None,
) -> tuple[Path, str]:
    path, payload, digest = _artifact(repository_root, reference, context)
    relative_parts = path.relative_to(repository_root).parts
    required_prefix = (
        "docs",
        "v2-cutover",
        "evidence",
        "c0b",
        subdirectory,
    )
    expected_parts = required_prefix + (f"{expected_id}.json",)
    if relative_parts != expected_parts:
        raise GateError(
            f"{context}: artifact path must be "
            f"docs/v2-cutover/evidence/c0b/{subdirectory}/{expected_id}.json"
        )
    _validate_sanitized_payload(
        payload,
        expected_id,
        expected_time,
        expected_kind,
        context,
        expected_result,
    )
    return path, digest


def _validate_snapshot_manifest(
    payload: dict[str, object],
    row_records: dict[str, dict[str, object]],
    repository_root: Path,
) -> tuple[datetime, str, str]:
    context = "C0-B snapshot manifest"
    _exact_json_keys(
        payload,
        {"schema_version", "kind", "captured_at", "document_sha", "code_sha", "items"},
        context,
    )
    if type(payload["schema_version"]) is not int or payload["schema_version"] != 1:
        raise GateError(f"{context}: schema_version must be integer 1")
    if payload["kind"] != "c0b_snapshot_manifest":
        raise GateError(f"{context}: kind must be c0b_snapshot_manifest")
    captured_at = _parse_kst_timestamp(
        _json_string(payload, "captured_at", context), f"{context} captured_at"
    )
    document_sha = _json_string(payload, "document_sha", context)
    code_sha = _json_string(payload, "code_sha", context)
    if not GIT_SHA_PATTERN.fullmatch(document_sha) or not GIT_SHA_PATTERN.fullmatch(code_sha):
        raise GateError(f"{context}: document_sha and code_sha must be full lowercase git SHA formats")

    items = payload.get("items")
    if not isinstance(items, list):
        raise GateError(f"{context}: items must be a list")
    expected_ids = list(C0_B_ITEM_IDS.values())
    if len(items) != len(expected_ids):
        raise GateError(f"{context}: all C0-B item records are required")
    item_times: list[datetime] = []
    source_paths: set[Path] = set()
    for index, item in enumerate(items):
        item_context = f"{context} item {index + 1}"
        if not isinstance(item, dict):
            raise GateError(f"{item_context}: item must be an object")
        _exact_json_keys(item, {"id", "label", "source", "captured_at", "sha256"}, item_context)
        expected_id = expected_ids[index]
        if item.get("id") != expected_id:
            raise GateError(f"{item_context}: item IDs must be complete and ordered")
        expected_label = list(C0_B_ITEM_IDS)[index]
        if item.get("label") != expected_label:
            raise GateError(f"{item_context}: item label does not match its ID")
        source_reference = _json_string(item, "source", item_context)
        item_time = _parse_kst_timestamp(
            _json_string(item, "captured_at", item_context), f"{item_context} captured_at"
        )
        item_sha = _json_string(item, "sha256", item_context)
        if not re.fullmatch(r"[0-9a-f]{64}", item_sha):
            raise GateError(f"{item_context}: sha256 must be 64 lowercase hex characters")
        row_record = row_records[expected_id]
        if item_time != row_record["timestamp"] or item_sha != row_record["capture_sha"]:
            raise GateError(f"{item_context}: manifest metadata does not match the C0-B table")
        source_path, source_digest = _validate_item_artifact(
            repository_root,
            source_reference,
            "items",
            expected_id,
            item_time,
            "c0b_capture",
            f"{item_context} source",
        )
        if source_digest != item_sha:
            raise GateError(f"{item_context}: source artifact digest does not match sha256")
        if source_path in source_paths:
            raise GateError(f"{item_context}: source artifacts must be unique")
        source_paths.add(source_path)
        item_times.append(item_time)
    if item_times and captured_at < max(item_times):
        raise GateError(f"{context}: manifest captured_at cannot predate its items")
    return captured_at, document_sha, code_sha


def _validate_diff_artifact(
    payload: dict[str, object],
    snapshot_digest: str,
    row_records: dict[str, dict[str, object]],
    repository_root: Path,
) -> datetime:
    context = "C0-B diff artifact"
    _exact_json_keys(
        payload,
        {"schema_version", "kind", "snapshot_manifest_sha256", "compared_at", "result", "items"},
        context,
    )
    if type(payload["schema_version"]) is not int or payload["schema_version"] != 1:
        raise GateError(f"{context}: schema_version must be integer 1")
    if payload["kind"] != "c0b_diff":
        raise GateError(f"{context}: kind must be c0b_diff")
    if payload["snapshot_manifest_sha256"] != snapshot_digest:
        raise GateError(f"{context}: snapshot manifest digest does not match")
    compared_at = _parse_kst_timestamp(
        _json_string(payload, "compared_at", context), f"{context} compared_at"
    )
    result = payload.get("result")
    if result not in {"NO_DIFF", "DIFF"}:
        raise GateError(f"{context}: result must be NO_DIFF or DIFF")
    items = payload.get("items")
    if not isinstance(items, list):
        raise GateError(f"{context}: items must be a list")
    expected_ids = list(C0_B_ITEM_IDS.values())
    if len(items) != len(expected_ids):
        raise GateError(f"{context}: all C0-B item diff records are required")
    observed_results: list[str] = []
    detail_paths: set[Path] = set()
    for index, item in enumerate(items):
        item_context = f"{context} item {index + 1}"
        if not isinstance(item, dict):
            raise GateError(f"{item_context}: item must be an object")
        _exact_json_keys(
            item,
            {"id", "result", "details_source", "details_sha256"},
            item_context,
        )
        expected_id = expected_ids[index]
        if item.get("id") != expected_id:
            raise GateError(f"{item_context}: item IDs must be complete and ordered")
        item_result = item.get("result")
        details_source = item.get("details_source")
        details_sha = item.get("details_sha256")
        if item_result not in {"NO_DIFF", "DIFF"}:
            raise GateError(f"{item_context}: result must be NO_DIFF or DIFF")
        if not isinstance(details_sha, str) or not re.fullmatch(r"[0-9a-f]{64}", details_sha):
            raise GateError(f"{item_context}: details_sha256 must be 64 lowercase hex characters")
        if not isinstance(details_source, str) or not details_source:
            raise GateError(f"{item_context}: details_source must be an artifact reference")
        row_record = row_records[expected_id]
        if item_result != row_record["diff_result"] or details_sha != row_record["diff_sha"]:
            raise GateError(f"{item_context}: diff metadata does not match the C0-B table")
        detail_path, detail_digest = _validate_item_artifact(
            repository_root,
            details_source,
            "diff-items",
            expected_id,
            row_record["timestamp"],
            "c0b_diff_detail",
            f"{item_context} details",
            item_result,
        )
        if detail_digest != details_sha:
            raise GateError(f"{item_context}: details artifact digest does not match")
        if detail_path in detail_paths:
            raise GateError(f"{item_context}: detail artifacts must be unique")
        detail_paths.add(detail_path)
        observed_results.append(item_result)
    expected_result = "DIFF" if "DIFF" in observed_results else "NO_DIFF"
    if result != expected_result:
        raise GateError(f"{context}: aggregate result does not match item results")
    return compared_at


def _validate_c0_b(
    c0_text: str,
    repository_root: Path,
    c0_a_complete: bool,
    latest_c0_a_approval: datetime | None,
    latest_c0_a_sha: tuple[str, str] | None,
) -> bool:
    rows = _table(
        _section(c0_text, "C0-B 운영 기준선 재확인 기록"),
        C0_B_HEADER,
        "C0-B baseline record",
    )
    if [row[0] for row in rows] != C0_B_ITEMS:
        raise GateError("C0-B rows are missing, duplicated, or out of order")

    completed_items = 0
    row_records: dict[str, dict[str, object]] = {}
    approval_document_sha: str | None = None
    approval_code_sha: str | None = None
    for row in rows[:-1]:
        item, captured_value, difference, state, owner, timestamp = row
        if state == C0_B_PENDING_STATES[item]:
            if [captured_value, difference, owner, timestamp] != ["-", "-", "-", "-"]:
                raise GateError(f"{item}: pending C0-B rows must contain only placeholders")
            continue
        if state != "확인 완료":
            raise GateError(f"{item}: unsupported C0-B state: {state}")
        if not c0_a_complete:
            raise GateError(f"{item}: C0-B capture cannot complete before all C0-A decisions")
        if captured_value == "-" or difference == "-":
            raise GateError(f"{item}: completed C0-B rows require value and diff")
        _validate_owner(owner, f"{item} owner")
        completed_at = _parse_kst_timestamp(timestamp, f"{item} approval time")
        if latest_c0_a_approval is None or completed_at < latest_c0_a_approval:
            raise GateError(f"{item}: C0-B completion cannot predate C0-A approval")

        expected_id = C0_B_ITEM_IDS[item]
        raw_capture = _unwrap_code(captured_value)
        if item == "Java 실행 코드 SHA":
            capture_match = JAVA_CAPTURE_CELL_PATTERN.fullmatch(raw_capture)
            if not capture_match:
                raise GateError(f"{item}: invalid git/item/checksum record")
            recorded_id = capture_match.group(2)
            capture_sha = capture_match.group(3)
        elif item == "승인 당시 요구사항·명세 문서/v2 코드 SHA":
            capture_match = APPROVAL_CAPTURE_CELL_PATTERN.fullmatch(raw_capture)
            if not capture_match:
                raise GateError(f"{item}: invalid document/code/item/checksum record")
            approval_document_sha = capture_match.group(1)
            approval_code_sha = capture_match.group(2)
            recorded_id = capture_match.group(3)
            capture_sha = capture_match.group(4)
        else:
            capture_match = CAPTURE_CELL_PATTERN.fullmatch(raw_capture)
            if not capture_match:
                raise GateError(f"{item}: invalid item/checksum record")
            recorded_id = capture_match.group(1)
            capture_sha = capture_match.group(2)
        if recorded_id != expected_id:
            raise GateError(f"{item}: captured item ID does not match the table row")

        diff_match = DIFF_CELL_PATTERN.fullmatch(_unwrap_code(difference))
        if not diff_match:
            raise GateError(f"{item}: invalid diff result/checksum record")
        row_records[expected_id] = {
            "timestamp": completed_at,
            "capture_sha": capture_sha,
            "diff_result": diff_match.group(1),
            "diff_sha": diff_match.group(2),
        }
        completed_items += 1

    final_item, snapshot_ref, diff_ref, final_state, owner, timestamp = rows[-1]
    if final_state == C0_B_PENDING_STATES[final_item]:
        if [snapshot_ref, diff_ref, owner, timestamp] != ["-", "-", "-", "-"]:
            raise GateError("C0-B final pending row must contain only placeholders")
        return False
    if final_state != "재승인 완료":
        raise GateError(f"C0-B final bundle: unsupported state: {final_state}")
    if not c0_a_complete or completed_items != len(rows) - 1:
        raise GateError("C0-B final reapproval requires complete C0-A and C0-B rows")
    _validate_owner(owner, "C0-B final owner")
    final_approval_time = _parse_kst_timestamp(timestamp, "C0-B final approval time")
    snapshot_path, snapshot_payload, snapshot_digest = _artifact(
        repository_root,
        snapshot_ref,
        "C0-B snapshot manifest",
    )
    diff_path, diff_payload, _ = _artifact(
        repository_root,
        diff_ref,
        "C0-B diff artifact",
    )
    if snapshot_path == diff_path:
        raise GateError("C0-B snapshot manifest and diff artifact must be separate files")
    snapshot_time, document_sha, code_sha = _validate_snapshot_manifest(
        snapshot_payload, row_records, repository_root
    )
    if document_sha != approval_document_sha or code_sha != approval_code_sha:
        raise GateError("C0-B snapshot document/code SHAs do not match the approval row")
    if latest_c0_a_sha is None or (document_sha, code_sha) != latest_c0_a_sha:
        raise GateError(
            "C0-B document/code SHAs do not match the latest C0-A approval"
        )
    diff_time = _validate_diff_artifact(
        diff_payload,
        snapshot_digest,
        row_records,
        repository_root,
    )
    if diff_time < snapshot_time:
        raise GateError("C0-B diff comparison cannot predate the snapshot manifest")
    if final_approval_time < diff_time:
        raise GateError("C0-B final reapproval cannot predate the completed diff")
    return True


def _c2_state(c0_text: str) -> str:
    matches = re.findall(r"(?m)^C2 개발 상태: `([^`]+)`$", c0_text)
    if len(matches) != 1:
        raise GateError("expected exactly one C2 development state marker")
    state = matches[0]
    if state == "완료":
        raise GateError(
            "C2 complete is reserved until a comprehensive exit gate exists"
        )
    if state not in {"미시작", "진행 중"}:
        raise GateError(f"unsupported C2 development state: {state}")
    return state


def _validate_trace(trace_text: str, board_text: str, c2_state: str) -> None:
    rows = _table(trace_text, TRACE_HEADER, "delivery trace")
    row_by_gate: dict[str, list[str]] = {}
    c2_gates = ("D-03", "C2 미래 화면", "C2 종합")
    for gate in c2_gates:
        matching_rows = [row for row in rows if row[0] == gate]
        if len(matching_rows) != 1:
            raise GateError(f"delivery trace must contain exactly one {gate} row")
        row_by_gate[gate] = matching_rows[0]
    statuses = {gate: row_by_gate[gate][7] for gate in c2_gates}
    if any(state not in {"미구현", "부분", "완료"} for state in statuses.values()):
        raise GateError("C2 trace rows use an unsupported implementation state")
    if c2_state == "미시작" and any(state != "미구현" for state in statuses.values()):
        raise GateError("C2 is marked unstarted but a C2 trace row shows progress")
    if c2_state == "진행 중" and statuses["C2 종합"] != "부분":
        raise GateError("C2 in progress requires the comprehensive C2 trace to be partial")
    board_statuses = _board_trace_statuses(board_text, c2_gates)
    if board_statuses != statuses:
        raise GateError("planning board C2 trace statuses do not match the delivery trace")


def verify_repository(repository_root: Path, *, allow_non_git: bool = False) -> None:
    root = repository_root.resolve()
    c0_path = root / "docs/v2-cutover/C0_DECISIONS.md"
    trace_path = root / "docs/v2-cutover/DELIVERY_TRACE.md"
    board_path = root / "docs/v2-cutover/review-board/app.js"
    if not c0_path.is_file() or not trace_path.is_file() or not board_path.is_file():
        raise GateError("C0 decision, delivery trace, or planning board document is missing")
    c0_text = c0_path.read_text(encoding="utf-8")
    trace_text = trace_path.read_text(encoding="utf-8")
    board_text = board_path.read_text(encoding="utf-8")
    _assert_visible_markdown_records(
        c0_text,
        tuple(
            [f"## D-{number:02d}." for number in range(1, 11)]
            + ["## C0-A 제품 결정 기록", "## C0-B 운영 기준선 재확인 기록"]
        ),
        "C0 decision document",
    )
    _assert_visible_markdown_records(
        trace_text,
        ("| 결정·게이트 | 정본 요구사항 | 기능·API | 화면 | QA ID·검증 범위 | 실행 SHA·run/artifact | 증적 링크 | 상태 | 다음 필수 승인 |",),
        "delivery trace",
    )
    c2_state = _c2_state(c0_text)
    decisions = _decision_sections(c0_text)
    _validate_board_choices(board_text, decisions)
    c0_a_complete, latest_c0_a_approval, latest_c0_a_sha = _validate_c0_a(
        c0_text, decisions
    )
    approved_decisions = sum(
        1 for state, _ in decisions.values() if state == "조건부 승인"
    )
    _validate_board_state(
        board_text, c0_a_complete, approved_decisions, c2_state
    )
    c0_b_complete = _validate_c0_b(
        c0_text,
        root,
        c0_a_complete,
        latest_c0_a_approval,
        latest_c0_a_sha,
    )
    _validate_c2_source_diff(root, c2_state, allow_non_git=allow_non_git)
    _validate_trace(trace_text, board_text, c2_state)
    if c2_state != "미시작" and not c0_b_complete:
        raise GateError("C2 progress requires complete C0-A and C0-B reapproval records")


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(f"usage: {argv[0]} <repository-root>", file=sys.stderr)
        return 2
    try:
        verify_repository(Path(argv[1]))
    except (GateError, OSError) as error:
        print(f"C0 documentation gate FAIL: {error}", file=sys.stderr)
        return 1
    print(
        "C0 documentation gate PASS (structure/ordering/checksums only; "
        "approver identity and git object existence are not authenticated)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
