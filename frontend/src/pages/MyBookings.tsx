import React, { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import Navbar from '../components/Navbar';
import { BookingAPI, Session, errorMessage } from '../services/api';
import type { BookingDto } from '../services/api';

const STATUS_COLORS: Record<BookingDto['status'], string> = {
  CONFIRMED: '#22c55e',
  PENDING: '#f59e0b',
  CANCELLED: '#94a3b8',
  EXPIRED: '#ef4444',
};

const fmt = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : '');

const MyBookings: React.FC = () => {
  const navigate = useNavigate();
  const loggedIn = Session.get() !== null; // boolean, so the effect below doesn't re-run every render
  const [bookings, setBookings] = useState<BookingDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [message, setMessage] = useState('');

  const load = useCallback(async () => {
    const s = Session.get();
    if (!s) return;
    try {
      setBookings(await BookingAPI.history(s.userId));
    } catch (e) {
      setMessage(errorMessage(e, 'Could not load your bookings'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    window.scrollTo(0, 0);
    if (!loggedIn) {
      navigate('/login', { state: { from: '/my-bookings' }, replace: true });
      return;
    }
    load();
  }, [loggedIn, navigate, load]);

  const act = async (id: number, fn: (id: number) => Promise<unknown>, failText: string) => {
    setBusyId(id);
    setMessage('');
    try {
      await fn(id);
    } catch (e) {
      setMessage(errorMessage(e, failText));
    } finally {
      setBusyId(null);
      load();
    }
  };

  return (
    <main style={{ background: 'var(--bg-dark)', minHeight: '100vh', paddingBottom: '6rem' }}>
      <Navbar />
      <div className="container" style={{ paddingTop: '8rem', maxWidth: '900px' }}>
        <h1 style={{ fontSize: '2.5rem', letterSpacing: '-1px', marginBottom: '2rem' }}>My Bookings</h1>

        {message && <div style={{ color: '#fbbf24', marginBottom: '1.5rem', fontWeight: 500 }}>{message}</div>}
        {loading && <div style={{ color: 'var(--text-muted)' }}>Loading…</div>}
        {!loading && bookings.length === 0 && (
          <div style={{ color: 'var(--text-muted)' }}>
            You have no bookings yet. <Link to="/" style={{ color: 'var(--primary)' }}>Browse movies</Link>
          </div>
        )}

        <div style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>
          {bookings.map((b) => (
            <div key={b.id} className="glass-effect" style={{ padding: '1.5rem 2rem', borderRadius: '20px', border: '1px solid var(--glass-border)' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '1rem', flexWrap: 'wrap' }}>
                <div>
                  <div style={{ fontSize: '1.3rem', fontWeight: 700 }}>{b.movieTitle ?? `Show #${b.showId}`}</div>
                  <div style={{ color: 'var(--text-muted)', marginTop: '0.25rem' }}>
                    {b.showDate} {b.startTime ? String(b.startTime).slice(0, 5) : ''} · Booking #{b.id}
                  </div>
                </div>
                <span style={{
                  color: STATUS_COLORS[b.status], border: `1px solid ${STATUS_COLORS[b.status]}`,
                  padding: '4px 14px', borderRadius: '100px', fontSize: '0.8rem', fontWeight: 700,
                }}>
                  {b.status}
                </span>
              </div>

              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '1rem', gap: '1rem', flexWrap: 'wrap' }}>
                <div>
                  <div style={{ fontWeight: 600 }}>Seats: {b.seats.map((s) => s.seatNumber).join(', ')}</div>
                  <div style={{ color: 'var(--text-muted)', fontSize: '0.85rem', marginTop: '0.25rem' }}>
                    Total ₹{Number(b.totalPrice)}
                    {b.status === 'CONFIRMED' && ` · Confirmed ${fmt(b.confirmedAt)}`}
                    {b.status === 'CANCELLED' && ` · Cancelled ${fmt(b.cancelledAt)}`}
                    {b.status === 'PENDING' && ` · Hold ends ${fmt(b.holdExpiresAt)}`}
                    {b.status === 'EXPIRED' && ` · Hold expired ${fmt(b.holdExpiresAt)}`}
                  </div>
                </div>

                <div style={{ display: 'flex', gap: '0.75rem' }}>
                  {b.status === 'PENDING' && (
                    <button className="btn-primary" disabled={busyId === b.id}
                      onClick={() => act(b.id, BookingAPI.confirm, 'Could not confirm booking')}
                      style={{ padding: '0.6rem 1.5rem', borderRadius: '12px' }}>
                      Confirm
                    </button>
                  )}
                  {(b.status === 'PENDING' || b.status === 'CONFIRMED') && (
                    <button className="btn-glass" disabled={busyId === b.id}
                      onClick={() => act(b.id, BookingAPI.cancel, 'Could not cancel booking')}
                      style={{ padding: '0.6rem 1.5rem', borderRadius: '12px' }}>
                      Cancel
                    </button>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </main>
  );
};

export default MyBookings;
