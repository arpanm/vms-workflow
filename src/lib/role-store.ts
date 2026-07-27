// Lightweight global store using module state + listeners.
import { safeDemoMode } from "./feature-flags";

type Listener = () => void;

export type Role =
  | "pmo"
  | "biz_lead"
  | "vendor_pm"
  | "approver"
  | "uat_owner"
  | "procurement"
  | "finance";

export const ROLES: { id: Role; label: string; persona: string }[] = [
  { id: "pmo", label: "PMO / Governance", persona: "Delivery Governance" },
  { id: "biz_lead", label: "Biz-IT Lead", persona: "Priya R." },
  { id: "vendor_pm", label: "Vendor PM", persona: "Automatrix" },
  { id: "approver", label: "Approver", persona: "Asish" },
  { id: "uat_owner", label: "UAT Owner", persona: "Karthik M." },
  { id: "procurement", label: "Procurement", persona: "Procurement Review" },
  { id: "finance", label: "Finance", persona: "AP Team" },
];

let role: Role = "pmo";
const listeners = new Set<Listener>();

export const roleStore = {
  get: () => role,
  set: (r: Role) => {
    if (!safeDemoMode) return;
    role = r;
    if (typeof window !== "undefined") {
      sessionStorage.setItem("demo-role", r);
    }
    listeners.forEach((l) => l());
  },
  subscribe: (l: Listener) => {
    listeners.add(l);
    return () => listeners.delete(l);
  },
  init: () => {
    if (safeDemoMode && typeof window !== "undefined") {
      const saved = sessionStorage.getItem("demo-role") as Role | null;
      if (saved) role = saved;
    }
  },
};
