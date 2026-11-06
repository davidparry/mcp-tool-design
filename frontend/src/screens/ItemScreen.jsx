import { useState } from 'react'
import { api } from '../api.js'
import { date, money, variantLabel } from '../format.js'
import Problem from './Problem.jsx'

/** Screen 2: purchased item selection and return or exchange eligibility. */
export default function ItemScreen({ order, onStart }) {
  const [checked, setChecked] = useState(null)
  const [error, setError] = useState(null)

  async function check(item, action) {
    setError(null)
    try {
      const eligibility =
        action === 'EXCHANGE'
          ? await api.exchangeEligibility(order.reference, item.itemId)
          : await api.returnEligibility(order.reference, item.itemId)
      setChecked({ item, action, eligibility })
    } catch (e) {
      setError(e)
    }
  }

  return (
    <section>
      <h2>Order {order.reference}: choose the item</h2>
      <table data-testid="order-items">
        <thead>
          <tr>
            <th>Item</th>
            <th>Variant</th>
            <th>Qty</th>
            <th>Paid each</th>
            <th>Returned / exchanged</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {order.items.map((item) => (
            <tr key={item.itemId}>
              <td>
                {item.productName}
                <div className="muted">
                  {item.itemId} · {item.sku}
                </div>
              </td>
              <td>{variantLabel(item) || '—'}</td>
              <td>{item.quantity}</td>
              <td>{money(item.unitPrice)}</td>
              <td>
                {item.returnedQuantity} / {item.exchangedQuantity}
              </td>
              <td className="actions">
                <button type="button" onClick={() => check(item, 'EXCHANGE')} data-testid={`exchange-${item.itemId}`}>
                  Exchange
                </button>
                <button type="button" onClick={() => check(item, 'RETURN')} data-testid={`return-${item.itemId}`}>
                  Return
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <Problem error={error} />
      {checked && (
        <div className={`card ${checked.eligibility.eligible ? 'ok' : 'blocked'}`} data-testid="eligibility">
          <h3>
            {checked.action === 'EXCHANGE' ? 'Exchange' : 'Return'} · {checked.item.productName}
          </h3>
          {checked.eligibility.eligible ? (
            <p>
              Eligible until {date(checked.eligibility.windowEndsOn)} for up to {checked.eligibility.maxQuantity}{' '}
              unit{checked.eligibility.maxQuantity === 1 ? '' : 's'}.
            </p>
          ) : (
            <p>
              <strong>Not eligible</strong> ({checked.eligibility.reasonCode}): {checked.eligibility.reason}
            </p>
          )}
          <p className="muted">
            {checked.eligibility.policy.returnCategory}: {checked.eligibility.policy.description}
          </p>
          {checked.eligibility.eligible && (
            <button type="button" onClick={() => onStart(checked.item, checked.action, checked.eligibility)}>
              {checked.action === 'EXCHANGE' ? 'Choose a replacement' : 'Preview the refund'}
            </button>
          )}
        </div>
      )}
    </section>
  )
}
