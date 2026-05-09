import { useSyncExternalStore } from "react";
import { roleStore, type Role } from "./role-store";

export function useRole(): [Role, (r: Role) => void] {
  const role = useSyncExternalStore(
    (cb) => roleStore.subscribe(cb),
    () => roleStore.get(),
    () => roleStore.get(),
  );
  return [role, roleStore.set];
}
