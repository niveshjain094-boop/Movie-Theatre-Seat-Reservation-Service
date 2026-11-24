import React, { useCallback, useEffect, useMemo, useState } from 'react';
import Navbar from '../components/Navbar';
import { Armchair, ChevronLeft, CreditCard, Info, Timer } from 'lucide-react';
import { useNavigate, useParams } from 'react-router-dom';
import { BookingAPI, ShowAPI, Session, errorMessage } from '../services/api';
import type { BookingDto, SeatStatus } from '../services/api';

const SeatSelection: React.FC = () => {
  const { showId } = useParams<{ showId: string }>();
  const navigate = useNavigate();
  const sid = Number(showId);

  const [show, setShow] = useState<any>(null);
  const [seats, setSeats] = useState<SeatStatus[]>([]);
  const [selected, setSelected] = useState<number[]>([]);      // seatIds picked locally
  const [hold, setHold] = useState<BookingDto | null>(null);   // active PENDING booking
  const [confirmed, setConfirmed] = useState<BookingDto | null>(null);
  const [now, setNow] = useState(Date.now());
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');

  useEffect(() => { window.scrollTo(0, 0); }, []);

  const loadSeats = useCallback(async () => {
    try { setSeats(await BookingAPI.seats(sid)); }
    catch (e) { setMessage(errorMessage(e, 'Could not load seats')); }
  }, [sid]);

  useEffect(() => {
    ShowAPI.getById(sid).then(setShow).catch((e) => setMessage(errorMessage(e, 'Show not found')));
    loadSeats();
    const poll = setInterval(loadSeats, 10000);   // pick up holds/bookings made by others
    return () => clearInterval(poll);
  }, [sid, loadSeats]);

  // 1s ticker for the hold countdown
  useEffect(() => {
    if (!hold) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [hold]);

  const secondsLeft = hold?.holdExpiresAt
    ? Math.max(0, Math.floor((new Date(hold.holdExpiresAt).getTime() - now) / 1000))
    : 0;

  // Hold lapsed on the client: the server has already treated it as released.
  useEffect(() => {
    if (hold && secondsLeft === 0) {
      setHold(null);
      setSelected([]);
      setMessage('Your 5-minute hold expired. Please select your seats again.');
      loadSeats();
    }
  }, [hold, secondsLeft, loadSeats]);

  const rows = useMemo(() => {
    const byRow = new Map<string, SeatStatus[]>();
    seats.forEach((s) => {
      const key = s.row ?? '-';
      byRow.set(key, [...(byRow.get(key) ?? []), s]);
    });
    byRow.forEach((list) => list.sort((a, b) => (a.col ?? 0) - (b.col ?? 0) || a.seatId - b.seatId));
    return [...byRow.entries()].sort(([a], [b]) => a.localeCompare(b));
  }, [seats]);

  const price = show?.ticketPrice ?? 0;
  const seatLabel = (id: number) => seats.find((s) => s.seatId === id)?.seatNumber ?? String(id);

  const toggleSeat = (seat: SeatStatus) => {
    if (hold || seat.status !== 'AVAILABLE') return;
    setMessage('');
    setSelected((cur) => (cur.includes(seat.seatId) ? cur.filter((x) => x !== seat.seatId) : [...cur, seat.seatId]));
  };

  const requireLogin = (): number | null => {
    const session = Session.get();
    if (!session) {
      navigate('/login', { state: { from: `/book/${sid}` } });
      return null;
    }
    return session.userId;
  };

  const holdSeats = async () => {
    const userId = requireLogin();
    if (userId === null) return;
    setBusy(true); setMessage('');
    try {
      setHold(await BookingAPI.hold(userId, sid, selected));
      setNow(Date.now());
    } catch (e) {
      setMessage(errorMessage(e, 'Could not hold these seats'));
      setSelected([]);
    } finally {
      setBusy(false);
      loadSeats();
    }
  };

  const confirm = async () => {
    if (!hold) return;
    setBusy(true); setMessage('');
    try {
      setConfirmed(await BookingAPI.confirm(hold.id));
      setHold(null); setSelected([]);
    } catch (e: any) {
      setMessage(errorMessage(e, 'Could not confirm booking'));
      if (e?.response?.status === 409) { setHold(null); setSelected([]); }
    } finally {
      setBusy(false);
      loadSeats();
    }
  };

  const release = async () => {
    if (!hold) return;
    setBusy(true);
    try { await BookingAPI.cancel(hold.id); }
    catch (e) { setMessage(errorMessage(e, 'Could not release seats')); }
    finally { setHold(null); setSelected([]); setBusy(false); loadSeats(); }
  };

  const cancelConfirmed = async () => {
    if (!confirmed) return;
    setBusy(true);
    try {
      await BookingAPI.cancel(confirmed.id);
      setConfirmed(null);
      setMessage('Booking cancelled. Your seats have been released.');
    } catch (e) { setMessage(errorMessage(e, 'Could not cancel booking')); }
    finally { setBusy(false); loadSeats(); }
  };

  const mmss = `${String(Math.floor(secondsLeft / 60)).padStart(2, '0')}:${String(secondsLeft % 60).padStart(2, '0')}`;
  const barCount = hold ? hold.seats.length : selected.length;
  const barLabels = hold ? hold.seats.map((s) => s.seatNumber) : selected.map(seatLabel);
  const barTotal = hold ? Number(hold.totalPrice) : selected.length * price;

  return (
    <main style={{ background: 'var(--bg-dark)', minHeight: '100vh', paddingBottom: '10rem' }}>
      <Navbar />

      <div className="container" style={{ paddingTop: '8rem' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '4rem' }}>
          <button onClick={() => navigate(-1)} style={{ color: 'white', background: 'none', display: 'flex', alignItems: 'center', gap: '0.5rem', fontWeight: 500, opacity: 0.8 }}>
            <ChevronLeft size={20} /> Back
          </button>

          <div style={{ textAlign: 'center' }}>
            <h1 style={{ fontSize: '2.5rem', letterSpacing: '-1px', marginBottom: '0.5rem' }}>{show?.movie?.title ?? 'Loading…'}</h1>
            <p style={{ color: 'var(--text-muted)' }}>
              {[show?.screen?.theater?.name, show?.screen?.name, show && `${show.showDate} ${String(show.startTime).slice(0, 5)}`]
                .filter(Boolean).join(' | ')}
            </p>
          </div>

          <div style={{ width: '80px' }}></div>
        </div>

        {message && (
          <div style={{ textAlign: 'center', marginBottom: '2rem', color: '#fbbf24', fontWeight: 500 }}>{message}</div>
        )}

        {confirmed && (
          <div className="glass-effect" style={{ padding: '1.5rem 2rem', borderRadius: '20px', marginBottom: '3rem', textAlign: 'center' }}>
            <h2 style={{ marginBottom: '0.5rem' }}>Booking #{confirmed.id} confirmed 🎉</h2>
            <p style={{ color: 'var(--text-muted)', marginBottom: '1rem' }}>
              Seats {confirmed.seats.map((s) => s.seatNumber).join(', ')} · ₹{Number(confirmed.totalPrice)}
            </p>
            <button className="btn-glass" disabled={busy} onClick={cancelConfirmed} style={{ padding: '0.6rem 1.5rem', borderRadius: '12px' }}>
              Cancel this booking
            </button>
          </div>
        )}

        {/* Screen */}
        <div style={{ textAlign: 'center', marginBottom: '6rem' }}>
          <div style={{
            width: '85%', height: '8px',
            background: 'linear-gradient(to right, transparent, var(--primary), transparent)',
            boxShadow: '0 15px 30px rgba(225, 29, 72, 0.5)',
            margin: '0 auto 1.5rem', borderRadius: '100%'
          }}></div>
          <span style={{ color: 'var(--text-muted)', fontSize: '0.8rem', letterSpacing: '4px', fontWeight: 600 }}>SCREEN THIS WAY</span>
        </div>

        {/* Seat Grid (from the backend) */}
        <div style={{
          display: 'flex', flexDirection: 'column', gap: '1rem', alignItems: 'center',
          padding: '2rem', background: 'rgba(255,255,255,0.02)', borderRadius: '32px',
          border: '1px solid var(--glass-border)', overflowX: 'auto'
        }}>
          {rows.map(([row, list]) => (
            <div key={row} style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
              <span style={{ width: '2rem', color: 'var(--text-muted)', fontWeight: 700, fontSize: '0.9rem' }}>{row}</span>
              <div style={{ display: 'flex', gap: '0.75rem' }}>
                {list.map((seat) => {
                  const mine = selected.includes(seat.seatId) || (hold?.seats.some((s) => s.id === seat.seatId) ?? false);
                  const booked = seat.status === 'BOOKED';
                  const held = seat.status === 'HELD' && !mine;
                  const blocked = booked || held;
                  return (
                    <button
                      key={seat.seatId}
                      disabled={blocked || !!hold}
                      onClick={() => toggleSeat(seat)}
                      title={`${seat.seatNumber}${held ? ' (held by someone)' : booked ? ' (booked)' : ''}`}
                      style={{
                        width: '36px', height: '36px', borderRadius: '10px',
                        background: booked ? '#1e293b' : held ? '#78350f' : mine ? 'var(--primary)' : 'transparent',
                        border: blocked ? 'none' : mine ? 'none' : '1px solid var(--glass-border)',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        transition: 'var(--transition)',
                        cursor: blocked || hold ? 'not-allowed' : 'pointer',
                        transform: mine ? 'scale(1.1)' : 'scale(1)'
                      }}
                      className={!blocked && !hold ? 'hover-scale' : ''}
                    >
                      <Armchair size={18} color={booked ? '#475569' : held ? '#fbbf24' : mine ? 'white' : 'var(--text-muted)'} />
                    </button>
                  );
                })}
              </div>
            </div>
          ))}
          {seats.length === 0 && <span style={{ color: 'var(--text-muted)' }}>No seats found for this show.</span>}
        </div>

        {/* Legend */}
        <div style={{ display: 'flex', justifyContent: 'center', gap: '3rem', marginTop: '5rem' }}>
          {[
            { label: 'Available', color: 'transparent', border: '1px solid var(--glass-border)' },
            { label: 'Selected', color: 'var(--primary)', border: 'none' },
            { label: 'Held', color: '#78350f', border: 'none' },
            { label: 'Booked', color: '#1e293b', border: 'none' },
          ].map((item) => (
            <div key={item.label} style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
              <div style={{ width: '20px', height: '20px', borderRadius: '6px', background: item.color, border: item.border }}></div>
              <span style={{ fontSize: '0.9rem', fontWeight: 500, color: 'var(--text-muted)' }}>{item.label}</span>
            </div>
          ))}
        </div>

        <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', gap: '0.5rem', marginTop: '3rem', color: 'rgba(255,255,255,0.4)', fontSize: '0.85rem' }}>
          <Info size={14} />
          <span>Selected seats are held for 5 minutes. Confirm before the timer ends.</span>
        </div>
      </div>

      {/* Summary bar */}
      {barCount > 0 && (
        <div className="glass-effect animate-fade" style={{
          position: 'fixed', bottom: '2.5rem', left: '50%', transform: 'translateX(-50%)',
          width: 'min(90%, 800px)', padding: '1.25rem 2.5rem', borderRadius: '24px',
          display: 'flex', justifyContent: 'space-between', alignItems: 'center',
          boxShadow: 'var(--shadow-premium)', zIndex: 1001, border: '1px solid rgba(225, 29, 72, 0.3)'
        }}>
          <div style={{ display: 'flex', gap: '2.5rem', alignItems: 'center' }}>
            <div>
              <div style={{ fontSize: '0.85rem', color: 'var(--text-muted)', marginBottom: '0.25rem' }}>{barCount} Seats</div>
              <div style={{ maxHeight: '40px', overflowX: 'auto', color: 'white', fontWeight: 600 }}>{barLabels.join(', ')}</div>
            </div>
            <div style={{ height: '40px', width: '1px', background: 'var(--glass-border)' }}></div>
            <div>
              <div style={{ fontSize: '0.85rem', color: 'var(--text-muted)', marginBottom: '0.25rem' }}>
                {hold ? <span style={{ display: 'inline-flex', alignItems: 'center', gap: '0.35rem' }}><Timer size={14} /> Held for {mmss}</span> : 'Total Amount'}
              </div>
              <div style={{ fontSize: '1.75rem', fontWeight: 800, color: 'var(--primary)' }}>₹{barTotal}</div>
            </div>
          </div>

          {hold ? (
            <div style={{ display: 'flex', gap: '0.75rem' }}>
              <button className="btn-glass" disabled={busy} onClick={release} style={{ padding: '1rem 1.5rem', borderRadius: '16px' }}>Release</button>
              <button className="btn-primary" disabled={busy} onClick={confirm} style={{ padding: '1rem 2rem', fontSize: '1.1rem', borderRadius: '16px' }}>
                Confirm Booking <CreditCard size={20} />
              </button>
            </div>
          ) : (
            <button className="btn-primary" disabled={busy} onClick={holdSeats} style={{ padding: '1rem 3rem', fontSize: '1.1rem', borderRadius: '16px' }}>
              {busy ? 'Holding…' : 'Hold Seats'} <CreditCard size={20} />
            </button>
          )}
        </div>
      )}

      <style>{`
        .hover-scale:hover {
          background: var(--primary-muted) !important;
          border-color: var(--primary) !important;
          transform: scale(1.15) translateY(-2px);
        }
      `}</style>
    </main>
  );
};

export default SeatSelection;
