const API_KEY = import.meta.env.VITE_API_KEY ?? 'frontend-dev-key'

export class ApiError extends Error {
  constructor(problem) {
    super(problem.detail ?? problem.title ?? 'Request failed')
    this.problem = problem
    this.code = problem.code
    this.status = problem.status
  }
}

async function request(method, path, body) {
  const response = await fetch(path, {
    method,
    headers: {
      'X-Api-Key': API_KEY,
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  const json = await response.json().catch(() => ({}))
  if (!response.ok) {
    throw new ApiError({ status: response.status, ...json })
  }
  return json
}

const enc = encodeURIComponent

export const api = {
  searchOrders: (query, page = 0, size = 10) =>
    request('GET', `/api/orders?query=${enc(query)}&page=${page}&size=${size}`),
  order: (reference) => request('GET', `/api/orders/${enc(reference)}`),
  orderItems: (reference) => request('GET', `/api/orders/${enc(reference)}/items`),
  exchangeEligibility: (reference, itemId) =>
    request('GET', `/api/orders/${enc(reference)}/items/${enc(itemId)}/exchange-eligibility`),
  returnEligibility: (reference, itemId) =>
    request('GET', `/api/orders/${enc(reference)}/items/${enc(itemId)}/return-eligibility`),
  variants: (productId) => request('GET', `/api/products/${enc(productId)}/variants`),
  inventory: (sku) => request('GET', `/api/inventory?sku=${enc(sku)}`),
  deliveryOptions: (warehouseId, postalCode) =>
    request('GET', `/api/delivery-options?warehouseId=${enc(warehouseId)}&postalCode=${enc(postalCode)}`),
  createExchangeQuote: (body) => request('POST', '/api/exchange-quotes', body),
  createReturnQuote: (body) => request('POST', '/api/return-quotes', body),
  submitExchange: (quoteId) => request('POST', '/api/exchanges', { quoteId }),
  submitReturn: (quoteId) => request('POST', '/api/returns', { quoteId }),
}
