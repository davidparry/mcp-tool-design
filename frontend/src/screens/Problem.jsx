export default function Problem({ error }) {
  if (!error) return null
  return (
    <div className="problem" role="alert">
      <strong>{error.code ?? 'error'}</strong> {error.message}
    </div>
  )
}
