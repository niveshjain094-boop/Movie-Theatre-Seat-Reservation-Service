import axios from 'axios';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api';

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

// ---- auth session (JWT kept in localStorage) -------------------------------
const SESSION_KEY = 'bms.session';

export interface Session {
  accessToken: string;
  userId: number;
  email: string;
  role: string;
}

export const Session = {
  get(): Session | null {
    try {
      const raw = localStorage.getItem(SESSION_KEY);
      return raw ? (JSON.parse(raw) as Session) : null;
    } catch {
      return null;
    }
  },
  set(s: Session) {
    localStorage.setItem(SESSION_KEY, JSON.stringify(s));
  },
  clear() {
    localStorage.removeItem(SESSION_KEY);
  },
};

// Attach the JWT to every request; drop a stale session on 401.
api.interceptors.request.use((config) => {
  const s = Session.get();
  if (s?.accessToken) config.headers.Authorization = `Bearer ${s.accessToken}`;
  return config;
});
api.interceptors.response.use(
  (r) => r,
  (error) => {
    if (error?.response?.status === 401) Session.clear();
    return Promise.reject(error);
  }
);

/** Pulls the backend's RFC 7807 `detail` out of an axios error. */
export const errorMessage = (e: any, fallback = 'Something went wrong'): string =>
  e?.response?.data?.detail ?? e?.response?.data?.message ?? (e?.message || fallback);

// ---- types -----------------------------------------------------------------
export type SeatHoldStatus = 'AVAILABLE' | 'HELD' | 'BOOKED';

export interface SeatStatus {
  seatId: number;
  seatNumber: string;
  row: string | null;
  col: number | null;
  seatType: string | null;
  status: SeatHoldStatus;
  holdExpiresAt: string | null;
}

export interface BookingDto {
  id: number;
  userId: number;
  showId: number;
  movieTitle: string | null;
  showDate: string | null;
  startTime: string | null;
  seats: { id: number; seatNumber: string }[];
  totalPrice: number;
  status: 'PENDING' | 'CONFIRMED' | 'CANCELLED' | 'EXPIRED';
  bookedAt: string | null;
  holdExpiresAt: string | null;
  confirmedAt: string | null;
  cancelledAt: string | null;
}

// ---- endpoints -------------------------------------------------------------
export const MovieAPI = {
  getAll: async () => {
    // Backend returns a Spring Page ({ content: [...] }); the pages expect a plain array.
    const response = await api.get('/movies', { params: { size: 100 } });
    return response.data?.content ?? response.data;
  },
  getById: async (id: number) => {
    const response = await api.get(`/movies/${id}`);
    return response.data;
  },
  search: async (query: string) => {
    const response = await api.get(`/movies/search?title=${encodeURIComponent(query)}`);
    return response.data?.content ?? response.data;
  },
};

export const CityAPI = {
  getAll: async () => {
    const response = await api.get('/cities');
    return response.data;
  },
};

export const AuthAPI = {
  login: async (email: string, password: string): Promise<Session> => {
    const { data } = await api.post('/users/login', { email, password });
    const s: Session = {
      accessToken: data.accessToken,
      userId: data.userId,
      email: data.email,
      role: data.role,
    };
    Session.set(s);
    return s;
  },
  register: async (name: string, email: string, password: string) => {
    const { data } = await api.post('/users/register', { name, email, password });
    return data;
  },
  logout: () => Session.clear(),
};

export const ShowAPI = {
  getById: async (id: number) => (await api.get(`/shows/${id}`)).data,
  getByMovie: async (movieId: number) => (await api.get(`/shows/movie/${movieId}`)).data,
};

export const BookingAPI = {
  seats: async (showId: number): Promise<SeatStatus[]> =>
    (await api.get(`/bookings/show/${showId}/seats`)).data,
  hold: async (userId: number, showId: number, seatIds: number[]): Promise<BookingDto> =>
    (await api.post('/bookings/hold', { userId, showId, seatIds })).data,
  confirm: async (bookingId: number): Promise<BookingDto> =>
    (await api.post(`/bookings/${bookingId}/confirm`)).data,
  cancel: async (bookingId: number): Promise<BookingDto> =>
    (await api.put(`/bookings/${bookingId}/cancel`)).data,
  // Backend returns a Spring Page, newest first; unwrap to a plain array.
  history: async (userId: number): Promise<BookingDto[]> => {
    const { data } = await api.get(`/bookings/user/${userId}`, { params: { size: 100 } });
    return data?.content ?? data;
  },
};

export default api;
