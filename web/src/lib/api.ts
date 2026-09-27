/* ============================================================
   LEDGER NOIR — Typed API Client
   Base: http://localhost:8000
   Auth token stored in localStorage under key `et_token`.
   All requests include authHeaders(); on 401 → throws LockedError.
   ============================================================ */

const BASE_URL = 'http://localhost:8000';
const TOKEN_KEY = 'et_token';

// ── Auth helpers ──────────────────────────────────────────────

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY);
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  if (token) {
    return { Authorization: `Bearer ${token}` };
  }
  return {};
}

// ── Typed error for locked / unauthenticated state ────────────

export class LockedError extends Error {
  readonly status = 401;
  constructor(message = 'Server is locked. Please enter your PIN.') {
    super(message);
    this.name = 'LockedError';
  }
}

// ── Internal fetch wrapper ────────────────────────────────────

async function apiFetch<T>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...authHeaders(),
    ...(options.headers as Record<string, string> | undefined),
  };

  const res = await fetch(`${BASE_URL}${path}`, {
    ...options,
    headers,
  });

  if (res.status === 401) {
    throw new LockedError();
  }

  if (!res.ok) {
    throw new Error(`API error ${res.status}: ${await res.text()}`);
  }

  // 204 No Content or empty body
  const text = await res.text();
  if (!text) return undefined as unknown as T;
  return JSON.parse(text) as T;
}

// ── Domain types ──────────────────────────────────────────────

export interface Transaction {
  id: number;
  amount: number;
  direction: 'credit' | 'debit';
  merchant: string;
  occurred_at: string; // ISO 8601
  account_id: number;
  category_id: number | null; // W1: number|null (not string)
  note?: string;
  entry_type?: string;
  balance_after?: number;
}

export interface CategoryTotal {
  category: string;
  total: number;
}

export interface Account {
  id: number;
  bank: string;
  type: string;
  last4: string;
  current_balance: number;
}

export interface BudgetCategory {
  category_id: number;
  assigned: number;
  spent: number;
  remaining: number;
  status: 'ok' | 'warning' | 'exceeded' | 'unassigned' | string; // W9: exceeded not overspent
}

export interface BudgetView {
  month: string; // e.g. "2025-06"
  ready_to_assign: number;
  categories: BudgetCategory[];
}

export interface RecurringItem {
  merchant: string;
  expected_amount: number;
  cadence_days: number;
  last_seen_at: string;  // ISO 8601
  next_expected_at: string; // ISO 8601
}

export interface ReviewItem {
  id: number;
  channel: string;
  sender: string;
  body: string;
  received_at: string; // ISO 8601
}

export interface BalancePoint {
  captured_at: string; // W8: renamed from date → captured_at
  balance: number;
}

export interface Category {
  id: number;
  name: string;
}

// ── Transaction endpoints ──────────────────────────────────────

export function getTransactions(): Promise<Transaction[]> {
  return apiFetch<Transaction[]>('/transactions');
}

export interface TransactionSearchParams {
  q?: string;
  direction?: 'credit' | 'debit';
  category_id?: number;
  account_id?: number;
  start?: string; // W6: renamed from from → start
  end?: string;   // W6: renamed from to → end
  min_amount?: number; // W7: proper params
  max_amount?: number; // W7: proper params
}

export function searchTransactions(
  params: TransactionSearchParams
): Promise<Transaction[]> {
  const qs = new URLSearchParams(
    Object.fromEntries(
      Object.entries(params)
        .filter(([, v]) => v !== undefined && v !== null)
        .map(([k, v]) => [k, String(v)])
    )
  ).toString();
  return apiFetch<Transaction[]>(`/transactions/search${qs ? `?${qs}` : ''}`);
}

export interface ManualTransactionPayload {
  amount: number;
  direction: 'credit' | 'debit';
  merchant: string;
  occurred_at: string;
  account_id?: number | null;
  category_id?: number | null; // W1: integer|null
  note?: string;
}

export function createManualTransaction(
  payload: ManualTransactionPayload
): Promise<Transaction> {
  return apiFetch<Transaction>('/transactions/manual', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export function updateTransaction(
  id: number,
  payload: Partial<ManualTransactionPayload>
): Promise<Transaction> {
  return apiFetch<Transaction>(`/transactions/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(payload),
  });
}

export interface SplitEntry {
  amount: number;
  category_id: number | null; // W1: integer|null
  note?: string;
}

export function splitTransaction(
  id: number,
  splits: SplitEntry[]
): Promise<{ status: string; count: number }> {
  return apiFetch<{ status: string; count: number }>(`/transactions/${id}/splits`, {
    method: 'PUT',
    body: JSON.stringify({ splits }),
  });
}

// ── Reports ────────────────────────────────────────────────────

export function getSummary(): Promise<CategoryTotal[]> {
  return apiFetch<CategoryTotal[]>('/reports/summary');
}

// ── Sync ──────────────────────────────────────────────────────

export function syncGmail(): Promise<{ ingested: number; fetched: number }> { // W10
  return apiFetch<{ ingested: number; fetched: number }>('/sync/gmail', { method: 'POST' });
}

// ── Budget ────────────────────────────────────────────────────

export function getBudget(month: string): Promise<BudgetView> {
  return apiFetch<BudgetView>(`/budget/${month}`);
}

export function setBudgetIncome(payload: {
  month: string;
  amount: number; // W2: renamed income→amount
}): Promise<{ status: string }> { // W2: returns {status} not BudgetView
  return apiFetch<{ status: string }>('/budget/income', {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

// W3: flat body, renamed fields
export function assignBudget(payload: {
  category_id: number;
  month: string;
  assigned_amount: number;
}): Promise<{ status: string }> { // returns {status} not BudgetView
  return apiFetch<{ status: string }>('/budget/assign', {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

// ── Categories ─────────────────────────────────────────────────

export function getCategories(): Promise<Category[]> { // W5
  return apiFetch<Category[]>('/categories');
}

// ── Accounts ──────────────────────────────────────────────────

export function getAccounts(): Promise<Account[]> {
  return apiFetch<Account[]>('/accounts');
}

export function getNetWorth(): Promise<{ net_worth: number }> {
  return apiFetch<{ net_worth: number }>('/networth');
}

export function getAccountBalanceHistory(
  id: number
): Promise<BalancePoint[]> {
  return apiFetch<BalancePoint[]>(`/accounts/${id}/balance-history`);
}

// ── Recurring ─────────────────────────────────────────────────

export function getRecurring(): Promise<RecurringItem[]> {
  return apiFetch<RecurringItem[]>('/recurring');
}

// ── Review queue ──────────────────────────────────────────────

export function getReview(): Promise<ReviewItem[]> {
  return apiFetch<ReviewItem[]>('/review');
}

// ── Auth ──────────────────────────────────────────────────────

export async function unlock(pin: string): Promise<string> {
  const data = await apiFetch<{ token: string }>('/auth/unlock', {
    method: 'POST',
    body: JSON.stringify({ pin }),
  });
  setToken(data.token);
  return data.token;
}

// ── Export helpers ────────────────────────────────────────────

export async function downloadTransactionsCsv(): Promise<void> {
  const res = await fetch(`${BASE_URL}/export/transactions.csv`, {
    headers: { ...authHeaders() },
  });
  if (res.status === 401) throw new LockedError();
  if (!res.ok) throw new Error('export failed');
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'transactions.csv';
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
