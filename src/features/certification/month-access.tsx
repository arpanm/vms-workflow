import { useNavigate } from "@tanstack/react-router";
import { ArrowRight, ShieldCheck } from "lucide-react";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

export function MonthAccess({
  destination,
  title,
  detail,
}: {
  destination: "/certification/$monthId" | "/confirmation/$monthId";
  title: string;
  detail: string;
}) {
  const navigate = useNavigate();
  const [monthId, setMonthId] = useState("");
  const [error, setError] = useState("");

  function submit(event: React.FormEvent) {
    event.preventDefault();
    const value = monthId.trim();
    if (!value) {
      setError("Enter an engagement month ID.");
      return;
    }
    void navigate({ to: destination, params: { monthId: value } });
  }

  return (
    <Card className="max-w-xl">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <ShieldCheck className="h-4 w-4" aria-hidden="true" />
          {title}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <p className="mb-4 text-sm text-muted-foreground">{detail}</p>
        <form onSubmit={submit} noValidate className="space-y-3">
          <div>
            <Label htmlFor="engagement-month-id">Engagement month ID</Label>
            <Input
              id="engagement-month-id"
              className="mt-1"
              value={monthId}
              onChange={(event) => {
                setMonthId(event.target.value);
                setError("");
              }}
              aria-invalid={Boolean(error)}
              aria-describedby={error ? "month-id-error" : undefined}
              autoComplete="off"
            />
            {error && (
              <p id="month-id-error" className="mt-1 text-sm text-destructive" role="alert">
                {error}
              </p>
            )}
          </div>
          <Button type="submit">
            Open authorized month
            <ArrowRight className="ml-2 h-4 w-4" aria-hidden="true" />
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
