import { useState } from 'react';
import { api } from '../../api/client.js';
import { useLoad } from './common.jsx';
import { TermsTab } from './TermsTab.jsx';
import { RoomsTab } from './RoomsTab.jsx';
import { CoursesTab } from './CoursesTab.jsx';
import { SectionsTab } from './SectionsTab.jsx';
import { OfferingsTab } from './OfferingsTab.jsx';
import { AvailabilityTab } from './AvailabilityTab.jsx';

// In the order an admin sets up a new term.
const TABS = [
  { id: 'terms', label: 'Terms and periods', needsTerm: false },
  { id: 'rooms', label: 'Rooms', needsTerm: false },
  { id: 'courses', label: 'Courses', needsTerm: false },
  { id: 'sections', label: 'Sections and students', needsTerm: true },
  { id: 'offerings', label: 'Who teaches what', needsTerm: true },
  { id: 'availability', label: 'Teacher availability', needsTerm: true },
];

export default function SetupPage() {
  const [tab, setTab] = useState('terms');
  const [termId, setTermId] = useState('');
  const terms = useLoad(async () => {
    const list = await api.get('/admin/setup/terms');
    setTermId((current) => current || String((list.find((t) => t.current) || list[0] || {}).id || ''));
    return list;
  }, []);

  const active = TABS.find((t) => t.id === tab);
  const term = terms.data?.find((t) => String(t.id) === termId);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-3xl font-bold">Setup</h1>
        <p className="text-muted">Work through the tabs in order when preparing a new term.</p>
      </div>

      <div role="tablist" aria-label="Setup sections" className="flex flex-wrap gap-1 border-b border-line">
        {TABS.map((t) => (
          <button key={t.id} type="button" role="tab" id={`tab-${t.id}`} aria-selected={tab === t.id}
                  aria-controls="setup-panel" onClick={() => setTab(t.id)}
                  className={`-mb-px border-b-4 px-3 py-2 ${tab === t.id ? 'border-highlight font-bold' : 'border-transparent text-muted hover:text-ink'}`}>
            {t.label}
          </button>
        ))}
      </div>

      <div role="tabpanel" id="setup-panel" aria-labelledby={`tab-${tab}`} className="space-y-6">
        {terms.error && <p role="alert" className="text-danger">{terms.error}</p>}

        {active.needsTerm && terms.data && (
          <div>
            <label htmlFor="term" className="font-bold">Term</label>
            <select id="term" value={termId} onChange={(e) => setTermId(e.target.value)}
                    className="mt-1 block min-w-56 rounded border border-line bg-white px-3 py-2">
              {terms.data.map((t) => <option key={t.id} value={t.id}>{t.name}{t.current ? ' (current)' : ''}</option>)}
            </select>
          </div>
        )}
        {active.needsTerm && terms.data && !term && <p className="text-muted">Create a term first, on the first tab.</p>}

        {tab === 'terms' && terms.data && <TermsTab terms={terms.data} reload={terms.reload} />}
        {tab === 'rooms' && <RoomsTab />}
        {tab === 'courses' && <CoursesTab />}
        {tab === 'sections' && term && <SectionsTab key={term.id} term={term} />}
        {tab === 'offerings' && term && <OfferingsTab key={term.id} term={term} />}
        {tab === 'availability' && term && <AvailabilityTab key={term.id} term={term} />}
      </div>
    </div>
  );
}
