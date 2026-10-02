import { useEffect, useState } from 'react'

export default function App() {
  const [batches, setBatches] = useState([])
  const [source, setSource] = useState('SYSTEM_A')
  const [file, setFile] = useState(null)
  const [message, setMessage] = useState('')

  const load = () => fetch('/api/batches').then(r => r.json()).then(setBatches)

  useEffect(() => {
    load()
    const t = setInterval(load, 3000)   // auto-refresh so you can watch status change
    return () => clearInterval(t)
  }, [])

  const upload = async () => {
    if (!file) return
    const form = new FormData()
    form.append('source', source)
    form.append('file', file)
    const res = await fetch('/api/batches', { method: 'POST', body: form })
    setMessage(res.status === 409 ? 'Already uploaded' : res.ok ? 'Uploaded' : 'Error')
    load()
  }

  return (
    <div style={{ padding: 24 }}>
      <h1>Data Quality Platform</h1>
      <select value={source} onChange={e => setSource(e.target.value)}>
        <option>SYSTEM_A</option><option>SYSTEM_B</option>
      </select>
      <input type="file" accept=".csv" onChange={e => setFile(e.target.files[0])} />
      <button onClick={upload}>Upload</button> {message}

      <table border="1" cellPadding="6" style={{ marginTop: 16 }}>
        <thead><tr><th>File</th><th>Source</th><th>Status</th><th>Rows</th>
          <th>Valid</th><th>Invalid</th><th>Duplicate</th></tr></thead>
        <tbody>{batches.map(b => (
          <tr key={b.id}><td>{b.fileName}</td><td>{b.source}</td><td>{b.status}</td>
            <td>{b.totalRows}</td><td>{b.validRows}</td><td>{b.invalidRows}</td>
            <td>{b.duplicateRows}</td></tr>))}
        </tbody>
      </table>
    </div>
  )
}