import React from 'react';

interface CardProps {
  children: React.ReactNode;
  className?: string;
  style?: React.CSSProperties;
  onClick?: () => void;
  /** Pad the inner content (default true) */
  padded?: boolean;
}

export function Card({
  children,
  className = '',
  style,
  onClick,
  padded = true,
}: CardProps) {
  return (
    <div
      className={`ln-card${padded ? ' ln-card--padded' : ''} ${className}`}
      style={style}
      onClick={onClick}
      role={onClick ? 'button' : undefined}
      tabIndex={onClick ? 0 : undefined}
    >
      {children}
      <style>{`
        .ln-card {
          background: rgba(255, 255, 255, 0.03);
          border: 1px solid rgba(255, 255, 255, 0.08);
          border-radius: 20px;
          backdrop-filter: blur(12px);
          -webkit-backdrop-filter: blur(12px);
          box-shadow: 0 4px 24px rgba(0, 0, 0, 0.4),
                      0 1px 0 rgba(255, 255, 255, 0.04) inset;
          transition: border-color 250ms cubic-bezier(0.4, 0, 0.2, 1),
                      box-shadow 250ms cubic-bezier(0.4, 0, 0.2, 1);
          position: relative;
        }
        .ln-card--padded {
          padding: 24px;
        }
        .ln-card:hover {
          border-color: rgba(45, 212, 191, 0.35);
          box-shadow: 0 0 0 1px rgba(45, 212, 191, 0.15),
                      0 4px 24px rgba(0, 0, 0, 0.4),
                      0 1px 0 rgba(255, 255, 255, 0.04) inset;
        }
        .ln-card[role="button"] {
          cursor: pointer;
        }
        .ln-card[role="button"]:focus-visible {
          outline: 2px solid rgba(45, 212, 191, 0.6);
          outline-offset: 2px;
        }
      `}</style>
    </div>
  );
}
