import { useState } from 'react'
import Batches from './Batches'
import Failures from './Failures'
import Reconciliation from './Reconciliation'

export default function App() {
  const [tab, setTab] = useState('batches')
  return (<div style={{ padding: 24 }}>
    <h1>Data Quality Platform</h1>
    {['batches', 'failures', 'reconciliation'].map(t =>
      <button key={t} onClick={() => setTab(t)} disabled={tab === t}>{t}</button>)}
    <div style={{ marginTop: 16 }}>
      {tab === 'batches' && <Batches />}
      {tab === 'failures' && <Failures />}
      {tab === 'reconciliation' && <Reconciliation />}
    </div>
  </div>)
}