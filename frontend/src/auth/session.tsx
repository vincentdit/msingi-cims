import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { clearSession, getSessionUser, getToken, setSession, type Realm } from '../api/client';
import type { TokenResponse } from '../api/types';
import { setLanguage } from '../i18n';

type User = TokenResponse['user'];

interface SessionCtx {
  user(realm: Realm): User | null;
  signIn(realm: Realm, t: TokenResponse): void;
  signOut(realm: Realm): void;
}

const Ctx = createContext<SessionCtx | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [, setVersion] = useState(0);
  const bump = () => setVersion((v) => v + 1);
  // a session the server has ended (expired, or taken back by the administrator) sends the user to sign in
  useEffect(() => { window.addEventListener('cims:signed-out', bump); return () => window.removeEventListener('cims:signed-out', bump); }, []);

  const user = useCallback((realm: Realm) => (getToken(realm) ? getSessionUser<User>(realm) : null), []);
  const signIn = useCallback((realm: Realm, t: TokenResponse) => {
    setSession(realm, t.accessToken, t.expiresIn, t.user);
    // the language kept with the account follows the person to this device (BR-40)
    if (t.user?.language === 'en' || t.user?.language === 'sw') setLanguage(t.user.language);
    bump();
  }, []);
  const signOut = useCallback((realm: Realm) => {
    clearSession(realm);
    bump();
  }, []);

  return <Ctx.Provider value={{ user, signIn, signOut }}>{children}</Ctx.Provider>;
}

export function useSession() {
  const v = useContext(Ctx);
  if (!v) throw new Error('useSession outside SessionProvider');
  return v;
}

export function RequireAuth({ realm, children }: { realm: Realm; children: ReactNode }) {
  const { user } = useSession();
  const location = useLocation();
  if (!user(realm)) {
    const login = realm === 'staff' ? '/staff/login' : realm === 'student' ? '/student/login' : realm === 'platform' ? '/platform/login' : '/login';
    return <Navigate to={login} replace state={{ from: location.pathname + location.search }} />;
  }
  return <>{children}</>;
}
