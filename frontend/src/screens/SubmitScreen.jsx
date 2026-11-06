import { useState } from 'react'
import { api } from '../api.js'
import { date, money, time } from '../format.js'
import Problem from './Problem.jsx'

/** Screen 4: selected quote submission and the resulting action status. */
export default function SubmitScreen({ order, selection, quote }) {
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const isExchange = quote.type === 'EXCHANGE'

  async function submit() {
    setError(null)
    try {
      setResult(isExchange ? await api.submitExchange(quote.quoteId) : await api.submitReturn(quote.quoteId))
    } catch (e) {
      setError(e)
    }
  }

  return (
    <section>
      <h2>
        Submit {isExchange ? 'the exchange' : 'the return'} for order {order.reference}
      </h2>
      <div className="card" data-testid="selected-quote">
        <h3>Quote {quote.quoteId}</h3>
        <p>
          {selection.item.productName}
          {isExchange
            ? ` → ${quote.replacementSku}, ${quote.deliveryMethod.toLowerCase()} from ${quote.warehouseName ?? quote.warehouseId}, arriving ${date(quote.estimatedArrival)}. The customer pays ${money(quote.additionalCost)}.`
            : `, ${quote.quantity} unit${quote.quantity === 1 ? '' : 's'}. The customer gets back ${money(quote.refundAmount)}.`}
        </p>
        <p className="muted">Quote expires at {time(quote.expiresAt)}. Submit only what the customer approved.</p>
        {!result && (
          <button type="button" onClick={submit} data-testid="submit">
            Submit {isExchange ? 'exchange' : 'return'}
          </button>
        )}
      </div>
      <Problem error={error} />
      {result && (
        <div className="card ok" data-testid="action-status">
          <h3>
            {isExchange ? `Exchange ${result.exchangeId}` : `Return ${result.returnId}`}{' '}
            <span className="badge delivered">{result.status}</span>
          </h3>
          <p>
            Submitted {time(result.submittedAt)}.{' '}
            {isExchange
              ? `${result.replacementSku} ships ${result.deliveryMethod.toLowerCase()} from ${result.warehouseId} and arrives ${date(result.estimatedArrival)}. Charged ${money(result.additionalCost)}.`
              : `Refund of ${money(result.refundAmount)} once the item arrives.`}
          </p>
        </div>
      )}
    </section>
  )
}
