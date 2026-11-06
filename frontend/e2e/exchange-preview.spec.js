import { expect, test } from '@playwright/test'

// The talk's opening demo: the support screens answer the order 1042 request with clicks, and the API calls the
// browser makes along the way are the workflow the endpoint MCP server leaves to the model.
test('the order 1042 preview finds the one option that meets both limits', async ({ page }) => {
  const calls = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (url.pathname.startsWith('/api/')) calls.push(`${request.method()} ${url.pathname}`)
  })

  await page.goto('/')
  await page.getByLabel('Order reference, customer name, or email').fill('1042')
  await page.getByRole('button', { name: 'Search' }).click()
  await page.getByTestId('search-results').getByRole('button', { name: '1042' }).click()
  await expect(page.getByTestId('order-detail')).toContainText('Maya Chen')
  await page.getByRole('button', { name: 'Help with this order' }).click()

  await page.getByTestId('exchange-1042-1').click()
  await expect(page.getByTestId('eligibility')).toContainText('Eligible until')
  await page.getByRole('button', { name: 'Choose a replacement' }).click()

  await page.getByLabel('Replacement').selectOption('TRB-L-BLU')
  await page.getByLabel('Arrive by').fill('2026-11-13')
  await page.getByLabel('Cost limit').fill('15')
  await page.getByRole('button', { name: 'Find options' }).click()

  const options = page.getByTestId('exchange-options')
  await expect(options.locator('tbody tr')).toHaveCount(4)
  const fits = options.locator('tbody tr.fit')
  await expect(fits).toHaveCount(1)
  await expect(fits).toContainText('Columbus')
  await expect(fits).toContainText('Ground')
  await expect(fits).toContainText('$11.99')
  await expect(fits).toContainText('Nov 12')
  await expect(options.locator('tbody tr.miss')).toHaveCount(3)
  await page.screenshot({ path: 'test-results/order-1042-preview.png', fullPage: true })

  console.log(`The preview took ${calls.length} API calls:\n  ${calls.join('\n  ')}`)
  expect(calls.filter((c) => c === 'POST /api/exchange-quotes')).toHaveLength(4)
  expect(calls.filter((c) => c === 'GET /api/delivery-options')).toHaveLength(2)
  expect(calls).toHaveLength(12)
  expect(calls).not.toContain('POST /api/exchanges')
})
