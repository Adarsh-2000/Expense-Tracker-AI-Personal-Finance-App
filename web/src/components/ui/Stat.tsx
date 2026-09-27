import React from 'react';

interface StatProps {
  label: string;
  value: string | React.ReactNode;
  /** Optional sub-label or trend text */
  sub?: string | React.ReactNode;
  /** Colour treatment for the value */
  tone?: 'neutral' | 'credit' | 'debit' | 'warning' | 'danger';
  /** Display value in small text rather than large */
  compact?: boolean;
}

const toneColor: Record<NonNullable<StatProps['tone']>, string> = {
  neutral: 'var(--color-text-primary, #E8EEF2)',
  credit: 'var(--color-credit, #34D399)',
  debit: 'var(--color-debit, #FB7185)',
  warning: 'var(--color-warning, #FBBF24)',
  danger: 'var(--color-danger, #F43F5E)',
};

export function Stat({
  label,
  value,
  sub,
  tone = 'neutral',
  compact = false,
}: StatProps) {
  const color = toneColor[tone];

  return (
    <>
      <div className="ln-stat">
        <span className="ln-stat__label">{label}</span>
        <span
          className={`ln-stat__value tnum ${compact ? 'ln-stat__value--compact' : ''}`}
          style={{ color }}
        >
          {value}
        </span>
        {sub && <span className="ln-stat__sub">{sub}</span>}
      </div>
      <style>{`
        .ln-stat {
          display: flex;
          flex-direction: column;
          gap: 4px;
        }
        .ln-stat__label {
          font-family: var(--font-body, 'Satoshi', sans-serif);
          font-size: 11px;
          font-weight: 500;
          letter-spacing: 0.08em;
          text-transform: uppercase;
          color: var(--color-text-faint, #5A6675);
        }
        .ln-stat__value {
          font-family: var(--font-display, 'Clash Display', serif);
          font-size: 2rem;
          font-weight: 700;
          line-height: 1.1;
          letter-spacing: -0.03em;
          font-feature-settings: "tnum" 1;
          font-variant-numeric: tabular-nums;
        }
        .ln-stat__value--compact {
          font-size: 1.25rem;
        }
        .ln-stat__sub {
          font-size: 12px;
          color: var(--color-text-muted, #8A97A6);
          font-variant-numeric: tabular-nums;
        }
      `}</style>
    </>
  );
}
