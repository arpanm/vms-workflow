import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  HeadContent,
  Link,
  Navigate,
  Outlet,
  createRootRouteWithContext,
  useRouter,
  useRouterState,
} from "@tanstack/react-router";
import { LogOut } from "lucide-react";

import { AppSidebar } from "@/components/app-sidebar";
import { DemoBanner } from "@/components/demo-banner";
import { RoleSwitcher } from "@/components/role-switcher";
import { Button } from "@/components/ui/button";
import { SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar";
import { Toaster } from "@/components/ui/sonner";
import { SessionProvider, useSession } from "@/features/auth/session-provider";
import { safeDemoMode } from "@/lib/feature-flags";

function NotFoundComponent() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="max-w-md text-center">
        <h1 className="text-7xl font-bold">404</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          This page is unavailable or disabled by configuration.
        </p>
        <Link
          to="/"
          className="mt-6 inline-flex rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground"
        >
          Back to dashboard
        </Link>
      </div>
    </div>
  );
}

function ErrorComponent({ error, reset }: { error: Error; reset: () => void }) {
  const router = useRouter();
  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="max-w-md text-center">
        <h1 className="text-xl font-semibold">Something went wrong</h1>
        <p className="mt-2 text-sm text-muted-foreground">{error.message}</p>
        <button
          onClick={() => {
            void router.invalidate();
            reset();
          }}
          className="mt-6 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground"
        >
          Retry
        </button>
      </div>
    </div>
  );
}

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()({
  head: () => ({
    meta: [
      { charSet: "utf-8" },
      { name: "viewport", content: "width=device-width, initial-scale=1" },
      { title: "Cadence — Workforce & Delivery Governance" },
      {
        name: "description",
        content: "Workforce, delivery evidence and month-close governance across organizations.",
      },
    ],
  }),
  component: RootComponent,
  notFoundComponent: NotFoundComponent,
  errorComponent: ErrorComponent,
});

function RootComponent() {
  const { queryClient } = Route.useRouteContext();

  return (
    <QueryClientProvider client={queryClient}>
      <HeadContent />
      <SessionProvider>
        <ApplicationGate />
      </SessionProvider>
      <Toaster richColors position="top-right" />
    </QueryClientProvider>
  );
}

function ApplicationGate() {
  const pathname = useRouterState({
    select: (state) => state.location.pathname,
  });
  const { user, loading } = useSession();

  if (pathname === "/login") {
    return <Outlet />;
  }

  if (!safeDemoMode && loading) {
    return (
      <main className="grid min-h-screen place-items-center bg-background">
        <p className="text-sm text-muted-foreground" role="status">
          Checking your session…
        </p>
      </main>
    );
  }

  if (!safeDemoMode && !user) {
    return <Navigate to="/login" search={{ returnTo: pathname }} replace />;
  }

  return <ApplicationShell />;
}

function ApplicationShell() {
  return (
    <SidebarProvider>
      <div className="flex min-h-screen w-full bg-background">
        <AppSidebar />
        <div className="flex min-w-0 flex-1 flex-col">
          <DemoBanner />
          <header className="sticky top-0 z-10 flex h-14 items-center justify-between gap-3 border-b border-border bg-background/80 px-4 backdrop-blur">
            <div className="flex items-center gap-2">
              <SidebarTrigger />
              <div className="hidden flex-col leading-tight lg:flex">
                <span className="text-[11px] uppercase tracking-wider text-muted-foreground">
                  Cadence
                </span>
                <span className="text-sm font-medium">Workforce & Delivery Governance</span>
              </div>
            </div>
            <div className="flex items-center gap-2">
              <RoleSwitcher />
              <UserMenu />
            </div>
          </header>
          <main className="min-w-0 flex-1">
            <Outlet />
          </main>
        </div>
      </div>
    </SidebarProvider>
  );
}

function UserMenu() {
  const { user, signOut } = useSession();

  if (!user) return null;

  return (
    <div className="flex items-center gap-2">
      <div className="hidden text-right leading-tight xl:block">
        <p className="text-sm font-medium">{user.displayName}</p>
        <p className="text-xs text-muted-foreground">{user.email}</p>
      </div>
      <Button variant="outline" size="sm" className="gap-1.5" onClick={() => void signOut()}>
        <LogOut className="h-4 w-4" />
        Sign out
      </Button>
    </div>
  );
}
