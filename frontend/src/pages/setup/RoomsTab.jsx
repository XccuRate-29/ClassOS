import { useState } from 'react';
import { api } from '../../api/client.js';
import { Feedback, Field, inputClass, primaryButton, secondaryButton, useAction, useLoad } from './common.jsx';

const EMPTY = { code: '', building: '', capacity: '', roomType: 'CLASSROOM', active: true };
const TYPE_LABEL = { CLASSROOM: 'Classroom', LAB: 'Lab', SEMINAR_HALL: 'Seminar hall' };

export function RoomsTab() {
  const rooms = useLoad(() => api.get('/admin/setup/rooms'), []);
  const [form, setForm] = useState(EMPTY);
  const [editingId, setEditingId] = useState(null);
  const act = useAction();
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  async function save(e) {
    e.preventDefault();
    const body = { ...form, capacity: Number(form.capacity) };
    const ok = await act.run(() => (editingId ? api.put(`/admin/setup/rooms/${editingId}`, body) : api.post('/admin/setup/rooms', body)),
      editingId ? 'Room updated.' : 'Room added.');
    if (ok) { setForm(EMPTY); setEditingId(null); rooms.reload(); }
  }

  async function toggle(r) {
    const body = { code: r.code, building: r.building, capacity: r.capacity, roomType: r.roomType, active: !r.active };
    if (await act.run(() => api.put(`/admin/setup/rooms/${r.id}`, body), r.active ? `${r.code} taken out of use.` : `${r.code} back in use.`)) rooms.reload();
  }

  return (
    <div className="space-y-6">
      <form onSubmit={save} className="rounded-md border border-line bg-white p-5">
        <h2 className="text-lg font-bold">{editingId ? 'Edit room' : 'Add a room'}</h2>
        <div className="mt-4 grid gap-4 sm:grid-cols-4">
          <Field id="r-code" label="Code" error={act.fields.code}>
            <input id="r-code" value={form.code} onChange={set('code')} placeholder="A-101" className={inputClass} required />
          </Field>
          <Field id="r-building" label="Building" error={act.fields.building}>
            <input id="r-building" value={form.building} onChange={set('building')} className={inputClass} required />
          </Field>
          <Field id="r-cap" label="Seats" error={act.fields.capacity}>
            <input id="r-cap" type="number" min="1" value={form.capacity} onChange={set('capacity')} className={inputClass} required />
          </Field>
          <Field id="r-type" label="Type">
            <select id="r-type" value={form.roomType} onChange={set('roomType')} className={inputClass}>
              {Object.entries(TYPE_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
          </Field>
        </div>
        <Feedback notice={act.notice} error={act.error} />
        <div className="mt-4 flex gap-3">
          <button type="submit" disabled={act.busy} className={primaryButton}>{editingId ? 'Save room' : 'Add room'}</button>
          {editingId && <button type="button" onClick={() => { setEditingId(null); setForm(EMPTY); }} className={secondaryButton}>Cancel</button>}
        </div>
      </form>

      {rooms.error && <p role="alert" className="text-danger">{rooms.error}</p>}
      {rooms.data && (
        <div className="overflow-x-auto rounded-md border border-line bg-white">
          <table className="w-full min-w-[36rem] text-left text-sm">
            <thead className="border-b border-line text-muted">
              <tr>
                <th scope="col" className="px-4 py-2 font-bold">Room</th>
                <th scope="col" className="px-4 py-2 font-bold">Type</th>
                <th scope="col" className="px-4 py-2 font-bold">Seats</th>
                <th scope="col" className="px-4 py-2 font-bold">Status</th>
                <th scope="col" className="px-4 py-2"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {rooms.data.map((r) => (
                <tr key={r.id} className="border-b border-line last:border-0">
                  <td className="px-4 py-2"><span className="font-bold">{r.code}</span> <span className="text-muted">{r.building}</span></td>
                  <td className="px-4 py-2">{TYPE_LABEL[r.roomType]}</td>
                  <td className="px-4 py-2">{r.capacity}</td>
                  <td className="px-4 py-2">{r.active ? 'In use' : 'Not in use'}</td>
                  <td className="px-4 py-2 text-right">
                    <span className="flex justify-end gap-2">
                      <button type="button" className={secondaryButton} onClick={() => {
                        setEditingId(r.id);
                        setForm({ code: r.code, building: r.building, capacity: String(r.capacity), roomType: r.roomType, active: r.active });
                      }}>Edit</button>
                      <button type="button" className={secondaryButton} onClick={() => toggle(r)}>
                        {r.active ? 'Take out of use' : 'Put back in use'}
                      </button>
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
