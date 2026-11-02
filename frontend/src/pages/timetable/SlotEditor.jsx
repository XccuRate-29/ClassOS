import { useEffect, useMemo, useState } from 'react';
import { api } from '../../api/client.js';
import { DAY_NAMES, hhmm } from '../../lib/time.js';

const CONFLICT_LABEL = { ROOM: 'Room in use', TEACHER: 'Teacher busy', SECTION: 'Section busy' };

/**
 * Add or move a class. Every change is checked against the server as you edit
 * (POST /check), so clashes show up before saving rather than after.
 */
export function SlotEditor({ initial, setup, offeringFilter, onSaved, onCancel }) {
  const [form, setForm] = useState(initial);
  const [check, setCheck] = useState(null);
  const [checkError, setCheckError] = useState('');
  const [saveError, setSaveError] = useState('');
  const [saving, setSaving] = useState(false);

  const offerings = useMemo(() => setup.offerings.filter(offeringFilter), [setup.offerings, offeringFilter]);
  const set = (key) => (e) => setForm((f) => ({ ...f, [key]: e.target.value }));

  const request = useMemo(() => ({
    offeringId: Number(form.offeringId),
    roomId: Number(form.roomId),
    dayOfWeek: Number(form.dayOfWeek),
    startTime: form.startTime,
    endTime: form.endTime,
    kind: form.kind,
  }), [form]);

  const ready = request.offeringId && request.roomId && form.startTime && form.endTime;

  // Debounced live check.
  useEffect(() => {
    if (!ready) return undefined;
    let cancelled = false;
    const timer = setTimeout(async () => {
      try {
        const q = form.slotId ? `?excludeSlotId=${form.slotId}` : '';
        const result = await api.post(`/admin/timetable/check${q}`, request);
        if (!cancelled) {
          setCheck(result);
          setCheckError('');
        }
      } catch (err) {
        if (!cancelled) {
          setCheck(null);
          setCheckError(err.message);
        }
      }
    }, 300);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [request, ready, form.slotId]);

  const hasConflicts = check?.conflicts?.length > 0;
  const hasWarnings = check?.warnings?.length > 0;

  async function save(e) {
    e.preventDefault();
    setSaving(true);
    setSaveError('');
    try {
      const body = { ...request, force: hasWarnings };
      const saved = form.slotId
        ? await api.put(`/admin/timetable/slots/${form.slotId}`, body)
        : await api.post('/admin/timetable/slots', body);
      onSaved(saved);
    } catch (err) {
      // 409 here means someone else changed the timetable between the check and the save.
      if (err.data?.conflicts) setCheck({ conflicts: err.data.conflicts, warnings: err.data.warnings || [] });
      setSaveError(err.message);
    } finally {
      setSaving(false);
    }
  }

  const input = 'mt-1 w-full rounded border border-line bg-white px-3 py-2';

  return (
    <form onSubmit={save} className="rounded-md border border-line bg-white p-5" aria-labelledby="editor-heading">
      <h2 id="editor-heading" className="text-lg font-bold">{form.slotId ? 'Move class' : 'Add a class'}</h2>

      <div className="mt-4 grid gap-4 sm:grid-cols-2">
        <div className="sm:col-span-2">
          <label htmlFor="offering" className="font-bold">Course and section</label>
          <select id="offering" value={form.offeringId} onChange={set('offeringId')} className={input} required>
            <option value="">Choose…</option>
            {offerings.map((o) => (
              <option key={o.id} value={o.id}>
                {o.courseCode} {o.courseTitle}, {o.sectionName} ({o.teacherName})
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="kind" className="font-bold">Type</label>
          <select id="kind" value={form.kind} onChange={set('kind')} className={input}>
            <option value="LECTURE">Lecture</option>
            <option value="LAB">Lab</option>
          </select>
        </div>
        <div>
          <label htmlFor="room" className="font-bold">Room</label>
          <select id="room" value={form.roomId} onChange={set('roomId')} className={input} required>
            <option value="">Choose…</option>
            {setup.rooms.map((r) => (
              <option key={r.id} value={r.id}>
                {r.code} ({r.roomType === 'LAB' ? 'lab' : r.roomType === 'SEMINAR_HALL' ? 'seminar hall' : 'classroom'}, {r.capacity} seats)
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="day" className="font-bold">Day</label>
          <select id="day" value={form.dayOfWeek} onChange={set('dayOfWeek')} className={input}>
            {DAY_NAMES.slice(0, setup.term.workingDays).map((name, i) => (
              <option key={name} value={i + 1}>{name}</option>
            ))}
          </select>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label htmlFor="start" className="font-bold">Starts</label>
            <input id="start" type="time" value={hhmm(form.startTime)} onChange={set('startTime')} className={input} required />
          </div>
          <div>
            <label htmlFor="end" className="font-bold">Ends</label>
            <input id="end" type="time" value={hhmm(form.endTime)} onChange={set('endTime')} className={input} required />
          </div>
        </div>
      </div>

      <div className="mt-5" aria-live="polite">
        {checkError && <p className="text-danger">{checkError}</p>}
        {check && !hasConflicts && !hasWarnings && <p className="text-ok">No clashes at this time.</p>}
        {hasConflicts && (
          <div className="border-l-4 border-danger pl-3">
            <p className="font-bold text-danger">Clashes, so this can't be saved:</p>
            <ul className="mt-1 space-y-1 text-sm">
              {check.conflicts.map((c, i) => (
                <li key={`${c.type}-${c.slot.id}-${i}`}>
                  <span className="font-bold">{CONFLICT_LABEL[c.type]}:</span> {c.slot.courseCode} for {c.slot.sectionName},
                  {' '}{c.slot.teacherName}, {c.slot.roomCode}, {hhmm(c.slot.startTime)}–{hhmm(c.slot.endTime)}
                </li>
              ))}
            </ul>
          </div>
        )}
        {hasWarnings && (
          <div className="mt-3 border-l-4 border-highlight pl-3">
            <p className="font-bold">Check before saving:</p>
            <ul className="mt-1 space-y-1 text-sm">
              {check.warnings.map((w) => <li key={w.type}>{w.message}</li>)}
            </ul>
          </div>
        )}
        {saveError && <p role="alert" className="mt-3 text-danger">{saveError}</p>}
      </div>

      <div className="mt-5 flex gap-3">
        <button type="submit" disabled={saving || !ready || hasConflicts || !check}
                className="rounded bg-board px-4 py-2 font-bold text-white hover:bg-board-deep disabled:opacity-50">
          {saving ? 'Saving…' : hasWarnings ? 'Save anyway' : 'Save class'}
        </button>
        <button type="button" onClick={onCancel} className="rounded border border-line px-4 py-2">Cancel</button>
      </div>
    </form>
  );
}
