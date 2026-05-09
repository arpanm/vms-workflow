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

const nav = [
  { title: "Executive Dashboard", url: "/", icon: LayoutDashboard, group: "Overview" },
  { title: "Engagements", url: "/engagements", icon: Building2, group: "Overview" },
  { title: "Requirements", url: "/requirements", icon: ListChecks, group: "Delivery" },
  { title: "Monthly Scope Engine", url: "/scope", icon: Boxes, group: "Delivery" },
  { title: "Approvals", url: "/approvals", icon: ShieldCheck, group: "Governance" },
  { title: "UAT", url: "/uat", icon: ClipboardCheck, group: "Governance" },
  { title: "Invoices", url: "/invoices", icon: Receipt, group: "Finance" },
];

export function AppSidebar() {
  const { state } = useSidebar();
  const collapsed = state === "collapsed";
  const path = useRouterState({ select: (s) => s.location.pathname });

  const groups = ["Overview", "Delivery", "Governance", "Finance"] as const;

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
                  .filter((n) => n.group === g)
                  .map((item) => {
                    const active = path === item.url;
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
