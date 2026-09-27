import { useState, useCallback } from 'react';
import {
  BrowserRouter,
  Routes,
  Route,
  NavLink,
  useNavigate,
  useLocation,
} from 'react-router-dom';
import { LockScreen } from './components/LockScreen';
import { Dashboard } from './pages/Dashboard';
import { Transactions } from './pages/Transactions';
import { Budget } from './pages/Budget';
import { Accounts } from './pages/Accounts';
import { clearToken, syncGmail, LockedError } from './lib/api';

// ── Nav icon SVGs ──────────────────────────────────────────────

const IconDashboard = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
    <rect x="3" y="3" width="7" height="7" rx="1.5" />
    <rect x="14" y="3" width="7" height="7" rx="1.5" />
    <rect x="3" y="14" width="7" height="7" rx="1.5" />
    <rect x="14" y="14" width="7" height="7" rx="1.5" />
  </svg>
);

const IconTransactions = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
    <path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2" />
    <rect x="9" y="3" width="6" height="4" rx="1" />
    <line x1="9" y1="12" x2="15" y2="12" />
    <line x1="9" y1="16" x2="13" y2="16" />
  </svg>
);

const IconBudget = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
    <circle cx="12" cy="12" r="9" />
    <path d="M12 7v5l3 3" />
    <path d="M8 12h1M15 12h1M12 8v1M12 15v1" />
  </svg>
);

const IconAccounts = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
    <rect x="2" y="5" width="20" height="14" rx="2" />
    <line x1="2" y1="10" x2="22" y2="10" />
  </svg>
);

const IconMail = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
    <path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z" />
    <polyline points="22,6 12,13 2,6" />
  </svg>
);

const IconLock = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
    <rect x="3" y="11" width="18" height="11" rx="2" />
    <path d="M7 11V7a5 5 0 0 1 10 0v4" />
  </svg>
);

// ── Sidebar ────────────────────────────────────────────────────

interface SidebarProps {
  onLock: () => void;
  onSyncGmail: () => void;
  syncing: boolean;
}

function Sidebar({ onLock, onSyncGmail, syncing }: SidebarProps) {
  const navItems = [
    { to: '/',              label: 'Dashboard',    icon: <IconDashboard /> },
    { to: '/transactions',  label: 'Transactions', icon: <IconTransactions /> },
    { to: '/budget',        label: 'Budget',       icon: <IconBudget /> },
    { to: '/accounts',      label: 'Accounts',     icon: <IconAccounts /> },
  ];

  return (
    <>
      <aside className="sidebar">
        {/* Logo */}
        <div className="sidebar__logo">
          <span className="sidebar__logo-text gradient-text">LN</span>
          <span className="sidebar__logo-name">Ledger Noir</span>
        </div>

        {/* Nav */}
        <nav className="sidebar__nav" aria-label="Main navigation">
          {navItems.map(({ to, label, icon }) => (
            <NavLink
              key={to}
              to={to}
              end={to === '/'}
              className={({ isActive }) =>
                `sidebar__link${isActive ? ' sidebar__link--active' : ''}`
              }
            >
              <span className="sidebar__link-icon">{icon}</span>
              <span className="sidebar__link-label">{label}</span>
              <span className="sidebar__link-indicator" aria-hidden />
            </NavLink>
          ))}
        </nav>

        {/* Bottom actions */}
        <div className="sidebar__actions">
          <button
            className="sidebar__action-btn"
            onClick={onSyncGmail}
            disabled={syncing}
            title="Sync Gmail"
          >
            <span className="sidebar__action-icon">
              {syncing ? (
                <span className="sidebar__spinner" />
              ) : (
                <IconMail />
              )}
            </span>
            <span>{syncing ? 'Syncing…' : 'Sync Gmail'}</span>
          </button>
          <button
            className="sidebar__action-btn sidebar__action-btn--lock"
            onClick={onLock}
            title="Lock app"
          >
            <span className="sidebar__action-icon"><IconLock /></span>
            <span>Lock</span>
          </button>
        </div>
      </aside>

      <style>{`
        .sidebar {
          width: 240px;
          flex-shrink: 0;
          height: 100vh;
          position: sticky;
          top: 0;
          display: flex;
          flex-direction: column;
          background: var(--color-surface, #11161D);
          border-right: 1px solid rgba(255, 255, 255, 0.06);
          padding: 28px 16px;
          gap: 8px;
        }

        .sidebar__logo {
          display: flex;
          align-items: center;
          gap: 10px;
          padding: 0 8px 24px;
          border-bottom: 1px solid rgba(255, 255, 255, 0.06);
          margin-bottom: 8px;
        }
        .sidebar__logo-text {
          font-family: var(--font-display, 'Clash Display', serif);
          font-size: 1.5rem;
          font-weight: 700;
          letter-spacing: -0.04em;
        }
        .sidebar__logo-name {
          font-family: var(--font-body, 'Satoshi', sans-serif);
          font-size: 13px;
          font-weight: 700;
          color: var(--color-text-muted, #8A97A6);
          letter-spacing: 0.01em;
        }

        .sidebar__nav {
          display: flex;
          flex-direction: column;
          gap: 2px;
          flex: 1;
        }

        .sidebar__link {
          display: flex;
          align-items: center;
          gap: 10px;
          padding: 10px 12px;
          border-radius: 10px;
          color: var(--color-text-muted, #8A97A6);
          font-size: 14px;
          font-weight: 500;
          text-decoration: none;
          position: relative;
          transition: color 200ms ease, background 200ms ease;
          outline: none;
        }
        .sidebar__link:hover {
          background: rgba(255, 255, 255, 0.04);
          color: var(--color-text-primary, #E8EEF2);
        }
        .sidebar__link:focus-visible {
          outline: 2px solid rgba(45, 212, 191, 0.5);
          outline-offset: 1px;
        }

        .sidebar__link--active {
          background: rgba(45, 212, 191, 0.08);
          color: var(--color-teal, #2DD4BF);
          font-weight: 700;
        }
        .sidebar__link--active .sidebar__link-indicator {
          position: absolute;
          right: 0;
          top: 50%;
          transform: translateY(-50%);
          width: 3px;
          height: 24px;
          background: linear-gradient(135deg, #2DD4BF, #34D399);
          border-radius: 2px 0 0 2px;
        }

        .sidebar__link-icon {
          display: flex;
          align-items: center;
          flex-shrink: 0;
        }

        .sidebar__actions {
          display: flex;
          flex-direction: column;
          gap: 4px;
          padding-top: 16px;
          border-top: 1px solid rgba(255, 255, 255, 0.06);
        }

        .sidebar__action-btn {
          display: flex;
          align-items: center;
          gap: 10px;
          padding: 9px 12px;
          border-radius: 10px;
          background: none;
          border: none;
          color: var(--color-text-faint, #5A6675);
          font-size: 13px;
          font-weight: 500;
          font-family: var(--font-body, 'Satoshi', sans-serif);
          cursor: pointer;
          width: 100%;
          text-align: left;
          transition: color 200ms ease, background 200ms ease;
        }
        .sidebar__action-btn:hover:not(:disabled) {
          background: rgba(255, 255, 255, 0.04);
          color: var(--color-text-primary, #E8EEF2);
        }
        .sidebar__action-btn:disabled {
          opacity: 0.5;
          cursor: wait;
        }
        .sidebar__action-btn--lock:hover {
          color: var(--color-debit, #FB7185) !important;
        }
        .sidebar__action-icon {
          display: flex;
          align-items: center;
          width: 18px;
          justify-content: center;
        }
        .sidebar__spinner {
          width: 14px;
          height: 14px;
          border: 2px solid currentColor;
          border-top-color: transparent;
          border-radius: 50%;
          animation: ln-spin 0.6s linear infinite;
          display: inline-block;
        }
        @keyframes ln-spin {
          to { transform: rotate(360deg); }
        }
      `}</style>
    </>
  );
}

// ── App shell & routing ────────────────────────────────────────

function AppShell() {
  const [syncing, setSyncing] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();

  const handleLock = useCallback(() => {
    clearToken();
    navigate('/?lock', { replace: true });
    window.location.reload();
  }, [navigate]);

  const handleSyncGmail = useCallback(async () => {
    setSyncing(true);
    try {
      const res = await syncGmail();
      console.info(`Gmail sync: ${res.ingested} transactions imported`);
    } catch (err) {
      if (err instanceof LockedError) {
        handleLock();
      } else {
        console.error('Sync failed', err);
      }
    } finally {
      setSyncing(false);
    }
  }, [handleLock]);

  // suppress unused location warning
  void location;

  return (
    <>
      <div className="app-shell">
        <Sidebar
          onLock={handleLock}
          onSyncGmail={handleSyncGmail}
          syncing={syncing}
        />
        <main className="app-main">
          <Routes>
            <Route path="/" element={<Dashboard />} />
            <Route path="/transactions" element={<Transactions />} />
            <Route path="/budget" element={<Budget />} />
            <Route path="/accounts" element={<Accounts />} />
            <Route
              path="*"
              element={
                <div style={{ textAlign: 'center', padding: '80px 24px', color: 'var(--color-text-faint)', fontFamily: 'var(--font-display)', fontSize: '1.5rem' }}>
                  404 — Page not found
                </div>
              }
            />
          </Routes>
        </main>
      </div>

      <style>{`
        .app-shell {
          display: flex;
          min-height: 100vh;
        }
        .app-main {
          flex: 1;
          min-width: 0;
          padding: 32px 40px;
        }
        @media (max-width: 768px) {
          .app-shell {
            flex-direction: column;
          }
          .app-main {
            padding: 20px 16px;
          }
        }
      `}</style>
    </>
  );
}

// ── Root: lock gate ────────────────────────────────────────────

export default function App() {
  // Show lock screen if ?lock is in URL OR if a prior API call threw LockedError
  const searchParams = new URLSearchParams(window.location.search);
  const hasLockParam = searchParams.has('lock');

  const [locked, setLocked] = useState(hasLockParam);

  const handleUnlock = useCallback(() => {
    setLocked(false);
    // Remove ?lock from URL without reload
    const url = new URL(window.location.href);
    url.searchParams.delete('lock');
    window.history.replaceState({}, '', url.toString());
  }, []);

  if (locked) {
    return <LockScreen onUnlock={handleUnlock} />;
  }

  return (
    <BrowserRouter>
      <AppShell />
    </BrowserRouter>
  );
}
