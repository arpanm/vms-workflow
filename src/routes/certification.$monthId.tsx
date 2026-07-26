import { Outlet, createFileRoute } from "@tanstack/react-router";

export const Route = createFileRoute("/certification/$monthId")({
  component: CertificationMonthLayout,
});

function CertificationMonthLayout() {
  return <Outlet />;
}
