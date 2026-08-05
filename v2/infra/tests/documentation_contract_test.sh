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
  "${cutover_root}/CUTOVER_ROLLBACK.md"
  "${cutover_root}/DELIVERY_TRACE.md"
  "${cutover_root}/FUNCTIONAL_SPEC.md"
  "${cutover_root}/README.md"
  "${cutover_root}/REQUIREMENTS.md"
  "${cutover_root}/SCREEN_SPEC.md"
  "${cutover_root}/TRACEABILITY_QA.md"
  "${board_root}/index.html"
  "${board_root}/styles.css"
  "${board_root}/app.js"
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
    V2-QA-001 QA-NAV-001 QA-OPS-001 QA-RWD-001 QA-SAF-001 QA-OPS-002 QA-MAN-001 \
    '별도 실행 승인'
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

grep -F 'D-07 선택은 공유 시험 서버 자원 생성·변경의 실행 승인이 아님' \
  "${cutover_root}/REQUIREMENTS.md" >/dev/null
grep -F '이 선택은 향후 QA의 범위·보존 기준만 정한다.' \
  "${cutover_root}/C0_DECISIONS.md" >/dev/null
grep -F '공유 시험 서버 생성은' "${cutover_root}/SCREEN_SPEC.md" >/dev/null
grep -F '공유 시험 서버 생성은 대상·영향·정리 방법 확인 뒤 실행 직전 사용자 승인 필수' \
  "${cutover_root}/TRACEABILITY_QA.md" >/dev/null
grep -F '섀도 개발과 로컬·mock·일회용 격리 테스트는 **C0-A/B 승인 이후**' \
  "${cutover_root}/TRACEABILITY_QA.md" >/dev/null
grep -F '공유 시험 서버의 자원·데이터 생성·상태 변경·스케줄 시작' \
  "${cutover_root}/TRACEABILITY_QA.md" >/dev/null
grep -F '아래 표는 그 승인 뒤의 미래 자동 동작 명세이며 현재 실행 권한이' \
  "${cutover_root}/FUNCTIONAL_SPEC.md" >/dev/null
grep -F 'C0-A/B 뒤 섀도 가시성 개발·로컬 화면 QA 자동, 공유 시험 서버 조회·데이터 생성은 실행 승인 필수' \
  "${cutover_root}/REQUIREMENTS.md" >/dev/null
grep -F 'C0-A/B 뒤 섀도 상태 개발·로컬 QA 자동, 공유 시험 서버 조회·데이터 생성은 실행 직전 승인 필수' \
  "${cutover_root}/SCREEN_SPEC.md" >/dev/null
grep -F '기준 digest 갱신 자체는 이 gate가' "${cutover_root}/C0_DECISIONS.md" >/dev/null
if grep -Eq '승인된 범위 내 자동 생성 가능|C0 승인 후 자동 가능|C0에서.*승인 후.*자동 생성 가능|스테이징 테스트 데이터 생성은.*C0 범위 승인 이후|승인된 종류·건수 안에서만 자동 진행|C0 범위 승인 이후.*자동 진행 가능' \
  "${cutover_root}/REQUIREMENTS.md" \
  "${cutover_root}/FUNCTIONAL_SPEC.md" \
  "${cutover_root}/SCREEN_SPEC.md" \
  "${cutover_root}/TRACEABILITY_QA.md"; then
  printf 'C0 planning approval must not authorize shared test-server mutations\n' >&2
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
grep -F '[`DELIVERY_TRACE.md`](DELIVERY_TRACE.md)' \
  "${cutover_root}/README.md" >/dev/null
for planning_ci_path in \
  'docs/v2-cutover/**' \
  'docs/templates/V2_FEATURE_PROPOSAL_TEMPLATE.md' \
  '.github/PULL_REQUEST_TEMPLATE/v2-feature.md'; do
  [[ "$(grep -Fc -- "- \"${planning_ci_path}\"" "${v2_ci_workflow}")" -eq 2 ]]
done
grep -F 'a8b6bccefa6b13487bd62a3206c205fe460687d8b356c815adb6ff19419a7872' \
  "${cutover_root}/C0_DECISIONS.md" >/dev/null

printf 'C0 decision, planning board, single trace, feature template, and PR gate contract PASS\n'
