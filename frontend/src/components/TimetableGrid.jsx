import { DAY_NAMES, DAY_SHORT, coveredPeriodIndexes, hhmm } from '../lib/time.js';

/**
 * Weekly grid: periods down, days across. A class spanning two periods (a lab block)
 * uses rowSpan, so the table structure itself shows its length.
 *
 * describe(slot) returns the lines to show; onSlot / onEmptyCell are optional.
 */
export function TimetableGrid({ periods, workingDays, slots, describe, onSlot, onEmptyCell, selectedSlotId }) {
  if (!periods.length) {
    return <p className="text-muted">The period grid for this term hasn't been set up yet.</p>;
  }
  const days = Array.from({ length: workingDays }, (_, i) => i + 1);

  // Group slots by the cell where they start; remember which cells are covered by spans.
  const starts = new Map();
  const covered = new Set();
  const outside = [];
  for (const slot of slots) {
    const rows = coveredPeriodIndexes(periods, slot.startTime, slot.endTime);
    if (!rows.length || slot.dayOfWeek > workingDays) {
      outside.push(slot);
      continue;
    }
    const key = `${slot.dayOfWeek}-${rows[0]}`;
    const group = starts.get(key) || { slots: [], span: 0 };
    group.slots.push(slot);
    group.span = Math.max(group.span, rows.length);
    starts.set(key, group);
  }
  for (const [key, group] of starts) {
    const [day, row] = key.split('-').map(Number);
    for (let r = row + 1; r < row + group.span; r++) covered.add(`${day}-${r}`);
  }

  return (
    <div>
      <div className="overflow-x-auto rounded-md border border-line bg-white">
        <table className="w-full min-w-[46rem] table-fixed border-collapse text-sm">
          <caption className="sr-only">Weekly timetable</caption>
          <thead>
            <tr className="border-b border-line">
              <th scope="col" className="w-24 px-2 py-2 text-left font-bold text-muted">Period</th>
              {days.map((d) => (
                <th key={d} scope="col" className="px-2 py-2 text-left font-bold">
                  <abbr title={DAY_NAMES[d - 1]} className="no-underline">{DAY_SHORT[d - 1]}</abbr>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {periods.map((p, row) => [
              <tr key={p.periodNo} className="border-b border-line align-top">
                <th scope="row" className="px-2 py-2 text-left font-normal">
                  <span className="font-bold">P{p.periodNo}</span>
                  <span className="block text-xs text-muted">{hhmm(p.startTime)}–{hhmm(p.endTime)}</span>
                </th>
                {days.map((d) => {
                  const key = `${d}-${row}`;
                  if (covered.has(key)) return null;
                  const group = starts.get(key);
                  if (group) {
                    return (
                      <td key={key} rowSpan={group.span} className="border-l border-line p-1">
                        <div className="flex h-full flex-col gap-1">
                          {group.slots.map((s) => (
                            <SlotCard key={s.id} slot={s} lines={describe(s)} onClick={onSlot}
                                      selected={s.id === selectedSlotId} />
                          ))}
                        </div>
                      </td>
                    );
                  }
                  return (
                    <td key={key} className="border-l border-line p-1">
                      {onEmptyCell && (
                        <button
                          type="button"
                          onClick={() => onEmptyCell(d, row)}
                          aria-label={`Add a class on ${DAY_NAMES[d - 1]}, period ${p.periodNo}`}
                          className="h-full min-h-10 w-full rounded text-muted/40 hover:bg-paper hover:text-muted focus-visible:text-muted"
                        >
                          +
                        </button>
                      )}
                    </td>
                  );
                })}
              </tr>,
              p.breakAfter && row < periods.length - 1 ? (
                <tr key={`break-${p.periodNo}`} className="border-b border-line bg-paper">
                  <td colSpan={days.length + 1} className="px-2 py-1 text-xs text-muted">
                    Break until {hhmm(periods[row + 1].startTime)}
                  </td>
                </tr>
              ) : null,
            ])}
          </tbody>
        </table>
      </div>
      {outside.length > 0 && (
        <div className="mt-3 text-sm">
          <p className="font-bold">Outside the period grid</p>
          <ul className="mt-1 space-y-1">
            {outside.map((s) => (
              <li key={s.id}>
                {DAY_NAMES[s.dayOfWeek - 1]} {hhmm(s.startTime)}–{hhmm(s.endTime)}: {describe(s).join(', ')}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function SlotCard({ slot, lines, onClick, selected }) {
  const lab = slot.kind === 'LAB';
  const body = (
    <>
      <span className="block font-bold">{lines[0]}</span>
      {lines.slice(1).map((l) => (
        <span key={l} className="block text-xs text-muted">{l}</span>
      ))}
      {(lab || slot.locked) && (
        <span className="mt-1 block text-xs">
          {[lab && 'Lab', slot.locked && 'Pinned'].filter(Boolean).join(', ')}
        </span>
      )}
    </>
  );
  const cls = `block h-full w-full rounded border-l-4 px-2 py-1.5 text-left ${
    lab ? 'border-board bg-[#e4ece9]' : 'border-board/50 bg-paper'
  } ${selected ? 'outline outline-[3px] outline-highlight' : ''}`;
  return onClick ? (
    <button type="button" className={cls} onClick={() => onClick(slot)} aria-pressed={selected}>
      {body}
    </button>
  ) : (
    <div className={cls}>{body}</div>
  );
}
