import { useState } from 'react'
import { api } from '../api.js'
import { date, money, status, time } from '../format.js'
import Problem from './Problem.jsx'

/** Screen 1: order search and order details. */
export default function OrderScreen({ onOpen }) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState(null)
  const [detail, setDetail] = useState(null)
  const [error, setError] = useState(null)

  async function search(event) {
    event.preventDefault()
    setError(null)
    setDetail(null)
    try {
      setResults(await api.searchOrders(query.trim()))
    } catch (e) {
      setError(e)
    }
  }

  async function show(reference) {
    setError(null)
    try {
      const [order, items] = await Promise.all([api.order(reference), api.orderItems(reference)])
      setDetail({ ...order, items: items.items })
    } catch (e) {
      setError(e)
    }
  }

  return (
    <section>
      <h2>Find the order</h2>
      <form className="search" onSubmit={search}>
        <input
          aria-label="Order reference, customer name, or email"
          placeholder="Order reference, customer name, or email"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <button type="submit">Search</button>
      </form>
      <Problem error={error} />
      {results && (
        <table data-testid="search-results">
          <thead>
            <tr>
              <th>Order</th>
              <th>Customer</th>
              <th>Placed</th>
              <th>Status</th>
              <th>Units</th>
              <th>Total</th>
            </tr>
          </thead>
          <tbody>
            {results.items.map((o) => (
              <tr key={o.reference} className="clickable" onClick={() => show(o.reference)}>
                <td>
                  <button type="button" className="link">
                    {o.reference}
                  </button>
                </td>
                <td>{o.customerName}</td>
                <td>{date(o.placedAt.slice(0, 10))}</td>
                <td>{status(o.status)}</td>
                <td>{o.itemCount}</td>
                <td>{money(o.total)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <td colSpan={6}>
                {results.totalItems} order{results.totalItems === 1 ? '' : 's'}
              </td>
            </tr>
          </tfoot>
        </table>
      )}
      {detail && (
        <div className="card" data-testid="order-detail">
          <h3>
            Order {detail.reference} <span className={`badge ${detail.status.toLowerCase()}`}>{status(detail.status)}</span>
          </h3>
          <dl>
            <dt>Customer</dt>
            <dd>
              {detail.customer.name} · {detail.customer.email}
            </dd>
            <dt>Ships to</dt>
            <dd>
              {detail.shipTo.line1}, {detail.shipTo.city}, {detail.shipTo.state} {detail.shipTo.postalCode}
            </dd>
            <dt>Placed</dt>
            <dd>
              {date(detail.placedAt.slice(0, 10))} at {time(detail.placedAt)}
            </dd>
            <dt>Shipped</dt>
            <dd>{date(detail.shippedOn)}</dd>
            <dt>{detail.deliveredOn ? 'Delivered' : 'Estimated delivery'}</dt>
            <dd>{date(detail.deliveredOn ?? detail.estimatedDelivery)}</dd>
            <dt>Total</dt>
            <dd>
              {money(detail.total)} ({money(detail.subtotal)} + {money(detail.shippingCharge)} shipping)
            </dd>
          </dl>
          <ul className="items">
            {detail.items.map((item) => (
              <li key={item.itemId}>
                {item.quantity} × {item.productName} {item.size || item.color ? `(${[item.size, item.color].filter(Boolean).join(', ')})` : ''} ·{' '}
                {money(item.unitPrice)}
              </li>
            ))}
          </ul>
          <button type="button" onClick={() => onOpen(detail)}>
            Help with this order
          </button>
        </div>
      )}
    </section>
  )
}
