/**
 * Format a number as Indian Rupees (no paise).
 * e.g. 125000 → "₹1,25,000"
 */
export function inr(n: number): string {
  return (
    '₹' +
    new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 }).format(n)
  );
}

/**
 * Format a number as Indian Rupees with paise (2 decimal places).
 * e.g. 125000.50 → "₹1,25,000.50"
 */
export function inrPaise(n: number): string {
  return (
    '₹' +
    new Intl.NumberFormat('en-IN', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(n)
  );
}
