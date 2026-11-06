import { useEffect, useState } from 'react'
import { api } from '../api.js'
import { date, money, time } from '../format.js'
import Problem from './Problem.jsx'

/** Screen 3: replacement selection, delivery options, and quote preview (or the refund preview for a return). */
export default function QuoteScreen({ order, selection, onChoose }) {
  return selection.action === 'EXCHANGE' ? (
    <ExchangePreview order={order} selection={selection} onChoose={onChoose} />
  ) : (
    <ReturnPreview order={order} selection={selection} onChoose={onChoose} />
  )
}

function ExchangePreview({ order, selection, onChoose }) {
  const { item, eligibility } = selection
  const [product, setProduct] = useState(null)
  const [sku, setSku] = useState('')
  const [quantity, setQuantity] = useState(1)
  const [deliverBy, setDeliverBy] = useState('')
  const [limit, setLimit] = useState('')
  const [comparison, setComparison] = useState('under')
  const [quotes, setQuotes] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => {
    api.variants(item.productId).then(setProduct).catch(setError)
  }, [item.productId])

  async function findOptions(event) {
    event.preventDefault()
    setError(null)
    setQuotes(null)
    setBusy(true)
    try {
      const stock = await api.inventory(sku)
      const stocked = stock.warehouses.filter((w) => w.available >= quantity)
      const found = []
      for (const warehouse of stocked) {
        const delivery = await api.deliveryOptions(warehouse.warehouseId, order.shipTo.postalCode)
        for (const option of delivery.options) {
          const quote = await api.createExchangeQuote({
            orderReference: order.reference,
            itemId: item.itemId,
            replacementSku: sku,
            quantity,
            warehouseId: warehouse.warehouseId,
            deliveryMethod: option.method,
          })
          found.push({ ...quote, warehouseName: warehouse.name })
        }
      }
      found.sort((a, b) => Number(a.additionalCost.amount) - Number(b.additionalCost.amount))
      setQuotes(found)
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  function misses(quote) {
    const reasons = []
    if (deliverBy && quote.estimatedArrival > deliverBy) reasons.push('arrives too late')
    if (limit !== '') {
      const cost = Number(quote.additionalCost.amount)
      const max = Number(limit)
      if (comparison === 'under' ? !(cost < max) : !(cost <= max)) reasons.push('costs too much')
    }
    return reasons
  }

  const replacements = product ? product.variants.filter((v) => v.sku !== item.sku) : []

  return (
    <section>
      <h2>
        Exchange {item.productName} ({item.sku}) from order {order.reference}
      </h2>
      <form className="constraints" onSubmit={findOptions}>
        <label>
          Replacement
          <select value={sku} onChange={(e) => setSku(e.target.value)} required aria-label="Replacement">
            <option value="">Choose a variant</option>
            {replacements.map((v) => (
              <option key={v.sku} value={v.sku}>
                {[v.size, v.color].filter(Boolean).join(', ')} · {v.sku} · {money(v.price)}
              </option>
            ))}
          </select>
        </label>
        <label>
          Quantity
          <input
            type="number"
            min={1}
            max={eligibility.maxQuantity}
            value={quantity}
            onChange={(e) => setQuantity(Number(e.target.value))}
          />
        </label>
        <label>
          Arrive by
          <input type="date" value={deliverBy} onChange={(e) => setDeliverBy(e.target.value)} aria-label="Arrive by" />
        </label>
        <label>
          Additional cost
          <span className="inline">
            <select value={comparison} onChange={(e) => setComparison(e.target.value)} aria-label="Cost comparison">
              <option value="under">under</option>
              <option value="at_most">at most</option>
            </select>
            <input
              type="number"
              step="0.01"
              min="0"
              value={limit}
              onChange={(e) => setLimit(e.target.value)}
              aria-label="Cost limit"
              placeholder="no limit"
            />
          </span>
        </label>
        <button type="submit" disabled={!sku || busy}>
          {busy ? 'Quoting…' : 'Find options'}
        </button>
      </form>
      <Problem error={error} />
      {quotes && quotes.length === 0 && (
        <div className="card blocked" data-testid="no-stock">
          No warehouse has {quantity} of {sku} in stock.
        </div>
      )}
      {quotes && quotes.length > 0 && (
        <table data-testid="exchange-options">
          <thead>
            <tr>
              <th>Ships from</th>
              <th>Method</th>
              <th>Arrives</th>
              <th>Price difference</th>
              <th>Shipping</th>
              <th>Additional cost</th>
              <th>Quote</th>
              <th>Your limits</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {quotes.map((q) => {
              const reasons = misses(q)
              return (
                <tr key={q.quoteId} className={reasons.length ? 'miss' : 'fit'} data-testid={`option-${q.quoteId}`}>
                  <td>{q.warehouseName}</td>
                  <td>{q.deliveryMethod === 'GROUND' ? 'Ground' : 'Express'}</td>
                  <td>{date(q.estimatedArrival)}</td>
                  <td>{money(q.priceDifference)}</td>
                  <td>{money(q.shippingCharge)}</td>
                  <td className="amount">{money(q.additionalCost)}</td>
                  <td>
                    {q.quoteId}
                    <div className="muted">expires {time(q.expiresAt)}</div>
                  </td>
                  <td>{reasons.length ? reasons.join(', ') : 'meets both'}</td>
                  <td>
                    <button type="button" onClick={() => onChoose({ ...q, type: 'EXCHANGE' })}>
                      Select
                    </button>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
    </section>
  )
}

function ReturnPreview({ order, selection, onChoose }) {
  const { item, eligibility } = selection
  const [quantity, setQuantity] = useState(eligibility.maxQuantity)
  const [quote, setQuote] = useState(null)
  const [error, setError] = useState(null)

  async function preview(event) {
    event.preventDefault()
    setError(null)
    try {
      setQuote(await api.createReturnQuote({ orderReference: order.reference, itemId: item.itemId, quantity }))
    } catch (e) {
      setError(e)
    }
  }

  return (
    <section>
      <h2>
        Return {item.productName} from order {order.reference}
      </h2>
      <form className="constraints" onSubmit={preview}>
        <label>
          Quantity
          <input
            type="number"
            min={1}
            max={eligibility.maxQuantity}
            value={quantity}
            onChange={(e) => setQuantity(Number(e.target.value))}
          />
        </label>
        <button type="submit">Preview the refund</button>
      </form>
      <Problem error={error} />
      {quote && (
        <div className="card" data-testid="return-quote">
          <h3>Quote {quote.quoteId}</h3>
          <dl>
            <dt>Item refund</dt>
            <dd>{money(quote.itemRefund)}</dd>
            <dt>Return shipping fee</dt>
            <dd>−{money(quote.returnShippingFee)}</dd>
            <dt>Restocking fee</dt>
            <dd>−{money(quote.restockingFee)}</dd>
            <dt>Refund</dt>
            <dd className="amount">{money(quote.refundAmount)}</dd>
            <dt>Expires</dt>
            <dd>{time(quote.expiresAt)}</dd>
          </dl>
          <button type="button" onClick={() => onChoose({ ...quote, type: 'RETURN' })}>
            Use this quote
          </button>
        </div>
      )}
    </section>
  )
}
