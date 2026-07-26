import { Link, useRouterState } from "@tanstack/react-router";
import {
  LayoutDashboard,
  ListChecks,
  Boxes,
  ShieldCheck,
  ClipboardCheck,
  Receipt,
  Building2,
  Sparkles,
  UserRoundSearch,
  Timer,
  CalendarCheck2,
  FileWarning,
  CalendarClock,
  GanttChartSquare,
  HeartPulse,
} from "lucide-react";

import {
  Sidebar,
  SidebarContent,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  useSidebar,
} from "@/components/ui/sidebar";
import { featureFlags } from "@/lib/feature-flags";

const nav = [
  { title: "Dashboard", url: "/", icon: LayoutDashboard, group: "Overview", legacy: false, workforce: false, delivery: false },
  { title: "Engagements", url: "/engagements", icon: Building2, group: "Overview", legacy: true, workforce: false, delivery: false },
  { title: "Employees", url: "/workforce/employees", icon: UserRoundSearch, group: "Workforce", legacy: false, workforce: true, delivery: false },
  { title: "Today", url: "/attendance/today", icon: Timer, group: "Workforce", legacy: false, workforce: true, delivery: false },
  { title: "Leave", url: "/attendance/leave", icon: CalendarCheck2, group: "Workforce", legacy: false, workforce: true, delivery: false },
  { title: "Regularizations", url: "/attendance/regularizations", icon: FileWarning, group: "Workforce", legacy: false, workforce: true, delivery: false },
  { title: "Month status", url: "/attendance/month-close", icon: CalendarClock, group: "Workforce", legacy: false, workforce: true, delivery: false },
  { title: "Delivery plans", url: "/delivery/plans", icon: GanttChartSquare, group: "Delivery", legacy: false, workforce: false, delivery: true },
  { title: "Linear health", url: "/delivery/integration-health", icon: HeartPulse, group: "Delivery", legacy: false, workforce: false, delivery: true },
  { title: "Requirements", url: "/requirements", icon: ListChecks, group: "Delivery", legacy: true, workforce: false, delivery: false },
  { title: "Monthly Scope Engine", url: "/scope", icon: Boxes, group: "Delivery", legacy: true, workforce: false, delivery: false },
  { title: "Approvals", url: "/approvals", icon: ShieldCheck, group: "Governance", legacy: true, workforce: false, delivery: false },
  { title: "UAT", url: "/uat", icon: ClipboardCheck, group: "Governance", legacy: true, workforce: false, delivery: false },
  { title: "Invoices", url: "/invoices", icon: Receipt, group: "Finance", legacy: true, workforce: false, delivery: false },
];

export function AppSidebar() {
  const { state } = useSidebar();
  const collapsed = state === "collapsed";
  const path = useRouterState({ select: (s) => s.location.pathname });

  const groups = ["Overview", "Workforce", "Delivery", "Governance", "Finance"] as const;

  return (
    <Sidebar collapsible="icon">
      <SidebarHeader className="border-b border-sidebar-border">
        <div className="flex items-center gap-2 px-2 py-2">
          <div className="grid h-8 w-8 place-items-center rounded-lg bg-[var(--gradient-accent)] text-accent-foreground shadow-sm">
            <Sparkles className="h-4 w-4" />
          </div>
          {!collapsed && (
            <div className="flex flex-col leading-tight">
              <span className="text-sm font-semibold text-sidebar-foreground">Cadence</span>
              <span className="text-[11px] text-sidebar-foreground/60">Delivery Governance</span>
            </div>
          )}
        </div>
      </SidebarHeader>

      <SidebarContent>
        {groups.map((g) => (
          <SidebarGroup key={g}>
            {!collapsed && <SidebarGroupLabel>{g}</SidebarGroupLabel>}
            <SidebarGroupContent>
              <SidebarMenu>
                {nav
                  .filter(
                    (n) =>
                      n.group === g &&
                      (!n.legacy || featureFlags.legacyFixedCost) &&
                      (!n.workforce || featureFlags.workforceGovernance) &&
                      (!n.delivery || featureFlags.linear),
                  )
                  .map((item) => {
                    const active =
                      path === item.url || path.startsWith(`${item.url}/`);
                    return (
                      <SidebarMenuItem key={item.url}>
                        <SidebarMenuButton asChild isActive={active}>
                          <Link to={item.url} className="flex items-center gap-2">
                            <item.icon className="h-4 w-4" />
                            {!collapsed && <span>{item.title}</span>}
                          </Link>
                        </SidebarMenuButton>
                      </SidebarMenuItem>
                    );
                  })}
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        ))}
      </SidebarContent>
    </Sidebar>
  );
}
