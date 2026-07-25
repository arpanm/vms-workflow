import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";

import {
  sessionClient,
  type AuthenticatedUser,
} from "@/lib/auth/session-client";

type SessionContextValue = {
  user: AuthenticatedUser | null;
  loading: boolean;
  error: Error | null;
  refresh: () => Promise<void>;
  signIn: (returnTo?: string) => void;
  signOut: () => Promise<void>;
};

const SessionContext = createContext<SessionContextValue | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthenticatedUser | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setUser(await sessionClient.getCurrentUser());
    } catch (cause) {
      setUser(null);
      setError(
        cause instanceof Error ? cause : new Error("Unable to resolve the session."),
      );
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const value = useMemo<SessionContextValue>(
    () => ({
      user,
      loading,
      error,
      refresh,
      signIn: (returnTo) => sessionClient.beginLogin(returnTo),
      signOut: async () => {
        await sessionClient.logout();
        setUser(null);
      },
    }),
    [error, loading, refresh, user],
  );

  return (
    <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
  );
}

// The hook intentionally shares the provider's module-private context.
// eslint-disable-next-line react-refresh/only-export-components
export function useSession() {
  const session = useContext(SessionContext);
  if (!session) {
    throw new Error("useSession must be used inside SessionProvider.");
  }
  return session;
}
