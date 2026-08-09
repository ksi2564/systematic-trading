#!/usr/bin/env python3
"""Independently validate sanitized C0-B captures and final bundle artifacts."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from contract import (
    C0BError,
    DEFAULT_KNOWN_DEPLOYMENTS,
    DEFAULT_REPOSITORY_ROOT,
    validate_bundle_directory,
    validate_capture_directory,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]


def _add_workflow_context_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--workflow-sha", required=True)
    parser.add_argument("--workflow-run-id", required=True)
    parser.add_argument("--workflow-run-attempt", required=True)


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate C0-B evidence contracts.")
    commands = parser.add_subparsers(dest="command", required=True)
    captures = commands.add_parser("captures", help="validate the twelve capture files")
    captures.add_argument("--capture-dir", required=True, type=Path)
    captures.add_argument("--captured-at", required=True)
    captures.add_argument(
        "--decision-file",
        type=Path,
        default=REPOSITORY_ROOT / "docs/v2-cutover/C0_DECISIONS.md",
    )
    captures.add_argument(
        "--known-deployments", type=Path, default=DEFAULT_KNOWN_DEPLOYMENTS
    )
    captures.add_argument("--repository-root", type=Path, default=DEFAULT_REPOSITORY_ROOT)
    _add_workflow_context_arguments(captures)
    bundle = commands.add_parser("bundle", help="validate snapshot, diff, and all item files")
    bundle.add_argument("--evidence-dir", required=True, type=Path)
    bundle.add_argument(
        "--decision-file",
        type=Path,
        default=REPOSITORY_ROOT / "docs/v2-cutover/C0_DECISIONS.md",
    )
    bundle.add_argument(
        "--known-deployments", type=Path, default=DEFAULT_KNOWN_DEPLOYMENTS
    )
    bundle.add_argument("--repository-root", type=Path, default=DEFAULT_REPOSITORY_ROOT)
    _add_workflow_context_arguments(bundle)
    return parser.parse_args()


def main() -> int:
    args = _arguments()
    try:
        if args.command == "captures":
            validate_capture_directory(
                args.capture_dir,
                args.captured_at,
                known_deployments_path=args.known_deployments,
                repository_root=args.repository_root,
                decision_file=args.decision_file,
                expected_workflow_sha=args.workflow_sha,
                expected_workflow_run_id=args.workflow_run_id,
                expected_workflow_run_attempt=args.workflow_run_attempt,
            )
            message = "Validated twelve sanitized C0-B capture files."
        elif args.command == "bundle":
            validate_bundle_directory(
                args.evidence_dir,
                decision_file=args.decision_file,
                known_deployments_path=args.known_deployments,
                repository_root=args.repository_root,
                expected_workflow_sha=args.workflow_sha,
                expected_workflow_run_id=args.workflow_run_id,
                expected_workflow_run_attempt=args.workflow_run_attempt,
            )
            message = "Validated the sanitized C0-B snapshot and diff bundle."
        else:  # argparse makes this unreachable; keep the command fail-closed.
            raise C0BError("unsupported validation command")
    except (C0BError, OSError) as error:
        print(f"C0-B validation failed: {error}", file=sys.stderr)
        return 1
    print(message)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
