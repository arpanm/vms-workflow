import { useQuery } from "@tanstack/react-query";

import { apiClient } from "./api-client";

export type Engagement = {
  id: string;
  name: string;
  vendor: string;
  category: string;
  monthly_capacity_hours: number;
  approver: string;
  business_owner: string;
  color: string;
};

export type Requirement = {
  id: string;
  engagement_id: string | null;
  title: string;
  description: string;
  module: string;
  priority: "p1" | "p2" | "p3" | "p4";
  rank: number;
  status:
    | "draft"
    | "submitted"
    | "reviewed"
    | "prioritized"
    | "estimated"
    | "approved"
    | "planned"
    | "in_development"
    | "uat"
    | "signed_off"
    | "closed";
  story_points: number;
  estimated_hours: number;
  business_owner: string;
  acceptance_criteria: string;
  uat_cases: string;
  business_justification: string;
  carry_forward: boolean;
  target_month: string;
  created_at: string;
};

export type Approval = {
  id: string;
  requirement_id: string;
  approver: string;
  status: string;
  requested_at: string;
  acted_at: string | null;
  sla_hours: number;
  notes: string;
};

export type UatItem = {
  id: string;
  requirement_id: string;
  status: string;
  uat_owner: string;
  handover_date: string | null;
  signoff_date: string | null;
  defects_open: number;
};

export type Invoice = {
  id: string;
  engagement_id: string;
  invoice_number: string;
  amount: number;
  currency: string;
  status:
    | "draft"
    | "uploaded"
    | "tech_approved"
    | "finance_approved"
    | "payment_initiated"
    | "paid";
  uploaded_at: string;
  tech_approved_at: string | null;
  finance_approved_at: string | null;
  paid_at: string | null;
  period_month: string;
};

export const legacyQueryKeys = {
  engagements: ["legacy", "engagements"] as const,
  requirements: ["legacy", "requirements"] as const,
  approvals: ["legacy", "approvals"] as const,
  uatItems: ["legacy", "uat-items"] as const,
  invoices: ["legacy", "invoices"] as const,
};

const legacyQuery = <T,>(path: string) => () =>
  apiClient.get<T[]>(`/legacy/${path}`);

export const useEngagements = () =>
  useQuery({
    queryKey: legacyQueryKeys.engagements,
    queryFn: legacyQuery<Engagement>("engagements"),
  });

export const useRequirements = () =>
  useQuery({
    queryKey: legacyQueryKeys.requirements,
    queryFn: legacyQuery<Requirement>("requirements"),
  });

export const useApprovals = () =>
  useQuery({
    queryKey: legacyQueryKeys.approvals,
    queryFn: legacyQuery<Approval>("approvals"),
  });

export const useUat = () =>
  useQuery({
    queryKey: legacyQueryKeys.uatItems,
    queryFn: legacyQuery<UatItem>("uat-items"),
  });

export const useInvoices = () =>
  useQuery({
    queryKey: legacyQueryKeys.invoices,
    queryFn: legacyQuery<Invoice>("invoices"),
  });
