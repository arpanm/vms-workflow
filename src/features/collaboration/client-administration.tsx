import { useState, type FormEvent } from "react";
import { Building2, UserPlus } from "lucide-react";
import { toast } from "sonner";

import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useActiveScope } from "@/features/core-admin/scope-provider";

import { collaborationApi } from "./api";
import type { ClientUser, ClientView } from "./contracts";
import { useCollaborationMutation } from "./hooks";

export function ClientAdministrationWorkspace() {
  const scope = useActiveScope();
  const onboard = useCollaborationMutation(collaborationApi.onboardClient);
  const addUser = useCollaborationMutation(
    ({ clientId, input }: { clientId: string; input: Parameters<typeof collaborationApi.addClientUser>[1] }) =>
      collaborationApi.addClientUser(clientId, input),
  );
  const [client, setClient] = useState<ClientView | null>(null);
  const [users, setUsers] = useState<ClientUser[]>([]);

  const submitClient = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const result = (await onboard.mutateAsync({
      clientCode: required(data, "clientCode").toUpperCase(),
      legalName: required(data, "legalName"),
      displayName: required(data, "displayName"),
      primaryDomain: required(data, "primaryDomain"),
      timezone: "Asia/Kolkata",
      engagementCode: required(data, "engagementCode").toUpperCase(),
      engagementName: required(data, "engagementName"),
      vendorOrganizationId: scope.organization?.id ?? "",
      procurementOrganizationId: null,
      engagementModel: "DEDICATED_RESOURCE_MONTHLY",
      startDate: required(data, "startDate"),
      projectCode: required(data, "projectCode").toUpperCase(),
      projectName: required(data, "projectName"),
    })) as ClientView;
    setClient(result);
    setUsers([]);
    toast.success("Client, engagement and delivery months onboarded");
  };

  const submitUser = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!client) return;
    const data = new FormData(event.currentTarget);
    const result = (await addUser.mutateAsync({
      clientId: client.organizationId,
      input: {
        identitySubject: required(data, "identitySubject"),
        email: required(data, "email"),
        displayName: required(data, "displayName"),
        roleCodes: data.getAll("roleCode").filter(
          (value): value is string => typeof value === "string" && Boolean(value),
        ),
        validFrom: new Date().toISOString().slice(0, 10),
        validTo: null,
      },
    })) as ClientUser;
    setUsers((current) => [...current.filter((user) => user.userProfileId !== result.userProfileId), result]);
    toast.success("Client user and permissions added");
    event.currentTarget.reset();
  };

  return (
    <div>
      <PageHeader
        title="Client onboarding"
        description="Create a client, its delivery scope and multiple permission-bound users."
      />
      <div className="grid gap-5 p-6 xl:grid-cols-2">
        <Card>
          <CardHeader><CardTitle className="flex items-center gap-2"><Building2 className="h-4 w-4" />New client</CardTitle></CardHeader>
          <CardContent>
            <form className="grid gap-3 md:grid-cols-2" onSubmit={submitClient}>
              <FormField id="client-code" name="clientCode" label="Client code" />
              <FormField id="client-display-name" name="displayName" label="Display name" />
              <FormField id="client-legal-name" name="legalName" label="Legal name" />
              <FormField id="client-domain" name="primaryDomain" label="Primary domain" />
              <FormField id="client-engagement-code" name="engagementCode" label="Engagement code" />
              <FormField id="client-engagement-name" name="engagementName" label="Engagement name" />
              <FormField id="client-project-code" name="projectCode" label="Project code" />
              <FormField id="client-project-name" name="projectName" label="Project name" />
              <FormField id="client-start-date" name="startDate" label="Start date" type="date" />
              <div className="flex items-end"><Button disabled={onboard.isPending || !scope.organization?.id}>{onboard.isPending ? "Onboarding…" : "Onboard client"}</Button></div>
            </form>
          </CardContent>
        </Card>
        <Card className={!client ? "opacity-70" : undefined}>
          <CardHeader><CardTitle className="flex items-center gap-2"><UserPlus className="h-4 w-4" />Add client users</CardTitle></CardHeader>
          <CardContent className="space-y-4">
            <p className="text-sm text-muted-foreground">
              {client ? `${client.displayName} · ${client.engagementCode} · ${client.provisionedMonthCount} months ready` : "Onboard a client first."}
            </p>
            <form className="grid gap-3 md:grid-cols-2" onSubmit={submitUser}>
              <FormField id="client-user-subject" name="identitySubject" label="Identity subject" disabled={!client} />
              <FormField id="client-user-email" name="email" label="Email" type="email" disabled={!client} />
              <FormField id="client-user-display-name" name="displayName" label="Display name" disabled={!client} />
              <div className="space-y-1">
                <Label htmlFor="roleCode">Role and action permissions</Label>
                <select id="roleCode" name="roleCode" multiple required disabled={!client} className="min-h-28 w-full rounded-md border bg-background px-3 py-2 text-sm">
                  <option value="CLIENT_PRODUCT_OWNER">Client product owner</option>
                  <option value="CLIENT_APPROVER">Client approver</option>
                  <option value="ORG_ADMIN">Client administrator</option>
                  <option value="AUDITOR_READONLY">Read-only auditor</option>
                </select>
                <p className="text-xs text-muted-foreground">Select one or more roles; each role grants its governed action permissions.</p>
              </div>
              <div className="md:col-span-2"><Button disabled={!client || addUser.isPending}>{addUser.isPending ? "Adding…" : "Add user"}</Button></div>
            </form>
            {client ? users.map((user) => (
              <div key={user.userProfileId} className="rounded-md border p-3 text-sm">
                <p className="font-medium">{user.displayName} · {user.email}</p>
                <p className="mt-1 text-xs text-muted-foreground">{user.roleCodes.join(", ")} · {user.permissions.length} effective permissions</p>
                <RoleGrantPanel
                  client={client}
                  user={user}
                  onGranted={(updated) => setUsers((current) =>
                    current.map((entry) =>
                      entry.userProfileId === updated.userProfileId ? updated : entry,
                    ),
                  )}
                />
              </div>
            )) : null}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

function RoleGrantPanel({
  client,
  user,
  onGranted,
}: {
  client: ClientView;
  user: ClientUser;
  onGranted: (user: ClientUser) => void;
}) {
  const [roleCode, setRoleCode] = useState("CLIENT_PRODUCT_OWNER");
  const [scopeType, setScopeType] = useState<"ORGANIZATION" | "ENGAGEMENT" | "PROJECT">(
    "ORGANIZATION",
  );
  const grant = useCollaborationMutation(
    (input: Parameters<typeof collaborationApi.grantClientRole>[2]) =>
      collaborationApi.grantClientRole(client.organizationId, user.userProfileId, input),
  );
  const scopeId = scopeType === "ORGANIZATION"
    ? client.organizationId
    : scopeType === "ENGAGEMENT"
      ? client.engagementId
      : client.projectId;
  return (
    <div className="mt-3 grid gap-2 border-t pt-3 md:grid-cols-[1fr_1fr_auto]">
      <select
        aria-label={`Role for ${user.displayName}`}
        className="h-9 rounded-md border bg-background px-2"
        value={roleCode}
        onChange={(event) => setRoleCode(event.target.value)}
      >
        <option value="CLIENT_PRODUCT_OWNER">Client product owner</option>
        <option value="CLIENT_APPROVER">Client approver</option>
        <option value="ORG_ADMIN">Client administrator</option>
        <option value="AUDITOR_READONLY">Read-only auditor</option>
      </select>
      <select
        aria-label={`Permission scope for ${user.displayName}`}
        className="h-9 rounded-md border bg-background px-2"
        value={scopeType}
        onChange={(event) => setScopeType(event.target.value as typeof scopeType)}
      >
        <option value="ORGANIZATION">Entire client</option>
        <option value="ENGAGEMENT">Current engagement</option>
        <option value="PROJECT">Initial project</option>
      </select>
      <Button
        size="sm"
        variant="outline"
        disabled={grant.isPending}
        onClick={() => {
          void grant.mutateAsync({
            roleCode,
            scopeType,
            scopeId,
            validFrom: new Date().toISOString().slice(0, 10),
            validTo: null,
          }).then((result) => {
            onGranted(result as ClientUser);
            toast.success("Scoped role and action permissions granted");
          }).catch((error) => {
            toast.error(error instanceof Error ? error.message : "Role grant failed");
          });
        }}
      >
        Grant
      </Button>
    </div>
  );
}

function FormField({
  id,
  name,
  label,
  type = "text",
  disabled = false,
}: {
  id: string;
  name: string;
  label: string;
  type?: string;
  disabled?: boolean;
}) {
  return <div className="space-y-1"><Label htmlFor={id}>{label}</Label><Input id={id} name={name} type={type} required disabled={disabled} /></div>;
}

function required(data: FormData, name: string) {
  const value = data.get(name);
  if (typeof value !== "string" || !value.trim()) throw new Error(`${name} is required`);
  return value.trim();
}
