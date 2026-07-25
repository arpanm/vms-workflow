import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { getStatusPresentation } from "@/lib/status-presentation";

const statusStyles: Record<string, string> = {
  draft: "bg-muted text-muted-foreground",
  submitted: "bg-info/15 text-info-foreground border-info/30",
  reviewed: "bg-info/15 text-info-foreground border-info/30",
  prioritized: "bg-info/20 text-info-foreground border-info/30",
  estimated: "bg-warning/20 text-warning-foreground border-warning/40",
  approved: "bg-success/20 text-success-foreground border-success/40",
  planned: "bg-success/15 text-success-foreground border-success/30",
  in_development: "bg-primary/15 text-primary border-primary/30",
  uat: "bg-warning/20 text-warning-foreground border-warning/40",
  signed_off: "bg-success/25 text-success-foreground border-success/40",
  closed: "bg-muted text-muted-foreground",

  pending: "bg-warning/20 text-warning-foreground border-warning/40",
  rejected: "bg-destructive/15 text-destructive border-destructive/30",
  escalated: "bg-destructive/15 text-destructive border-destructive/30",

  not_started: "bg-muted text-muted-foreground",
  in_progress: "bg-info/15 text-info-foreground border-info/30",
  blocked: "bg-destructive/15 text-destructive border-destructive/30",
  legacy_unverified: "bg-warning/20 text-warning-foreground border-warning/40",

  uploaded: "bg-info/15 text-info-foreground border-info/30",
  tech_approved: "bg-warning/20 text-warning-foreground border-warning/40",
  finance_approved: "bg-success/20 text-success-foreground border-success/40",
  payment_initiated: "bg-primary/15 text-primary border-primary/30",
  paid: "bg-success/25 text-success-foreground border-success/40",
};

const priorityStyles: Record<string, string> = {
  p1: "bg-destructive/15 text-destructive border-destructive/40",
  p2: "bg-warning/20 text-warning-foreground border-warning/40",
  p3: "bg-info/15 text-info-foreground border-info/30",
  p4: "bg-muted text-muted-foreground",
};

export function StatusBadge({ status, className }: { status: string; className?: string }) {
  const presentation = getStatusPresentation(status);

  return (
    <Badge
      variant="outline"
      className={cn(
        "rounded-full border px-2 py-0.5 text-[11px] font-medium capitalize",
        statusStyles[presentation.styleKey] ?? "bg-muted text-muted-foreground",
        className,
      )}
    >
      {presentation.label}
    </Badge>
  );
}

export function PriorityBadge({ priority }: { priority: string }) {
  return (
    <Badge
      variant="outline"
      className={cn(
        "rounded-full border px-2 py-0.5 text-[11px] font-bold uppercase tracking-wide",
        priorityStyles[priority] ?? "bg-muted",
      )}
    >
      {priority}
    </Badge>
  );
}
