import { useState } from 'react';
import { api } from '../../api/client.js';
import { Feedback, Field, inputClass, primaryButton, useAction } from '../setup/common.jsx';

/** Bulk import: paste CSV or choose a .csv file (email,full name,role). */
export function ImportUsers({ onImported }) {
  const [csv, setCsv] = useState('');
  const [password, setPassword] = useState('');
  const [result, setResult] = useState(null);
  const act = useAction();

  function readFile(e) {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => setCsv(String(reader.result || ''));
    reader.readAsText(file);
  }

  async function submit(e) {
    e.preventDefault();
    setResult(null);
    const r = await act.run(() => api.post('/admin/users/import', { csv, temporaryPassword: password }));
    if (r) {
      setResult(r);
      if (r.created > 0) onImported(r.created);
    }
  }

  return (
    <form onSubmit={submit} className="rounded-md border border-line bg-white p-5">
      <h2 className="text-lg font-bold">Import users from a spreadsheet</h2>
      <p className="text-sm text-muted">
        Save the sheet as CSV with three columns: <code>email,full name,role</code>. Role is STUDENT, TEACHER,
        LAB_ASSISTANT or ADMIN. Names must not contain commas.
      </p>
      <div className="mt-4 grid gap-4 sm:grid-cols-2">
        <Field id="imp-file" label="CSV file">
          <input id="imp-file" type="file" accept=".csv,text/csv" onChange={readFile} className="mt-1 block w-full" />
        </Field>
        <Field id="imp-pw" label="Temporary password for everyone" error={act.fields.temporaryPassword}>
          <input id="imp-pw" type="text" autoComplete="off" minLength={10} value={password}
                 onChange={(e) => setPassword(e.target.value)} className={inputClass} required />
        </Field>
        <Field id="imp-csv" label="Or paste the rows" className="sm:col-span-2">
          <textarea id="imp-csv" rows={5} value={csv} onChange={(e) => setCsv(e.target.value)}
                    placeholder={'email,full name,role\nasha.rawat@college.edu,Asha Rawat,STUDENT'} className={`${inputClass} font-mono text-sm`} />
        </Field>
      </div>
      <p className="mt-2 text-sm text-muted">Tell people to change the temporary password on their Account page after signing in.</p>
      <Feedback notice={act.notice} error={act.error} />
      {result && (
        <div className="mt-3 text-sm" role="status">
          <p className="font-bold text-ok">Created {result.created} account{result.created === 1 ? '' : 's'}.</p>
          {result.errors.length > 0 && (
            <>
              <p className="mt-2 font-bold">Skipped {result.errors.length} line{result.errors.length === 1 ? '' : 's'}:</p>
              <ul className="mt-1 space-y-1">
                {result.errors.map((er) => <li key={er.line}>Line {er.line}: {er.message} <span className="text-muted">({er.text})</span></li>)}
              </ul>
            </>
          )}
        </div>
      )}
      <button type="submit" disabled={act.busy || !csv.trim() || password.length < 10} className={`${primaryButton} mt-4`}>
        {act.busy ? 'Importing…' : 'Import'}
      </button>
    </form>
  );
}
