export function dateTime(iso) {
  if (!iso) return '';
  return new Date(iso).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' });
}

export function fileSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export function marks(value) {
  return value == null ? '' : String(Number(value));
}

/** "2026-10-20T17:00" (datetime-local input) -> ISO string with the browser's offset. */
export function localInputToIso(value) {
  return value ? new Date(value).toISOString() : null;
}

export const PRIORITY_LABEL = { 1: 'High', 2: 'Normal', 3: 'Low' };
