import { useEffect, useState, useCallback } from 'react';
import { motion } from 'motion/react';
import { Card } from '../components/ui/Card';
import { Button } from '../components/ui/Button';
import { Stat } from '../components/ui/Stat';
import {
  getBudget,
  setBudgetIncome,
  assignBudget,
  getCategories,
  getSummary,
  type BudgetCategory,
  type Category,
  type CategoryTotal,
  LockedError,
} from '../lib/api';
import { inr } from '../lib/format';

// ── Helpers ────────────────────────────────────────────────────

function formatMonth(ym: string): string {
  const [y, m] = ym.split('-');
  const d = new Date(parseInt(y), parseInt(m) - 1, 1);
  return d.toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });
}

function addMonths(ym: string, delta: number): string {
  const [y, m] = ym.split('-').map(Number);
  const d = new Date(y, m - 1 + delta, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

function currentMonth(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

// ── Skeleton ───────────────────────────────────────────────────

function Skeleton({ h = 16, r = 8, w = '100%' }: { h?: number; r?: number; w?: string }) {
  return (
    <div style={{
      width: w, height: h, borderRadius: r,
      background: 'rgba(255,255,255,0.07)',
      animation: 'budget-pulse 1.4s ease-in-out infinite',
    }} />
  );
}

// ── Category row ───────────────────────────────────────────────

interface CategoryRowProps {
  cat: BudgetCategory;
  categoryName: string;
  assignedInput: string;
  onChange: (val: string) => void;
  onSave: () => void;
  saving: boolean;
}

function CategoryRow({ cat, categoryName, assignedInput, onChange, onSave, saving }: CategoryRowProps) {
  const assigned = parseFloat(assignedInput) || 0;
  const pct = assigned > 0 ? Math.min((cat.spent / assigned) * 100, 100) : 0;
  const status = cat.status === 'exceeded' ? 'exceeded' : pct >= 90 ? 'warning' : 'ok';
  const barColor =
    status === 'exceeded' ? 'var(--color-danger)'
    : status === 'warning' ? 'var(--color-warning)'
    : 'var(--color-credit)';
  const remaining = assigned - cat.spent;

  return (
    <div style={{
      padding: '14px 0',
      borderBottom: '1px solid rgba(255,255,255,0.05)',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8 }}>
        {/* Category name */}
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--color-text-primary)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
            {categoryName}
          </div>
          <div className="tnum" style={{ fontSize: 11, color: 'var(--color-text-faint)', marginTop: 2 }}>
            Spent: {inr(cat.spent)}
            {remaining !== 0 && (
              <span style={{ marginLeft: 8, color: remaining >= 0 ? 'var(--color-credit)' : 'var(--color-danger)' }}>
                {remaining >= 0 ? `${inr(remaining)} left` : `${inr(Math.abs(remaining))} over`}
              </span>
            )}
          </div>
        </div>

        {/* Assign input */}
        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexShrink: 0 }}>
          <div style={{ position: 'relative' }}>
            <span style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--color-text-faint)', fontSize: 13, pointerEvents: 'none' }}>₹</span>
            <input
              type="number"
              value={assignedInput}
              onChange={(e) => onChange(e.target.value)}
              style={{
                width: 110,
                paddingLeft: 24,
                paddingRight: 10,
                height: 34,
                background: 'rgba(255,255,255,0.05)',
                border: '1px solid rgba(255,255,255,0.1)',
                borderRadius: 8,
                color: 'var(--color-text-primary)',
                fontSize: 13,
                fontFamily: 'var(--font-display)',
                fontFeatureSettings: '"tnum" 1',
                outline: 'none',
              }}
              onFocus={(e) => { e.currentTarget.style.borderColor = 'rgba(45,212,191,0.5)'; }}
              onBlur={(e) => { e.currentTarget.style.borderColor = 'rgba(255,255,255,0.1)'; }}
            />
          </div>
          <Button variant="ghost" size="sm" loading={saving} onClick={onSave} style={{ flexShrink: 0 }}>
            Set
          </Button>
        </div>
      </div>

      {/* Progress bar */}
      <div style={{ height: 5, background: 'rgba(255,255,255,0.07)', borderRadius: 99, overflow: 'hidden' }}>
        <motion.div
          key={`${cat.category_id}-${assigned}`}
          initial={{ width: 0 }}
          animate={{ width: `${pct}%` }}
          transition={{ duration: 0.6, ease: 'easeOut' }}
          style={{ height: '100%', background: barColor, borderRadius: 99 }}
        />
      </div>
    </div>
  );
}

// ── Budget page ───────────────────────────────────────────────

export function Budget() {
  const [month, setMonth] = useState(currentMonth());
  const [readyToAssign, setReadyToAssign] = useState(0);
  const [categories, setCategories] = useState<BudgetCategory[]>([]);
  const [summaryCategories, setSummaryCategories] = useState<CategoryTotal[]>([]);
  const [categoryList, setCategoryList] = useState<Category[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // Per-category assignment inputs: category_id (number) → string
  const [assignInputs, setAssignInputs] = useState<Record<number, string>>({});
  const [savingCategory, setSavingCategory] = useState<number | null>(null);

  // Income
  const [incomeInput, setIncomeInput] = useState('');
  const [savingIncome, setSavingIncome] = useState(false);
  const [incomeError, setIncomeError] = useState('');

  const loadBudget = useCallback(async (m: string) => {
    setLoading(true);
    setError('');
    try {
      const [b, s, cats] = await Promise.allSettled([getBudget(m), getSummary(), getCategories()]);
      if (cats.status === 'fulfilled') {
        setCategoryList(cats.value);
      }
      if (b.status === 'fulfilled') {
        const view = b.value;
        setReadyToAssign(view.ready_to_assign);
        setCategories(view.categories);
        // Seed inputs from existing assigned values
        const inputs: Record<number, string> = {};
        view.categories.forEach((cat) => {
          inputs[cat.category_id] = cat.assigned > 0 ? String(cat.assigned) : '';
        });
        setAssignInputs((prev) => ({ ...inputs, ...prev }));
      }
      if (s.status === 'fulfilled') {
        setSummaryCategories(s.value);
      }
    } catch (e) {
      setError(e instanceof LockedError ? 'Session locked.' : 'Could not load budget.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void loadBudget(month); }, [month, loadBudget]);

  const handleSaveIncome = async () => {
    const amount = parseFloat(incomeInput);
    if (!amount || amount <= 0) { setIncomeError('Enter a valid income.'); return; }
    setSavingIncome(true); setIncomeError('');
    try {
      await setBudgetIncome({ month, amount });
      // Re-fetch budget to get updated ready_to_assign
      const updated = await getBudget(month);
      setReadyToAssign(updated.ready_to_assign);
      setCategories(updated.categories);
    } catch (e) {
      setIncomeError(e instanceof LockedError ? 'Session locked.' : 'Could not save income.');
    } finally { setSavingIncome(false); }
  };

  const handleSaveCategory = async (categoryId: number) => {
    const assigned_amount = parseFloat(assignInputs[categoryId] ?? '0');
    if (assigned_amount < 0) return;
    setSavingCategory(categoryId);
    try {
      await assignBudget({ category_id: categoryId, month, assigned_amount });
      // Re-fetch budget to get updated ready_to_assign
      const updated = await getBudget(month);
      setReadyToAssign(updated.ready_to_assign);
      setCategories(updated.categories);
    } catch (e) {
      setError(e instanceof LockedError ? 'Session locked.' : 'Could not save assignment.');
    } finally { setSavingCategory(null); }
  };

  const stagger = (i: number) => ({
    initial: { opacity: 0, y: 14 },
    animate: { opacity: 1, y: 0 },
    transition: { duration: 0.35, delay: i * 0.06, ease: 'easeOut' as const },
  });

  // Build id→name map from category list
  const categoryNameMap: Record<number, string> = Object.fromEntries(categoryList.map((c) => [c.id, c.name]));

  // Merged category list: budget rows + summary rows not in budget
  const allCategories = categories;
  const summaryOnly = summaryCategories.filter((sc) => !categories.some((c) => categoryNameMap[c.category_id] === sc.category || c.category_id === 0));

  return (
    <>
      <style>{`
        @keyframes budget-pulse {
          0%, 100% { opacity: 0.5; }
          50% { opacity: 1; }
        }
      `}</style>

      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 28 }}>
        <h2 style={{ margin: 0, fontSize: '1.5rem', fontFamily: 'var(--font-display)' }}>Budget</h2>
        {/* Month navigator */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <button
            onClick={() => setMonth(addMonths(month, -1))}
            style={{ background: 'rgba(255,255,255,0.07)', border: 'none', borderRadius: 8, color: 'var(--color-text-primary)', width: 34, height: 34, cursor: 'pointer', fontSize: 16, display: 'flex', alignItems: 'center', justifyContent: 'center' }}
          >‹</button>
          <span style={{ fontFamily: 'var(--font-display)', fontSize: '1rem', fontWeight: 600, minWidth: 140, textAlign: 'center' }}>
            {formatMonth(month)}
          </span>
          <button
            onClick={() => setMonth(addMonths(month, 1))}
            style={{ background: 'rgba(255,255,255,0.07)', border: 'none', borderRadius: 8, color: 'var(--color-text-primary)', width: 34, height: 34, cursor: 'pointer', fontSize: 16, display: 'flex', alignItems: 'center', justifyContent: 'center' }}
          >›</button>
        </div>
      </div>

      {error && <div style={{ color: 'var(--color-warning)', fontSize: 13, marginBottom: 14 }}>{error}</div>}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 2fr', gap: 20, alignItems: 'start' }}>
        {/* Left column */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {/* Ready to assign */}
          <motion.div {...stagger(0)}>
            <Card style={{
              background: 'linear-gradient(135deg, rgba(45,212,191,0.08), rgba(52,211,153,0.05))',
              borderColor: 'rgba(45,212,191,0.2)',
            }}>
              {loading ? (
                <>
                  <Skeleton w="60%" h={11} />
                  <div style={{ marginTop: 10 }}><Skeleton h={44} w="80%" /></div>
                </>
              ) : (
                <Stat
                  label="Ready to assign"
                  value={inr(readyToAssign)}
                  tone={readyToAssign >= 0 ? 'credit' : 'danger'}
                  sub={readyToAssign >= 0 ? 'Available to budget' : 'Over-assigned!'}
                />
              )}
            </Card>
          </motion.div>

          {/* Set income */}
          <motion.div {...stagger(1)}>
            <Card>
              <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--color-text-faint)', marginBottom: 14 }}>
                Monthly income
              </div>
              <div style={{ display: 'flex', gap: 8 }}>
                <div style={{ position: 'relative', flex: 1 }}>
                  <span style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--color-text-faint)', fontSize: 14, pointerEvents: 'none' }}>₹</span>
                  <input
                    type="number"
                    value={incomeInput}
                    onChange={(e) => setIncomeInput(e.target.value)}
                    placeholder="0"
                    style={{
                      width: '100%', paddingLeft: 28, paddingRight: 12,
                      height: 40, background: 'rgba(255,255,255,0.05)',
                      border: '1px solid rgba(255,255,255,0.1)', borderRadius: 10,
                      color: 'var(--color-text-primary)', fontSize: 14,
                      fontFamily: 'var(--font-display)', fontFeatureSettings: '"tnum" 1',
                      outline: 'none',
                    }}
                    onFocus={(e) => { e.currentTarget.style.borderColor = 'rgba(45,212,191,0.5)'; }}
                    onBlur={(e) => { e.currentTarget.style.borderColor = 'rgba(255,255,255,0.1)'; }}
                  />
                </div>
                <Button variant="primary" size="sm" loading={savingIncome} onClick={handleSaveIncome}>Set</Button>
              </div>
              {incomeError && <p style={{ color: 'var(--color-debit)', fontSize: 12, marginTop: 6 }}>{incomeError}</p>}
            </Card>
          </motion.div>

          {/* Summary-only categories (not in budget) */}
          {summaryOnly.length > 0 && (
            <motion.div {...stagger(2)}>
              <Card>
                <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--color-text-faint)', marginBottom: 10 }}>
                  Unbudgeted categories
                </div>
                {summaryOnly.map((sc) => (
                  <div key={sc.category} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '6px 0', borderBottom: '1px solid rgba(255,255,255,0.05)' }}>
                    <span style={{ fontSize: 13, color: 'var(--color-text-muted)' }}>{sc.category}</span>
                    <span className="tnum" style={{ fontSize: 12, color: 'var(--color-debit)', fontWeight: 600 }}>{inr(sc.total)}</span>
                  </div>
                ))}
              </Card>
            </motion.div>
          )}
        </div>

        {/* Right column: category assignments */}
        <motion.div {...stagger(2)}>
          <Card>
            <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--color-text-faint)', marginBottom: 4 }}>
              Category assignments
            </div>
            {loading ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 14, marginTop: 16 }}>
                {[1, 2, 3, 4, 5].map((i) => <Skeleton key={i} h={48} />)}
              </div>
            ) : allCategories.length === 0 ? (
              <div style={{ textAlign: 'center', padding: '32px 0', color: 'var(--color-text-faint)', fontSize: 14 }}>
                No categories yet. Add transactions to get started.
              </div>
            ) : (
              allCategories.map((cat) => (
                <CategoryRow
                  key={cat.category_id}
                  cat={cat}
                  categoryName={categoryNameMap[cat.category_id] ?? String(cat.category_id)}
                  assignedInput={assignInputs[cat.category_id] ?? ''}
                  onChange={(v) => setAssignInputs((prev) => ({ ...prev, [cat.category_id]: v }))}
                  onSave={() => void handleSaveCategory(cat.category_id)}
                  saving={savingCategory === cat.category_id}
                />
              ))
            )}
          </Card>
        </motion.div>
      </div>
    </>
  );
}
