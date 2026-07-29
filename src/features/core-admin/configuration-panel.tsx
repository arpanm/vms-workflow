import { CalendarCog } from "lucide-react";
import { useState } from "react";

import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

import { MutationNotice } from "./components";
import type {
  ConfigurationView,
  EngagementAdministrationView,
} from "./contracts";
import { usePublishConfiguration } from "./hooks";

export function ConfigurationPanel({
  engagement,
  configurations,
  editable,
  onReload,
}: {
  engagement: EngagementAdministrationView;
  configurations: ConfigurationView[];
  editable: boolean;
  onReload: () => void;
}) {
  const mutation = usePublishConfiguration(engagement.id);
  const [validFrom, setValidFrom] = useState("");
  const [timezone, setTimezone] = useState("Asia/Kolkata");
  const [planningDueDay, setPlanningDueDay] = useState("25");
  const [certificationDueDay, setCertificationDueDay] = useState("5");
  const [confirmationDueDay, setConfirmationDueDay] = useState("7");

  function publish(event: React.FormEvent) {
    event.preventDefault();
    mutation.mutate({
      validFrom,
      timezone,
      planningDueDay: Number(planningDueDay),
      certificationDueDay: Number(certificationDueDay),
      confirmationDueDay: Number(confirmationDueDay),
      reopenPolicy: { reasonRequired: true, approvalRequired: true },
      notificationPolicy: { recipientSnapshotRequired: true },
      expectedEngagementVersion: engagement.version,
    });
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <CalendarCog className="h-4 w-4" aria-hidden="true" />
          Effective configuration history
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {configurations.length ? (
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
            {configurations.map((configuration) => (
              <div key={configuration.id} className="rounded-md border p-3 text-sm">
                <div className="flex items-center justify-between gap-3">
                  <p className="font-medium">Version {configuration.version}</p>
                  <StatusBadge status={configuration.status} />
                </div>
                <p className="mt-2 text-xs text-muted-foreground">
                  {configuration.validFrom}
                  {configuration.validTo ? ` — ${configuration.validTo}` : " onward"}
                  {" · "}
                  {configuration.timezone}
                </p>
                <p className="mt-1 text-xs text-muted-foreground">
                  Due days: planning {configuration.planningDueDay ?? "—"},
                  certification {configuration.certificationDueDay ?? "—"},
                  confirmation {configuration.confirmationDueDay ?? "—"}
                </p>
              </div>
            ))}
          </div>
        ) : (
          <p className="rounded-md border border-dashed p-4 text-sm text-muted-foreground">
            No published configuration is visible for this engagement.
          </p>
        )}
        {editable ? (
          <form className="space-y-3 border-t pt-4" onSubmit={publish}>
            <p className="font-medium">Publish prospective version</p>
            <div className="grid gap-3 md:grid-cols-5">
              <div>
                <Label htmlFor="configuration-valid-from">Effective from</Label>
                <Input
                  id="configuration-valid-from"
                  type="date"
                  value={validFrom}
                  onChange={(event) => setValidFrom(event.target.value)}
                  required
                />
              </div>
              <div>
                <Label htmlFor="configuration-timezone">Timezone</Label>
                <Input
                  id="configuration-timezone"
                  value={timezone}
                  onChange={(event) => setTimezone(event.target.value)}
                  required
                />
              </div>
              <DueDay
                id="planning-due-day"
                label="Planning due day"
                value={planningDueDay}
                onChange={setPlanningDueDay}
              />
              <DueDay
                id="certification-due-day"
                label="Certification due day"
                value={certificationDueDay}
                onChange={setCertificationDueDay}
              />
              <DueDay
                id="confirmation-due-day"
                label="Confirmation due day"
                value={confirmationDueDay}
                onChange={setConfirmationDueDay}
              />
            </div>
            <p className="text-xs text-muted-foreground">
              Publishing creates a new immutable version. Required reopen
              approval and recipient snapshot controls remain enabled.
            </p>
            <MutationNotice
              error={mutation.error}
              pending={mutation.isPending}
              onReload={onReload}
            />
            <Button
              type="submit"
              disabled={mutation.isPending || !validFrom || !timezone.trim()}
            >
              Publish configuration version
            </Button>
          </form>
        ) : (
          <p className="border-t pt-3 text-sm text-muted-foreground">
            Read-only: engagement.configure is not granted in this scope.
          </p>
        )}
      </CardContent>
    </Card>
  );
}

function DueDay({
  id,
  label,
  value,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <div>
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        type="number"
        min={1}
        max={28}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        required
      />
    </div>
  );
}
