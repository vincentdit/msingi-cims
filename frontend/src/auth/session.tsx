import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { clearSession, getSessionUser, getToken, setSession, type Realm } from '../api/client';
import type { TokenResponse } from '../api/types';

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

  const user = useCallback((realm: Realm) => (getToken(realm) ? getSessionUser<User>(realm) : null), []);
  const signIn = useCallback((realm: Realm, t: TokenResponse) => {
    setSession(realm, t.accessToken, t.expiresIn, t.user);
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
    return <Navigate to={realm === 'staff' ? '/staff/login' : realm === 'student' ? '/student/login' : '/login'} replace state={{ from: location.pathname }} />;
  }
  return <>{children}</>;
}
