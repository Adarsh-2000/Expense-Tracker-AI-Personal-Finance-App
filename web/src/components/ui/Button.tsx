import React from 'react';

type ButtonVariant = 'primary' | 'ghost' | 'danger';
type ButtonSize = 'sm' | 'md' | 'lg';

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  fullWidth?: boolean;
  children: React.ReactNode;
}

export function Button({
  variant = 'primary',
  size = 'md',
  loading = false,
  fullWidth = false,
  children,
  disabled,
  className = '',
  style,
  ...rest
}: ButtonProps) {
  const cls = [
    'ln-btn',
    `ln-btn--${variant}`,
    `ln-btn--${size}`,
    fullWidth ? 'ln-btn--full' : '',
    loading ? 'ln-btn--loading' : '',
    className,
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <>
      <button
        className={cls}
        disabled={disabled || loading}
        style={style}
        {...rest}
      >
        {loading ? (
          <span className="ln-btn__spinner" aria-hidden />
        ) : null}
        <span className={loading ? 'ln-btn__label--loading' : ''}>
          {children}
        </span>
      </button>
      <style>{`
        .ln-btn {
          display: inline-flex;
          align-items: center;
          justify-content: center;
          gap: 8px;
          font-family: var(--font-body, 'Satoshi', sans-serif);
          font-weight: 700;
          letter-spacing: 0.01em;
          border-radius: 12px;
          border: none;
          cursor: pointer;
          transition: opacity 150ms ease, transform 150ms ease, box-shadow 150ms ease;
          position: relative;
          white-space: nowrap;
          text-decoration: none;
          -webkit-user-select: none;
          user-select: none;
        }
        .ln-btn:focus-visible {
          outline: 2px solid rgba(45, 212, 191, 0.7);
          outline-offset: 2px;
        }
        .ln-btn:active:not(:disabled) {
          transform: scale(0.97);
        }
        .ln-btn:disabled {
          opacity: 0.4;
          cursor: not-allowed;
        }

        /* Sizes */
        .ln-btn--sm {
          font-size: 13px;
          padding: 6px 14px;
          height: 32px;
          border-radius: 8px;
        }
        .ln-btn--md {
          font-size: 14px;
          padding: 10px 20px;
          height: 42px;
        }
        .ln-btn--lg {
          font-size: 15px;
          padding: 14px 28px;
          height: 52px;
          border-radius: 14px;
        }
        .ln-btn--full {
          width: 100%;
        }

        /* Variants */
        .ln-btn--primary {
          background: linear-gradient(135deg, #2DD4BF, #34D399);
          color: #0A0D12;
          box-shadow: 0 4px 16px rgba(45, 212, 191, 0.25);
        }
        .ln-btn--primary:hover:not(:disabled) {
          opacity: 0.9;
          box-shadow: 0 6px 24px rgba(45, 212, 191, 0.35);
        }

        .ln-btn--ghost {
          background: rgba(255, 255, 255, 0.05);
          color: var(--color-text-primary, #E8EEF2);
          border: 1px solid rgba(255, 255, 255, 0.1);
        }
        .ln-btn--ghost:hover:not(:disabled) {
          background: rgba(255, 255, 255, 0.08);
          border-color: rgba(45, 212, 191, 0.3);
          color: #2DD4BF;
        }

        .ln-btn--danger {
          background: rgba(244, 63, 94, 0.15);
          color: #F43F5E;
          border: 1px solid rgba(244, 63, 94, 0.3);
        }
        .ln-btn--danger:hover:not(:disabled) {
          background: rgba(244, 63, 94, 0.25);
        }

        /* Loading */
        .ln-btn--loading {
          cursor: wait;
        }
        .ln-btn__label--loading {
          opacity: 0.4;
        }
        .ln-btn__spinner {
          width: 14px;
          height: 14px;
          border: 2px solid currentColor;
          border-top-color: transparent;
          border-radius: 50%;
          animation: ln-spin 0.6s linear infinite;
          flex-shrink: 0;
        }
        @keyframes ln-spin {
          to { transform: rotate(360deg); }
        }
      `}</style>
    </>
  );
}
