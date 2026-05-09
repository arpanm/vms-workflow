import { create } from "zustand";

// Lightweight zustand-free store using React context would also work; using simple module state + listeners.
type Listener = () => void;

export type Role =
  | "pmo"
  | "biz_lead"
  | "vendor_pm"
  | "approver"
  | "uat_owner"
  | "finance";

export const ROLES: { id: Role; label: string; persona: string }[] = [
  { id: "pmo", label: "PMO / Governance", persona: "Delivery Governance" },
  { id: "biz_lead", label: "Biz-IT Lead", persona: "Priya R." },
  { id: "vendor_pm", label: "Vendor PM", persona: "Automatrix" },
  { id: "approver", label: "Approver", persona: "Asish" },
  { id: "uat_owner", label: "UAT Owner", persona: "Karthik M." },
  { id: "finance", label: "Finance", persona: "AP Team" },
];

let role: Role = "pmo";
const listeners = new Set<Listener>();

export const roleStore = {
  get: () => role,
  set: (r: Role) => {
    role = r;
    if (typeof window !== "undefined") localStorage.setItem("role", r);
    listeners.forEach((l) => l());
  },
  subscribe: (l: Listener) => {
    listeners.add(l);
    return () => listeners.delete(l);
  },
  init: () => {
    if (typeof window !== "undefined") {
      const saved = localStorage.getItem("role") as Role | null;
      if (saved) role = saved;
    }
  },
};
