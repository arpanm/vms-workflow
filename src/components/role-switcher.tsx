import { ROLES } from "@/lib/role-store";
import { useRole } from "@/lib/use-role";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { UserCircle2 } from "lucide-react";
import { safeDemoMode } from "@/lib/feature-flags";

export function RoleSwitcher() {
  const [role, setRole] = useRole();
  const current = ROLES.find((r) => r.id === role)!;

  if (!safeDemoMode) return null;

  return (
    <div className="flex items-center gap-2">
      <div className="hidden flex-col items-end leading-tight xl:flex">
        <span className="text-xs text-muted-foreground">Viewing as</span>
        <span className="text-sm font-medium">{current.persona}</span>
      </div>
      <Select value={role} onValueChange={(v) => setRole(v as typeof role)}>
        <SelectTrigger className="h-9 w-[140px] gap-2 sm:w-[200px]">
          <UserCircle2 className="h-4 w-4 text-muted-foreground" />
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {ROLES.map((r) => (
            <SelectItem key={r.id} value={r.id}>
              <div className="flex flex-col">
                <span className="text-sm">{r.label}</span>
                <span className="text-[11px] text-muted-foreground">{r.persona}</span>
              </div>
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}
