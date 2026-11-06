export function money(value) {
  if (!value) return ''
  const amount = Number(value.amount)
  const sign = amount < 0 ? '-' : ''
  return `${sign}$${Math.abs(amount).toFixed(2)}`
}

const DATE = new Intl.DateTimeFormat('en-US', {
  weekday: 'short',
  month: 'short',
  day: 'numeric',
  year: 'numeric',
  timeZone: 'UTC',
})

const TIME = new Intl.DateTimeFormat('en-US', {
  hour: 'numeric',
  minute: '2-digit',
  timeZone: 'America/Chicago',
  timeZoneName: 'short',
})

export function date(isoDate) {
  if (!isoDate) return '—'
  return DATE.format(new Date(`${isoDate}T00:00:00Z`))
}

export function time(isoDateTime) {
  if (!isoDateTime) return '—'
  return TIME.format(new Date(isoDateTime))
}

export function variantLabel(item) {
  return [item.size, item.color].filter(Boolean).join(', ')
}

export function status(value) {
  return value ? value.charAt(0) + value.slice(1).toLowerCase() : ''
}
