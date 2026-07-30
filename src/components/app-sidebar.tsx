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
  BadgeCheck,
  SendHorizontal,
  FileBarChart,
  ScanSearch,
  DatabaseBackup,
  ContactRound,
  GitPullRequestArrow,
  CalendarSync,
  CircleHelp,
  ListTodo,
  UserRoundPlus,
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
import { useActiveScope } from "@/features/core-admin/scope-provider";
import type { PermissionRequirement } from "@/features/core-admin/permissions";

const nav: Array<{
  title: string;
  url: string;
  icon: typeof LayoutDashboard;
  group: string;
  legacy: boolean;
  workforce: boolean;
  delivery: boolean;
  permission?: PermissionRequirement;
}> = [
  {
    title: "Client work items",
    url: "/work-items",
    icon: ListTodo,
    group: "Delivery",
    legacy: false,
    workforce: false,
    delivery: false,
    permission: "workitem.read",
  },
  {
    title: "Dashboard",
    url: "/",
    icon: LayoutDashboard,
    group: "Overview",
    legacy: false,
    workforce: false,
    delivery: false,
  },
  {
    title: "Client onboarding",
    url: "/administration/clients",
    icon: UserRoundPlus,
    group: "Administration",
    legacy: false,
    workforce: false,
    delivery: false,
    permission: "client.onboard",
  },
  {
    title: "Engagements",
    url: "/engagements",
    icon: Building2,
    group: "Overview",
    legacy: true,
    workforce: false,
    delivery: false,
    permission: "catalog.read",
  },
  {
    title: "Employees",
    url: "/workforce/employees",
    icon: UserRoundSearch,
    group: "Workforce",
    legacy: false,
    workforce: true,
    delivery: false,
    permission: ["workforce.read", "employee.read"],
  },
  {
    title: "Today",
    url: "/attendance/today",
    icon: Timer,
    group: "Workforce",
    legacy: false,
    workforce: true,
    delivery: false,
    permission: ["attendance.write", "attendance.self.checkin", "attendance.read"],
  },
  {
    title: "Leave",
    url: "/attendance/leave",
    icon: CalendarCheck2,
    group: "Workforce",
    legacy: false,
    workforce: true,
    delivery: false,
    permission: ["workforce.read", "leave.self.apply", "leave.read"],
  },
  {
    title: "Regularizations",
    url: "/attendance/regularizations",
    icon: FileWarning,
    group: "Workforce",
    legacy: false,
    workforce: true,
    delivery: false,
    permission: ["workforce.read", "regularization.self.apply", "regularization.read"],
  },
  {
    title: "Month status",
    url: "/attendance/month-close",
    icon: CalendarClock,
    group: "Workforce",
    legacy: false,
    workforce: true,
    delivery: false,
    permission: ["workforce.read", "attendance.close", "attendance.read"],
  },
  {
    title: "Delivery plans",
    url: "/delivery/plans",
    icon: GanttChartSquare,
    group: "Delivery",
    legacy: false,
    workforce: false,
    delivery: true,
    permission: ["delivery.read", "deliverable.plan.create"],
  },
  {
    title: "Linear health",
    url: "/delivery/integration-health",
    icon: HeartPulse,
    group: "Delivery",
    legacy: false,
    workforce: false,
    delivery: true,
    permission: ["delivery.read", "integration.read_health"],
  },
  {
    title: "Certification",
    url: "/certification",
    icon: BadgeCheck,
    group: "Governance",
    legacy: false,
    workforce: false,
    delivery: false,
    permission: ["certification.read", "deliverable.delivery.certify"],
  },
  {
    title: "Confirmation",
    url: "/confirmation",
    icon: SendHorizontal,
    group: "Governance",
    legacy: false,
    workforce: false,
    delivery: false,
    permission: ["certification.read", "confirmation.request"],
  },
  {
    title: "Requirements",
    url: "/requirements",
    icon: ListChecks,
    group: "Delivery",
    legacy: true,
    workforce: false,
    delivery: false,
    permission: "catalog.read",
  },
  {
    title: "Monthly Scope Engine",
    url: "/scope",
    icon: Boxes,
    group: "Delivery",
    legacy: true,
    workforce: false,
    delivery: false,
    permission: "catalog.read",
  },
  {
    title: "Approvals",
    url: "/approvals",
    icon: ShieldCheck,
    group: "Governance",
    legacy: true,
    workforce: false,
    delivery: false,
    permission: "catalog.read",
  },
  {
    title: "UAT",
    url: "/uat",
    icon: ClipboardCheck,
    group: "Governance",
    legacy: true,
    workforce: false,
    delivery: false,
    permission: "catalog.read",
  },
  {
    title: "Finance workspace",
    url: "/finance",
    icon: Receipt,
    group: "Finance",
    legacy: false,
    workforce: false,
    delivery: false,
    permission: ["finance.read", "INVOICE_VIEW", "invoice.upload", "invoice.review"],
  },
  {
    title: "Procurement",
    url: "/finance/procurement",
    icon: ScanSearch,
    group: "Finance",
    legacy: false,
    workforce: false,
    delivery: false,
    permission: ["finance.read", "PROCUREMENT_REVIEW", "invoice.review"],
  },
  {
    title: "Finance reports",
    url: "/finance/reports",
    icon: FileBarChart,
    group: "Finance",
    legacy: false,
    workforce: false,
    delivery: false,
    permission: ["finance.read", "REPORT_VIEW", "finance.report.read"],
  },
  {
    title: "Historical migration",
    url: "/migration",
    icon: DatabaseBackup,
    group: "Governance",
    legacy: false,
    workforce: false,
    delivery: false,
    permission: ["migration.read", "migration.execute"],
  },
  {
    title: "Engagement administration",
    url: "/administration/engagements",
    icon: Building2,
    group: "Administration",
    legacy: false,
    workforce: false,
    delivery: false,
    permission: ["catalog.read", "engagement.read"],
  },
  {
    title: "Contact groups",
    url: "/administration/contact-groups",
    icon: ContactRound,
    group: "Administration",
    legacy: false,
    workforce: false,
    delivery: false,
    permission: ["contacts.manage", "engagement.configure"],
  },
  {
    title: "Approval inbox",
    url: "/administration/approval-requests",
    icon: ShieldCheck,
    group: "Administration",
    legacy: false,
    workforce: false,
    delivery: false,
    permission: ["approval.request.create", "approval.request.act"],
  },
  {
    title: "Policies & delegations",
    url: "/administration/approval-policies",
    icon: GitPullRequestArrow,
    group: "Administration",
    legacy: false,
    workforce: false,
    delivery: false,
    permission: ["approval.policy.manage", "delegation.manage"],
  },
  {
    title: "Month governance",
    url: "/administration/months",
    icon: CalendarSync,
    group: "Administration",
    legacy: false,
    workforce: false,
    delivery: false,
    permission: ["month.transition", "catalog.read"],
  },
  {
    title: "Role guides & support",
    url: "/support",
    icon: CircleHelp,
    group: "Overview",
    legacy: false,
    workforce: false,
    delivery: false,
  },
];

export function AppSidebar() {
  const { state, isMobile, setOpenMobile } = useSidebar();
  const collapsed = state === "collapsed";
  const path = useRouterState({ select: (s) => s.location.pathname });
  const { can } = useActiveScope();

  const groups = [
    "Overview",
    "Workforce",
    "Delivery",
    "Governance",
    "Finance",
    "Administration",
  ] as const;

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
                      (!n.delivery || featureFlags.linear) &&
                      (!n.permission || can(n.permission)),
                  )
                  .map((item) => {
                    const active = path === item.url || path.startsWith(`${item.url}/`);
                    return (
                      <SidebarMenuItem key={item.url}>
                        <SidebarMenuButton asChild isActive={active}>
                          <Link
                            to={item.url}
                            className="flex items-center gap-2"
                            aria-label={item.title}
                            onClick={() => {
                              if (isMobile) {
                                setOpenMobile(false);
                              }
                            }}
                          >
                            <item.icon className="h-4 w-4" aria-hidden="true" />
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
