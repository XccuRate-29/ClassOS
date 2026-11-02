import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { api, setUnauthorizedHandler } from '../api/client.js';
import { tokenStore } from '../api/tokenStore.js';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  // 'loading' only while restoring a session from a stored token.
  const [status, setStatus] = useState(() => (tokenStore.get() ? 'loading' : 'ready'));

  const logout = useCallback(() => {
    tokenStore.clear();
    setUser(null);
    setStatus('ready');
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(logout);
  }, [logout]);

  // On page load, turn a stored token back into a user. The server is asked
  // (rather than decoding the token here) so a deactivated account is caught.
  useEffect(() => {
    if (!tokenStore.get()) return;
    let cancelled = false;
    api
      .get('/auth/me')
      .then((me) => {
        if (!cancelled) {
          setUser(me);
          setStatus('ready');
        }
      })
      .catch(() => {
        if (!cancelled) logout();
      });
    return () => {
      cancelled = true;
    };
  }, [logout]);

  const login = useCallback(async (email, password) => {
    const res = await api.post('/auth/login', { email, password });
    tokenStore.set(res.accessToken);
    setUser(res.user);
    setStatus('ready');
    return res.user;
  }, []);

  const value = useMemo(() => ({ user, status, login, logout }), [user, status, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
