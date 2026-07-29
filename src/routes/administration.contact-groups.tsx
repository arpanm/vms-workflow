import { createFileRoute } from "@tanstack/react-router";
import { ContactRound, Plus, UserPlus } from "lucide-react";
import { useState } from "react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  CoreAdminBoundary,
  MutationNotice,
} from "@/features/core-admin/components";
import type { ContactGroupView } from "@/features/core-admin/contracts";
import {
  useAddContactMember,
  useContactGroups,
  useCreateContactGroup,
} from "@/features/core-admin/hooks";
import { coreAdminPermissions } from "@/features/core-admin/permissions";
import { useActiveScope } from "@/features/core-admin/scope-provider";

export const Route = createFileRoute("/administration/contact-groups")({
  head: () => ({
    meta: [
      { title: "Contact groups — Cadence" },
      {
        name: "description",
        content:
          "Effective-dated engagement contact and approval recipient groups.",
      },
    ],
  }),
  component: ContactGroupsPage,
});

const GROUP_TYPES = [
  "CLIENT_PRODUCT_OWNERS",
  "CLIENT_APPROVERS",
  "VENDOR_DELIVERY",
  "VENDOR_HR",
  "VENDOR_FINANCE",
  "PROCUREMENT_CC",
  "ESCALATION",
  "AUDIT_OBSERVERS",
  "OTHER",
] as const;

function ContactGroupsPage() {
  const scope = useActiveScope();
  const engagementId = scope.selection.engagementId;
  const authorized = scope.can(coreAdminPermissions.contactsManage);
  const query = useContactGroups(engagementId, authorized);

  return (
    <div>
      <PageHeader
        title="Contact groups"
        description="Recipient membership is effective-dated. Historical messages retain their original recipient snapshot."
      />
      <CoreAdminBoundary authorized={authorized} query={query}>
        <div className="space-y-5 p-6">
          <CreateGroupForm engagementId={engagementId} />
          {query.data?.length ? (
            <div className="grid gap-4 xl:grid-cols-2">
              {query.data.map((group) => (
                <ContactGroupCard key={group.id} group={group} />
              ))}
            </div>
          ) : (
            <CoreAdminBoundary
              empty
              emptyTitle="No contact groups"
              emptyDescription="Create required product owner, vendor delivery and Procurement CC groups before publishing notification workflows."
            >
              {null}
            </CoreAdminBoundary>
          )}
        </div>
      </CoreAdminBoundary>
    </div>
  );
}

function CreateGroupForm({ engagementId }: { engagementId: string }) {
  const mutation = useCreateContactGroup(engagementId);
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [groupType, setGroupType] = useState<(typeof GROUP_TYPES)[number]>(
    "CLIENT_PRODUCT_OWNERS",
  );

  function submit(event: React.FormEvent) {
    event.preventDefault();
    mutation.mutate(
      { code: code.trim().toUpperCase(), name: name.trim(), groupType },
      {
        onSuccess: () => {
          setCode("");
          setName("");
        },
      },
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <Plus className="h-4 w-4" aria-hidden="true" />
          Create group
        </CardTitle>
      </CardHeader>
      <CardContent>
        <form
          className="grid items-end gap-3 md:grid-cols-[1fr_1.5fr_1.5fr_auto]"
          onSubmit={submit}
        >
          <div>
            <Label htmlFor="contact-code">Code</Label>
            <Input
              id="contact-code"
              value={code}
              onChange={(event) => setCode(event.target.value)}
              placeholder="PROCUREMENT_CC"
              required
            />
          </div>
          <div>
            <Label htmlFor="contact-name">Name</Label>
            <Input
              id="contact-name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="Central Procurement"
              required
            />
          </div>
          <div>
            <Label htmlFor="contact-type">Group type</Label>
            <Select
              value={groupType}
              onValueChange={(value) =>
                setGroupType(value as (typeof GROUP_TYPES)[number])
              }
            >
              <SelectTrigger id="contact-type">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {GROUP_TYPES.map((value) => (
                  <SelectItem key={value} value={value}>
                    {value.replaceAll("_", " ")}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <Button
            type="submit"
            disabled={mutation.isPending || !code.trim() || !name.trim()}
          >
            Create
          </Button>
        </form>
        <div className="mt-3">
          <MutationNotice error={mutation.error} pending={mutation.isPending} />
        </div>
      </CardContent>
    </Card>
  );
}

function ContactGroupCard({ group }: { group: ContactGroupView }) {
  const mutation = useAddContactMember(group.engagementId, group.id);
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [roleAttribution, setRoleAttribution] = useState(group.groupType);
  const [verified, setVerified] = useState(false);

  function addMember(event: React.FormEvent) {
    event.preventDefault();
    mutation.mutate(
      {
        email: email.trim(),
        displayName: displayName.trim(),
        roleAttribution: roleAttribution.trim(),
        verified,
        validFrom: new Date().toISOString().slice(0, 10),
        expectedGroupVersion: group.version,
      },
      {
        onSuccess: () => {
          setEmail("");
          setDisplayName("");
          setVerified(false);
        },
      },
    );
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex items-start justify-between gap-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <ContactRound className="h-4 w-4" aria-hidden="true" />
            {group.name}
          </CardTitle>
          <StatusBadge status={group.status} />
        </div>
        <p className="text-xs text-muted-foreground">
          {group.code} · {group.groupType.replaceAll("_", " ")} · version{" "}
          {group.version}
        </p>
      </CardHeader>
      <CardContent className="space-y-4">
        {group.members.length ? (
          <ul className="divide-y rounded-md border">
            {group.members.map((member) => (
              <li key={member.id} className="flex justify-between gap-3 p-3 text-sm">
                <div>
                  <p className="font-medium">{member.displayName}</p>
                  <p className="text-xs text-muted-foreground">{member.email}</p>
                </div>
                <div className="text-right">
                  <StatusBadge status={member.verified ? "VERIFIED" : "UNVERIFIED"} />
                  <p className="mt-1 text-xs text-muted-foreground">
                    {member.roleAttribution}
                  </p>
                </div>
              </li>
            ))}
          </ul>
        ) : (
          <p className="rounded-md border border-dashed p-4 text-sm text-muted-foreground">
            No effective members. This group cannot satisfy recipient or
            approval routing until a verified member is added.
          </p>
        )}
        <form className="space-y-3 border-t pt-4" onSubmit={addMember}>
          <p className="flex items-center gap-2 text-sm font-medium">
            <UserPlus className="h-4 w-4" aria-hidden="true" />
            Add effective member
          </p>
          <div className="grid gap-3 sm:grid-cols-2">
            <div>
              <Label htmlFor={`member-name-${group.id}`}>Display name</Label>
              <Input
                id={`member-name-${group.id}`}
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
                required
              />
            </div>
            <div>
              <Label htmlFor={`member-email-${group.id}`}>Verified email</Label>
              <Input
                id={`member-email-${group.id}`}
                type="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                required
              />
            </div>
            <div>
              <Label htmlFor={`member-role-${group.id}`}>Role attribution</Label>
              <Input
                id={`member-role-${group.id}`}
                value={roleAttribution}
                onChange={(event) => setRoleAttribution(event.target.value)}
                required
              />
            </div>
            <label className="flex items-center gap-2 self-end pb-2 text-sm">
              <Checkbox
                checked={verified}
                onCheckedChange={(value) => setVerified(value === true)}
              />
              Identity/email verified
            </label>
          </div>
          <MutationNotice error={mutation.error} pending={mutation.isPending} />
          <Button
            type="submit"
            variant="outline"
            disabled={mutation.isPending || !email.trim() || !displayName.trim()}
          >
            Add member
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
