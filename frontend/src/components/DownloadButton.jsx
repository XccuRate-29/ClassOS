import { useState } from 'react';
import { api } from '../api/client.js';

export function DownloadButton({ path, label, className = 'rounded border border-line bg-white px-3 py-1.5 text-sm' }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function go() {
    setBusy(true);
    setError('');
    try {
      await api.download(path);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <span className="inline-flex flex-col">
      <button type="button" onClick={go} disabled={busy} className={className}>{busy ? 'Downloading…' : label}</button>
      {error && <span role="alert" className="text-sm text-danger">{error}</span>}
    </span>
  );
}
