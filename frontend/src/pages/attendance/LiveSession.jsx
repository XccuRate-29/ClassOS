import { useEffect, useRef, useState } from 'react';
import QRCode from 'qrcode';
import { api } from '../../api/client.js';

/**
 * The teacher's projector view: a QR code and the same 6-digit code, both changing
 * every few seconds. Scanning the QR opens /attend on the student's phone.
 */
export function LiveSession({ sessionId, onClosed }) {
  const [view, setView] = useState(null);
  const [qr, setQr] = useState('');
  const [secondsLeft, setSecondsLeft] = useState(0);
  const [error, setError] = useState('');
  const [detail, setDetail] = useState(null);
  const [showList, setShowList] = useState(false);
  const [confirmClose, setConfirmClose] = useState(false);
  const panel = useRef(null);
  const deadline = useRef(0);

  // Poll the code: refetch just after it rotates, and at least every 3 s for the live count.
  useEffect(() => {
    let cancelled = false;
    let timer;
    async function tick() {
      try {
        const v = await api.get(`/teacher/attendance/sessions/${sessionId}/code`);
        if (cancelled) return;
        setView(v);
        setError('');
        if (v.status !== 'OPEN') {
          onClosed?.();
          return;
        }
        deadline.current = Date.now() + v.validForMs;
        timer = setTimeout(tick, Math.min(v.validForMs + 150, 3000));
      } catch (err) {
        if (cancelled) return;
        setError(err.message);
        timer = setTimeout(tick, 3000);
      }
    }
    tick();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [sessionId, onClosed]);

  const link = view?.code ? `${window.location.origin}/attend?s=${sessionId}&c=${view.code}` : '';

  useEffect(() => {
    if (!link) return undefined;
    let cancelled = false;
    QRCode.toDataURL(link, { width: 420, margin: 1, errorCorrectionLevel: 'M' })
      .then((url) => !cancelled && setQr(url))
      .catch(() => !cancelled && setQr(''));
    return () => { cancelled = true; };
  }, [link]);

  useEffect(() => {
    const t = setInterval(() => setSecondsLeft(Math.max(0, Math.ceil((deadline.current - Date.now()) / 1000))), 250);
    return () => clearInterval(t);
  }, []);

  async function loadDetail() {
    try {
      setDetail(await api.get(`/teacher/attendance/sessions/${sessionId}`));
    } catch (err) {
      setError(err.message);
    }
  }

  useEffect(() => {
    if (showList) loadDetail();
  }, [showList, view?.presentCount]); // eslint-disable-line react-hooks/exhaustive-deps

  async function setPresent(studentId, present) {
    try {
      setDetail(await api.post(`/teacher/attendance/sessions/${sessionId}/manual`, { studentId, present }));
    } catch (err) {
      setError(err.message);
    }
  }

  async function close() {
    setConfirmClose(false);
    try {
      await api.post(`/teacher/attendance/sessions/${sessionId}/close`);
      onClosed?.();
    } catch (err) {
      setError(err.message);
    }
  }

  function projector() {
    panel.current?.requestFullscreen?.().catch(() => {});
  }

  if (!view) return <p className="text-muted" role="status">{error || 'Opening attendance…'}</p>;

  return (
    <section aria-labelledby="live-heading" className="space-y-4">
      <div ref={panel} className="flex flex-col items-center gap-4 rounded-md border border-line bg-white p-6 text-center">
        <h2 id="live-heading" className="text-lg font-bold">Scan to mark attendance</h2>
        {qr ? (
          <img src={qr} alt="QR code for marking attendance" className="aspect-square w-full max-w-[min(70vh,26rem)]" />
        ) : (
          <div className="aspect-square w-full max-w-[26rem] bg-paper" />
        )}
        <p>
          <span className="block text-sm text-muted">Or type this code</span>
          <span className="font-bold tabular-nums tracking-[0.3em] text-5xl" aria-live="off">{view.code}</span>
        </p>
        <p className="text-sm text-muted" aria-live="off">New code in {secondsLeft} s</p>
        <p className="text-2xl font-bold" aria-live="polite">
          {view.presentCount} of {view.enrolledCount} present
        </p>
      </div>

      {error && <p role="alert" className="text-danger">{error}</p>}

      <div className="flex flex-wrap gap-3">
        <button type="button" onClick={projector} className="rounded border border-line bg-white px-4 py-2">
          Show full screen
        </button>
        <button type="button" onClick={() => setShowList((v) => !v)} className="rounded border border-line bg-white px-4 py-2">
          {showList ? 'Hide students' : 'Show students'}
        </button>
        {!confirmClose ? (
          <button type="button" onClick={() => setConfirmClose(true)}
                  className="rounded bg-board px-4 py-2 font-bold text-white hover:bg-board-deep">
            Close attendance
          </button>
        ) : (
          <span className="flex flex-wrap items-center gap-3">
            <span>Everyone not marked will be recorded absent.</span>
            <button type="button" onClick={close} className="rounded bg-board px-4 py-2 font-bold text-white">Close now</button>
            <button type="button" onClick={() => setConfirmClose(false)} className="rounded border border-line px-4 py-2">Keep open</button>
          </span>
        )}
      </div>

      {showList && detail && <StudentList detail={detail} onSet={setPresent} />}
    </section>
  );
}

export function StudentList({ detail, onSet }) {
  const rejected = Object.entries(detail.rejectedAttempts || {});
  return (
    <div className="space-y-3">
      {rejected.length > 0 && (
        <p className="border-l-4 border-highlight pl-3 text-sm">
          Rejected attempts: {rejected.map(([k, n]) => `${n} ${LABEL[k] || k}`).join(', ')}.
          Expired codes or phone mismatches can mean a code was shared outside the room.
        </p>
      )}
      <div className="overflow-x-auto rounded-md border border-line bg-white">
        <table className="w-full min-w-[34rem] text-left text-sm">
          <thead className="border-b border-line text-muted">
            <tr>
              <th scope="col" className="px-4 py-2 font-bold">Student</th>
              <th scope="col" className="px-4 py-2 font-bold">Status</th>
              <th scope="col" className="px-4 py-2 font-bold">How</th>
              <th scope="col" className="px-4 py-2 font-bold"><span className="sr-only">Change</span></th>
            </tr>
          </thead>
          <tbody>
            {detail.students.map((s) => (
              <tr key={s.studentId} className="border-b border-line last:border-0">
                <td className="px-4 py-2">{s.fullName}</td>
                <td className={`px-4 py-2 ${s.status === 'PRESENT' ? 'text-ok' : s.status === 'ABSENT' ? 'text-danger' : 'text-muted'}`}>
                  {s.status === 'PRESENT' ? 'Present' : s.status === 'ABSENT' ? 'Absent' : 'Not marked'}
                </td>
                <td className="px-4 py-2 text-muted">{METHOD[s.method] || ''}</td>
                <td className="px-4 py-2 text-right">
                  <button type="button" onClick={() => onSet(s.studentId, s.status !== 'PRESENT')}
                          className="rounded border border-line px-3 py-1">
                    {s.status === 'PRESENT' ? 'Mark absent' : 'Mark present'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

const METHOD = { QR: 'Scanned', OTP: 'Typed code', MANUAL: 'By teacher', AUTO: 'At close' };
const LABEL = {
  WRONG_CODE: 'wrong code', EXPIRED_CODE: 'expired code', DEVICE_MISMATCH: 'different phone',
  DEVICE_IN_USE: 'shared phone', NOT_ENROLLED: 'not in section', NETWORK_BLOCKED: 'off campus network',
  TOO_MANY_ATTEMPTS: 'too many tries', SESSION_CLOSED: 'after closing', DEVICE_MISSING: 'no device id',
};
