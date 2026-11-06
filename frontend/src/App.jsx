import { useState } from 'react'
import OrderScreen from './screens/OrderScreen.jsx'
import ItemScreen from './screens/ItemScreen.jsx'
import QuoteScreen from './screens/QuoteScreen.jsx'
import SubmitScreen from './screens/SubmitScreen.jsx'
import './styles.css'

const STEPS = ['Find the order', 'Choose the item', 'Preview options', 'Submit']

export default function App() {
  const [step, setStep] = useState(0)
  const [order, setOrder] = useState(null)
  const [selection, setSelection] = useState(null)
  const [quote, setQuote] = useState(null)

  function openOrder(loaded) {
    setOrder(loaded)
    setSelection(null)
    setQuote(null)
    setStep(1)
  }

  function startAction(item, action, eligibility) {
    setSelection({ item, action, eligibility })
    setQuote(null)
    setStep(2)
  }

  function chooseQuote(chosen) {
    setQuote(chosen)
    setStep(3)
  }

  return (
    <div className="app">
      <header>
        <div className="brand">Trailhead Pet Supply</div>
        <div className="subtitle">Order support</div>
      </header>
      <ol className="steps">
        {STEPS.map((label, index) => (
          <li key={label} className={index === step ? 'current' : index < step ? 'done' : ''}>
            <button type="button" disabled={index > step} onClick={() => setStep(index)}>
              {index + 1}. {label}
            </button>
          </li>
        ))}
      </ol>
      <main>
        {step === 0 && <OrderScreen onOpen={openOrder} />}
        {step === 1 && order && <ItemScreen order={order} onStart={startAction} />}
        {step === 2 && selection && <QuoteScreen order={order} selection={selection} onChoose={chooseQuote} />}
        {step === 3 && quote && <SubmitScreen order={order} selection={selection} quote={quote} />}
      </main>
    </div>
  )
}
