import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../../api/client.js';
import { deviceHeaders } from '../../lib/device.js';

/** Opened by scanning the teacher's QR code: marks attendance straight away. */
export default function AttendLanding() {
  const [params] = useSearchParams();
  const [result, setResult] = useState(null);
  const sent = useRef(false);  // React StrictMode runs effects twice in development; send once

  useEffect(() => {
    if (sent.current) return;
    sent.current = true;
    const sessionId = Number(params.get('s'));
    const code = params.get('c') || '';
    if (!sessionId || !/^\d{6}$/.test(code)) {
      setResult({ ok: false, text: "This link isn't a valid attendance code. Scan the code on the screen again." });
      return;
    }
    api.post('/student/attendance/mark', { sessionId, code, method: 'QR' }, { headers: deviceHeaders() })
      .then((r) => setResult({ ok: true, text: r.detail }))
      .catch((err) => setResult({ ok: false, text: err.message }));
  }, [params]);

  return (
    <div className="mx-auto max-w-md py-12 text-center">
      {!result && <p className="text-xl" role="status">Marking your attendance…</p>}
      {result && (
        <>
          <p role="status" className={`text-2xl font-bold ${result.ok ? 'text-ok' : 'text-danger'}`}>{result.text}</p>
          <Link to="/student/attendance" className="mt-6 inline-block underline">Go to your attendance</Link>
        </>
      )}
    </div>
  );
}
