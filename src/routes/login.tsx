import { createFileRoute, Navigate } from "@tanstack/react-router";
import { LogIn, ShieldCheck } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { useSession } from "@/features/auth/session-provider";
import { safeDemoMode } from "@/lib/feature-flags";
import { sessionClient } from "@/lib/auth/session-client";

type LoginSearch = {
  returnTo?: string;
};

export const Route = createFileRoute("/login")({
  validateSearch: (search: Record<string, unknown>): LoginSearch => ({
    returnTo:
      typeof search.returnTo === "string" ? search.returnTo : undefined,
  }),
  head: () => ({
    meta: [
      { title: "Sign in — Cadence" },
      {
        name: "description",
        content: "Sign in through the configured organization identity provider.",
      },
    ],
  }),
  component: LoginPage,
});

function LoginPage() {
  const { returnTo } = Route.useSearch();
  const { user, loading, error, signIn } = useSession();
  const loginConfigured = sessionClient.isLoginConfigured();

  if (safeDemoMode || user) {
    return <Navigate to="/" replace />;
  }

  return (
    <main className="grid min-h-screen place-items-center bg-muted/30 px-4">
      <Card className="w-full max-w-md border-border/60 shadow-[var(--shadow-elevated)]">
        <CardHeader className="space-y-3 text-center">
          <div className="mx-auto grid h-12 w-12 place-items-center rounded-xl bg-primary text-primary-foreground">
            <ShieldCheck className="h-6 w-6" />
          </div>
          <CardTitle>Sign in to Cadence</CardTitle>
          <p className="text-sm text-muted-foreground">
            Continue through your organization&apos;s identity provider. Access
            is determined by backend memberships and permissions.
          </p>
        </CardHeader>
        <CardContent className="space-y-3">
          {!loginConfigured && (
            <p className="rounded-md border border-warning/40 bg-warning/15 p-3 text-sm text-warning-foreground">
              Sign-in is blocked until the deployment configures
              <code className="mx-1 font-mono">VITE_OIDC_LOGIN_PATH</code>
              with a same-origin BFF login endpoint.
            </p>
          )}
          {error && (
            <p className="rounded-md border border-destructive/30 bg-destructive/10 p-3 text-sm text-destructive">
              Session status could not be checked. You can retry through the
              identity provider.
            </p>
          )}
          <Button
            className="w-full gap-2"
            disabled={loading || !loginConfigured}
            onClick={() => signIn(returnTo)}
          >
            <LogIn className="h-4 w-4" />
            {loading
              ? "Checking session…"
              : loginConfigured
                ? "Continue with SSO"
                : "SSO configuration required"}
          </Button>
        </CardContent>
      </Card>
    </main>
  );
}
