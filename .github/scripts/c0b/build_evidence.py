#!/usr/bin/env python3
"""Build the repository-ready C0-B snapshot and diff bundle atomically."""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from contract import (
    C0BError,
    DEFAULT_KNOWN_DEPLOYMENTS,
    DEFAULT_REPOSITORY_ROOT,
    build_bundle_atomic,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build sanitized C0-B evidence bundle.")
    parser.add_argument("--capture-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--snapshot-at", required=True)
    parser.add_argument("--compared-at", required=True)
    parser.add_argument("--workflow-sha", required=True)
    parser.add_argument("--workflow-run-id", required=True)
    parser.add_argument("--workflow-run-attempt", required=True)
    parser.add_argument("--diff-collector", default="c0b-code-owned-comparison")
    parser.add_argument(
        "--known-deployments", type=Path, default=DEFAULT_KNOWN_DEPLOYMENTS
    )
    parser.add_argument("--repository-root", type=Path, default=DEFAULT_REPOSITORY_ROOT)
    parser.add_argument(
        "--decision-file",
        type=Path,
        default=REPOSITORY_ROOT / "docs/v2-cutover/C0_DECISIONS.md",
    )
    return parser.parse_args()


def main() -> int:
    os.umask(0o077)
    args = _arguments()
    try:
        build_bundle_atomic(
            capture_dir=args.capture_dir,
            output_dir=args.output_dir,
            decision_file=args.decision_file,
            snapshot_at=args.snapshot_at,
            compared_at=args.compared_at,
            diff_collector=args.diff_collector,
            expected_workflow_sha=args.workflow_sha,
            expected_workflow_run_id=args.workflow_run_id,
            expected_workflow_run_attempt=args.workflow_run_attempt,
            known_deployments_path=args.known_deployments,
            repository_root=args.repository_root,
        )
    except (C0BError, OSError) as error:
        print(f"C0-B bundle build failed: {error}", file=sys.stderr)
        return 1
    print("Built and validated the sanitized C0-B snapshot and diff bundle.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
