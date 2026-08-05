#!/usr/bin/env bash
set -Eeuo pipefail

readonly documentation_test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly repository_root="$(cd -- "${documentation_test_dir}/../../.." && pwd -P)"
readonly cutover_root="${repository_root}/docs/v2-cutover"
readonly board_root="${cutover_root}/review-board"
readonly v2_ci_workflow="${repository_root}/.github/workflows/v2-ci.yml"
readonly c0_gate_verifier="${documentation_test_dir}/verify_c0_gate.py"
readonly c0_gate_tests="${documentation_test_dir}/test_verify_c0_gate.py"
readonly runway_wrapper="${board_root}/run-verified-runway.sh"
readonly runway_evidence_verifier="${board_root}/verify-runway-evidence.mjs"

required_files=(
  "${cutover_root}/C0_DECISIONS.md"
  "${cutover_root}/CUTOVER_ROLLBACK.md"
  "${cutover_root}/DELIVERY_TRACE.md"
  "${cutover_root}/FUNCTIONAL_SPEC.md"
  "${cutover_root}/IMPLEMENTATION_RUNWAY.md"
  "${cutover_root}/README.md"
  "${cutover_root}/REQUIREMENTS.md"
  "${cutover_root}/SCREEN_SPEC.md"
  "${cutover_root}/TRACEABILITY_QA.md"
  "${board_root}/index.html"
  "${board_root}/styles.css"
  "${board_root}/app.js"
  "${runway_wrapper}"
  "${board_root}/verify-runway.mjs"
  "${runway_evidence_verifier}"
  "${repository_root}/docs/templates/V2_FEATURE_PROPOSAL_TEMPLATE.md"
  "${repository_root}/.github/PULL_REQUEST_TEMPLATE/v2-feature.md"
  "${v2_ci_workflow}"
  "${repository_root}/v2/frontend/package.json"
  "${repository_root}/v2/frontend/playwright.planning.config.ts"
  "${repository_root}/v2/frontend/e2e-planning/review-board.spec.ts"
  "${c0_gate_verifier}"
  "${c0_gate_tests}"
)
for required_file in "${required_files[@]}"; do
  [[ -f "${required_file}" ]] || {
    printf 'missing documentation contract file: %s\n' "${required_file}" >&2
    exit 1
  }
done

python3 - \
  "${cutover_root}" \
  "${repository_root}/docs/templates/V2_FEATURE_PROPOSAL_TEMPLATE.md" \
  "${repository_root}/.github/PULL_REQUEST_TEMPLATE/v2-feature.md" <<'PY'
from pathlib import Path
from urllib.parse import unquote
import re
import sys

cutover_root = Path(sys.argv[1]).resolve()
documents = sorted(cutover_root.rglob("*.md")) + [Path(value).resolve() for value in sys.argv[2:]]
link_pattern = re.compile(r"(?<!!)\[[^\]]*\]\(([^)]+)\)")


def github_heading_anchors(path: Path) -> set[str]:
    anchors: set[str] = set()
    occurrences: dict[str, int] = {}
    fenced = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.lstrip().startswith("```"):
            fenced = not fenced
            continue
        if fenced:
            continue
        match = re.match(r"^#{1,6}\s+(.+?)\s*#*\s*$", line)
        if not match:
            continue
        heading = re.sub(r"<[^>]+>", "", match.group(1)).replace("`", "")
        slug = re.sub(r"[^\w\- ]", "", heading.lower(), flags=re.UNICODE)
        slug = re.sub(r"\s+", "-", slug.strip())
        duplicate_index = occurrences.get(slug, 0)
        occurrences[slug] = duplicate_index + 1
        anchors.add(slug if duplicate_index == 0 else f"{slug}-{duplicate_index}")
    return anchors


failures: list[str] = []
anchor_cache: dict[Path, set[str]] = {}
for document in documents:
    fenced = False
    for line_number, line in enumerate(document.read_text(encoding="utf-8").splitlines(), 1):
        if line.lstrip().startswith("```"):
            fenced = not fenced
            continue
        if fenced:
            continue
        for match in link_pattern.finditer(line):
            raw_target = match.group(1).strip()
            if raw_target.startswith("<") and ">" in raw_target:
                raw_target = raw_target[1:raw_target.index(">")]
            else:
                raw_target = raw_target.split(maxsplit=1)[0]
            if re.match(r"^[a-z][a-z0-9+.-]*:", raw_target, flags=re.IGNORECASE) or raw_target.startswith("//"):
                continue
            relative_path, separator, raw_fragment = raw_target.partition("#")
            target = document if not relative_path else (document.parent / unquote(relative_path)).resolve()
            if not target.exists():
                failures.append(f"{document}:{line_number}: missing relative link target {raw_target}")
                continue
            if separator and target.suffix.lower() == ".md":
                anchors = anchor_cache.setdefault(target, github_heading_anchors(target))
                fragment = unquote(raw_fragment).lower()
                if fragment not in anchors:
                    failures.append(
                        f"{document}:{line_number}: missing Markdown anchor #{fragment} in {target}"
                    )

if failures:
    raise SystemExit("\n".join(failures))
PY

PYTHONDONTWRITEBYTECODE=1 python3 "${c0_gate_verifier}" "${repository_root}"
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s "${documentation_test_dir}" \
  -p 'test_verify_c0_gate.py'
[[ "$(grep -Ec '^    def test_' "${c0_gate_tests}")" -eq 55 ]]
grep -F 'C0 gate 양성/음성 55건' "${cutover_root}/DELIVERY_TRACE.md" >/dev/null
grep -F 'C0 문서·기록 검사 55/55 로컬 통과' "${board_root}/app.js" >/dev/null
grep -F 'C0 gate는 51/51이며 이후 추가된 C0-B 수집 승인 검사는 포함하지 않는다.' \
  "${cutover_root}/DELIVERY_TRACE.md" >/dev/null
grep -F '현재 C0-B 승인 경계 보강본: 로컬 planning 10/10과 C0 gate 55/55' \
  "${cutover_root}/DELIVERY_TRACE.md" >/dev/null

for decision_number in 01 02 03 04 05 06 07 08 09 10; do
  decision_id="D-${decision_number}"
  [[ "$(grep -c "^## ${decision_id}\." "${cutover_root}/C0_DECISIONS.md")" -eq 1 ]]
  [[ "$(grep -c "id: \"${decision_id}\"" "${board_root}/app.js")" -eq 1 ]]
done

for screen_id in S-01 S-04 S-08 S-09 S-10; do
  grep -F "id: \"${screen_id}\"" "${board_root}/app.js" >/dev/null
done

canonical_requirement_ids() {
  sed -nE 's/^\| `(V2-[A-Z]+-[0-9]{3})` \|.*/\1/p' \
    "${cutover_root}/REQUIREMENTS.md" | sort -u
}

artifact_requirement_ids() {
  grep -Eo 'V2-[A-Z]+-[0-9]{3}' "$1" | sort -u
}

for requirement_artifact in \
  "${cutover_root}/DELIVERY_TRACE.md" \
  "${board_root}/app.js"; do
  if ! requirement_diff="$(diff -u \
    <(canonical_requirement_ids) \
    <(artifact_requirement_ids "${requirement_artifact}") 2>&1)"; then
    printf 'canonical requirement ID drift in %s:\n%s\n' \
      "${requirement_artifact}" "${requirement_diff}" >&2
    exit 1
  fi
done

grep -F 'id="planning-status"' "${board_root}/index.html" >/dev/null
grep -F 'id="c2-status"' "${board_root}/index.html" >/dev/null
grep -F 'const planningState = Object.freeze({' "${board_root}/app.js" >/dev/null
grep -F '브라우저나 서버에 저장·전송·승인되지 않고' "${board_root}/index.html" >/dev/null
grep -F '붙여넣어 보내도 C0-A 답변만 전달되며, C0-B 수집은 따로 승인해야 해요.' \
  "${board_root}/index.html" >/dev/null
grep -F '이번 기획·QA 준비가 만든 실제 주문 · 증권사 자격증명 · Java 중단 · 접속 경로 변경 0건' \
  "${board_root}/index.html" >/dev/null
grep -F 'C1 인증 화면 읽기 검증은 C0-A와 병렬로 준비할 수 있어요.' \
  "${board_root}/index.html" >/dev/null
grep -F '후보 배포 승인 + 직접 로그인 필요' "${board_root}/index.html" >/dev/null
grep -F '비밀번호·일회용 인증번호·토큰·쿠키는 보내지 마세요.' \
  "${board_root}/index.html" >/dev/null
grep -F '후보 배포·시험 서버 변경·자격증명 사용·실제 주문·Java 중단·접속 경로 변경' \
  "${board_root}/index.html" >/dev/null
grep -F '마지막 앱 배포 기록' "${board_root}/index.html" >/dev/null
grep -F '#54 · 66e374c' "${board_root}/index.html" >/dev/null
grep -F '역사적 독립 검증 snapshot' "${board_root}/index.html" >/dev/null
grep -F '46a34ea' "${board_root}/index.html" >/dev/null
grep -F 'PR #57 · Draft' "${board_root}/index.html" >/dev/null
grep -F '현재 검토 보드 화면 검사 10/10 통과 · PR 자동검사 확인 대기 · 운영 미배포' \
  "${board_root}/index.html" >/dev/null
grep -F '기능·안전 독립 검증 기준 <strong>b2037f3</strong>' \
  "${board_root}/index.html" >/dev/null
grep -F '현재 서버가 이 버전인지 원격 재확인 전' "${board_root}/index.html" >/dev/null
grep -F 'id="review-draft"' "${board_root}/index.html" >/dev/null
grep -F 'id="copy-review-draft"' "${board_root}/index.html" >/dev/null
grep -F 'id="trace-col-decision"' "${board_root}/index.html" >/dev/null
grep -F 'id="trace-col-approval"' "${board_root}/index.html" >/dev/null
grep -F 'id="review-handoff-title" tabindex="-1"' "${board_root}/index.html" >/dev/null
[[ "$(grep -c 'keyConditions:' "${board_root}/app.js")" -eq 10 ]]
[[ "$(grep -c '{ name:' "${board_root}/app.js")" -eq 35 ]]
[[ "$(grep -c 'reply:' "${board_root}/app.js")" -eq 35 ]]
[[ "$(grep -c 'executionBoundary:' "${board_root}/app.js")" -eq 3 ]]
grep -F '향후 QA 범위·보존 기준 동의' "${board_root}/app.js" >/dev/null
grep -F '이 선택만으로 시험 서버 자원을 만들거나 바꾸지 않아요.' \
  "${board_root}/app.js" >/dev/null
grep -F '공유 환경 복구 명령은 대상과 영향을 확인한 뒤 직전에 다시 승인해요.' \
  "${board_root}/app.js" >/dev/null
grep -F '실제 주문·Java 중단·전체 전환은 각 단계의 별도 승인 전까지 실행하지 않아요.' \
  "${board_root}/app.js" >/dev/null
grep -F 'class="next-decision"' "${board_root}/app.js" >/dev/null
grep -F 'candidate.open = false' "${board_root}/app.js" >/dev/null
grep -F 'headers="${headerId}" data-label="${label}"' "${board_root}/app.js" >/dev/null
grep -F 'C5-A v2 단건 제출·대조 → 사용자 선택. C5-B 선택 시에만 범위·기간 승인 후 5주기 자동운용 → C6 전환 승인' \
  "${board_root}/app.js" >/dev/null
grep -F 'data-view="runway"' "${board_root}/index.html" >/dev/null
grep -F 'data-panel="runway"' "${board_root}/index.html" >/dev/null
grep -F 'C2 개발 6묶음' "${board_root}/index.html" >/dev/null
[[ "$(grep -c 'class="bundle-number"' "${board_root}/index.html")" -eq 6 ]]
grep -F '20거래일 + 5주기 + 20거래일' "${board_root}/index.html" >/dev/null
python3 - "${board_root}/index.html" <<'PY'
from pathlib import Path
import sys

board = Path(sys.argv[1]).read_text(encoding="utf-8")


def section(start: str, end: str) -> str:
    start_at = board.index(start)
    end_at = board.index(end, start_at)
    return board[start_at:end_at]


day_flow = section('<section class="day-flow"', '</section>')
daily_markers = [
    '<strong>16:15 ET</strong>',
    '<strong>다음 거래일 09:45 ET</strong>',
    '<strong>주문 의도만 만들기</strong>',
    '<strong>Java와 비교하기</strong>',
    '<strong>주문 제출 0건 확인</strong>',
]
if day_flow.count("<li>") != 5:
    raise SystemExit("runway daily flow must contain exactly five steps")
positions = [day_flow.index(marker) for marker in daily_markers]
if positions != sorted(positions):
    raise SystemExit("runway daily flow order changed")

scope = section('<div class="scope-column-grid">', '</div>')
if scope.count("<article>") != 3:
    raise SystemExit("runway authority scope must contain exactly three cards")

cutover = section('<ol class="cutover-steps">', '</ol>')
stage_markers = [
    '<span>C0-A</span><strong>제품 규칙 승인</strong>',
    '<span>C0-B ①</span><strong>운영값 읽기 승인</strong>',
    '<span>C0-B ②</span><strong>운영값 결과 재승인</strong>',
    '<span>C2</span><strong>주문 없는 자동 병행 개발</strong>',
    '<span>C2 후보</span><strong>후보 배포 별도 승인</strong>',
    '<span>C3 승인</span><strong>대상 · 기간 · 영향 승인</strong>',
    '<span>C3</span><strong>세 레인 20거래일 관찰</strong>',
    '<span>C4-A</span><strong>LIVE 후보 격리 인수</strong>',
    '<span>C4-B</span><strong>주문 없는 복구 훈련</strong>',
    '<span>C5 후보</span><strong>제출 차단 배포 별도 승인</strong>',
    '<span>C5-A</span><strong>사용자 별도 승인 뒤 v2 1회 제출</strong>',
    '<span>C5-B</span><strong>범위 · 기간 승인 뒤 시스템 자동</strong>',
    '<span>C6</span><strong>v2 단독 운영 전환</strong>',
    '<span>C7</span><strong>20거래일 뒤 Java 퇴역 승인</strong>',
]
if cutover.count("<li") != 14:
    raise SystemExit("runway cutover must contain exactly fourteen stages")
positions = [cutover.index(marker) for marker in stage_markers]
if positions != sorted(positions):
    raise SystemExit("runway cutover stage order changed")
PY
grep -F '[IMPLEMENTATION_RUNWAY.md](IMPLEMENTATION_RUNWAY.md)' \
  "${cutover_root}/README.md" >/dev/null
grep -F 'C0-A 확정 → C0-B 수집 별도 승인 → 정제된 운영값 수집·차이' \
  "${cutover_root}/IMPLEMENTATION_RUNWAY.md" >/dev/null
grep -F 'C2-6. 전체 흐름을 주문 없이 연결' \
  "${cutover_root}/IMPLEMENTATION_RUNWAY.md" >/dev/null
if grep -Eq '기존 Java 자동운용은 그대로 유지|현재 주문 소유권</span><strong>기존 Java|주문은 매번 사용자 승인|매 실행 승인' \
  "${board_root}/index.html" "${board_root}/app.js"; then
  printf 'planning board overstates Java runtime facts or the future approval model\n' >&2
  exit 1
fi
if grep -Eq 'ALGORITHM_DIFF|algorithm parity|operational parity|bid/ask|haircut|manifest|fake-clock|fixture|snapshot|exporter|owner-only|scheduler|execution=false|GET/HEAD' \
  "${board_root}/app.js"; then
  printf 'planning board must lead with plain Korean instead of unexplained technical terms\n' >&2
  exit 1
fi
if grep -Eiq 'fetch\(|XMLHttpRequest|WebSocket|localStorage|sessionStorage|indexedDB|<form([[:space:]>])' \
  "${board_root}/index.html" "${board_root}/app.js"; then
  printf 'planning board must remain local-only and non-submitting\n' >&2
  exit 1
fi
node --check "${board_root}/app.js"
node --check "${board_root}/verify-runway.mjs"
node --check "${runway_evidence_verifier}"
python3 - "${board_root}/styles.css" <<'PY'
from pathlib import Path
import re
import sys

css = Path(sys.argv[1]).read_text(encoding="utf-8")
light_tokens = css.split("@media", 1)[0]
colors = dict(re.findall(r"--([a-z-]+):\s*(#[0-9a-fA-F]{6})", light_tokens))


def luminance(value: str) -> float:
    channels = []
    for offset in (1, 3, 5):
        channel = int(value[offset : offset + 2], 16) / 255
        channels.append(
            channel / 12.92
            if channel <= 0.04045
            else ((channel + 0.055) / 1.055) ** 2.4
        )
    return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]


def contrast(foreground: str, background: str) -> float:
    first = luminance(foreground)
    second = luminance(background)
    return (max(first, second) + 0.05) / (min(first, second) + 0.05)


for foreground, background in (
    ("green", "green-soft"),
    ("orange", "orange-soft"),
    ("red", "red-soft"),
):
    ratio = contrast(colors[foreground], colors[background])
    if ratio < 4.5:
        raise SystemExit(
            f"planning status contrast is below 4.5:1: {foreground}/{background}={ratio:.2f}"
        )

for background in ("page", "surface", "surface-soft"):
    ratio = contrast(colors["text-secondary"], colors[background])
    if ratio < 4.5:
        raise SystemExit(
            "planning secondary text contrast is below 4.5:1: "
            f"text-secondary/{background}={ratio:.2f}"
        )

blue_ratio = contrast(colors["blue-solid"], "#ffffff")
if blue_ratio < 4.5:
    raise SystemExit(f"planning blue badge contrast is below 4.5:1: {blue_ratio:.2f}")
PY

assert_row_terms() {
  local artifact_path="$1"
  local row_marker="$2"
  shift 2

  local matched_row
  if [[ "$(grep -Fc -- "${row_marker}" "${artifact_path}")" -ne 1 ]]; then
    printf 'expected exactly one trace row containing %s in %s\n' \
      "${row_marker}" "${artifact_path}" >&2
    exit 1
  fi
  matched_row="$(grep -F -- "${row_marker}" "${artifact_path}")"
  for required_term in "$@"; do
    if [[ "${matched_row}" != *"${required_term}"* ]]; then
      printf 'trace row %s in %s is missing %s\n' \
        "${row_marker}" "${artifact_path}" "${required_term}" >&2
      exit 1
    fi
  done
}

assert_row_terms "${cutover_root}/TRACEABILITY_QA.md" '| `V2-LIV-001` |' \
  C4-A 'broker simulator' 'durable journal' '단일 주문 소유권' \
  '자동 재전송 금지' 'crash matrix' C4-B 'C5-A/B'
assert_row_terms "${cutover_root}/TRACEABILITY_QA.md" '| `V2-REL-001` |' \
  C4-A '실증권사 미연결' simulator 'crash matrix' '단일 소유권' \
  C4-B '후보 배포 승인' C5-A C5-B C6

for trace_artifact in "${cutover_root}/DELIVERY_TRACE.md" "${board_root}/app.js"; do
  if [[ "${trace_artifact}" == *DELIVERY_TRACE.md ]]; then
    d0102_marker='| D-01~02 · C0-B |'
    d03_marker='| D-03 |'
    d04_marker='| D-04 |'
    d0506_marker='| D-05~06 |'
    d10_marker='| D-10 |'
    c1_isolation_marker='| C1 격리 |'
    current_ui_marker='| C1 현재 UI |'
    future_ui_marker='| C2 미래 화면 |'
    c2_summary_marker='| C2 종합 |'
    common_spec_marker='| 공통 명세 |'
  else
    d0102_marker='["D-01~02",'
    d03_marker='["D-03",'
    d04_marker='["D-04",'
    d0506_marker='["D-05~06",'
    d10_marker='["D-10",'
    c1_isolation_marker='["C1 격리",'
    d07_marker='["D-07",'
    current_ui_marker='["현재 UI",'
    future_ui_marker='["C2 미래 화면",'
    c2_summary_marker='["C2 종합",'
    common_spec_marker='["공통 명세",'
  fi

  if [[ "${trace_artifact}" == *DELIVERY_TRACE.md ]]; then
    d07_marker='| D-07 |'
  fi

  assert_row_terms "${trace_artifact}" "${d07_marker}" \
    V2-QA-001 QA-NAV-001 QA-OPS-001 QA-RWD-001 QA-SAF-001 QA-OPS-002 QA-MAN-001 \
    '별도 실행 승인'
  assert_row_terms "${trace_artifact}" "${current_ui_marker}" \
    V2-UI-001 V2-OPS-001 S-01 S-02 S-03 S-05 S-06 S-07 \
    QA-NAV-001 QA-OPS-001 QA-SAF-001 QA-RWD-001
  assert_row_terms "${trace_artifact}" "${future_ui_marker}" \
    V2-OPS-001 V2-API-001 S-01 S-04 S-08 QA-SHD-001 '결과 재승인'
  assert_row_terms "${trace_artifact}" "${c2_summary_marker}" \
    V2-AUT-001 V2-DAT-001 V2-PER-001 V2-OPS-001 V2-API-001 \
    S-01 S-04 S-08 QA-SHD-001 QA-PAR-001 '결과 재승인'
  assert_row_terms "${trace_artifact}" "${d03_marker}" \
    V2-AUT-001 V2-DAT-001 QA-SHD-001 '결과 재승인'
  assert_row_terms "${trace_artifact}" "${d04_marker}" \
    V2-DAT-001 V2-SAF-001 QA-SAF-001 '결과 재승인'
  assert_row_terms "${trace_artifact}" "${d0506_marker}" \
    V2-STR-002 V2-PER-001 QA-PAR-001 '결과 재승인'
  if [[ "${trace_artifact}" == *DELIVERY_TRACE.md ]]; then
    assert_row_terms "${trace_artifact}" "${d10_marker}" \
      V2-LIV-001 V2-APR-001 C4-A simulator 'crash matrix' '단일 주문 소유권' \
      QA-LIV-001 QA-RCV-001 C4-B 'C5 후보' C5-A C5-B 'Java 복원 선택 시'
  else
    assert_row_terms "${trace_artifact}" "${d10_marker}" \
      V2-LIV-001 V2-APR-001 C4-A '실증권사 미연결 모의 제출기' \
      '장애 시점별 시험' '단일 소유권' QA-LIV-001 QA-RCV-001 \
      C4-B 'C5 후보' C5-A C5-B
  fi
  assert_row_terms "${trace_artifact}" "${d0102_marker}" \
    V2-STR-001 V2-STR-002 V2-DAT-001 '수집' '결과'
  assert_row_terms "${trace_artifact}" "${c1_isolation_marker}" \
    V2-CUT-001 V2-CUT-002 '수집' '결과'
  assert_row_terms "${trace_artifact}" "${common_spec_marker}" \
    V2-DOC-001 S-01 S-04 S-08 S-09 S-10 QA-PLN-001 QA-RWY-001
done

assert_row_terms "${cutover_root}/TRACEABILITY_QA.md" '| `QA-LIV-001` |' \
  C4-A simulator 'durable journal' '단일 주문 소유권' '실제 자격증명' '실주문은 정확히 0'
assert_row_terms "${cutover_root}/TRACEABILITY_QA.md" '| `QA-RCV-001` |' \
  C4-B '제출 차단' '미확정 주문 0건' snapshot RTO/RPO '사용자 실행 승인 필수'
assert_row_terms "${cutover_root}/TRACEABILITY_QA.md" '| `QA-MAN-004` |' \
  C7 Java '서비스·스케줄·자동 주문 경로 퇴역' '설정·데이터·감사·복구 artifact 보존'
assert_row_terms "${cutover_root}/TRACEABILITY_QA.md" '| `V2-RBK-001` |' \
  QA-INF-001 QA-RCV-001 C4-B '제출 차단·주문 없는 복구 인수는 계획·미구현'
assert_row_terms "${cutover_root}/DELIVERY_TRACE.md" '| D-09 |' \
  V2-RBK-001 QA-INF-001 QA-RCV-001 C4-B '공유 환경 훈련은 실행 직전 승인'
assert_row_terms "${board_root}/app.js" '["D-09",' \
  V2-RBK-001 QA-INF-001 QA-RCV-001 C4-B '계획·미구현' '공유 환경 실행은 별도 승인'
for release_gate in \
  '| C4-A LIVE 후보 격리 인수 |' \
  '| C4-B 주문 없는 복구 |' \
  '| C5 후보 배포 |' \
  '| C7 Java 퇴역 |'; do
  grep -F "${release_gate}" "${cutover_root}/TRACEABILITY_QA.md" >/dev/null
done
assert_row_terms "${cutover_root}/TRACEABILITY_QA.md" '| C4-A LIVE 후보 격리 인수 |' \
  QA-LIV-001 simulator '실주문 0'
assert_row_terms "${cutover_root}/TRACEABILITY_QA.md" '| C4-B 주문 없는 복구 |' \
  QA-RCV-001 '제출 차단' '미확정 주문 0'
assert_row_terms "${cutover_root}/TRACEABILITY_QA.md" '| C5 후보 배포 |' \
  '동일 SHA' '제출 차단' preflight
assert_row_terms "${cutover_root}/TRACEABILITY_QA.md" '| C7 Java 퇴역 |' \
  QA-MAN-004 '서비스·스케줄·주문 경로 중단' 'artifact 보존'

grep -F 'D-07 선택은 공유 시험 서버 자원 생성·변경의 실행 승인이 아님' \
  "${cutover_root}/REQUIREMENTS.md" >/dev/null
grep -F '이 선택은 향후 QA의 범위·보존 기준만 정한다.' \
  "${cutover_root}/C0_DECISIONS.md" >/dev/null
grep -F '공유 시험 서버 생성은' "${cutover_root}/SCREEN_SPEC.md" >/dev/null
grep -F '공유 시험 서버 생성은 대상·영향·정리 방법 확인 뒤 실행 직전 사용자 승인 필수' \
  "${cutover_root}/TRACEABILITY_QA.md" >/dev/null
grep -F '섀도 개발과 관련 로컬·mock·일회용 격리 테스트는 **C0-A 확정 → C0-B 읽기 전용' \
  "${cutover_root}/TRACEABILITY_QA.md" >/dev/null
grep -F '수집 별도 승인 → 수집·차이 확인 → C0-B 결과 재승인 이후** 승인된 전략·시점 기준' \
  "${cutover_root}/TRACEABILITY_QA.md" >/dev/null
grep -F '공유 시험 서버의 자원·데이터 생성·상태 변경·스케줄 시작' \
  "${cutover_root}/TRACEABILITY_QA.md" >/dev/null
grep -F '아래 표는 그 승인 뒤의 미래 자동 동작 명세이며 현재 실행 권한이' \
  "${cutover_root}/FUNCTIONAL_SPEC.md" >/dev/null
grep -F 'C0-B 결과 재승인 뒤 섀도 가시성 개발·로컬 화면 QA 자동, 공유 시험 서버 조회·데이터 생성은 실행 승인 필수' \
  "${cutover_root}/REQUIREMENTS.md" >/dev/null
grep -F 'C0-B 결과 재승인 뒤 섀도 상태 개발·로컬 QA 자동, 공유 시험 서버 조회·데이터 생성은 실행 직전 승인 필수' \
  "${cutover_root}/SCREEN_SPEC.md" >/dev/null
grep -F '| C0-B 읽기 전용 수집 승인 | - | - | 승인 대기 | - | - |' \
  "${cutover_root}/C0_DECISIONS.md" >/dev/null
grep -F '접근방법=GitHub Actions PROD SSH로 운영 호스트 shell 조회·DB SELECT; 권한=읽기 전용; 정제=필수; 저장위치=docs/v2-cutover/evidence/c0b/; 원문저장=금지; 보존=Git 이력' \
  "${cutover_root}/C0_DECISIONS.md" >/dev/null
grep -F '가장 늦은 C0-A 승인 ≤ C0-B 수집 승인 ≤ 각 항목 캡처 ≤ snapshot 완성 ≤' \
  "${cutover_root}/C0_DECISIONS.md" >/dev/null
grep -F '① 운영값 수집 승인 → ② 수집 결과 재승인' \
  "${board_root}/index.html" >/dev/null
grep -F 'C0-B 운영값 수집을 승인하지 않습니다.' \
  "${board_root}/app.js" >/dev/null
grep -F 'GitHub 자동화를 통해 운영 서버의 설정과 DB를 조회' \
  "${board_root}/app.js" >/dev/null
grep -F '조회 명령만 허용 · 변경 명령 없음' "${board_root}/app.js" >/dev/null
grep -F '정제·마스킹한 증적만 지정 폴더에 저장 · docs/v2-cutover/evidence/c0b/' \
  "${board_root}/app.js" >/dev/null
grep -F '프로젝트 변경 이력에 계속 남음' "${board_root}/app.js" >/dev/null
grep -F '<th id="trace-col-approval" scope="col">승인 순서</th>' \
  "${board_root}/index.html" >/dev/null
grep -F '기준 digest 갱신 자체는 이 gate가' "${cutover_root}/C0_DECISIONS.md" >/dev/null
if grep -Eq '승인된 범위 내 자동 생성 가능|C0 승인 후 자동 가능|C0에서.*승인 후.*자동 생성 가능|스테이징 테스트 데이터 생성은.*C0 범위 승인 이후|승인된 종류·건수 안에서만 자동 진행|C0 범위 승인 이후.*자동 진행 가능' \
  "${cutover_root}/REQUIREMENTS.md" \
  "${cutover_root}/FUNCTIONAL_SPEC.md" \
  "${cutover_root}/SCREEN_SPEC.md" \
  "${cutover_root}/TRACEABILITY_QA.md"; then
  printf 'C0 planning approval must not authorize shared test-server mutations\n' >&2
  exit 1
fi
if grep -Eq 'C0-A/B|C0-A 승인 뒤 읽기 전용 수집만 자동 가능|C0-A가 끝난 뒤 읽기 전용으로 수집|C0-A 승인 뒤 C2 개발을 시작하기 전에 C0-B에서' \
  "${cutover_root}/C0_DECISIONS.md" \
  "${cutover_root}/CUTOVER_ROLLBACK.md" \
  "${cutover_root}/FUNCTIONAL_SPEC.md" \
  "${cutover_root}/README.md" \
  "${cutover_root}/REQUIREMENTS.md" \
  "${cutover_root}/SCREEN_SPEC.md" \
  "${cutover_root}/TRACEABILITY_QA.md" \
  "${board_root}/index.html" \
  "${board_root}/app.js"; then
  printf 'C0-A must not authorize C0-B collection or pre-reapproval C2 work\n' >&2
  exit 1
fi

for template_heading in \
  '## 3. 사용자 결정' \
  '## 4. 기능 계약' \
  '## 5. 화면 기획' \
  '## 6. API·권한·보안' \
  '## 7. QA와 증적' \
  '## 8. 배포와 복구' \
  '## 9. 완료 판정'; do
  grep -Fx "${template_heading}" \
    "${repository_root}/docs/templates/V2_FEATURE_PROPOSAL_TEMPLATE.md" >/dev/null
done

grep -F '[v2 전환 검토 보드](review-board/index.html)' \
  "${cutover_root}/README.md" >/dev/null
grep -F '"review:planning": "python3 -m http.server 4181 --bind 127.0.0.1 --directory ../../docs/v2-cutover"' \
  "${repository_root}/v2/frontend/package.json" >/dev/null
grep -F 'http://127.0.0.1:4181/review-board/' \
  "${cutover_root}/README.md" >/dev/null
grep -F "baseURL: 'http://127.0.0.1:4182/docs/v2-cutover/review-board/'" \
  "${repository_root}/v2/frontend/playwright.planning.config.ts" >/dev/null
grep -F "python3 -m http.server 4182 --bind 127.0.0.1 --directory ../.." \
  "${repository_root}/v2/frontend/playwright.planning.config.ts" >/dev/null
grep -F "const manifestPath = testInfo.outputPath('planning-320-manifest.json');" \
  "${repository_root}/v2/frontend/e2e-planning/review-board.spec.ts" >/dev/null
grep -F 'path: manifestPath' \
  "${repository_root}/v2/frontend/e2e-planning/review-board.spec.ts" >/dev/null
grep -F 'QA-RWY-001 PASS' "${board_root}/verify-runway.mjs" >/dev/null
grep -F "'docs/v2-cutover/IMPLEMENTATION_RUNWAY.md'" \
  "${board_root}/verify-runway.mjs" >/dev/null
grep -F "check(!('RUNWAY_BASE_URL' in process.env)" \
  "${board_root}/verify-runway.mjs" >/dev/null
grep -Eq "listen\(0,[[:space:]]*['\"]127\.0\.0\.1['\"]" \
  "${board_root}/verify-runway.mjs"
grep -F '.address()' "${board_root}/verify-runway.mjs" >/dev/null
grep -F '/docs/v2-cutover/review-board/styles.css' \
  "${board_root}/verify-runway.mjs" >/dev/null
grep -F '/docs/v2-cutover/review-board/app.js' \
  "${board_root}/verify-runway.mjs" >/dev/null
grep -F 'const allowedRequestUrls = new Set([' \
  "${board_root}/verify-runway.mjs" >/dev/null
grep -F 'allowedRequestUrls.size === 3' \
  "${board_root}/verify-runway.mjs" >/dev/null
grep -Eq "\.route\(" "${board_root}/verify-runway.mjs"
grep -F 'route.abort(' "${board_root}/verify-runway.mjs" >/dev/null
grep -Eq "\.on\(['\"]websocket['\"]" "${board_root}/verify-runway.mjs"
grep -Eq "\.on\(['\"]popup['\"]" "${board_root}/verify-runway.mjs"
grep -Eq "\.on\(['\"]download['\"]" "${board_root}/verify-runway.mjs"
grep -F 'addInitScript' "${board_root}/verify-runway.mjs" >/dev/null
for storage_method in setItem removeItem clear; do
  grep -F "${storage_method}" "${board_root}/verify-runway.mjs" >/dev/null
done
for daily_step in \
  '16:15 ET' '다음 거래일 09:45 ET' '주문 의도만 만들기' \
  'Java와 비교하기' '주문 제출 0건 확인'; do
  grep -F "${daily_step}" "${board_root}/verify-runway.mjs" >/dev/null
done
grep -F 'const CUTOVER_CARDS = [' \
  "${board_root}/verify-runway.mjs" >/dev/null
grep -F 'same(contracts.cutoverCards, CUTOVER_CARDS' \
  "${board_root}/verify-runway.mjs" >/dev/null
for exact_stage in \
  'C0-A' 'C0-B ①' 'C0-B ②' 'C2 후보' 'C3 승인' 'C3' \
  'C4-A' 'C4-B' 'C5 후보' 'C5-A' 'C5-B' 'C6' 'C7'; do
  grep -F "${exact_stage}" "${board_root}/verify-runway.mjs" >/dev/null
done

grep -F 'v2/frontend/artifacts/planning-runway-verified' \
  "${runway_evidence_verifier}" >/dev/null
for runway_evidence_file in \
  QA-RWY-001-desktop.png \
  QA-RWY-001-mobile.png \
  QA-RWY-001-manifest.json; do
  grep -F "${runway_evidence_file}" "${runway_evidence_verifier}" >/dev/null
done
grep -F 'readdir' "${runway_evidence_verifier}" >/dev/null
grep -F 'lstat' "${runway_evidence_verifier}" >/dev/null
grep -F 'createHash' "${runway_evidence_verifier}" >/dev/null
grep -F 'QA-RWY-001' "${runway_evidence_verifier}" >/dev/null
bash -n "${runway_wrapper}"
for wrapper_guard in \
  'remote tcp "localhost:*"' \
  'qa_darwin_environment=(' \
  '"${qa_env_bin}" -i' \
  "readonly qa_unshare_bin='/usr/bin/unshare'" \
  "readonly qa_ip_bin='/usr/sbin/ip'" \
  ' --net -- ' \
  '/usr/bin/env -i' \
  '--clear-groups' \
  '--bounding-set=-all' \
  '--no-new-privs' \
  'QA_RUNWAY_NETWORK_ISOLATION=linux-network-namespace'; do
  grep -F -- "${wrapper_guard}" "${runway_wrapper}" >/dev/null
done
for main_guard in \
  'QA_RUNWAY_NETWORK_ISOLATION must be exactly' \
  '192.0.2.1' \
  'OS UDP egress probe' \
  'OS TCP egress probe' \
  'localStorage' \
  "Object.getOwnPropertyDescriptor(Document.prototype, 'cookie')" \
  'globalThis.caches' \
  'CacheStorage?.prototype' \
  'AbortSignal' \
  'MessageChannel' \
  'Scheduler?.prototype' \
  'RTCPeerConnection' \
  'SharedWorker' \
  'requestAnimationFrame' \
  'metaRefresh' \
  'checkVisibility({ checkOpacity: true, checkVisibilityCSS: true })' \
  'hasSafeRenderedStyle' \
  'webkitTextFillColor' \
  'document.elementsFromPoint' \
  'DOM.getNodeForLocation' \
  'DOM.querySelectorAll' \
  'ignorePointerEventsNone' \
  'cdp-backend-node-tree' \
  'cdp-isolated-world' \
  'collectIsolatedVisualAudit' \
  'textPaintSafeNodeCount' \
  'contentFitSafeNodeCount' \
  'contrastSafeNodeCount' \
  'minimumContrastRatio' \
  'leafSurfaceSafeNodeCount' \
  'leafPseudoSafeNodeCount' \
  'leafPseudoFailures' \
  'opaqueLeafSurface' \
  'rootSurface' \
  'dangerousPseudoSurfaces' \
  'extremePaintSurfaces' \
  'cdpOcclusionAudit' \
  'isolatedVisualAudit' \
  "Emulation.setScriptExecutionDisabled" \
  'root.querySelectorAll' \
  'pseudoTextNodes' \
  'assertSourceSnapshotUnchanged' \
  'exact static response provenance' \
  "schemaVersion: '4.0'"; do
  grep -F -- "${main_guard}" "${board_root}/verify-runway.mjs" >/dev/null
done
if grep -F 'data-qa-rwy-cdp-occlusion' "${board_root}/verify-runway.mjs" >/dev/null; then
  printf 'runway CDP occlusion audit must not expose a fixed DOM marker\n' >&2
  exit 1
fi
for independent_guard in \
  "manifest.schemaVersion === '4.0'" \
  'scriptExecutionDisabled' \
  'styleSafeCount' \
  'unoccludedCount' \
  'textPaintSafeNodeCount' \
  'contentFitSafeNodeCount' \
  'contrastSafeNodeCount' \
  'leafSurfaceSafeNodeCount' \
  'leafPseudoSafeNodeCount' \
  'minimumContrastRatio' \
  'rootSurface' \
  'dangerousPseudoSurfaces' \
  'extremePaintSurfaces' \
  'served in-memory byte digests' \
  'OS network isolation kind mismatch' \
  'declarative egress surface' \
  'contract node group keys'; do
  grep -F -- "${independent_guard}" "${runway_evidence_verifier}" >/dev/null
done

python3 - "${v2_ci_workflow}" <<'PY'
from pathlib import Path
import sys

workflow = Path(sys.argv[1]).read_text(encoding="utf-8")
main = "bash ../../docs/v2-cutover/review-board/run-verified-runway.sh"
direct_main = "node ../../docs/v2-cutover/review-board/verify-runway.mjs"
independent = "node ../../docs/v2-cutover/review-board/verify-runway-evidence.mjs"
zero_order = "        run: npm run test:e2e\n"
artifact_name = "name: v2-qa-runway-evidence-${{ github.run_attempt }}"
artifact_path = "path: v2/frontend/artifacts/planning-runway-verified"
single_markers = [main, zero_order, artifact_name, artifact_path]
if any(workflow.count(marker) != 1 for marker in single_markers):
    raise SystemExit("runway wrapper, zero-order QA, and upload markers must each occur exactly once")
if workflow.count(independent) != 2:
    raise SystemExit("runway evidence must be independently verified before and after zero-order QA")
if direct_main in workflow:
    raise SystemExit("runway main verifier must run only through the OS network-isolation wrapper")
first_independent = workflow.index(independent)
second_independent = workflow.index(independent, first_independent + len(independent))
positions = [workflow.index(main), first_independent, workflow.index(zero_order), second_independent, workflow.index(artifact_name), workflow.index(artifact_path)]
if positions != sorted(positions):
    raise SystemExit("runway wrapper, precheck, zero-order QA, final pre-upload check, and upload order changed")
runway_steps = workflow[workflow.index("- name: Verify the implementation runway without actions or orders"):workflow.index("- name: Upload UI QA evidence")]
if "continue-on-error" in runway_steps:
    raise SystemExit("runway verification and upload steps must remain fail-closed")
upload_start = workflow.rindex("- name: Upload independently verified runway evidence", 0, workflow.index(artifact_name))
upload_end = workflow.index("- name:", workflow.index(artifact_path))
upload = workflow[upload_start:upload_end]
for required in ("if: success()", "if-no-files-found: error", "retention-days: 30"):
    if required not in upload:
        raise SystemExit(f"runway success-only upload is missing {required}")
if "if: always()" in upload:
    raise SystemExit("runway verified evidence must not upload after a failed verifier or QA")
PY
grep -F '[`DELIVERY_TRACE.md`](DELIVERY_TRACE.md)' \
  "${cutover_root}/README.md" >/dev/null
for planning_ci_path in \
  'docs/v2-cutover/**' \
  'docs/templates/V2_FEATURE_PROPOSAL_TEMPLATE.md' \
  '.github/PULL_REQUEST_TEMPLATE/v2-feature.md'; do
  [[ "$(grep -Fc -- "- \"${planning_ci_path}\"" "${v2_ci_workflow}")" -eq 2 ]]
done
grep -F '39e1f896720ba8b4a7e33d3148ceb96b3ce284b4f4330e44c037690409483962' \
  "${cutover_root}/C0_DECISIONS.md" >/dev/null

printf 'C0 decision, planning board, single trace, feature template, and PR gate contract PASS\n'
