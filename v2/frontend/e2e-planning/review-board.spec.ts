import { expect, test, type Page } from '@playwright/test';

async function expectNoHorizontalOverflow(page: Page) {
  const hasHorizontalOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
  );
  expect(hasHorizontalOverflow).toBe(false);
}

const expectedDecisionReplies = [
  ['A 승인', '수정 요청', '설명 요청'],
  ['A 승인', '처음부터 데이터 의미 수정', '설명 요청'],
  ['권장안 승인', '시각/신선도 수정', '설명 요청'],
  ['A 승인', '기존 방식 유지', '설명 요청'],
  ['A 승인', '관리 밖 종목 정책 수정', '수량 규칙 수정', '설명 요청'],
  ['A 승인', '공통 입력 비교만 사용', '설명 요청'],
  ['권장 범위·보존 승인', '전부 읽기 전용', '범위/보존 수정', '설명 요청'],
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
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write'], {
    origin: 'http://127.0.0.1:4181'
  });
  page.on('request', (request) => {
    observedRequests.push({ method: request.method(), url: request.url() });
  });

  await page.goto('./');

  await expect(page).toHaveTitle('Wall-Ant v2 전환 검토 보드');
  await expect(page.getByRole('heading', { name: /지금 결정할 건/ })).toBeVisible();
  await expect(page.getByText('기획안 · 승인 대기', { exact: true })).toBeVisible();
  const safetyStrip = page.getByLabel('현재 안전 상태');
  await expect(
    safetyStrip.getByText('마지막 제어 작업에서 미중단 · C0-B 재확인 대기', { exact: true })
  ).toBeVisible();
  await expect(
    safetyStrip.getByText('코드 후보는 차단 · 원격 재확인 대기', { exact: true })
  ).toBeVisible();
  const versionProof = page.getByLabel('배포와 검증 버전 구분');
  await expect(versionProof.getByText('#54 · 66e374c', { exact: true })).toBeVisible();
  await expect(versionProof.getByText('a6a7174', { exact: true })).toBeVisible();
  await expect(versionProof.getByText('PR #57 · Draft', { exact: true })).toBeVisible();
  await expect(versionProof.getByText('현재 서버가 이 버전인지 원격 재확인 전', { exact: true })).toBeVisible();
  await expect(page.getByText('C1 인증 화면 읽기 검증은 C0-A와 병렬로 준비할 수 있어요.', { exact: true })).toBeVisible();
  await expect(page.getByText('후보 배포 승인 + 직접 로그인 필요', { exact: true })).toBeVisible();
  await expect(page.getByText(/비밀번호·일회용 인증번호·토큰·쿠키는 보내지 마세요/)).toBeVisible();
  await expect(page.getByLabel('C0-A 검토 권한 경계')).toContainText(
    '후보 배포·시험 서버 변경·자격증명 사용·실제 주문·Java 중단·접속 경로 변경'
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
    await expectNoHorizontalOverflow(page);
    await decisionCard.locator('summary').click();
  }

  const projectViewport = page.viewportSize();
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoHorizontalOverflow(page);

  const firstDraftChoice = decisionCards.nth(0).locator('.draft-choice').first();
  await firstDraftChoice.focus();
  await page.keyboard.press('Space');
  await expect(firstDraftChoice).toBeChecked();
  await expect(page.locator('#reviewed-count')).toHaveText('1');
  await expect(page.locator('#review-draft')).toHaveValue(/D-01 A 승인/);
  await expect(page.getByText('기획안 · 승인 대기', { exact: true })).toBeVisible();

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
      await expectNoHorizontalOverflow(page);
      await decisionCard.locator('.draft-note-input').fill(
        'QQQ는 보유 허용, 그 외 종목은 자동 진행 중단'
      );
    } else {
      await decisionCard.locator('.option-card').first().click();
    }
  }

  await expect(page.locator('#reviewed-count')).toHaveText('10');
  const fifthDecisionNote = decisionCards.nth(4).locator('.draft-note-input');
  await fifthDecisionNote.fill('');
  await expect(page.locator('#draft-readiness')).toHaveText(
    '수정·설명 메모 1개를 적어 주세요.'
  );
  await expect(page.getByRole('button', { name: '메모 1개 작성 후 복사' })).toBeDisabled();
  await expectNoHorizontalOverflow(page);
  await fifthDecisionNote.fill('QQQ는 보유 허용, 그 외 종목은 자동 진행 중단');
  await expect(page.locator('#draft-readiness')).toHaveText('검토안이 준비됐어요.');
  await expect(page.locator('#review-draft')).toHaveValue(
    /D-05 관리 밖 종목 정책 수정: QQQ는 보유 허용, 그 외 종목은 자동 진행 중단/
  );
  await expect(page.locator('#review-draft')).toHaveValue(
    /후보 배포, 시험 서버 변경, 자격증명 전달·사용, 실제 주문, Java 중단, 접속 경로 변경을 승인하지 않습니다/
  );
  const copyReviewDraft = page.getByRole('button', { name: '검토안 복사' });
  await expect(copyReviewDraft).toBeEnabled();
  await testInfo.attach('review-handoff-ready-320.png', {
    body: await page.screenshot({ fullPage: true }),
    contentType: 'image/png'
  });
  await copyReviewDraft.click();
  await expect(page.locator('#draft-readiness')).toHaveText(
    '복사했어요. 이 대화에 붙여넣어야 전달돼요.'
  );
  const copiedDraft = await page.evaluate(() => navigator.clipboard.readText());
  expect(copiedDraft).toContain('D-01 A 승인');
  expect(copiedDraft).toContain('D-10 A를 미래 실전 명세로 승인');
  expect(copiedDraft).toContain('실제 주문');
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
  for (const screenName of ['자동 병행 비교', '검증 증적', '운영값 재확인', '전환 센터']) {
    await page.getByRole('button', { name: new RegExp(screenName) }).click();
    await expect(page.getByRole('heading', { name: screenName, exact: true })).toBeVisible();
    await expectNoHorizontalOverflow(page);
  }
  await page.getByRole('button', { name: /검증 증적/ }).click();
  await expect(page.getByText('로컬 가상 화면 검증', { exact: true })).toBeVisible();
  await expect(page.getByText('통합 안전 코드 a6a7174 · 26 / 26 통과', { exact: true })).toBeVisible();
  await expect(page.getByText('내 작업 버전 자동 검사', { exact: true })).toBeVisible();
  await expect(page.getByText('브랜치 CI 30965407547 · 5개 작업 통과', { exact: true })).toBeVisible();
  await expect(page.getByText('기준 버전과 합친 상태 검사', { exact: true })).toBeVisible();
  await expect(page.getByText('PR CI 30965409423 · 5개 작업 통과', { exact: true })).toBeVisible();
  await expect(page.getByText('증적 참조 무결성', { exact: true })).toBeVisible();
  await expect(page.getByText('78개 참조 · 모두 해시 검증 통과', { exact: true })).toBeVisible();
  await expect(page.getByText('운영 배포·인증 화면 검증', { exact: true })).toBeVisible();
  await expect(page.getByText(
    '차단 확인 · 후보 배포 승인과 사용자 직접 로그인 대기',
    { exact: true }
  )).toBeVisible();
  await page.getByRole('button', { name: /운영값 재확인/ }).click();
  await expect(page.getByText('0 / 12 · 아직 수집하지 않았어요', { exact: true })).toBeVisible();
  await expect(page.getByText('모드·소유권 확인 대기', { exact: true })).toBeVisible();
  await expect(page.getByText('12개 값·차이·문서 버전이 모두 맞아야 열려요.', { exact: true })).toBeVisible();
  await testInfo.attach('c0b-review-planning.png', {
    body: await page.locator('.screen-frame').screenshot(),
    contentType: 'image/png'
  });
  await expectNoHorizontalOverflow(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await expectNoHorizontalOverflow(page);
  if (projectViewport) {
    await page.setViewportSize(projectViewport);
  }
  await page.getByRole('button', { name: /전환 센터/ }).click();
  await expect(page.getByRole('button', { name: '사용자 승인 전 잠김' })).toBeDisabled();
  await testInfo.attach('screen-planning.png', {
    body: await page.screenshot({ fullPage: true }),
    contentType: 'image/png'
  });
  await page.getByRole('button', { name: '개발 추적표' }).click();
  await expect(page.locator('#trace-body tr')).toHaveCount(14);
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
  await expect(page.getByText('C0-A/B', { exact: true })).toBeVisible();
  await expect(page.getByText('단건→범위→전환 승인', { exact: true })).toBeVisible();
  for (const rowLabel of ['D-07', '현재 UI']) {
    const evidenceRow = page.locator('#trace-body tr').filter({
      has: page.getByText(rowLabel, { exact: true }),
    });
    await expect(evidenceRow).toHaveCount(1);
    await expect(evidenceRow).toContainText('통합 안전 코드 a6a7174');
    await expect(evidenceRow).toContainText('브랜치 CI 30965407547');
    await expect(evidenceRow).toContainText('기준 버전 합본 PR CI 30965409423');
  }

  await expectNoHorizontalOverflow(page);

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
