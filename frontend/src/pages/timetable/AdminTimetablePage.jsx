import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '../../api/client.js';
import { TimetableGrid } from '../../components/TimetableGrid.jsx';
import { DAY_NAMES, hhmm } from '../../lib/time.js';
import { GeneratePanel } from './GeneratePanel.jsx';
import { SlotEditor } from './SlotEditor.jsx';

const MODES = [
  { value: 'section', label: 'Section', param: 'sectionId' },
  { value: 'teacher', label: 'Teacher', param: 'teacherId' },
  { value: 'room', label: 'Room', param: 'roomId' },
];

export default function AdminTimetablePage() {
  const [setup, setSetup] = useState(null);
  const [error, setError] = useState('');
  const [mode, setMode] = useState('section');
  const [selectedId, setSelectedId] = useState('');
  const [view, setView] = useState(null);
  const [editor, setEditor] = useState(null);
  const [editorKey, setEditorKey] = useState(0);
  const [selectedSlot, setSelectedSlot] = useState(null);
  const [actionError, setActionError] = useState('');

  const loadSetup = useCallback(async () => {
    try {
      const s = await api.get('/admin/timetable/setup');
      setSetup(s);
      setError('');
      return s;
    } catch (err) {
      setError(err.message);
      return null;
    }
  }, []);

  useEffect(() => {
    loadSetup().then((s) => {
      if (s?.sections.length) setSelectedId(String(s.sections[0].id));
    });
  }, [loadSetup]);

  const options = useMemo(() => {
    if (!setup) return [];
    if (mode === 'section') return setup.sections.map((s) => ({ id: s.id, label: `${s.name} (${s.strength} students)` }));
    if (mode === 'teacher') return setup.teachers.map((t) => ({ id: t.id, label: t.fullName }));
    return setup.rooms.map((r) => ({ id: r.id, label: `${r.code} (${r.capacity} seats)` }));
  }, [setup, mode]);

  const loadView = useCallback(async () => {
    if (!setup || !selectedId) return;
    const param = MODES.find((m) => m.value === mode).param;
    try {
      setView(await api.get(`/admin/timetable?termId=${setup.term.id}&${param}=${selectedId}`));
      setError('');
    } catch (err) {
      setError(err.message);
    }
  }, [setup, mode, selectedId]);

  useEffect(() => {
    loadView();
  }, [loadView]);

  function changeMode(next) {
    setMode(next);
    setSelectedId('');
    setView(null);
    setEditor(null);
    setSelectedSlot(null);
  }

  async function refresh() {
    await loadSetup();
    await loadView();
  }

  // Only offer courses that make sense for what is being viewed.
  const offeringFilter = useCallback((o) => {
    if (mode === 'section') return String(o.sectionId) === selectedId;
    if (mode === 'teacher') return String(o.teacherId) === selectedId;
    return true;
  }, [mode, selectedId]);

  function openEditor(values) {
    setSelectedSlot(null);
    setActionError('');
    setEditor(values);
    setEditorKey((k) => k + 1);
  }

  function addAt(day, row) {
    const p = setup.periods[row];
    const firstOffering = setup.offerings.find(offeringFilter);
    openEditor({
      slotId: null,
      offeringId: firstOffering ? String(firstOffering.id) : '',
      roomId: mode === 'room' ? selectedId : '',
      dayOfWeek: String(day),
      startTime: hhmm(p.startTime),
      endTime: hhmm(p.endTime),
      kind: 'LECTURE',
    });
  }

  function moveSlot(slot) {
    openEditor({
      slotId: slot.id,
      offeringId: String(slot.offeringId),
      roomId: String(slot.roomId),
      dayOfWeek: String(slot.dayOfWeek),
      startTime: hhmm(slot.startTime),
      endTime: hhmm(slot.endTime),
      kind: slot.kind,
    });
  }

  async function slotAction(fn) {
    setActionError('');
    try {
      await fn();
      setSelectedSlot(null);
      await refresh();
    } catch (err) {
      setActionError(err.message);
    }
  }

  const describe = (s) => {
    if (mode === 'section') return [s.courseCode, s.teacherName, s.roomCode];
    if (mode === 'teacher') return [s.courseCode, s.sectionName, s.roomCode];
    return [s.courseCode, s.sectionName, s.teacherName];
  };

  if (error && !setup) return <p role="alert" className="text-danger">{error}</p>;
  if (!setup) return <p className="text-muted" role="status">Loading timetable…</p>;

  const sectionOfferings = mode === 'section' ? setup.offerings.filter(offeringFilter) : [];

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-3xl font-bold">Timetable</h1>
        <p className="text-muted">{setup.term.name}, {setup.term.workingDays} days a week.</p>
      </div>

      <GeneratePanel term={setup.term} onGenerated={refresh} />

      <section aria-labelledby="view-heading" className="space-y-4">
        <h2 id="view-heading" className="sr-only">Timetable view</h2>
        <div className="flex flex-wrap items-end gap-4">
          <fieldset>
            <legend className="font-bold">Show by</legend>
            <div className="mt-1 flex overflow-hidden rounded border border-line bg-white">
              {MODES.map((m) => (
                <label key={m.value}
                       className={`cursor-pointer px-3 py-2 ${mode === m.value ? 'bg-board text-white' : ''}`}>
                  <input type="radio" name="mode" value={m.value} checked={mode === m.value}
                         onChange={() => changeMode(m.value)} className="sr-only" />
                  {m.label}
                </label>
              ))}
            </div>
          </fieldset>
          <div>
            <label htmlFor="which" className="font-bold">{MODES.find((m) => m.value === mode).label}</label>
            <select id="which" value={selectedId} onChange={(e) => setSelectedId(e.target.value)}
                    className="mt-1 block min-w-56 rounded border border-line bg-white px-3 py-2">
              <option value="">Choose…</option>
              {options.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
            </select>
          </div>
        </div>

        {error && <p role="alert" className="text-danger">{error}</p>}

        {view && (
          <>
            <p className="text-sm text-muted">Select a class to move, pin or remove it, or an empty cell to add one.</p>
            <TimetableGrid
              periods={view.periods}
              workingDays={view.term.workingDays}
              slots={view.slots}
              describe={describe}
              onEmptyCell={addAt}
              onSlot={(s) => { setEditor(null); setActionError(''); setSelectedSlot(s); }}
              selectedSlotId={selectedSlot?.id}
            />
          </>
        )}

        {selectedSlot && (
          <div className="rounded-md border border-line bg-white p-5">
            <h3 className="text-lg font-bold">{selectedSlot.courseCode} {selectedSlot.courseTitle}</h3>
            <p>
              {selectedSlot.sectionName}, {selectedSlot.teacherName}, {selectedSlot.roomCode}.{' '}
              {DAY_NAMES[selectedSlot.dayOfWeek - 1]} {hhmm(selectedSlot.startTime)}–{hhmm(selectedSlot.endTime)}.
            </p>
            <p className="text-sm text-muted">
              {selectedSlot.source === 'GENERATED' ? 'Placed by the generator' : 'Added or moved by hand'}
              {selectedSlot.locked ? ', pinned (kept when regenerating).' : '.'}
            </p>
            {actionError && <p role="alert" className="mt-2 text-danger">{actionError}</p>}
            <div className="mt-4 flex flex-wrap gap-3">
              <button type="button" onClick={() => moveSlot(selectedSlot)}
                      className="rounded bg-board px-4 py-2 font-bold text-white hover:bg-board-deep">Move</button>
              <button type="button"
                      onClick={() => slotAction(() => api.patch(`/admin/timetable/slots/${selectedSlot.id}/lock`,
                        { locked: !selectedSlot.locked }))}
                      className="rounded border border-line px-4 py-2">
                {selectedSlot.locked ? 'Unpin' : 'Pin'}
              </button>
              <button type="button" onClick={() => slotAction(() => api.del(`/admin/timetable/slots/${selectedSlot.id}`))}
                      className="rounded border border-danger px-4 py-2 text-danger">Remove class</button>
            </div>
          </div>
        )}

        {editor && (
          <SlotEditor
            key={editorKey}
            initial={editor}
            setup={setup}
            offeringFilter={offeringFilter}
            onSaved={async () => { setEditor(null); await refresh(); }}
            onCancel={() => setEditor(null)}
          />
        )}
      </section>

      {mode === 'section' && sectionOfferings.length > 0 && (
        <section aria-labelledby="hours-heading">
          <h2 id="hours-heading" className="text-lg font-bold">Weekly hours scheduled</h2>
          <div className="mt-3 overflow-x-auto rounded-md border border-line bg-white">
            <table className="w-full min-w-[36rem] text-left text-sm">
              <thead className="border-b border-line text-muted">
                <tr>
                  <th scope="col" className="px-4 py-2 font-bold">Course</th>
                  <th scope="col" className="px-4 py-2 font-bold">Teacher</th>
                  <th scope="col" className="px-4 py-2 font-bold">Lectures</th>
                  <th scope="col" className="px-4 py-2 font-bold">Lab periods</th>
                </tr>
              </thead>
              <tbody>
                {sectionOfferings.map((o) => (
                  <tr key={o.id} className="border-b border-line last:border-0">
                    <td className="px-4 py-2">{o.courseCode} {o.courseTitle}</td>
                    <td className="px-4 py-2">{o.teacherName}</td>
                    <Hours done={o.scheduledLecturePeriods} need={o.lectureHoursPerWeek} />
                    <Hours done={o.scheduledLabPeriods} need={o.labHoursPerWeek} />
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  );
}

function Hours({ done, need }) {
  if (need === 0 && done === 0) return <td className="px-4 py-2 text-muted">None</td>;
  const short = done < need;
  return (
    <td className={`px-4 py-2 ${short ? 'font-bold text-danger' : ''}`}>
      {done} of {need}{short ? ' (short)' : ''}
    </td>
  );
}
