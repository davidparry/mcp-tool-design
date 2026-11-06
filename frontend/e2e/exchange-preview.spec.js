import { expect, test } from '@playwright/test'

test.use({ deviceScaleFactor: 2 })

function recordApiCalls(page) {
  const calls = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (url.pathname.startsWith('/api/')) calls.push(`${request.method()} ${url.pathname}`)
  })
  return calls
}

async function previewExchange(page, { reference, customer, itemId, replacement, deliverBy, limit }) {
  await page.goto('/')
  await page.getByLabel('Order reference, customer name, or email').fill(reference)
  await page.getByRole('button', { name: 'Search' }).click()
  await page.getByTestId('search-results').getByRole('button', { name: reference }).click()
  await expect(page.getByTestId('order-detail')).toContainText(customer)
  await page.getByRole('button', { name: 'Help with this order' }).click()

  await page.getByTestId(`exchange-${itemId}`).click()
  await expect(page.getByTestId('eligibility')).toContainText('Eligible until')
  await page.getByRole('button', { name: 'Choose a replacement' }).click()

  await page.getByLabel('Replacement').selectOption(replacement)
  if (deliverBy) await page.getByLabel('Arrive by').fill(deliverBy)
  await page.getByLabel('Cost limit').fill(limit)
  await page.getByRole('button', { name: 'Find options' }).click()
  return page.getByTestId('exchange-options')
}

// The talk's opening demo: the support screens answer the order 1042 request with clicks, and the API calls the
// browser makes along the way are the workflow the endpoint MCP server leaves to the model.
test('the order 1042 preview finds the one option that meets both limits', async ({ page }) => {
  const calls = recordApiCalls(page)
  const options = await previewExchange(page, {
    reference: '1042',
    customer: 'Maya Chen',
    itemId: '1042-1',
    replacement: 'TRB-L-BLU',
    deliverBy: '2026-11-13',
    limit: '15',
  })

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

// Benchmark task E1. Nothing fits under $30, so the price the customer hears is the lowest any warehouse can do,
// and only the stock check says which warehouses to ask.
test('the order 1025 preview finds nothing under $30 and Reno as the cheapest', async ({ page }) => {
  const calls = recordApiCalls(page)
  const options = await previewExchange(page, {
    reference: '1025',
    customer: 'Theo Walsh',
    itemId: '1025-1',
    replacement: 'BED-XL',
    limit: '30',
  })

  const rows = options.locator('tbody tr')
  await expect(rows).toHaveCount(4)
  await expect(options.locator('tbody tr.fit')).toHaveCount(0)
  await expect(rows.first()).toContainText('Reno')
  await expect(rows.first()).toContainText('Ground')
  await expect(rows.first()).toContainText('$45.00')
  await expect(rows.first()).toContainText('Nov 10')
  await expect(rows.nth(1)).toContainText('Columbus')
  await expect(rows.nth(1)).toContainText('$47.99')
  await expect(rows.nth(1)).toContainText('Nov 16')
  await page.screenshot({ path: 'test-results/order-1025-preview.png', fullPage: true })
  await page.locator('section').filter({ has: options }).screenshot({ path: 'test-results/order-1025-options.png' })

  console.log(`The preview took ${calls.length} API calls:\n  ${calls.join('\n  ')}`)
  expect(calls).toContain('GET /api/inventory')
  expect(calls.filter((c) => c === 'POST /api/exchange-quotes')).toHaveLength(4)
  expect(calls.filter((c) => c === 'GET /api/delivery-options')).toHaveLength(2)
  expect(calls).toHaveLength(12)
  expect(calls).not.toContain('POST /api/exchanges')
})
