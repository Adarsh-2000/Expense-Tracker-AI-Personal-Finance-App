import { useEffect, useState, useRef } from 'react';
import { motion } from 'motion/react';
import {
  PieChart,
  Pie,
  Cell,
  Tooltip as ReTooltip,
  ResponsiveContainer,
} from 'recharts';
import { Card } from '../components/ui/Card';
import { Stat } from '../components/ui/Stat';
import {
  getTransactions,
  getBudget,
  getSummary,
  getRecurring,
  getCategories,
  type Transaction,
  type CategoryTotal,
  type BudgetCategory,
  type Category,
  type RecurringItem,
  LockedError,
} from '../lib/api';
import { inr } from '../lib/format';

// ── Helpers ────────────────────────────────────────────────────

function currentMonth(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

function lastMonth(): string {
  const d = new Date();
  d.setMonth(d.getMonth() - 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

function monthTotalDebit(txns: Transaction[], monthPrefix: string): number {
  return txns
    .filter(
      (t) => t.direction === 'debit' && t.occurred_at.startsWith(monthPrefix)
    )
    .reduce((s, t) => s + t.amount, 0);
}

// ── Count-up animation ─────────────────────────────────────────

function useCountUp(target: number, duration = 1200): number {
  const [display, setDisplay] = useState(0);
  const rafRef = useRef<number | null>(null);

  useEffect(() => {
    if (target === 0) { setDisplay(0); return; }
    const start = performance.now();
    const step = (now: number) => {
      const elapsed = now - start;
      const progress = Math.min(elapsed / duration, 1);
      // ease-out cubic
      const eased = 1 - Math.pow(1 - progress, 3);
      setDisplay(Math.round(target * eased));
      if (progress < 1) {
        rafRef.current = requestAnimationFrame(step);
      }
    };
    rafRef.current = requestAnimationFrame(step);
    return () => { if (rafRef.current) cancelAnimationFrame(rafRef.current); };
  }, [target, duration]);

  return display;
}

// ── Skeleton ───────────────────────────────────────────────────

function Skeleton({ w = '100%', h = 16, r = 8 }: { w?: string | number; h?: number; r?: number }) {
  return (
    <div
      style={{
        width: w,
        height: h,
        borderRadius: r,
        background: 'rgba(255,255,255,0.07)',
        animation: 'dash-pulse 1.4s ease-in-out infinite',
      }}
    />
  );
}

// ── Donut colours ──────────────────────────────────────────────

const DONUT_COLORS = [
  '#2DD4BF', '#34D399', '#FBBF24', '#FB7185',
  '#818CF8', '#F472B6', '#A3E635', '#38BDF8',
];

// ── Daily heatmap ──────────────────────────────────────────────

interface HeatmapProps {
  txns: Transaction[];
  month: string; // YYYY-MM
}

function DailyHeatmap({ txns, month }: HeatmapProps) {
  const [tooltip, setTooltip] = useState<{ day: number; amount: number; x: number; y: number } | null>(null);

  const [year, mon] = month.split('-').map(Number);
  const daysInMonth = new Date(year, mon, 0).getDate();
  const firstDow = new Date(year, mon - 1, 1).getDay(); // 0=Sun

  // Daily totals
  const dailyMap: Record<number, number> = {};
  txns.forEach((t) => {
    if (!t.occurred_at.startsWith(month) || t.direction !== 'debit') return;
    const day = parseInt(t.occurred_at.slice(8, 10), 10);
    dailyMap[day] = (dailyMap[day] ?? 0) + t.amount;
  });

  const maxAmt = Math.max(...Object.values(dailyMap), 1);

  const days: (number | null)[] = [
    ...Array(firstDow).fill(null),
    ...Array.from({ length: daysInMonth }, (_, i) => i + 1),
  ];

  const DOW_LABELS = ['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa'];

  return (
    <div style={{ position: 'relative' }}>
      {/* DOW headers */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 4, marginBottom: 4 }}>
        {DOW_LABELS.map((d) => (
          <div key={d} style={{ textAlign: 'center', fontSize: 10, color: 'var(--color-text-faint)', fontWeight: 600, letterSpacing: '0.04em' }}>
            {d}
          </div>
        ))}
      </div>
      {/* Day cells */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 4 }}>
        {days.map((day, idx) => {
          if (!day) {
            return <div key={`empty-${idx}`} />;
          }
          const amt = dailyMap[day] ?? 0;
          const intensity = amt > 0 ? 0.15 + 0.85 * (amt / maxAmt) : 0;
          return (
            <div
              key={day}
              onMouseEnter={(e) => {
                const rect = (e.target as HTMLElement).getBoundingClientRect();
                setTooltip({ day, amount: amt, x: rect.left, y: rect.top });
              }}
              onMouseLeave={() => setTooltip(null)}
              style={{
                aspectRatio: '1',
                borderRadius: 4,
                background: amt > 0
                  ? `rgba(45, 212, 191, ${intensity})`
                  : 'rgba(255,255,255,0.04)',
                border: '1px solid rgba(255,255,255,0.05)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 9,
                color: intensity > 0.5 ? 'rgba(10,13,18,0.9)' : 'var(--color-text-faint)',
                fontWeight: 600,
                cursor: amt > 0 ? 'default' : undefined,
                transition: 'opacity 150ms',
              }}
            >
              {day}
            </div>
          );
        })}
      </div>
      {/* Tooltip */}
      {tooltip && (
        <div
          style={{
            position: 'fixed',
            left: tooltip.x + 20,
            top: tooltip.y - 10,
            background: 'rgba(22,29,39,0.97)',
            border: '1px solid rgba(45,212,191,0.3)',
            borderRadius: 8,
            padding: '6px 12px',
            fontSize: 12,
            color: 'var(--color-text-primary)',
            pointerEvents: 'none',
            zIndex: 9999,
            whiteSpace: 'nowrap',
          }}
        >
          <span style={{ color: 'var(--color-text-faint)' }}>
            {new Date(year, mon - 1, tooltip.day).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })}
          </span>
          {' — '}
          <span className="tnum" style={{ color: 'var(--color-debit)', fontWeight: 700 }}>
            {tooltip.amount > 0 ? inr(tooltip.amount) : 'No spend'}
          </span>
        </div>
      )}
    </div>
  );
}

// ── Budget bar ─────────────────────────────────────────────────

function BudgetBar({ cat, categoryName }: { cat: BudgetCategory; categoryName: string }) {
  const pct = cat.assigned > 0 ? Math.min((cat.spent / cat.assigned) * 100, 100) : 0;
  const status = cat.status;
  const barColor =
    status === 'exceeded' ? 'var(--color-danger)'
    : status === 'warning' ? 'var(--color-warning)'
    : 'var(--color-credit)';

  return (
    <div style={{ marginBottom: 10 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
        <span style={{ fontSize: 13, color: 'var(--color-text-primary)', fontWeight: 600 }}>
          {categoryName}
        </span>
        <span className="tnum" style={{ fontSize: 12, color: 'var(--color-text-muted)' }}>
          {inr(cat.spent)} / {inr(cat.assigned)}
        </span>
      </div>
      <div style={{ height: 5, background: 'rgba(255,255,255,0.07)', borderRadius: 99, overflow: 'hidden' }}>
        <motion.div
          initial={{ width: 0 }}
          animate={{ width: `${pct}%` }}
          transition={{ duration: 0.7, ease: 'easeOut' }}
          style={{ height: '100%', background: barColor, borderRadius: 99 }}
        />
      </div>
    </div>
  );
}

// ── Dashboard ─────────────────────────────────────────────────

export function Dashboard() {
  const [txns, setTxns] = useState<Transaction[]>([]);
  const [budget, setBudget] = useState<{ ready_to_assign: number; categories: BudgetCategory[] } | null>(null);
  const [summary, setSummary] = useState<CategoryTotal[]>([]);
  const [recurring, setRecurring] = useState<RecurringItem[]>([]);
  const [categoryList, setCategoryList] = useState<Category[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const month = currentMonth();
  const prevMonth = lastMonth();

  useEffect(() => {
    let cancelled = false;
    async function load() {
      setLoading(true);
      setError('');
      try {
        const [t, b, s, r, cats] = await Promise.allSettled([
          getTransactions(),
          getBudget(month),
          getSummary(),
          getRecurring(),
          getCategories(),
        ]);
        if (cancelled) return;
        if (t.status === 'fulfilled') setTxns(t.value);
        if (b.status === 'fulfilled') setBudget({ ready_to_assign: b.value.ready_to_assign, categories: b.value.categories });
        if (s.status === 'fulfilled') setSummary(s.value);
        if (r.status === 'fulfilled') setRecurring(r.value);
        if (cats.status === 'fulfilled') setCategoryList(cats.value);
      } catch (e) {
        if (!cancelled) setError(e instanceof LockedError ? 'Session locked.' : 'Could not load data.');
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    void load();
    return () => { cancelled = true; };
  }, [month]);

  const thisMonthSpend = monthTotalDebit(txns, month);
  const lastMonthSpend = monthTotalDebit(txns, prevMonth);
  const deltaPercent =
    lastMonthSpend > 0
      ? Math.round(((thisMonthSpend - lastMonthSpend) / lastMonthSpend) * 100)
      : 0;
  const deltaUp = deltaPercent > 0;

  const countedSpend = useCountUp(loading ? 0 : thisMonthSpend);

  const recent = [...txns]
    .sort((a, b) => b.occurred_at.localeCompare(a.occurred_at))
    .slice(0, 8);

  const topCategory = summary.length > 0 ? summary.reduce((a, b) => (a.total > b.total ? a : b)) : null;
  const totalSummarySpend = summary.reduce((s, c) => s + c.total, 0);

  // Build id→name map from categories
  const categoryNameMap: Record<number, string> = Object.fromEntries(categoryList.map((c) => [c.id, c.name]));

  const stagger = (i: number) => ({ initial: { opacity: 0, y: 18 }, animate: { opacity: 1, y: 0 }, transition: { duration: 0.4, delay: i * 0.07, ease: 'easeOut' as const } });

  return (
    <>
      <style>{`
        @keyframes dash-pulse {
          0%, 100% { opacity: 0.5; }
          50% { opacity: 1; }
        }
        .db-grid {
          display: grid;
          gap: 20px;
        }
        .db-top-row {
          display: grid;
          grid-template-columns: 2fr 1fr;
          gap: 20px;
        }
        .db-mid-row {
          display: grid;
          grid-template-columns: 1fr 1fr;
          gap: 20px;
        }
        .db-bottom-row {
          display: grid;
          grid-template-columns: 1fr 1fr 1fr;
          gap: 20px;
        }
        .db-section-label {
          font-size: 11px;
          font-weight: 700;
          letter-spacing: 0.09em;
          text-transform: uppercase;
          color: var(--color-text-faint);
          margin-bottom: 14px;
        }
        .db-hero-amount {
          font-family: var(--font-display);
          font-size: 3.5rem;
          font-weight: 700;
          letter-spacing: -0.04em;
          line-height: 1;
          background: var(--gradient-accent);
          -webkit-background-clip: text;
          -webkit-text-fill-color: transparent;
          background-clip: text;
          font-feature-settings: "tnum" 1;
          font-variant-numeric: tabular-nums;
        }
        .db-txn-row {
          display: flex;
          align-items: center;
          justify-content: space-between;
          padding: 9px 0;
          border-bottom: 1px solid rgba(255,255,255,0.05);
        }
        .db-txn-row:last-child { border-bottom: none; }
        .db-recur-row {
          display: flex;
          align-items: center;
          justify-content: space-between;
          padding: 8px 0;
          border-bottom: 1px solid rgba(255,255,255,0.05);
        }
        .db-recur-row:last-child { border-bottom: none; }
        .db-insight {
          background: linear-gradient(135deg, rgba(45,212,191,0.07), rgba(52,211,153,0.04));
          border: 1px solid rgba(45,212,191,0.15);
          border-radius: 12px;
          padding: 14px 18px;
          font-size: 13px;
          color: var(--color-text-muted);
          line-height: 1.55;
          margin-top: 20px;
        }
        .db-empty {
          color: var(--color-text-faint);
          font-size: 13px;
          text-align: center;
          padding: 20px 0;
        }
        @media (max-width: 900px) {
          .db-top-row, .db-mid-row, .db-bottom-row { grid-template-columns: 1fr; }
        }
      `}</style>

      <div className="db-grid">
        {error && (
          <div style={{ color: 'var(--color-warning)', fontSize: 13, padding: '8px 0' }}>{error}</div>
        )}

        {/* TOP ROW: Hero spend + Ready to assign */}
        <div className="db-top-row">
          {/* Hero */}
          <motion.div {...stagger(0)}>
            <Card style={{ background: 'rgba(45,212,191,0.04)', borderColor: 'rgba(45,212,191,0.15)' }}>
              <div className="db-section-label">Spent this month</div>
              {loading ? (
                <Skeleton h={56} r={10} />
              ) : (
                <div className="db-hero-amount">
                  {inr(countedSpend)}
                </div>
              )}
              {!loading && lastMonthSpend > 0 && (
                <div style={{ marginTop: 8, fontSize: 13, color: 'var(--color-text-muted)' }}>
                  vs last month{' '}
                  <span
                    className="tnum"
                    style={{ color: deltaUp ? 'var(--color-debit)' : 'var(--color-credit)', fontWeight: 700 }}
                  >
                    {deltaUp ? '↑' : '↓'} {Math.abs(deltaPercent)}%
                  </span>
                </div>
              )}
            </Card>
          </motion.div>

          {/* Ready to assign */}
          <motion.div {...stagger(1)}>
            <Card style={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', height: '100%' }}>
              {loading ? (
                <>
                  <Skeleton w="60%" h={11} />
                  <div style={{ marginTop: 10 }}><Skeleton h={40} w="80%" /></div>
                </>
              ) : (
                <Stat
                  label="Ready to assign"
                  value={inr(budget?.ready_to_assign ?? 0)}
                  tone={budget && budget.ready_to_assign >= 0 ? 'credit' : 'danger'}
                  sub="Available to budget"
                />
              )}
            </Card>
          </motion.div>
        </div>

        {/* MID ROW: Budget envelopes + Donut */}
        <div className="db-mid-row">
          {/* Budget envelopes */}
          <motion.div {...stagger(2)}>
            <Card>
              <div className="db-section-label">Budget envelopes</div>
              {loading ? (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                  {[1, 2, 3, 4].map((i) => <Skeleton key={i} h={32} />)}
                </div>
              ) : budget && budget.categories.length > 0 ? (
                budget.categories.slice(0, 6).map((cat) => (
                  <BudgetBar key={cat.category_id} cat={cat} categoryName={categoryNameMap[cat.category_id] ?? String(cat.category_id)} />
                ))
              ) : (
                <div className="db-empty">No budget data for this month.</div>
              )}
            </Card>
          </motion.div>

          {/* Category donut */}
          <motion.div {...stagger(3)}>
            <Card>
              <div className="db-section-label">Category breakdown</div>
              {loading ? (
                <Skeleton h={160} />
              ) : summary.length > 0 ? (
                <>
                  <ResponsiveContainer width="100%" height={170}>
                    <PieChart>
                      <Pie
                        data={summary}
                        cx="50%"
                        cy="50%"
                        innerRadius={50}
                        outerRadius={75}
                        paddingAngle={2}
                        dataKey="total"
                        nameKey="category"
                      >
                        {summary.map((_, idx) => (
                          <Cell key={idx} fill={DONUT_COLORS[idx % DONUT_COLORS.length]} />
                        ))}
                      </Pie>
                      <ReTooltip
                        formatter={(v) => [inr(Number(v)), '']}
                        contentStyle={{
                          background: 'rgba(22,29,39,0.97)',
                          border: '1px solid rgba(45,212,191,0.3)',
                          borderRadius: 8,
                          fontSize: 12,
                        }}
                      />
                    </PieChart>
                  </ResponsiveContainer>
                  <div style={{ marginTop: 8 }}>
                    {summary.slice(0, 5).map((c, i) => (
                      <div key={c.category} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '4px 0' }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                          <div style={{ width: 8, height: 8, borderRadius: 2, background: DONUT_COLORS[i % DONUT_COLORS.length], flexShrink: 0 }} />
                          <span style={{ fontSize: 12, color: 'var(--color-text-muted)' }}>{c.category}</span>
                        </div>
                        <span className="tnum" style={{ fontSize: 12, color: 'var(--color-text-primary)', fontWeight: 600 }}>{inr(c.total)}</span>
                      </div>
                    ))}
                  </div>
                </>
              ) : (
                <div className="db-empty">No category data yet.</div>
              )}
            </Card>
          </motion.div>
        </div>

        {/* Heatmap */}
        <motion.div {...stagger(4)}>
          <Card>
            <div className="db-section-label">Daily spend — {new Date().toLocaleDateString('en-IN', { month: 'long', year: 'numeric' })}</div>
            {loading ? <Skeleton h={120} /> : <DailyHeatmap txns={txns} month={month} />}
          </Card>
        </motion.div>

        {/* BOTTOM ROW: Recent txns + Recurring + Insight */}
        <div className="db-bottom-row">
          {/* Recent transactions */}
          <motion.div {...stagger(5)}>
            <Card>
              <div className="db-section-label">Recent transactions</div>
              {loading ? (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                  {[1, 2, 3, 4].map((i) => <Skeleton key={i} h={28} />)}
                </div>
              ) : recent.length > 0 ? (
                recent.map((t) => (
                  <div key={t.id} className="db-txn-row">
                    <div>
                      <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-text-primary)' }}>{t.merchant}</div>
                      <div style={{ fontSize: 11, color: 'var(--color-text-faint)' }}>
                        {new Date(t.occurred_at).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })}
                      </div>
                    </div>
                    <span
                      className="tnum"
                      style={{
                        fontSize: 13,
                        fontWeight: 700,
                        color: t.direction === 'credit' ? 'var(--color-credit)' : 'var(--color-debit)',
                      }}
                    >
                      {t.direction === 'credit' ? '+' : '−'}{inr(t.amount)}
                    </span>
                  </div>
                ))
              ) : (
                <div className="db-empty">No transactions yet.</div>
              )}
            </Card>
          </motion.div>

          {/* Upcoming recurring */}
          <motion.div {...stagger(6)}>
            <Card>
              <div className="db-section-label">Upcoming recurring</div>
              {loading ? (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                  {[1, 2, 3].map((i) => <Skeleton key={i} h={28} />)}
                </div>
              ) : recurring.length > 0 ? (
                recurring.slice(0, 6).map((r, i) => (
                  <div key={i} className="db-recur-row">
                    <div>
                      <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-text-primary)' }}>{r.merchant}</div>
                      <div style={{ fontSize: 11, color: 'var(--color-text-faint)' }}>
                        {new Date(r.next_expected_at).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })}
                      </div>
                    </div>
                    <span className="tnum" style={{ fontSize: 13, fontWeight: 700, color: 'var(--color-warning)' }}>
                      {inr(r.expected_amount)}
                    </span>
                  </div>
                ))
              ) : (
                <div className="db-empty">No recurring payments found.</div>
              )}
            </Card>
          </motion.div>

          {/* Insight */}
          <motion.div {...stagger(7)}>
            <Card>
              <div className="db-section-label">Insight</div>
              {loading ? (
                <Skeleton h={60} />
              ) : (
                <div className="db-insight">
                  {topCategory && totalSummarySpend > 0 ? (
                    <>
                      Top category this month:{' '}
                      <strong style={{ color: 'var(--color-teal)' }}>{topCategory.category}</strong>
                      {' '}at{' '}
                      <strong className="tnum">{inr(topCategory.total)}</strong>
                      {' '}({Math.round((topCategory.total / totalSummarySpend) * 100)}% of total spend).
                    </>
                  ) : (
                    'Start tracking expenses to see personalized insights here.'
                  )}
                </div>
              )}
              {!loading && recurring.length > 0 && (
                <div className="db-insight" style={{ marginTop: 10 }}>
                  <strong style={{ color: 'var(--color-teal)' }}>{recurring.length}</strong> recurring charge{recurring.length !== 1 ? 's' : ''} tracked.
                  Next up: <strong>{recurring[0].merchant}</strong> on{' '}
                  {new Date(recurring[0].next_expected_at).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })}.
                </div>
              )}
            </Card>
          </motion.div>
        </div>
      </div>
    </>
  );
}
