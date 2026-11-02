import { useCallback, useEffect, useState } from 'react';

export const inputClass = 'mt-1 w-full rounded border border-line bg-white px-3 py-2';
export const primaryButton = 'rounded bg-board px-4 py-2 font-bold text-white hover:bg-board-deep disabled:opacity-50';
export const secondaryButton = 'rounded border border-line bg-white px-3 py-1.5';

/** Label + control + optional field error, wired together for screen readers. */
export function Field({ id, label, error, children, className = '' }) {
  return (
    <div className={className}>
      <label htmlFor={id} className="font-bold">{label}</label>
      {children}
      {error && <p id={`${id}-error`} className="mt-1 text-sm text-danger">{error}</p>}
    </div>
  );
}

/** Loads data with fn() whenever deps change; reload() refetches. */
export function useLoad(fn, deps) {
  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const load = useCallback(fn, deps);
  const reload = useCallback(async () => {
    try {
      setData(await load());
      setError('');
    } catch (err) {
      setError(err.message);
    }
  }, [load]);
  useEffect(() => { reload(); }, [reload]);
  return { data, error, reload };
}

/** Success and error messages after an action. */
export function Feedback({ notice, error }) {
  return (
    <div aria-live="polite">
      {notice && <p role="status" className="mt-3 text-ok">{notice}</p>}
      {error && <p role="alert" className="mt-3 text-danger">{error}</p>}
    </div>
  );
}

/**
 * Runs an API action and turns the result into notice/error state, with field errors kept.
 * Returns the response body, or true for empty successful responses (201/204 without a body),
 * or undefined on failure, so callers can simply write: if (await act.run(...)) reload();
 */
export function useAction() {
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [fields, setFields] = useState({});
  const [busy, setBusy] = useState(false);
  const run = useCallback(async (fn, successText) => {
    setNotice('');
    setError('');
    setFields({});
    setBusy(true);
    try {
      const result = await fn();
      if (successText) setNotice(typeof successText === 'function' ? successText(result) : successText);
      return result ?? true;
    } catch (err) {
      setError(err.message);
      setFields(err.fields || {});
      return undefined;
    } finally {
      setBusy(false);
    }
  }, []);
  return { run, notice, error, fields, busy };
}
