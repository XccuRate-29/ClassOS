export const DAY_NAMES = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
export const DAY_SHORT = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

/** "09:00:00" -> "09:00" */
export const hhmm = (t) => (t ? t.slice(0, 5) : '');

export const toMinutes = (t) => Number(t.slice(0, 2)) * 60 + Number(t.slice(3, 5));

/** Indexes of the periods that overlap [start, end). */
export function coveredPeriodIndexes(periods, start, end) {
  const s = toMinutes(start);
  const e = toMinutes(end);
  const out = [];
  periods.forEach((p, i) => {
    if (toMinutes(p.startTime) < e && s < toMinutes(p.endTime)) out.push(i);
  });
  return out;
}

export function todayIso() {
  const d = new Date();
  const local = new Date(d.getTime() - d.getTimezoneOffset() * 60000);
  return local.toISOString().slice(0, 10);
}
