// The JWT is kept in sessionStorage: it survives a page refresh but is cleared
// when the tab closes. Trade-off: any script running on the page (XSS) can read
// it. A stronger option is an HttpOnly cookie set by the backend; see README.
const KEY = 'classos.token';

export const tokenStore = {
  get() {
    try {
      return sessionStorage.getItem(KEY);
    } catch {
      return null;
    }
  },
  set(token) {
    try {
      sessionStorage.setItem(KEY, token);
    } catch {
      /* storage unavailable: user stays signed in until refresh */
    }
  },
  clear() {
    try {
      sessionStorage.removeItem(KEY);
    } catch {
      /* ignore */
    }
  },
};
