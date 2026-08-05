import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { expect, test, type Locator, type Page, type TestInfo } from '@playwright/test';

async function expectNoHorizontalOverflow(page: Page) {
  const hasHorizontalOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
  );
  expect(hasHorizontalOverflow).toBe(false);
}

async function settleLayout(page: Page) {
  await page.evaluate(
    () => new Promise<void>((resolveFrame) => {
      requestAnimationFrame(() => requestAnimationFrame(() => resolveFrame()));
    })
  );
}

async function expectCompactReviewPage(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollHeight)).toBeLessThanOrEqual(10_000);
}

async function expectFullyVisible(locator: Locator) {
  await expect.poll(
    () => locator.evaluate((element) => {
      const rect = element.getBoundingClientRect();
      return rect.top >= -1 && rect.bottom <= window.innerHeight + 1;
    }),
    { message: 'focused navigation target must remain inside the viewport' }
  ).toBe(true);
}

function relativeLuminance(hex: string) {
  const channels = hex
    .replace('#', '')
    .match(/.{2}/g)!
    .map((value) => Number.parseInt(value, 16) / 255)
    .map((value) => (value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4));
  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
}

function contrastRatio(foreground: string, background: string) {
  const foregroundLuminance = relativeLuminance(foreground);
  const backgroundLuminance = relativeLuminance(background);
  return (
    (Math.max(foregroundLuminance, backgroundLuminance) + 0.05) /
    (Math.min(foregroundLuminance, backgroundLuminance) + 0.05)
  );
}

async function expectSecondaryTextContrast(page: Page) {
  const tokens = await page.evaluate(() => {
    const styles = getComputedStyle(document.documentElement);
    return Object.fromEntries(
      ['text-secondary', 'page', 'surface', 'surface-soft', 'green', 'green-soft'].map((token) => [
        token,
        styles.getPropertyValue(`--${token}`).trim()
      ])
    );
  });
  for (const background of ['page', 'surface', 'surface-soft']) {
    expect(
      contrastRatio(tokens['text-secondary'], tokens[background]),
      `--text-secondary must meet 4.5:1 against --${background}`
    ).toBeGreaterThanOrEqual(4.5);
  }
  expect(
    contrastRatio(tokens.green, tokens['green-soft']),
    '--green must meet 4.5:1 against --green-soft'
  ).toBeGreaterThanOrEqual(4.5);
}

type EvidenceRecord = {
  name: string;
  sha256: string;
  width: number;
  height: number;
  viewport: { width: number; height: number } | null;
};

async function attachPngEvidence(
  testInfo: TestInfo,
  records: EvidenceRecord[],
  name: string,
  body: Buffer,
  viewport: { width: number; height: number } | null
) {
  const record = {
    name,
    sha256: createHash('sha256').update(body).digest('hex'),
    width: body.readUInt32BE(16),
    height: body.readUInt32BE(20),
    viewport
  };
  if (viewport?.width === 320) {
    expect(record.height, `${name} must remain at or below 10,000px`).toBeLessThanOrEqual(10_000);
  }
  records.push(record);
  await testInfo.attach(name, { body, contentType: 'image/png' });
}

const expectedDecisionReplies = [
  ['A 승인', '수정 요청', '설명 요청'],
  ['A 승인', '처음부터 데이터 의미 수정', '설명 요청'],
  ['권장안 승인', '시각/신선도 수정', '설명 요청'],
  ['A 승인', '기존 방식 유지', '설명 요청'],
  ['A 승인', '관리 밖 종목 정책 수정', '수량 규칙 수정', '설명 요청'],
  ['A 승인', '공통 입력 비교만 사용', '설명 요청'],
  ['향후 QA 범위·보존 기준 동의', '전부 읽기 전용', '범위/보존 수정', '설명 요청'],
  ['A 승인', '관찰 기간/기준 수정', '설명 요청'],
  ['A 승인', '복구 목표 수정', '허용 명령 지정', '설명 요청'],
  [
    'A를 미래 실전 명세로 승인',
    '주문 연속/중단 정책 수정',
    '제한 시험 횟수 수정',
    '자동 제출 제외',
    '설명 요청'
  ]
] as const;

test('[QA-PLN-001] C0 결정·화면·추적 보드를 주문 없이 검토한다', async ({ page }, testInfo) => {
  const observedRequests: Array<{ method: string; url: string }> = [];
  const evidence320: EvidenceRecord[] = [];
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write'], {
    origin: 'http://127.0.0.1:4182'
  });
  page.on('request', (request) => {
    observedRequests.push({ method: request.method(), url: request.url() });
  });

  await page.goto('./');
  await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });

  await expect(page).toHaveTitle('Wall-Ant v2 전환 검토 보드');
  await expect(page.getByRole('heading', { name: /지금 결정할 건/ })).toBeVisible();
  await expect(page.getByText('기획안 · 승인 대기', { exact: true })).toBeVisible();
  const safetyStrip = page.getByLabel('현재 안전 상태');
  await expect(
    safetyStrip.getByText('마지막 제어 작업에서 미중단 · C0-B 수집 승인 대기', { exact: true })
  ).toBeVisible();
  await expect(
    safetyStrip.getByText('코드 후보는 차단 · 원격 재확인 대기', { exact: true })
  ).toBeVisible();
  const versionProof = page.getByLabel('배포와 검증 버전 구분');
  await expect(versionProof.getByText('#54 · 66e374c', { exact: true })).toBeVisible();
  await expect(versionProof.getByText('46a34ea', { exact: true })).toBeVisible();
  await expect(versionProof.getByText('PR #57 · Draft', { exact: true })).toBeVisible();
  await expect(versionProof).toContainText(
    '현재 검토 보드 화면 검사 10/10 통과 · PR 자동검사 확인 대기 · 운영 미배포'
  );
  await expect(versionProof.getByText('현재 서버가 이 버전인지 원격 재확인 전', { exact: true })).toBeVisible();
  await expect(page.getByText('C1 인증 화면 읽기 검증은 C0-A와 병렬로 준비할 수 있어요.', { exact: true })).toBeVisible();
  await expect(page.getByText('후보 배포 승인 + 직접 로그인 필요', { exact: true })).toBeVisible();
  await expect(page.getByText(/비밀번호·일회용 인증번호·토큰·쿠키는 보내지 마세요/)).toBeVisible();
  await expect(page.getByLabel('C0-A 검토 권한 경계')).toContainText(
    '후보 배포·시험 서버 변경·자격증명 사용·실제 주문·Java 중단·접속 경로 변경'
  );
  await expect(page.getByLabel('C0-A 검토 권한 경계')).toContainText(
    '① 운영값 수집 전 별도 승인과 ② 결과 확인 뒤 재승인'
  );
  await expect(page.locator('.decision-card')).toHaveCount(10);
  await expect(page.locator('.view-tabs')).toHaveCSS('position', 'static');
  await expect(page.locator('.view-tab').first()).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('[role="radiogroup"]')).toHaveCount(10);
  await expect(page.locator('input[type="radio"]')).toHaveCount(35);
  await expect(page.getByRole('radiogroup')).toHaveCount(1);
  await expect(page.locator('.key-conditions')).toHaveCount(10);
  await expect(page.locator('.option-card')).toHaveCount(35);
  const decisionCards = page.locator('.decision-card');
  await expect(page.locator('.decision-card[open]')).toHaveCount(1);
  await expect(page.locator('.decision-execution-boundary')).toHaveCount(3);
  await expect(decisionCards.nth(6).locator('.decision-execution-boundary')).toContainText(
    '이 선택만으로 시험 서버 자원을 만들거나 바꾸지 않아요.'
  );
  await expect(decisionCards.nth(8).locator('.decision-execution-boundary')).toContainText(
    '공유 환경 복구 명령은 대상과 영향을 확인한 뒤 직전에 다시 승인해요.'
  );
  await expect(decisionCards.nth(9).locator('.decision-execution-boundary')).toContainText(
    '실제 주문·Java 중단·전체 전환은 각 단계의 별도 승인 전까지 실행하지 않아요.'
  );
  await expect(page.locator('[data-decision-selection="D-01"]')).toHaveText('미응답');
  await expect(page.locator('[data-current-decision="D-01"]')).toBeDisabled();
  await expectSecondaryTextContrast(page);
  await page.emulateMedia({ colorScheme: 'dark', reducedMotion: 'reduce' });
  await expectSecondaryTextContrast(page);
  await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
  for (let index = 0; index < expectedDecisionReplies.length; index += 1) {
    const inputs = decisionCards.nth(index).locator('.draft-choice');
    expect(
      await inputs.evaluateAll((elements) =>
        elements.map((element) => (element as HTMLInputElement).dataset.decision)
      )
    ).toEqual(expectedDecisionReplies[index].map(() => `D-${String(index + 1).padStart(2, '0')}`));
    expect(
      await inputs.evaluateAll((elements) =>
        elements.map((element) => (element as HTMLInputElement).dataset.reply)
      )
    ).toEqual([...expectedDecisionReplies[index]]);
    expect(
      await inputs.evaluateAll((elements) =>
        elements.map((element) => Boolean((element as HTMLInputElement).dataset.noteLabel))
      )
    ).toEqual(expectedDecisionReplies[index].map((_, optionIndex) => optionIndex > 0));
  }
  await expect(page.locator('#reviewed-count')).toHaveText('0');
  await expect(page.locator('#review-draft')).toHaveValue(/D-01 미응답/);
  await expect(page.locator('#review-draft')).toHaveValue(/D-10 미응답/);
  await expect(page.getByRole('button', { name: '10개 응답 후 복사' })).toBeDisabled();
  await testInfo.attach('decision-board.png', {
    body: await page.screenshot({ fullPage: true }),
    contentType: 'image/png'
  });
  await expectNoHorizontalOverflow(page);
  for (let index = 1; index < 10; index += 1) {
    const decisionCard = decisionCards.nth(index);
    await decisionCard.locator('summary').click();
    await expect(decisionCard).toHaveJSProperty('open', true);
    await expect(page.locator('.decision-card[open]')).toHaveCount(1);
    await expectNoHorizontalOverflow(page);
    await decisionCard.locator('summary').click();
  }
  await decisionCards.nth(0).locator('summary').click();
  await expect(decisionCards.nth(0)).toHaveJSProperty('open', true);

  const projectViewport = page.viewportSize();
  await page.setViewportSize({ width: 320, height: 800 });
  await settleLayout(page);
  await expectNoHorizontalOverflow(page);

  const firstDraftChoice = decisionCards.nth(0).locator('.draft-choice').first();
  await firstDraftChoice.focus();
  await settleLayout(page);
  await page.evaluate(() => {
    const testWindow = window as Window & {
      __selectionMutationIds?: string[];
      __selectionObservers?: MutationObserver[];
    };
    testWindow.__selectionMutationIds = [];
    testWindow.__selectionObservers = [...document.querySelectorAll('[data-decision-selection]')]
      .map((selection) => {
        const observer = new MutationObserver(() => {
          testWindow.__selectionMutationIds?.push(
            selection.getAttribute('data-decision-selection') ?? ''
          );
        });
        observer.observe(selection, { childList: true });
        return observer;
      });
  });
  const scrollBeforeSelection = await page.evaluate(() => window.scrollY);
  await page.keyboard.press('Space');
  await settleLayout(page);
  await expect(firstDraftChoice).toBeChecked();
  await expect(firstDraftChoice).toBeFocused();
  expect(Math.abs((await page.evaluate(() => window.scrollY)) - scrollBeforeSelection)).toBeLessThanOrEqual(1);
  await expect(page.locator('#reviewed-count')).toHaveText('1');
  await expect(page.locator('#review-draft')).toHaveValue(/D-01 A 승인/);
  await expect(page.locator('[data-decision-selection="D-01"]')).toHaveText('선택 · A 승인');
  expect(await page.evaluate(() => (
    window as Window & { __selectionMutationIds?: string[] }
  ).__selectionMutationIds)).toEqual(['D-01']);
  expect(
    await page.locator('[data-decision-selection="D-01"]').evaluate(
      (element) => element.scrollHeight <= element.clientHeight + 1
    )
  ).toBe(true);
  await expect(page.getByText('기획안 · 승인 대기', { exact: true })).toBeVisible();
  const firstNextButton = decisionCards.nth(0).locator('.next-decision');
  await expect(firstNextButton).toBeEnabled();
  await expect(firstNextButton).toHaveText('다음 미응답 보기');
  await expectCompactReviewPage(page);
  await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'no-preference' });
  await firstNextButton.click();
  await expect(decisionCards.nth(0)).toHaveJSProperty('open', false);
  await expect(decisionCards.nth(1)).toHaveJSProperty('open', true);
  await expect(decisionCards.nth(1).locator('summary')).toBeFocused();
  await expectFullyVisible(decisionCards.nth(1).locator('summary'));
  await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });

  for (let index = 1; index < 10; index += 1) {
    const decisionCard = decisionCards.nth(index);
    const isOpen = await decisionCard.evaluate(
      (element) => (element as HTMLDetailsElement).open
    );
    if (!isOpen) await decisionCard.locator('summary').click();

    if (index === 4) {
      await decisionCard.locator('.option-card').nth(1).click();
      await expect(page.locator('#review-draft')).toHaveValue(
        /D-05 관리 밖 종목 정책 수정: \[내용을 적어 주세요\]/
      );
      await expect(decisionCard.locator('.draft-choice').nth(1)).toHaveAttribute(
        'aria-describedby',
        'draft-note-D-05-hint'
      );
      await expect(decisionCard.locator('.draft-note-input')).toHaveAttribute(
        'aria-required',
        'true'
      );
      await expect(decisionCard.locator('.draft-note-input')).toHaveJSProperty(
        'required',
        true
      );
      await expect(page.getByRole('button', { name: /응답 후 복사/ })).toBeDisabled();
      await expect(decisionCard.locator('.next-decision')).toBeDisabled();
      await expect(decisionCard.locator('[data-decision-selection="D-05"]')).toContainText('메모 필요');
      await expectNoHorizontalOverflow(page);
      await decisionCard.locator('.draft-note-input').fill(
        'QQQ는 보유 허용, 그 외 종목은 자동 진행 중단'
      );
      await expect(decisionCard.locator('.next-decision')).toBeEnabled();
      await expect(decisionCard.locator('[data-decision-selection="D-05"]')).toContainText('메모 작성됨');
    } else {
      await decisionCard.locator('.option-card').first().click();
    }
    const nextButton = decisionCard.locator('.next-decision');
    await expect(nextButton).toBeEnabled();
    await expect(page.locator('.decision-card[open]')).toHaveCount(1);
    await expectCompactReviewPage(page);
    expect(
      await decisionCard.locator('.decision-selection').evaluate(
        (element) => element.scrollHeight <= element.clientHeight + 1
      )
    ).toBe(true);
    if (index < 9) {
      await nextButton.click();
      await expect(decisionCards.nth(index + 1)).toHaveJSProperty('open', true);
      await expect(decisionCards.nth(index + 1).locator('summary')).toBeFocused();
      await expectFullyVisible(decisionCards.nth(index + 1).locator('summary'));
    } else {
      await expect(nextButton).toHaveText('검토안 확인');
      await attachPngEvidence(
        testInfo,
        evidence320,
        'review-handoff-ready-320.png',
        await page.screenshot({ fullPage: true }),
        page.viewportSize()
      );
      await nextButton.click();
      const handoffTitle = page.locator('#review-handoff-title');
      await expect(handoffTitle).toBeFocused();
      await expectFullyVisible(handoffTitle);
    }
  }

  await expect(page.locator('#reviewed-count')).toHaveText('10');
  const fifthDecisionNote = decisionCards.nth(4).locator('.draft-note-input');
  await decisionCards.nth(4).locator('summary').click();
  await fifthDecisionNote.fill('');
  await expect(page.locator('#draft-readiness')).toHaveText(
    '수정·설명 메모 1개를 적어 주세요.'
  );
  await expect(page.getByRole('button', { name: '메모 1개 작성 후 복사' })).toBeDisabled();
  await expectNoHorizontalOverflow(page);
  await fifthDecisionNote.fill('Q');
  await expect(page.locator('#draft-readiness')).toHaveText('검토안이 준비됐어요.');
  await page.evaluate(() => {
    const testWindow = window as Window & {
      __liveRegionMutations?: string[];
      __liveRegionObserver?: MutationObserver;
    };
    testWindow.__liveRegionMutations = [];
    const liveTargets = ['reviewed-count', 'draft-readiness'];
    testWindow.__liveRegionObserver = new MutationObserver((mutations) => {
      for (const mutation of mutations) {
        const target = mutation.target.parentElement?.closest('[id]') ?? mutation.target;
        testWindow.__liveRegionMutations?.push((target as HTMLElement).id);
      }
    });
    for (const targetId of liveTargets) {
      const target = document.getElementById(targetId);
      if (target) testWindow.__liveRegionObserver.observe(target, { childList: true, subtree: true });
    }
  });
  await fifthDecisionNote.pressSequentially('Q');
  await settleLayout(page);
  expect(await page.evaluate(() => (
    window as Window & { __liveRegionMutations?: string[] }
  ).__liveRegionMutations)).toEqual([]);
  await fifthDecisionNote.fill('QQQ는 보유 허용, 그 외 종목은 자동 진행 중단');
  await expect(page.locator('#draft-readiness')).toHaveText('검토안이 준비됐어요.');
  await expect(page.locator('#review-draft')).toHaveValue(
    /D-05 관리 밖 종목 정책 수정: QQQ는 보유 허용, 그 외 종목은 자동 진행 중단/
  );
  await expect(page.locator('#review-draft')).toHaveValue(
    /후보 배포, 시험 서버 변경, 자격증명 전달·사용, 실제 주문, Java 중단, 접속 경로 변경도 승인하지 않습니다/
  );
  await expect(page.locator('#review-draft')).toHaveValue(
    /C0-B 운영값 수집을 승인하지 않습니다/
  );
  await expect(page.locator('#review-draft')).toHaveValue(/두 번의 확인이 필요합니다/);
  await expect(page.locator('#review-draft')).toHaveValue(
    /두 번째 승인 전에는 C2 개발을 시작하지 않습니다/
  );
  await expect(page.locator('#review-draft')).toHaveValue(/D-07 실행 경계: 기획 기준 선택 · 실행 승인 아님/);
  await expect(page.locator('#review-draft')).toHaveValue(/D-09 실행 경계: 기획 기준 선택 · 실행 승인 아님/);
  await expect(page.locator('#review-draft')).toHaveValue(/D-10 실행 경계: 기획 기준 선택 · 실행 승인 아님/);
  const copyReviewDraft = page.getByRole('button', { name: '검토안 복사' });
  await expect(copyReviewDraft).toBeEnabled();
  await copyReviewDraft.click();
  await expect(page.locator('#draft-readiness')).toHaveText(
    '복사했어요. 붙여넣어도 C0-A만 전달돼요.'
  );
  const copiedDraft = await page.evaluate(() => navigator.clipboard.readText());
  expect(copiedDraft).toContain('D-01 A 승인');
  expect(copiedDraft).toContain('D-10 A를 미래 실전 명세로 승인');
  expect(copiedDraft).toContain('실제 주문');
  expect(copiedDraft).toContain('C0-B 운영값 수집을 승인하지 않습니다');
  expect(copiedDraft).toContain('두 번의 확인이 필요합니다');
  expect(copiedDraft).toContain('두 번째 승인 전에는 C2 개발을 시작하지 않습니다');
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);

  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: undefined
    });
  });
  await copyReviewDraft.click();
  await expect(page.locator('#draft-readiness')).toHaveText(
    '자동 복사가 차단됐어요. 선택된 내용을 직접 복사해 주세요.'
  );
  expect(
    await page.locator('#review-draft').evaluate((element) => {
      const textarea = element as HTMLTextAreaElement;
      return {
        focused: document.activeElement === textarea,
        selectionStart: textarea.selectionStart,
        selectionEnd: textarea.selectionEnd,
        length: textarea.value.length
      };
    })
  ).toEqual(expect.objectContaining({
    focused: true,
    selectionStart: 0,
    selectionEnd: expect.any(Number)
  }));
  const fallbackSelection = await page.locator('#review-draft').evaluate((element) => {
    const textarea = element as HTMLTextAreaElement;
    return [textarea.selectionEnd, textarea.value.length];
  });
  expect(fallbackSelection[0]).toBe(fallbackSelection[1]);
  await expectNoHorizontalOverflow(page);
  await testInfo.attach('review-handoff-fallback-320.png', {
    body: await page.screenshot({ fullPage: true }),
    contentType: 'image/png'
  });
  if (projectViewport) {
    await page.setViewportSize(projectViewport);
  }
  await testInfo.attach('review-handoff.png', {
    body: await page.locator('.review-handoff').screenshot(),
    contentType: 'image/png'
  });

  await page.getByRole('button', { name: '화면 미리보기' }).click();
  await expect(page.getByRole('button', { name: '결정 10가지' })).toHaveAttribute(
    'aria-pressed',
    'false'
  );
  await expect(page.getByRole('button', { name: '화면 미리보기' })).toHaveAttribute(
    'aria-pressed',
    'true'
  );
  await page.setViewportSize({ width: 320, height: 800 });
  await page.getByRole('button', { name: '결정 10가지' }).click();
  await expect.poll(
    () => page.locator('#review-draft').evaluate((element) => {
      const textarea = element as HTMLTextAreaElement;
      return textarea.clientHeight + 2 >= textarea.scrollHeight;
    })
  ).toBe(true);
  if (projectViewport) {
    await page.setViewportSize(projectViewport);
  }

  await page.reload();
  await expect(page.locator('#reviewed-count')).toHaveText('0');
  await expect(page.locator('#review-draft')).toHaveValue(/D-01 미응답/);
  await expect(page.getByRole('button', { name: '10개 응답 후 복사' })).toBeDisabled();
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);

  await page.getByRole('button', { name: '화면 미리보기' }).click();
  await expect(page.locator('.screen-select')).toHaveCount(5);
  await expect(page.getByRole('heading', { name: '오늘의 운영', exact: true })).toBeVisible();
  await expectNoHorizontalOverflow(page);
  const screenEvidence = [
    ['S-01', '오늘의 운영'],
    ['S-04', '자동 병행 비교'],
    ['S-08', '검증 증적'],
    ['S-10', '운영값 재확인'],
    ['S-09', '전환 센터']
  ] as const;
  await page.setViewportSize({ width: 320, height: 800 });
  for (const [screenId, screenName] of screenEvidence) {
    await page.getByRole('button', { name: new RegExp(screenName) }).click();
    await expect(page.getByRole('heading', { name: screenName, exact: true })).toBeVisible();
    await expectNoHorizontalOverflow(page);
    if (testInfo.project.name === 'planning-desktop') {
      await attachPngEvidence(
        testInfo,
        evidence320,
        `planning-320-${screenId}.png`,
        await page.screenshot({ fullPage: true }),
        page.viewportSize()
      );
    }
  }
  await page.getByRole('button', { name: /검증 증적/ }).click();
  const evidenceScreen = page.getByLabel('S-08 검증 증적 기획 미리보기');
  await expect(evidenceScreen.getByText('직전 독립 검증', { exact: true })).toBeVisible();
  await expect(evidenceScreen.getByText(
    '46a34ea · v2 가상 운영 화면 기능 검사 26 / 26 · C0 문서·기록 검사 51 / 51',
    { exact: true }
  )).toBeVisible();
  await expect(evidenceScreen.getByText('브랜치 자동 검사', { exact: true })).toBeVisible();
  await expect(evidenceScreen.getByText('CI 30975237262 · 5개 작업 통과', { exact: true })).toBeVisible();
  await expect(evidenceScreen.getByText('기준 버전과 합본 검사', { exact: true })).toBeVisible();
  await expect(evidenceScreen.getByText('PR CI 30975239146 · 5개 작업 통과', { exact: true })).toBeVisible();
  await expect(evidenceScreen.getByText('증적 참조 무결성', { exact: true })).toBeVisible();
  await expect(evidenceScreen.getByText('78개 참조 · 모두 해시 검증 통과', { exact: true })).toBeVisible();
  await expect(evidenceScreen.getByText('현재 보강본', { exact: true })).toBeVisible();
  await expect(evidenceScreen.getByText(
    '현재 검토 보드 화면 검사 10 / 10 · C0 문서·기록 검사 55 / 55 통과 · PR 자동검사 확인 대기 · 운영 미배포',
    { exact: true }
  )).toBeVisible();
  await expect(evidenceScreen.getByText('운영 배포·인증 화면 검증', { exact: true })).toBeVisible();
  await expect(evidenceScreen.getByText(
    '차단 확인 · 후보 배포 승인과 사용자 직접 로그인 대기',
    { exact: true }
  )).toBeVisible();
  await page.getByRole('button', { name: /운영값 재확인/ }).click();
  const c0bScreen = page.getByLabel('S-10 운영값 재확인 기획 미리보기');
  await expect(c0bScreen.getByText('1단계 · 운영값 수집', { exact: true })).toBeVisible();
  await expect(c0bScreen.getByText('별도 승인 대기', { exact: true })).toBeVisible();
  await expect(c0bScreen.getByText(
    'C0-A가 끝나도 바로 수집하지 않아요. 아래 범위를 따로 승인한 뒤에만 12개 값을 읽어요.',
    { exact: true }
  )).toBeVisible();
  await expect(c0bScreen.getByText(
    'GitHub 자동화를 통해 운영 서버의 설정과 DB를 조회 · 상세: PROD SSH · shell 조회 · DB SELECT',
    { exact: true }
  )).toBeVisible();
  await expect(c0bScreen.getByText(
    '조회 명령만 허용 · 변경 명령 없음',
    { exact: true }
  )).toBeVisible();
  await expect(c0bScreen.getByText(
    '정제·마스킹한 증적만 지정 폴더에 저장 · docs/v2-cutover/evidence/c0b/',
    { exact: true }
  )).toBeVisible();
  await expect(c0bScreen.getByText('저장하지 않음', { exact: true })).toBeVisible();
  await expect(c0bScreen.getByText(
    '프로젝트 변경 이력에 계속 남음',
    { exact: true }
  )).toBeVisible();
  await expect(c0bScreen.getByText('모드·소유권 확인 대기', { exact: true })).toBeVisible();
  await expect(c0bScreen.getByText('2단계 · 수집 결과', { exact: true })).toBeVisible();
  await expect(c0bScreen.getByText('결과 재승인 대기', { exact: true })).toBeVisible();
  await expect(c0bScreen.getByText(
    '12개 값과 C0-A 차이를 본 뒤 재승인해야 C2 개발을 시작해요.',
    { exact: true }
  )).toBeVisible();
  await testInfo.attach('c0b-review-planning.png', {
    body: await page.locator('.screen-frame').screenshot(),
    contentType: 'image/png'
  });
  await expectNoHorizontalOverflow(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoHorizontalOverflow(page);
  await page.getByRole('button', { name: /전환 센터/ }).click();
  await expect(page.getByRole('button', { name: '사용자 승인 전 잠김' })).toBeDisabled();
  await testInfo.attach('screen-planning.png', {
    body: await page.screenshot({ fullPage: true }),
    contentType: 'image/png'
  });
  await page.getByRole('button', { name: '개발 추적표' }).click();
  const c0bLegend = page.getByLabel('C0-B 승인 단계 범례');
  await expect(c0bLegend).toContainText('① 운영값 수집 승인 → ② 수집 결과 재승인');
  await expect(c0bLegend).toContainText('두 번째 승인 전에는 C2 개발을 시작하지 않아요.');
  await expect(page.locator('#trace-body tr')).toHaveCount(14);
  await expect(page.getByRole('columnheader')).toHaveCount(7);
  await expect(page.getByRole('columnheader', { name: '승인 순서' })).toBeVisible();
  await expect(page.locator('.trace-table thead')).toHaveCSS('position', 'absolute');
  expect(
    await page.locator('#trace-body td').evaluateAll((cells) =>
      cells.every((cell) => {
        const headerId = cell.getAttribute('headers');
        return Boolean(
          headerId &&
          cell.getAttribute('data-label') &&
          document.getElementById(headerId)?.tagName === 'TH'
        );
      })
    )
  ).toBe(true);
  const comprehensiveC2Row = page.locator('#trace-body tr').filter({
    has: page.getByText('C2 종합', { exact: true }),
  });
  await expect(comprehensiveC2Row).toHaveCount(1);
  await expect(comprehensiveC2Row).toContainText('V2-AUT-001');
  await expect(comprehensiveC2Row).toContainText('미구현');
  for (const rowLabel of ['D-03', 'C2 미래 화면', 'C2 종합']) {
    const c2Row = page.locator('#trace-body tr').filter({
      has: page.getByText(rowLabel, { exact: true }),
    });
    await expect(c2Row).toHaveCount(1);
    await expect(c2Row.locator('td').nth(5)).toHaveText('미구현');
  }
  const expectedApprovalOrder = new Map([
    ['D-01~02', 'C0-A → ① 수집 → ② 결과'],
    ['C1 격리', '① 수집 → ② 결과'],
    ['D-03', '② 결과 재승인'],
    ['D-04', '② 결과 재승인'],
    ['D-05~06', '② 결과 재승인'],
    ['C2 미래 화면', '② 결과 재승인'],
    ['C2 종합', '② 결과 재승인'],
  ]);
  for (const [rowLabel, approval] of expectedApprovalOrder) {
    const approvalRow = page.locator('#trace-body tr').filter({
      has: page.getByText(rowLabel, { exact: true }),
    });
    await expect(approvalRow).toHaveCount(1);
    await expect(approvalRow.locator('td').nth(6)).toHaveText(approval);
  }
  await expect(page.getByText('단건→범위→전환 승인', { exact: true })).toBeVisible();

  const commonSpecRow = page.locator('#trace-body tr').filter({
    has: page.getByText('공통 명세', { exact: true }),
  });
  await expect(commonSpecRow).toContainText('직전 정본 46a34ea');
  await expect(commonSpecRow).toContainText('직전 정본 화면 검사 2/2');
  await expect(commonSpecRow).toContainText('C0 문서·기록 검사 51/51');
  await expect(commonSpecRow).toContainText('현재 보강본 반복 화면 검사 10/10');
  await expect(commonSpecRow).toContainText('C0 문서·기록 검사 55/55 로컬 통과');
  await expect(commonSpecRow).toContainText('새 코드 버전·자동검사·증적 묶음 확인 대기');
  for (const rowLabel of ['D-07', '현재 UI']) {
    const evidenceRow = page.locator('#trace-body tr').filter({
      has: page.getByText(rowLabel, { exact: true }),
    });
    await expect(evidenceRow).toHaveCount(1);
    await expect(evidenceRow).toContainText('직전 독립 검증 46a34ea');
    await expect(evidenceRow).toContainText('v2 가상 운영 화면 기능 검사 26/26');
    await expect(evidenceRow).toContainText('브랜치 자동검사 30975237262');
    await expect(evidenceRow).toContainText('기준 버전 합본 자동검사 30975239146');
  }

  await expectNoHorizontalOverflow(page);

  if (testInfo.project.name === 'planning-desktop') {
    await attachPngEvidence(
      testInfo,
      evidence320,
      'planning-320-trace.png',
      await page.screenshot({ fullPage: true }),
      page.viewportSize()
    );
    const sourceSha = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
    const repositoryRoot = execFileSync('git', ['rev-parse', '--show-toplevel'], {
      encoding: 'utf8'
    }).trim();
    const gitStatus = execFileSync(
      'git',
      ['status', '--porcelain=v1'],
      { encoding: 'utf8' }
    ).trim();
    const sourcePaths = [
      'docs/v2-cutover/review-board/index.html',
      'docs/v2-cutover/review-board/styles.css',
      'docs/v2-cutover/review-board/app.js',
      'v2/frontend/playwright.planning.config.ts',
      'v2/frontend/e2e-planning/review-board.spec.ts'
    ];
    const sourceFiles = Object.fromEntries(
      sourcePaths.map((sourcePath) => [
        sourcePath,
        createHash('sha256')
          .update(readFileSync(resolve(repositoryRoot, sourcePath)))
          .digest('hex')
      ])
    );
    const sourceTreeSha256 = createHash('sha256')
      .update(JSON.stringify(sourceFiles))
      .digest('hex');
    if (process.env.CI) {
      expect(gitStatus, 'CI planning evidence requires a clean tracked tree').toBe('');
    }
    const manifestPath = testInfo.outputPath('planning-320-manifest.json');
    writeFileSync(
      manifestPath,
      `${JSON.stringify({
        sourceSha,
        gitClean: gitStatus === '',
        sourceTreeSha256,
        sourceFiles,
        viewport: { width: 320, height: 800 },
        files: evidence320
      }, null, 2)}\n`
    );
    await testInfo.attach('planning-320-manifest.json', {
      path: manifestPath,
      contentType: 'application/json'
    });
  }

  const origin = new URL(page.url()).origin;
  expect(observedRequests.length).toBeGreaterThanOrEqual(3);
  for (const request of observedRequests) {
    expect(request.method).toBe('GET');
    expect(new URL(request.url).origin).toBe(origin);
  }

  await testInfo.attach('delivery-trace.png', {
    body: await page.screenshot({ fullPage: true }),
    contentType: 'image/png'
  });
});
