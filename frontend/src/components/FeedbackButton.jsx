import { useEffect, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { api } from '../api/client.js';

const CATEGORIES = [
  ['BUG', 'Something is wrong'], ['IDEA', 'An idea'], ['PRAISE', 'Something works well'], ['OTHER', 'Other'],
];

/** "Feedback" in the header: a short form that records which page the person was on. */
export function FeedbackButton() {
  const [open, setOpen] = useState(false);
  const [category, setCategory] = useState('BUG');
  const [rating, setRating] = useState('');
  const [message, setMessage] = useState('');
  const [state, setState] = useState({ busy: false, done: false, error: '' });
  const location = useLocation();
  const panel = useRef(null);

  useEffect(() => {
    if (open) panel.current?.querySelector('textarea')?.focus();
  }, [open]);

  useEffect(() => {
    if (!open) return undefined;
    const onKey = (e) => e.key === 'Escape' && setOpen(false);
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  async function send(e) {
    e.preventDefault();
    setState({ busy: true, done: false, error: '' });
    try {
      await api.post('/feedback', { category, rating: rating ? Number(rating) : null, message, page: location.pathname });
      setState({ busy: false, done: true, error: '' });
      setMessage('');
      setRating('');
    } catch (err) {
      setState({ busy: false, done: false, error: err.message });
    }
  }

  return (
    <div className="relative">
      <button type="button" onClick={() => { setOpen((o) => !o); setState({ busy: false, done: false, error: '' }); }}
              aria-expanded={open} aria-controls="feedback-panel"
              className="rounded border border-white/40 px-3 py-1.5 hover:bg-board-deep">
        Feedback
      </button>
      {open && (
        <div id="feedback-panel" ref={panel} role="dialog" aria-label="Send feedback"
             className="absolute right-0 z-20 mt-2 w-80 max-w-[90vw] rounded-md border border-line bg-white p-4 text-ink shadow-lg">
          {state.done ? (
            <div className="space-y-3">
              <p className="font-bold text-ok">Thank you. Your feedback was sent.</p>
              <p className="text-sm">Have two minutes? <Link to="/survey" onClick={() => setOpen(false)} className="underline">Take the usability survey</Link>.</p>
              <button type="button" onClick={() => setOpen(false)} className="rounded border border-line px-3 py-1.5">Close</button>
            </div>
          ) : (
            <form onSubmit={send} className="space-y-3">
              <fieldset>
                <legend className="text-sm font-bold">What is it about?</legend>
                {CATEGORIES.map(([value, label]) => (
                  <label key={value} className="mt-1 flex items-center gap-2 text-sm">
                    <input type="radio" name="fb-cat" value={value} checked={category === value} onChange={() => setCategory(value)} />
                    {label}
                  </label>
                ))}
              </fieldset>
              <div>
                <label htmlFor="fb-msg" className="text-sm font-bold">Tell us more</label>
                <textarea id="fb-msg" rows={4} maxLength={4000} value={message} onChange={(e) => setMessage(e.target.value)}
                          required className="mt-1 w-full rounded border border-line px-2 py-1.5" />
              </div>
              <div>
                <label htmlFor="fb-rating" className="text-sm font-bold">How is this page overall? (optional)</label>
                <select id="fb-rating" value={rating} onChange={(e) => setRating(e.target.value)}
                        className="mt-1 w-full rounded border border-line px-2 py-1.5">
                  <option value="">No rating</option>
                  <option value="5">5, very good</option><option value="4">4</option><option value="3">3</option>
                  <option value="2">2</option><option value="1">1, very poor</option>
                </select>
              </div>
              <p className="text-xs text-muted">Sent with your name, role and the page you're on.</p>
              {state.error && <p role="alert" className="text-sm text-danger">{state.error}</p>}
              <div className="flex gap-2">
                <button type="submit" disabled={state.busy || !message.trim()}
                        className="rounded bg-board px-3 py-1.5 font-bold text-white disabled:opacity-50">Send</button>
                <button type="button" onClick={() => setOpen(false)} className="rounded border border-line px-3 py-1.5">Cancel</button>
              </div>
            </form>
          )}
        </div>
      )}
    </div>
  );
}
