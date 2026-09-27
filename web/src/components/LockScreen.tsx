import React, { useState, useRef, useEffect } from 'react';
import { unlock, LockedError } from '../lib/api';
import { Card } from './ui/Card';
import { Button } from './ui/Button';

interface LockScreenProps {
  onUnlock: () => void;
}

export function LockScreen({ onUnlock }: LockScreenProps) {
  const [pin, setPin] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  async function handleUnlock(e: React.FormEvent) {
    e.preventDefault();
    if (!pin) return;
    setError('');
    setLoading(true);
    try {
      await unlock(pin);
      onUnlock();
    } catch (err) {
      if (err instanceof LockedError) {
        setError('Incorrect PIN. Try again.');
      } else {
        setError('Could not reach server. Is it running?');
      }
      setPin('');
      inputRef.current?.focus();
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      <div className="ls-bg">
        <div className="ls-center">
          <Card padded={false} className="ls-card">
            {/* Wordmark */}
            <div className="ls-wordmark">
              <span className="ls-wordmark__text gradient-text">Ledger Noir</span>
              <span className="ls-wordmark__sub">Personal Finance, Privately Kept</span>
            </div>

            {/* Lock icon */}
            <div className="ls-lock-icon" aria-hidden>
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
                <path d="M7 11V7a5 5 0 0 1 10 0v4" />
              </svg>
            </div>

            {/* PIN form */}
            <form className="ls-form" onSubmit={handleUnlock}>
              <label className="ls-label" htmlFor="pin-input">
                Enter PIN
              </label>
              <input
                ref={inputRef}
                id="pin-input"
                className={`ls-input tnum${error ? ' ls-input--error' : ''}`}
                type="password"
                inputMode="numeric"
                pattern="[0-9]*"
                maxLength={12}
                value={pin}
                onChange={(e) => {
                  setPin(e.target.value.replace(/\D/g, ''));
                  setError('');
                }}
                placeholder="••••••"
                autoComplete="current-password"
                disabled={loading}
              />
              {error && (
                <p className="ls-error" role="alert">
                  {error}
                </p>
              )}
              <Button
                type="submit"
                variant="primary"
                size="lg"
                fullWidth
                loading={loading}
                disabled={!pin || loading}
              >
                Unlock
              </Button>
            </form>

            {/* Trust note */}
            <p className="ls-trust">
              <span className="ls-trust__dot" aria-hidden>●</span>
              Your data stays on your own server.
            </p>
          </Card>
        </div>
      </div>

      <style>{`
        .ls-bg {
          min-height: 100vh;
          display: flex;
          align-items: center;
          justify-content: center;
          padding: 24px;
        }

        .ls-center {
          width: 100%;
          max-width: 400px;
        }

        .ls-card {
          padding: 48px 40px 36px;
          display: flex;
          flex-direction: column;
          align-items: center;
          gap: 28px;
          border-color: rgba(45, 212, 191, 0.12) !important;
          box-shadow: 0 0 80px rgba(45, 212, 191, 0.06),
                      0 4px 40px rgba(0, 0, 0, 0.6) !important;
        }

        .ls-wordmark {
          text-align: center;
          display: flex;
          flex-direction: column;
          gap: 6px;
        }
        .ls-wordmark__text {
          font-family: var(--font-display, 'Clash Display', serif);
          font-size: 2.25rem;
          font-weight: 700;
          letter-spacing: -0.03em;
          line-height: 1;
        }
        .ls-wordmark__sub {
          font-size: 12px;
          color: var(--color-text-faint, #5A6675);
          letter-spacing: 0.04em;
        }

        .ls-lock-icon {
          color: var(--color-teal, #2DD4BF);
          opacity: 0.7;
        }

        .ls-form {
          width: 100%;
          display: flex;
          flex-direction: column;
          gap: 12px;
        }

        .ls-label {
          font-size: 11px;
          font-weight: 700;
          letter-spacing: 0.1em;
          text-transform: uppercase;
          color: var(--color-text-faint, #5A6675);
        }

        .ls-input {
          width: 100%;
          height: 52px;
          background: rgba(255, 255, 255, 0.04);
          border: 1px solid rgba(255, 255, 255, 0.1);
          border-radius: 12px;
          color: var(--color-text-primary, #E8EEF2);
          font-size: 1.5rem;
          letter-spacing: 0.3em;
          text-align: center;
          padding: 0 16px;
          transition: border-color 200ms ease, box-shadow 200ms ease;
          outline: none;
        }
        .ls-input::placeholder {
          color: rgba(90, 102, 117, 0.5);
          letter-spacing: 0.15em;
        }
        .ls-input:focus {
          border-color: rgba(45, 212, 191, 0.5);
          box-shadow: 0 0 0 3px rgba(45, 212, 191, 0.1);
        }
        .ls-input--error {
          border-color: rgba(251, 113, 133, 0.5) !important;
        }

        .ls-error {
          font-size: 13px;
          color: var(--color-debit, #FB7185);
          text-align: center;
          animation: ls-shake 0.3s ease;
        }
        @keyframes ls-shake {
          0%, 100% { transform: translateX(0); }
          25% { transform: translateX(-6px); }
          75% { transform: translateX(6px); }
        }

        .ls-trust {
          font-size: 12px;
          color: var(--color-text-faint, #5A6675);
          display: flex;
          align-items: center;
          gap: 6px;
        }
        .ls-trust__dot {
          font-size: 6px;
          color: var(--color-emerald, #34D399);
          line-height: 1;
        }
      `}</style>
    </>
  );
}
