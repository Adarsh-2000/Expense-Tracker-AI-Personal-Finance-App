import React, { useEffect, useState, useCallback } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { Card } from '../components/ui/Card';
import { Button } from '../components/ui/Button';
import {
  searchTransactions,
  getAccounts,
  getCategories,
  getReview,
  createManualTransaction,
  updateTransaction,
  splitTransaction,
  downloadTransactionsCsv,
  type Transaction,
  type Account,
  type Category,
  type ReviewItem,
  type TransactionSearchParams,
  type SplitEntry,
  LockedError,
} from '../lib/api';
import { inr } from '../lib/format';

// ── Skeleton ───────────────────────────────────────────────────

function Skeleton({ w = '100%', h = 16, r = 8 }: { w?: string | number; h?: number; r?: number }) {
  return (
    <div
      style={{
        width: w, height: h, borderRadius: r,
        background: 'rgba(255,255,255,0.07)',
        animation: 'pulse-fade 1.4s ease-in-out infinite',
      }}
    />
  );
}

// ── Input helper ───────────────────────────────────────────────

function FieldInput({
  label, value, onChange, type = 'text', placeholder = '', style = {},
}: {
  label: string; value: string; onChange: (v: string) => void;
  type?: string; placeholder?: string; style?: React.CSSProperties;
}) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
      <label style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--color-text-faint)' }}>
        {label}
      </label>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        style={{
          background: 'rgba(255,255,255,0.04)',
          border: '1px solid rgba(255,255,255,0.1)',
          borderRadius: 10,
          color: 'var(--color-text-primary)',
          fontSize: 13,
          padding: '8px 12px',
          outline: 'none',
          width: '100%',
          transition: 'border-color 200ms',
          ...style,
        }}
        onFocus={(e) => { e.currentTarget.style.borderColor = 'rgba(45,212,191,0.5)'; }}
        onBlur={(e) => { e.currentTarget.style.borderColor = 'rgba(255,255,255,0.1)'; }}
      />
    </div>
  );
}

function FieldSelect({
  label, value, onChange, children,
}: {
  label: string; value: string; onChange: (v: string) => void; children: React.ReactNode;
}) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
      <label style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--color-text-faint)' }}>
        {label}
      </label>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        style={{
          background: 'rgba(17,22,29,0.97)',
          border: '1px solid rgba(255,255,255,0.1)',
          borderRadius: 10,
          color: 'var(--color-text-primary)',
          fontSize: 13,
          padding: '8px 12px',
          outline: 'none',
          width: '100%',
          cursor: 'pointer',
        }}
      >
        {children}
      </select>
    </div>
  );
}

// ── Add manual transaction modal ───────────────────────────────

interface AddModalProps {
  accounts: Account[];
  categories: Category[];
  onClose: () => void;
  onSuccess: () => void;
}

function AddTransactionModal({ accounts, categories, onClose, onSuccess }: AddModalProps) {
  const [amount, setAmount] = useState('');
  const [merchant, setMerchant] = useState('');
  const [direction, setDirection] = useState<'debit' | 'credit'>('debit');
  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [accountId, setAccountId] = useState<number | ''>(accounts[0]?.id ?? '');
  const [categoryId, setCategoryId] = useState<number | ''>('');
  const [note, setNote] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    const amt = parseFloat(amount);
    if (!amt || !merchant || !accountId) {
      setError('Amount, merchant and account are required.');
      return;
    }
    setLoading(true);
    setError('');
    try {
      await createManualTransaction({
        amount: amt,
        merchant,
        direction,
        occurred_at: new Date(date).toISOString(),
        account_id: accountId as number,
        category_id: categoryId === '' ? null : categoryId as number,
        note: note || undefined,
      });
      onSuccess();
      onClose();
    } catch (e) {
      setError(e instanceof LockedError ? 'Session locked.' : 'Could not save transaction.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <motion.div
        initial={{ opacity: 0, scale: 0.95, y: 12 }}
        animate={{ opacity: 1, scale: 1, y: 0 }}
        exit={{ opacity: 0, scale: 0.95, y: 12 }}
        transition={{ duration: 0.2 }}
        className="modal-box"
        onClick={(e) => e.stopPropagation()}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
          <h3 style={{ margin: 0, fontSize: '1.1rem' }}>Add cash / manual entry</h3>
          <button onClick={onClose} style={{ background: 'none', border: 'none', color: 'var(--color-text-faint)', fontSize: 20, cursor: 'pointer', lineHeight: 1 }}>×</button>
        </div>
        <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <FieldInput label="Amount (₹)" value={amount} onChange={setAmount} type="number" placeholder="0" />
          <FieldInput label="Merchant" value={merchant} onChange={setMerchant} placeholder="Zomato, Uber, etc." />
          <FieldSelect label="Direction" value={direction} onChange={(v) => setDirection(v as 'debit' | 'credit')}>
            <option value="debit">Debit (expense)</option>
            <option value="credit">Credit (income)</option>
          </FieldSelect>
          <FieldInput label="Date" value={date} onChange={setDate} type="date" />
          <FieldSelect label="Account" value={String(accountId)} onChange={(v) => setAccountId(v === '' ? '' : Number(v))}>
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>{a.bank} ••{a.last4}</option>
            ))}
          </FieldSelect>
          <FieldSelect label="Category (optional)" value={String(categoryId)} onChange={(v) => setCategoryId(v === '' ? '' : Number(v))}>
            <option value="">— none —</option>
            {categories.map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </FieldSelect>
          <FieldInput label="Note (optional)" value={note} onChange={setNote} placeholder="Any notes…" />
          {error && <p style={{ color: 'var(--color-debit)', fontSize: 12, margin: 0 }}>{error}</p>}
          <div style={{ display: 'flex', gap: 10, marginTop: 4 }}>
            <Button variant="ghost" size="md" type="button" onClick={onClose} style={{ flex: 1 }}>Cancel</Button>
            <Button variant="primary" size="md" type="submit" loading={loading} style={{ flex: 2 }}>Save</Button>
          </div>
        </form>
      </motion.div>
    </div>
  );
}

// ── Transaction detail / edit drawer ──────────────────────────

interface DrawerProps {
  txn: Transaction;
  categories: Category[];
  onClose: () => void;
  onSuccess: () => void;
}

function TransactionDrawer({ txn, categories, onClose, onSuccess }: DrawerProps) {
  const [merchant, setMerchant] = useState(txn.merchant);
  const [amount, setAmount] = useState(String(txn.amount));
  const [note, setNote] = useState(txn.note ?? '');
  const [categoryId, setCategoryId] = useState<number | null>(txn.category_id);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  // Split editor — category_id as number | null
  const [splits, setSplits] = useState<{ category_id: number | null; amount: string; note: string }[]>([
    { category_id: txn.category_id, amount: '', note: '' },
    { category_id: null, amount: '', note: '' },
  ]);
  const [splitMode, setSplitMode] = useState(false);
  const [splitSaving, setSplitSaving] = useState(false);
  const [splitError, setSplitError] = useState('');

  const splitsTotal = splits.reduce((s, sp) => s + (parseFloat(sp.amount) || 0), 0);
  const splitsValid = Math.abs(splitsTotal - txn.amount) < 0.01 && splits.every((sp) => sp.category_id !== null && parseFloat(sp.amount) > 0);

  async function handleSave() {
    setSaving(true); setError('');
    try {
      await updateTransaction(txn.id, {
        merchant,
        amount: parseFloat(amount),
        note: note || undefined,
        category_id: categoryId,
      });
      onSuccess();
      onClose();
    } catch (e) {
      setError(e instanceof LockedError ? 'Session locked.' : 'Could not update.');
    } finally { setSaving(false); }
  }

  async function handleSplitSave() {
    setSplitSaving(true); setSplitError('');
    try {
      const payload: SplitEntry[] = splits
        .filter((s) => s.category_id !== null && parseFloat(s.amount) > 0)
        .map((s) => ({ category_id: s.category_id as number, amount: parseFloat(s.amount), note: s.note || undefined }));
      await splitTransaction(txn.id, payload);
      onSuccess();
      onClose();
    } catch (e) {
      setSplitError(e instanceof LockedError ? 'Session locked.' : 'Could not save splits.');
    } finally { setSplitSaving(false); }
  }

  return (
    <div className="drawer-overlay" onClick={onClose}>
      <motion.aside
        initial={{ x: '100%' }}
        animate={{ x: 0 }}
        exit={{ x: '100%' }}
        transition={{ type: 'spring', stiffness: 340, damping: 34 }}
        className="drawer-panel"
        onClick={(e) => e.stopPropagation()}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 24 }}>
          <h3 style={{ margin: 0, fontSize: '1.1rem' }}>Transaction detail</h3>
          <button onClick={onClose} style={{ background: 'none', border: 'none', color: 'var(--color-text-faint)', fontSize: 20, cursor: 'pointer' }}>×</button>
        </div>

        {/* Amount badge */}
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <span className="tnum" style={{
            fontFamily: 'var(--font-display)', fontSize: '2.2rem', fontWeight: 700,
            color: txn.direction === 'credit' ? 'var(--color-credit)' : 'var(--color-debit)',
          }}>
            {txn.direction === 'credit' ? '+' : '−'}{inr(txn.amount)}
          </span>
          <div style={{ fontSize: 12, color: 'var(--color-text-faint)', marginTop: 4 }}>
            {new Date(txn.occurred_at).toLocaleDateString('en-IN', { day: 'numeric', month: 'long', year: 'numeric' })}
          </div>
        </div>

        {/* Mode toggle */}
        <div style={{ display: 'flex', gap: 8, marginBottom: 20 }}>
          <button
            onClick={() => setSplitMode(false)}
            style={{
              flex: 1, padding: '8px', borderRadius: 8, border: 'none', cursor: 'pointer',
              background: !splitMode ? 'rgba(45,212,191,0.15)' : 'rgba(255,255,255,0.05)',
              color: !splitMode ? 'var(--color-teal)' : 'var(--color-text-muted)',
              fontSize: 13, fontWeight: 600,
            }}
          >Edit</button>
          <button
            onClick={() => setSplitMode(true)}
            style={{
              flex: 1, padding: '8px', borderRadius: 8, border: 'none', cursor: 'pointer',
              background: splitMode ? 'rgba(45,212,191,0.15)' : 'rgba(255,255,255,0.05)',
              color: splitMode ? 'var(--color-teal)' : 'var(--color-text-muted)',
              fontSize: 13, fontWeight: 600,
            }}
          >Split</button>
        </div>

        {!splitMode ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <FieldInput label="Merchant" value={merchant} onChange={setMerchant} />
            <FieldInput label="Amount (₹)" value={amount} onChange={setAmount} type="number" />
            <FieldSelect label="Category" value={String(categoryId ?? '')} onChange={(v) => setCategoryId(v === '' ? null : Number(v))}>
              <option value="">— uncategorised —</option>
              {categories.map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </FieldSelect>
            <FieldInput label="Note" value={note} onChange={setNote} placeholder="Optional note…" />
            {error && <p style={{ color: 'var(--color-debit)', fontSize: 12, margin: 0 }}>{error}</p>}
            <Button variant="primary" size="md" loading={saving} onClick={handleSave} fullWidth>Save changes</Button>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div style={{ fontSize: 12, color: 'var(--color-text-faint)' }}>
              Splits must total exactly{' '}
              <span className="tnum" style={{ color: 'var(--color-text-primary)', fontWeight: 700 }}>{inr(txn.amount)}</span>.
              Current: <span className="tnum" style={{ color: Math.abs(splitsTotal - txn.amount) < 0.01 ? 'var(--color-credit)' : 'var(--color-debit)', fontWeight: 700 }}>{inr(splitsTotal)}</span>
            </div>
            {splits.map((sp, i) => (
              <div key={i} style={{ display: 'flex', gap: 8, alignItems: 'flex-end' }}>
                <div style={{ flex: 2 }}>
                  <FieldSelect label={i === 0 ? 'Category' : ''} value={String(sp.category_id ?? '')} onChange={(v) => {
                    const next = [...splits]; next[i] = { ...next[i], category_id: v === '' ? null : Number(v) }; setSplits(next);
                  }}>
                    <option value="">— category —</option>
                    {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                  </FieldSelect>
                </div>
                <div style={{ flex: 1 }}>
                  <FieldInput label={i === 0 ? 'Amount (₹)' : ''} value={sp.amount} onChange={(v) => {
                    const next = [...splits]; next[i] = { ...next[i], amount: v }; setSplits(next);
                  }} type="number" placeholder="0" />
                </div>
                {splits.length > 2 && (
                  <button
                    onClick={() => setSplits(splits.filter((_, j) => j !== i))}
                    style={{ background: 'none', border: 'none', color: 'var(--color-danger)', cursor: 'pointer', fontSize: 16, paddingBottom: 8 }}
                  >×</button>
                )}
              </div>
            ))}
            <button
              onClick={() => setSplits([...splits, { category_id: null, amount: '', note: '' }])}
              style={{ background: 'none', border: '1px dashed rgba(255,255,255,0.12)', borderRadius: 8, color: 'var(--color-text-faint)', fontSize: 12, padding: '7px', cursor: 'pointer' }}
            >+ Add row</button>
            {splitError && <p style={{ color: 'var(--color-debit)', fontSize: 12, margin: 0 }}>{splitError}</p>}
            <Button variant="primary" size="md" loading={splitSaving} disabled={!splitsValid} onClick={handleSplitSave} fullWidth>
              Save splits
            </Button>
          </div>
        )}
      </motion.aside>
    </div>
  );
}

// ── Review queue panel ─────────────────────────────────────────

function ReviewPanel({ items }: { items: ReviewItem[] }) {
  if (items.length === 0) return null;
  return (
    <Card style={{ borderColor: 'rgba(251,191,36,0.25)', background: 'rgba(251,191,36,0.04)' }}>
      <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--color-warning)', marginBottom: 12 }}>
        Needs your attention ({items.length})
      </div>
      {items.map((item) => (
        <div key={item.id} style={{ padding: '10px 0', borderBottom: '1px solid rgba(255,255,255,0.05)' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 3 }}>
            <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--color-text-primary)' }}>{item.sender}</span>
            <span style={{ fontSize: 11, color: 'var(--color-text-faint)' }}>
              {new Date(item.received_at).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })}
            </span>
          </div>
          <div style={{ fontSize: 11, color: 'var(--color-text-muted)', lineHeight: 1.5 }}>
            [{item.channel}] {item.body.slice(0, 100)}{item.body.length > 100 ? '…' : ''}
          </div>
        </div>
      ))}
    </Card>
  );
}

// ── Transactions page ─────────────────────────────────────────

export function Transactions() {
  const [txns, setTxns] = useState<Transaction[]>([]);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [review, setReview] = useState<ReviewItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // Filter state
  const [q, setQ] = useState('');
  const [filterAccount, setFilterAccount] = useState('');
  const [filterCategory, setFilterCategory] = useState('');
  const [filterFrom, setFilterFrom] = useState('');
  const [filterTo, setFilterTo] = useState('');
  const [filterMin, setFilterMin] = useState('');
  const [filterMax, setFilterMax] = useState('');

  const [showAddModal, setShowAddModal] = useState(false);
  const [selectedTxn, setSelectedTxn] = useState<Transaction | null>(null);
  const [csvLoading, setCsvLoading] = useState(false);

  const fetchTxns = useCallback(async () => {
    const params: TransactionSearchParams = {};
    if (q) params.q = q;
    if (filterAccount) params.account_id = Number(filterAccount);
    if (filterCategory) params.category_id = Number(filterCategory);
    if (filterFrom) params.start = filterFrom;
    if (filterTo) params.end = filterTo;
    if (filterMin) params.min_amount = parseFloat(filterMin);
    if (filterMax) params.max_amount = parseFloat(filterMax);
    try {
      const results = await searchTransactions(params);
      setTxns(results);
    } catch (e) {
      setError(e instanceof LockedError ? 'Session locked.' : 'Could not load transactions.');
    }
  }, [q, filterAccount, filterCategory, filterFrom, filterTo, filterMin, filterMax]);

  useEffect(() => {
    let cancelled = false;
    async function init() {
      setLoading(true);
      try {
        const [a, c, r] = await Promise.allSettled([
          getAccounts(),
          getCategories(),
          getReview(),
        ]);
        if (cancelled) return;
        if (a.status === 'fulfilled') setAccounts(a.value);
        if (c.status === 'fulfilled') setCategories(c.value);
        if (r.status === 'fulfilled') setReview(r.value);
      } catch (_) { /* silent */ }
      if (!cancelled) {
        await fetchTxns();
        setLoading(false);
      }
    }
    void init();
    return () => { cancelled = true; };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Build id→name lookup for rendering
  const categoryNameMap = React.useMemo(
    () => Object.fromEntries(categories.map((c) => [c.id, c.name])),
    [categories]
  );

  // Search on filter change (debounced via search button)
  const handleSearch = useCallback(() => { void fetchTxns(); }, [fetchTxns]);

  async function handleCsvExport() {
    setCsvLoading(true);
    try { await downloadTransactionsCsv(); }
    catch (e) { setError(e instanceof LockedError ? 'Session locked.' : 'Export failed.'); }
    finally { setCsvLoading(false); }
  }

  return (
    <>
      <style>{`
        @keyframes pulse-fade {
          0%, 100% { opacity: 0.5; }
          50% { opacity: 1; }
        }
        .txn-filter-bar {
          display: grid;
          grid-template-columns: 2fr 1fr 1fr 1fr 1fr 1fr 1fr auto;
          gap: 10px;
          align-items: flex-end;
          margin-bottom: 20px;
        }
        .txn-table-row {
          display: grid;
          grid-template-columns: 2fr 1fr 1.2fr 1fr auto;
          align-items: center;
          padding: 11px 16px;
          border-bottom: 1px solid rgba(255,255,255,0.05);
          cursor: pointer;
          transition: background 150ms;
          border-radius: 8px;
        }
        .txn-table-row:hover { background: rgba(255,255,255,0.03); }
        .txn-table-header {
          display: grid;
          grid-template-columns: 2fr 1fr 1.2fr 1fr auto;
          padding: 0 16px 10px;
          font-size: 10px;
          font-weight: 700;
          letter-spacing: 0.08em;
          text-transform: uppercase;
          color: var(--color-text-faint);
        }
        .modal-overlay {
          position: fixed; inset: 0; z-index: 1000;
          background: rgba(0,0,0,0.7);
          backdrop-filter: blur(4px);
          display: flex; align-items: center; justify-content: center;
          padding: 24px;
        }
        .modal-box {
          background: var(--color-surface-high, #161D27);
          border: 1px solid rgba(45,212,191,0.15);
          border-radius: 20px;
          padding: 28px;
          width: 100%;
          max-width: 460px;
          box-shadow: 0 8px 40px rgba(0,0,0,0.6);
        }
        .drawer-overlay {
          position: fixed; inset: 0; z-index: 1000;
          background: rgba(0,0,0,0.5);
          backdrop-filter: blur(3px);
        }
        .drawer-panel {
          position: fixed; top: 0; right: 0; bottom: 0;
          width: 420px;
          background: var(--color-surface-high, #161D27);
          border-left: 1px solid rgba(45,212,191,0.12);
          box-shadow: -8px 0 40px rgba(0,0,0,0.5);
          padding: 32px 28px;
          overflow-y: auto;
        }
        @media (max-width: 900px) {
          .txn-filter-bar { grid-template-columns: 1fr 1fr; }
          .txn-table-row { grid-template-columns: 1fr auto; }
          .txn-table-header { display: none; }
          .drawer-panel { width: 100%; }
        }
      `}</style>

      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24 }}>
        <h2 style={{ margin: 0, fontSize: '1.5rem', fontFamily: 'var(--font-display)' }}>Transactions</h2>
        <div style={{ display: 'flex', gap: 10 }}>
          <Button variant="ghost" size="sm" loading={csvLoading} onClick={handleCsvExport}>Export CSV</Button>
          <Button variant="primary" size="sm" onClick={() => setShowAddModal(true)}>+ Add manual</Button>
        </div>
      </div>

      {error && <div style={{ color: 'var(--color-warning)', fontSize: 13, marginBottom: 12 }}>{error}</div>}

      {/* Review queue */}
      {review.length > 0 && (
        <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} style={{ marginBottom: 20 }}>
          <ReviewPanel items={review} />
        </motion.div>
      )}

      {/* Filter bar */}
      <Card style={{ marginBottom: 20 }}>
        <div className="txn-filter-bar">
          <FieldInput label="Search" value={q} onChange={setQ} placeholder="Merchant, note…" />
          <FieldSelect label="Account" value={filterAccount} onChange={setFilterAccount}>
            <option value="">All accounts</option>
            {accounts.map((a) => <option key={a.id} value={a.id}>{a.bank} ••{a.last4}</option>)}
          </FieldSelect>
          <FieldSelect label="Category" value={filterCategory} onChange={setFilterCategory}>
            <option value="">All categories</option>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </FieldSelect>
          <FieldInput label="From" value={filterFrom} onChange={setFilterFrom} type="date" />
          <FieldInput label="To" value={filterTo} onChange={setFilterTo} type="date" />
          <FieldInput label="Min ₹" value={filterMin} onChange={setFilterMin} type="number" placeholder="0" />
          <FieldInput label="Max ₹" value={filterMax} onChange={setFilterMax} type="number" placeholder="∞" />
          <div style={{ paddingBottom: 2 }}>
            <Button variant="primary" size="md" onClick={handleSearch}>Search</Button>
          </div>
        </div>
      </Card>

      {/* Transaction list */}
      <Card padded={false}>
        <div className="txn-table-header">
          <span>Merchant</span>
          <span>Date</span>
          <span>Category</span>
          <span style={{ textAlign: 'right' }}>Amount</span>
          <span />
        </div>
        {loading ? (
          <div style={{ padding: '16px 16px', display: 'flex', flexDirection: 'column', gap: 12 }}>
            {[1, 2, 3, 4, 5, 6].map((i) => <Skeleton key={i} h={36} />)}
          </div>
        ) : txns.length === 0 ? (
          <div style={{ textAlign: 'center', padding: '40px 24px', color: 'var(--color-text-faint)', fontSize: 14 }}>
            No transactions match your filters. Try adjusting the search.
          </div>
        ) : (
          txns.map((t, i) => (
            <motion.div
              key={t.id}
              initial={{ opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: i * 0.02, duration: 0.25 }}
              className="txn-table-row"
              onClick={() => setSelectedTxn(t)}
            >
              <div>
                <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-text-primary)' }}>{t.merchant}</div>
                {t.note && <div style={{ fontSize: 11, color: 'var(--color-text-faint)' }}>{t.note}</div>}
              </div>
              <div style={{ fontSize: 12, color: 'var(--color-text-muted)' }}>
                {new Date(t.occurred_at).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: '2-digit' })}
              </div>
              <div style={{ fontSize: 11, color: 'var(--color-text-faint)' }}>{t.category_id != null ? (categoryNameMap[t.category_id] ?? String(t.category_id)) : '—'}</div>
              <div style={{ textAlign: 'right' }}>
                <span className="tnum" style={{ fontSize: 13, fontWeight: 700, color: t.direction === 'credit' ? 'var(--color-credit)' : 'var(--color-debit)' }}>
                  {t.direction === 'credit' ? '+' : '−'}{inr(t.amount)}
                </span>
              </div>
              <div style={{ color: 'var(--color-text-faint)', fontSize: 16 }}>›</div>
            </motion.div>
          ))
        )}
      </Card>

      {/* Modals */}
      <AnimatePresence>
        {showAddModal && (
          <AddTransactionModal
            accounts={accounts}
            categories={categories}
            onClose={() => setShowAddModal(false)}
            onSuccess={() => void fetchTxns()}
          />
        )}
      </AnimatePresence>

      <AnimatePresence>
        {selectedTxn && (
          <TransactionDrawer
            txn={selectedTxn}
            categories={categories}
            onClose={() => setSelectedTxn(null)}
            onSuccess={() => { void fetchTxns(); }}
          />
        )}
      </AnimatePresence>
    </>
  );
}
