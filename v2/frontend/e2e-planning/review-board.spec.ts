import { expect, test, type Page } from '@playwright/test';

async function expectNoHorizontalOverflow(page: Page) {
  const hasHorizontalOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
  );
  expect(hasHorizontalOverflow).toBe(false);
}

test('[QA-PLN-001] C0 결정·화면·추적 보드를 주문 없이 검토한다', async ({ page }, testInfo) => {
  const observedRequests: Array<{ method: string; url: string }> = [];
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
  await expect(page.getByText('C1 인증 화면 읽기 검증은 C0-A와 동시에 진행할 수 있어요.', { exact: true })).toBeVisible();
  await expect(page.getByText('사용자 로그인 필요', { exact: true })).toBeVisible();
  await expect(page.getByText(/임시 로그인 세션은 저장소 밖 소유자 전용 경로/)).toBeVisible();
  await expect(page.locator('.decision-card')).toHaveCount(10);
  await expect(page.locator('.key-conditions')).toHaveCount(10);
  await expect(page.locator('.option-card')).toHaveCount(30);
  await expect(page.locator('#reviewed-count')).toHaveText('0');
  await testInfo.attach('decision-board.png', {
    body: await page.screenshot({ fullPage: true }),
    contentType: 'image/png'
  });
  await expectNoHorizontalOverflow(page);
  const decisionCards = page.locator('.decision-card');
  for (let index = 1; index < 10; index += 1) {
    const decisionCard = decisionCards.nth(index);
    await decisionCard.locator('summary').click();
    await expect(decisionCard).toHaveJSProperty('open', true);
    await expectNoHorizontalOverflow(page);
    await decisionCard.locator('summary').click();
  }

  const firstDraftChoice = page.getByRole('button', { name: '괜찮음' }).first();
  await firstDraftChoice.click();
  await expect(firstDraftChoice).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('#reviewed-count')).toHaveText('1');
  await expect(page.getByText('기획안 · 승인 대기', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: '화면 미리보기' }).click();
  await expect(page.locator('.screen-select')).toHaveCount(4);
  await expect(page.getByRole('heading', { name: '오늘의 운영', exact: true })).toBeVisible();
  await expectNoHorizontalOverflow(page);
  for (const screenName of ['자동 병행 비교', '검증 증적', '전환 센터']) {
    await page.getByRole('button', { name: new RegExp(screenName) }).click();
    await expect(page.getByRole('heading', { name: screenName, exact: true })).toBeVisible();
    await expectNoHorizontalOverflow(page);
  }
  await page.getByRole('button', { name: /검증 증적/ }).click();
  await expect(page.getByText('로컬 가상 화면 검증', { exact: true })).toBeVisible();
  await expect(page.getByText('기능 후보 1520166 · 26 / 26 통과', { exact: true })).toBeVisible();
  await expect(page.getByText('내 작업 버전 자동 검사', { exact: true })).toBeVisible();
  await expect(page.getByText('브랜치 CI 30959376884 · 5개 작업 통과', { exact: true })).toBeVisible();
  await expect(page.getByText('기준 버전과 합친 상태 검사', { exact: true })).toBeVisible();
  await expect(page.getByText('PR CI 30959379072 · 5개 작업 통과', { exact: true })).toBeVisible();
  await expect(page.getByText('증적 참조 무결성', { exact: true })).toBeVisible();
  await expect(page.getByText('78개 참조 · 모두 해시 검증 통과', { exact: true })).toBeVisible();
  await expect(page.getByText('운영 배포·인증 화면 검증', { exact: true })).toBeVisible();
  await expect(page.getByText(
    '차단 확인 · 사용자 승인 배포와 로그인 세션 대기',
    { exact: true }
  )).toBeVisible();
  await expectNoHorizontalOverflow(page);
  const projectViewport = page.viewportSize();
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
  await expect(page.locator('#trace-body tr')).toHaveCount(13);
  await expect(page.getByText('C0-A/B', { exact: true })).toBeVisible();
  await expect(page.getByText('단건→범위→전환 승인', { exact: true })).toBeVisible();

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
