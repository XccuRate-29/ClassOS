import { useEffect, useState } from 'react';
import { api } from '../api/client.js';

// The System Usability Scale (Brooke, 1996), free to use with acknowledgement.
// "the system" is replaced by "ClassOS", a standard adaptation.
// Odd statements are positive, even ones negative; the server computes the 0-100 score.
const STATEMENTS = [
  'I think that I would like to use ClassOS frequently.',
  'I found ClassOS unnecessarily complex.',
  'I thought ClassOS was easy to use.',
  'I think that I would need the support of a technical person to be able to use ClassOS.',
  'I found the various functions in ClassOS were well integrated.',
  'I thought there was too much inconsistency in ClassOS.',
  'I would imagine that most people would learn to use ClassOS very quickly.',
  'I found ClassOS very cumbersome to use.',
  'I felt very confident using ClassOS.',
  'I needed to learn a lot of things before I could get going with ClassOS.',
];
const SCALE = ['Strongly disagree', 'Disagree', 'Neutral', 'Agree', 'Strongly agree'];

export default function SurveyPage() {
  const [answers, setAnswers] = useState(Array(10).fill(null));
  const [comment, setComment] = useState('');
  const [mine, setMine] = useState(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => { api.get('/feedback/sus').then(setMine).catch((e) => setError(e.message)); }, []);

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      setMine(await api.post('/feedback/sus', { answers, comment }));
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  const complete = answers.every((a) => a !== null);

  return (
    <div className="max-w-3xl space-y-6">
      <div>
        <h1 className="text-3xl font-bold">Usability survey</h1>
        <p className="text-muted">
          Ten statements, about two minutes. Answer with your first reaction. Your answers help the team improve ClassOS
          and are reported only as averages.
        </p>
      </div>

      {mine?.answered && (
        <p role="status" className="border-l-4 border-ok bg-white px-3 py-2">
          Thank you, your response is recorded. You can answer again below to replace it.
        </p>
      )}

      <form onSubmit={submit} className="space-y-4">
        <ol className="space-y-4">
          {STATEMENTS.map((text, i) => (
            <li key={i} className="rounded-md border border-line bg-white p-4">
              <fieldset>
                <legend className="font-bold">{i + 1}. {text}</legend>
                <div className="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-5">
                  {SCALE.map((label, k) => (
                    <label key={label}
                           className={`flex cursor-pointer items-center gap-2 rounded border px-2 py-1.5 text-sm ${answers[i] === k + 1 ? 'border-board bg-paper font-bold' : 'border-line'}`}>
                      <input type="radio" name={`q${i}`} value={k + 1} checked={answers[i] === k + 1}
                             onChange={() => setAnswers((a) => a.map((v, j) => (j === i ? k + 1 : v)))} />
                      {label}
                    </label>
                  ))}
                </div>
              </fieldset>
            </li>
          ))}
        </ol>
        <div>
          <label htmlFor="sus-comment" className="font-bold">Anything else? (optional)</label>
          <textarea id="sus-comment" rows={3} maxLength={4000} value={comment} onChange={(e) => setComment(e.target.value)}
                    className="mt-1 w-full rounded border border-line bg-white px-3 py-2" />
        </div>
        {error && <p role="alert" className="text-danger">{error}</p>}
        <button type="submit" disabled={busy || !complete}
                className="rounded bg-board px-4 py-2 font-bold text-white hover:bg-board-deep disabled:opacity-50">
          {complete ? 'Submit survey' : `Answer all 10 (${answers.filter((a) => a !== null).length} done)`}
        </button>
      </form>
    </div>
  );
}
