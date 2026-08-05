#!/usr/bin/env bash
set -Eeuo pipefail

readonly documentation_test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly repository_root="$(cd -- "${documentation_test_dir}/../../.." && pwd -P)"
readonly cutover_root="${repository_root}/docs/v2-cutover"
readonly board_root="${cutover_root}/review-board"
readonly v2_ci_workflow="${repository_root}/.github/workflows/v2-ci.yml"
readonly c0_gate_verifier="${documentation_test_dir}/verify_c0_gate.py"
readonly c0_gate_tests="${documentation_test_dir}/test_verify_c0_gate.py"

required_files=(
  "${cutover_root}/C0_DECISIONS.md"
  "${cutover_root}/DELIVERY_TRACE.md"
  "${cutover_root}/README.md"
  "${board_root}/index.html"
  "${board_root}/styles.css"
  "${board_root}/app.js"
  "${repository_root}/docs/templates/V2_FEATURE_PROPOSAL_TEMPLATE.md"
  "${repository_root}/.github/PULL_REQUEST_TEMPLATE/v2-feature.md"
  "${v2_ci_workflow}"
  "${repository_root}/v2/frontend/package.json"
  "${c0_gate_verifier}"
  "${c0_gate_tests}"
)
for required_file in "${required_files[@]}"; do
  [[ -f "${required_file}" ]] || {
    printf 'missing documentation contract file: %s\n' "${required_file}" >&2
    exit 1
  }
done

PYTHONDONTWRITEBYTECODE=1 python3 "${c0_gate_verifier}" "${repository_root}"
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s "${documentation_test_dir}" \
  -p 'test_verify_c0_gate.py'
[[ "$(grep -Ec '^    def test_' "${c0_gate_tests}")" -eq 51 ]]
grep -F 'C0 gate 51/51 PASS' "${cutover_root}/DELIVERY_TRACE.md" >/dev/null
grep -F 'C0 기록 검사 51/51 로컬 통과' "${board_root}/app.js" >/dev/null

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
grep -F '복사만으로는 전송되거나 승인되지 않아요.' "${board_root}/index.html" >/dev/null
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
grep -F '기능 안전 검증 기준' "${board_root}/index.html" >/dev/null
grep -F 'a6a7174' "${board_root}/index.html" >/dev/null
grep -F 'PR #57 · Draft' "${board_root}/index.html" >/dev/null
grep -F '현재 서버가 이 버전인지 원격 재확인 전' "${board_root}/index.html" >/dev/null
grep -F 'id="review-draft"' "${board_root}/index.html" >/dev/null
grep -F 'id="copy-review-draft"' "${board_root}/index.html" >/dev/null
[[ "$(grep -c 'keyConditions:' "${board_root}/app.js")" -eq 10 ]]
[[ "$(grep -c '{ name:' "${board_root}/app.js")" -eq 35 ]]
[[ "$(grep -c 'reply:' "${board_root}/app.js")" -eq 35 ]]
grep -F 'C5-A 단건 승인 → C5-B 범위·기간 승인 후 5주기 자동운용 → C6 전환 승인' \
  "${board_root}/app.js" >/dev/null
if grep -Eq '기존 Java 자동운용은 그대로 유지|현재 주문 소유권</span><strong>기존 Java|주문은 매번 사용자 승인|매 실행 승인' \
  "${board_root}/index.html" "${board_root}/app.js"; then
  printf 'planning board overstates Java runtime facts or the future approval model\n' >&2
  exit 1
fi
if grep -Eq 'ALGORITHM_DIFF|algorithm parity|operational parity|bid/ask|haircut|manifest|fake-clock|fixture|snapshot|exporter|owner-only|scheduler|broker simulator|canary|LIVE|execution=false|GET/HEAD' \
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


for foreground, background in (("orange", "orange-soft"), ("red", "red-soft")):
    ratio = contrast(colors[foreground], colors[background])
    if ratio < 4.5:
        raise SystemExit(
            f"planning status contrast is below 4.5:1: {foreground}/{background}={ratio:.2f}"
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

for trace_artifact in "${cutover_root}/DELIVERY_TRACE.md" "${board_root}/app.js"; do
  if [[ "${trace_artifact}" == *DELIVERY_TRACE.md ]]; then
    current_ui_marker='| C1 현재 UI |'
    future_ui_marker='| C2 미래 화면 |'
    c2_summary_marker='| C2 종합 |'
    common_spec_marker='| 공통 명세 |'
  else
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
    V2-QA-001 QA-NAV-001 QA-OPS-001 QA-RWD-001 QA-SAF-001 QA-OPS-002 QA-MAN-001
  assert_row_terms "${trace_artifact}" "${current_ui_marker}" \
    V2-UI-001 V2-OPS-001 S-01 S-02 S-03 S-05 S-06 S-07 \
    QA-NAV-001 QA-OPS-001 QA-SAF-001 QA-RWD-001
  assert_row_terms "${trace_artifact}" "${future_ui_marker}" \
    V2-OPS-001 V2-API-001 S-01 S-04 S-08 QA-SHD-001
  assert_row_terms "${trace_artifact}" "${c2_summary_marker}" \
    V2-AUT-001 V2-DAT-001 V2-PER-001 V2-OPS-001 V2-API-001 \
    S-01 S-04 S-08 QA-SHD-001 QA-PAR-001
  assert_row_terms "${trace_artifact}" "${common_spec_marker}" \
    V2-DOC-001 S-01 S-04 S-08 S-09 S-10 QA-PLN-001
done

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
grep -F '[`DELIVERY_TRACE.md`](DELIVERY_TRACE.md)' \
  "${cutover_root}/README.md" >/dev/null
for planning_ci_path in \
  'docs/v2-cutover/**' \
  'docs/templates/V2_FEATURE_PROPOSAL_TEMPLATE.md' \
  '.github/PULL_REQUEST_TEMPLATE/v2-feature.md'; do
  [[ "$(grep -Fc -- "- \"${planning_ci_path}\"" "${v2_ci_workflow}")" -eq 2 ]]
done
grep -F '8665c89d83c2db8c412a4aa1cdd3e6c2e0aaad6fd904adb328fb0569e80bd275' \
  "${cutover_root}/C0_DECISIONS.md" >/dev/null

printf 'C0 decision, planning board, single trace, feature template, and PR gate contract PASS\n'
