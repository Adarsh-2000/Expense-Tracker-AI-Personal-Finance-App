import { useEffect, useState } from 'react';
import { motion } from 'motion/react';
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  Tooltip as ReTooltip,
  ResponsiveContainer,
  CartesianGrid,
} from 'recharts';
import { Card } from '../components/ui/Card';
import { Stat } from '../components/ui/Stat';
import {
  getAccounts,
  getNetWorth,
  getAccountBalanceHistory,
  type Account,
  type BalancePoint,
  LockedError,
} from '../lib/api';
import { inr } from '../lib/format';

// ── Skeleton ───────────────────────────────────────────────────

function Skeleton({ h = 16, r = 8, w = '100%' }: { h?: number; r?: number; w?: string }) {
  return (
    <div style={{
      width: w, height: h, borderRadius: r,
      background: 'rgba(255,255,255,0.07)',
      animation: 'acc-pulse 1.4s ease-in-out infinite',
    }} />
  );
}

// ── Account type badge ─────────────────────────────────────────

function AccountTypeBadge({ type }: { type: string }) {
  const colours: Record<string, { bg: string; text: string }> = {
    savings: { bg: 'rgba(45,212,191,0.12)', text: '#2DD4BF' },
    current: { bg: 'rgba(52,211,153,0.12)', text: '#34D399' },
    credit: { bg: 'rgba(251,113,133,0.12)', text: '#FB7185' },
    wallet: { bg: 'rgba(251,191,36,0.12)', text: '#FBBF24' },
  };
  const c = colours[type.toLowerCase()] ?? { bg: 'rgba(255,255,255,0.07)', text: 'var(--color-text-muted)' };
  return (
    <span style={{
      fontSize: 10, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase',
      background: c.bg, color: c.text, borderRadius: 999, padding: '3px 10px',
    }}>{type}</span>
  );
}

// ── Account card ───────────────────────────────────────────────

interface AccountCardProps {
  account: Account;
  selected: boolean;
  onClick: () => void;
}

function AccountCard({ account, selected, onClick }: AccountCardProps) {
  return (
    <Card
      onClick={onClick}
      style={{
        borderColor: selected ? 'rgba(45,212,191,0.4)' : undefined,
        boxShadow: selected
          ? '0 0 0 1px rgba(45,212,191,0.3), 0 4px 24px rgba(0,0,0,0.4)'
          : undefined,
        background: selected ? 'rgba(45,212,191,0.05)' : undefined,
        cursor: 'pointer',
        transition: 'all 200ms',
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 16 }}>
        <div>
          <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--color-text-primary)' }}>{account.bank}</div>
          <div style={{ fontSize: 12, color: 'var(--color-text-faint)', marginTop: 3 }}>••••&nbsp;{account.last4}</div>
        </div>
        <AccountTypeBadge type={account.type} />
      </div>
      <div>
        <div style={{ fontSize: 10, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--color-text-faint)', marginBottom: 4 }}>Balance</div>
        <div className="tnum" style={{
          fontFamily: 'var(--font-display)', fontSize: '1.6rem', fontWeight: 700, letterSpacing: '-0.03em',
          color: account.current_balance >= 0 ? 'var(--color-text-primary)' : 'var(--color-debit)',
        }}>
          {inr(account.current_balance)}
        </div>
      </div>
    </Card>
  );
}

// ── Custom tooltip for chart ───────────────────────────────────

interface ChartTooltipProps {
  active?: boolean;
  payload?: { value: number }[];
  label?: string;
}

function ChartTooltip({ active, payload, label }: ChartTooltipProps) {
  if (!active || !payload || !payload.length) return null;
  return (
    <div style={{
      background: 'rgba(22,29,39,0.97)', border: '1px solid rgba(45,212,191,0.3)',
      borderRadius: 10, padding: '10px 14px', fontSize: 12,
    }}>
      <div style={{ color: 'var(--color-text-faint)', marginBottom: 4 }}>{label}</div>
      <div className="tnum" style={{ color: 'var(--color-teal)', fontWeight: 700, fontSize: 14 }}>
        {inr(payload[0].value)}
      </div>
    </div>
  );
}

// ── Accounts page ─────────────────────────────────────────────

export function Accounts() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [netWorth, setNetWorth] = useState(0);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [history, setHistory] = useState<BalancePoint[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    async function load() {
      setLoading(true);
      setError('');
      try {
        const [a, n] = await Promise.allSettled([getAccounts(), getNetWorth()]);
        if (cancelled) return;
        if (a.status === 'fulfilled') {
          setAccounts(a.value);
          if (a.value.length > 0) setSelectedId(a.value[0].id);
        }
        if (n.status === 'fulfilled') setNetWorth(n.value.net_worth);
      } catch (e) {
        if (!cancelled) setError(e instanceof LockedError ? 'Session locked.' : 'Could not load accounts.');
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    void load();
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (!selectedId) return;
    let cancelled = false;
    async function loadHistory() {
      setHistoryLoading(true);
      try {
        const data = await getAccountBalanceHistory(selectedId!);
        if (!cancelled) setHistory(data);
      } catch (_) {
        if (!cancelled) setHistory([]);
      } finally {
        if (!cancelled) setHistoryLoading(false);
      }
    }
    void loadHistory();
    return () => { cancelled = true; };
  }, [selectedId]);

  const selectedAccount = accounts.find((a) => a.id === selectedId);

  // Format chart X axis dates
  const chartData = history.map((p) => ({
    date: new Date(p.captured_at).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' }),
    balance: p.balance,
    rawDate: p.captured_at,
  }));

  const stagger = (i: number) => ({
    initial: { opacity: 0, y: 16 },
    animate: { opacity: 1, y: 0 },
    transition: { duration: 0.38, delay: i * 0.07, ease: 'easeOut' as const },
  });

  // Net worth breakdown
  const totalAssets = accounts.filter((a) => a.current_balance > 0).reduce((s, a) => s + a.current_balance, 0);
  const totalLiabilities = accounts.filter((a) => a.current_balance < 0).reduce((s, a) => s + a.current_balance, 0);

  return (
    <>
      <style>{`
        @keyframes acc-pulse {
          0%, 100% { opacity: 0.5; }
          50% { opacity: 1; }
        }
        .acc-grid {
          display: grid;
          grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
          gap: 16px;
          margin-bottom: 24px;
        }
        @media (max-width: 768px) {
          .acc-grid { grid-template-columns: 1fr; }
        }
      `}</style>

      {/* Header */}
      <div style={{ marginBottom: 28 }}>
        <h2 style={{ margin: 0, fontFamily: 'var(--font-display)', fontSize: '1.5rem' }}>Accounts</h2>
        <p style={{ color: 'var(--color-text-faint)', fontSize: 13, marginTop: 4 }}>
          All connected bank accounts and balance history.
        </p>
      </div>

      {error && <div style={{ color: 'var(--color-warning)', fontSize: 13, marginBottom: 14 }}>{error}</div>}

      {/* Net worth hero */}
      <motion.div {...stagger(0)} style={{ marginBottom: 24 }}>
        <Card style={{
          background: 'linear-gradient(135deg, rgba(45,212,191,0.06), rgba(52,211,153,0.03))',
          borderColor: 'rgba(45,212,191,0.2)',
        }}>
          {loading ? (
            <div style={{ display: 'flex', gap: 40 }}>
              <div style={{ flex: 1 }}><Skeleton h={11} w="50%" /><div style={{ marginTop: 10 }}><Skeleton h={44} /></div></div>
              <div style={{ flex: 1 }}><Skeleton h={11} w="50%" /><div style={{ marginTop: 10 }}><Skeleton h={44} /></div></div>
              <div style={{ flex: 1 }}><Skeleton h={11} w="50%" /><div style={{ marginTop: 10 }}><Skeleton h={44} /></div></div>
            </div>
          ) : (
            <div style={{ display: 'flex', gap: 40, flexWrap: 'wrap' }}>
              <Stat
                label="Net worth"
                value={inr(netWorth)}
                tone={netWorth >= 0 ? 'neutral' : 'danger'}
                sub="Total across all accounts"
              />
              <Stat
                label="Total assets"
                value={inr(totalAssets)}
                tone="credit"
              />
              <Stat
                label="Total liabilities"
                value={inr(Math.abs(totalLiabilities))}
                tone={totalLiabilities < 0 ? 'debit' : 'neutral'}
              />
            </div>
          )}
        </Card>
      </motion.div>

      {/* Account cards */}
      {loading ? (
        <div className="acc-grid">
          {[1, 2, 3].map((i) => (
            <div key={i} style={{ borderRadius: 20, overflow: 'hidden' }}>
              <Skeleton h={140} r={20} />
            </div>
          ))}
        </div>
      ) : accounts.length === 0 ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: 'var(--color-text-faint)' }}>
          No accounts connected yet. Sync Gmail to get started.
        </div>
      ) : (
        <div className="acc-grid">
          {accounts.map((acc, i) => (
            <motion.div key={acc.id} {...stagger(i + 1)}>
              <AccountCard
                account={acc}
                selected={selectedId === acc.id}
                onClick={() => setSelectedId(acc.id)}
              />
            </motion.div>
          ))}
        </div>
      )}

      {/* Balance history chart */}
      {selectedAccount && (
        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.4 }}
        >
          <Card>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
              <div>
                <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--color-text-faint)' }}>
                  Balance history
                </div>
                <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--color-text-primary)', marginTop: 3 }}>
                  {selectedAccount.bank} ••••&nbsp;{selectedAccount.last4}
                </div>
              </div>
              <div className="tnum" style={{
                fontFamily: 'var(--font-display)', fontSize: '1.4rem', fontWeight: 700,
                color: selectedAccount.current_balance >= 0 ? 'var(--color-teal)' : 'var(--color-debit)',
              }}>
                {inr(selectedAccount.current_balance)}
              </div>
            </div>

            {historyLoading ? (
              <Skeleton h={220} r={12} />
            ) : chartData.length === 0 ? (
              <div style={{ textAlign: 'center', padding: '48px 0', color: 'var(--color-text-faint)', fontSize: 13 }}>
                No balance history available for this account.
              </div>
            ) : (
              <ResponsiveContainer width="100%" height={240}>
                <LineChart data={chartData} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
                  <CartesianGrid stroke="rgba(255,255,255,0.04)" strokeDasharray="4 4" vertical={false} />
                  <XAxis
                    dataKey="date"
                    tick={{ fill: 'var(--color-text-faint)', fontSize: 11 }}
                    axisLine={false}
                    tickLine={false}
                    interval="preserveStartEnd"
                  />
                  <YAxis
                    tick={{ fill: 'var(--color-text-faint)', fontSize: 11 }}
                    axisLine={false}
                    tickLine={false}
                    tickFormatter={(v: number) => `₹${(v / 1000).toFixed(0)}k`}
                    width={56}
                  />
                  <ReTooltip content={<ChartTooltip />} />
                  <Line
                    type="monotone"
                    dataKey="balance"
                    stroke="url(#lineGrad)"
                    strokeWidth={2.5}
                    dot={false}
                    activeDot={{ r: 5, fill: '#2DD4BF', strokeWidth: 0 }}
                  />
                  <defs>
                    <linearGradient id="lineGrad" x1="0" y1="0" x2="1" y2="0">
                      <stop offset="0%" stopColor="#2DD4BF" />
                      <stop offset="100%" stopColor="#34D399" />
                    </linearGradient>
                  </defs>
                </LineChart>
              </ResponsiveContainer>
            )}
          </Card>
        </motion.div>
      )}
    </>
  );
}
