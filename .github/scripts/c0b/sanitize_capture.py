#!/usr/bin/env python3
"""Convert the untrusted SSH stdout stream into atomic sanitized C0-B captures."""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from contract import (
    C0BError,
    approval_metadata,
    load_known_deployments,
    make_capture_payloads,
    parse_kst,
    parse_raw_stream,
    resolve_deployment,
    verify_local_git_commit,
    write_capture_directory_atomic,
)


SCRIPT_DIR = Path(__file__).resolve().parent
REPOSITORY_ROOT = SCRIPT_DIR.parents[2]


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Read C0-B raw protocol from stdin and write only sanitized JSON."
    )
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--captured-at", required=True)
    parser.add_argument("--collector", required=True)
    parser.add_argument("--workflow-sha", required=True)
    parser.add_argument("--workflow-run-id", required=True)
    parser.add_argument("--workflow-run-attempt", required=True)
    parser.add_argument("--repository-root", type=Path, default=REPOSITORY_ROOT)
    parser.add_argument(
        "--decision-file",
        type=Path,
        default=REPOSITORY_ROOT / "docs/v2-cutover/C0_DECISIONS.md",
    )
    parser.add_argument(
        "--known-deployments",
        type=Path,
        default=SCRIPT_DIR / "known_deployments.json",
    )
    return parser.parse_args()


def _require_runner_temp_output(path: Path) -> Path:
    runner_temp_value = os.environ.get("RUNNER_TEMP")
    if not runner_temp_value:
        raise C0BError("RUNNER_TEMP is required")
    runner_temp = Path(runner_temp_value)
    if runner_temp.is_symlink() or not runner_temp.is_dir():
        raise C0BError("RUNNER_TEMP must be a regular directory")
    resolved_runner_temp = runner_temp.resolve(strict=True)
    resolved_parent = path.parent.resolve(strict=True)
    try:
        resolved_parent.relative_to(resolved_runner_temp)
    except ValueError as error:
        raise C0BError("output directory must stay under RUNNER_TEMP") from error
    return resolved_parent / path.name


def run(args: argparse.Namespace) -> None:
    captured_time = parse_kst(args.captured_at, "capture time")
    document_sha, code_sha, _, collection_time = approval_metadata(args.decision_file)
    if captured_time < collection_time:
        raise C0BError("capture time predates collection approval")
    output_dir = _require_runner_temp_output(args.output_dir)
    deployments = load_known_deployments(args.known_deployments)

    # sys.stdin.buffer is the only raw input.  It is bounded and retained only
    # in memory until every record passes the exact allowlist and secret scan.
    raw = parse_raw_stream(sys.stdin.buffer)
    runtime_git_sha = resolve_deployment(raw, deployments)
    verify_local_git_commit(args.repository_root, runtime_git_sha)
    for revision in {document_sha, code_sha, args.workflow_sha}:
        verify_local_git_commit(args.repository_root, revision)
    payloads = make_capture_payloads(
        raw,
        captured_at=args.captured_at,
        collector=args.collector,
        workflow_sha=args.workflow_sha,
        runtime_git_sha=runtime_git_sha,
        approval_document_sha=document_sha,
        approval_code_sha=code_sha,
        workflow_run_id=args.workflow_run_id,
        workflow_run_attempt=args.workflow_run_attempt,
    )
    write_capture_directory_atomic(
        output_dir,
        payloads,
        known_deployments_path=args.known_deployments,
        repository_root=args.repository_root,
        decision_file=args.decision_file,
    )


def main() -> int:
    os.umask(0o077)
    try:
        run(_arguments())
    except (C0BError, OSError) as error:
        # Contract errors never interpolate raw values, so this diagnostic is
        # useful without reflecting the untrusted stream into Actions logs.
        print(f"C0-B sanitization failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
