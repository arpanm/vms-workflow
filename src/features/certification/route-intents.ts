const exactApplicationRoutes = new Set([
  "/",
  "/attendance/today",
  "/attendance/leave",
  "/attendance/regularizations",
  "/attendance/month-close",
  "/delivery/plans",
  "/delivery/integration-health",
  "/certification",
  "/confirmation",
]);

const parameterizedApplicationRoutes = [
  /^\/certification\/[^/?#]+(?:\/review)?$/,
  /^\/confirmation\/[^/?#]+$/,
  /^\/confirmation\/requests\/[^/?#]+$/,
  /^\/delivery\/plans\/[^/?#]+$/,
  /^\/workforce\/employees\/[^/?#]+$/,
];

export function resolveReadinessActionPath(path: string | null) {
  if (!path || path.includes("\\") || path.startsWith("//")) return null;

  const certificationMonth = path.match(/^\/certification\/months\/([^/?#]+)$/);
  if (certificationMonth) return `/certification/${certificationMonth[1]}`;

  const confirmationRequest = path.match(/^\/certification\/confirmation-requests\/([^/?#]+)$/);
  if (confirmationRequest) return `/confirmation/requests/${confirmationRequest[1]}`;

  if (path === "/delivery/integrations") return "/delivery/integration-health";
  if (path === "/attendance") return "/attendance/today";

  if (exactApplicationRoutes.has(path)) return path;
  return parameterizedApplicationRoutes.some((pattern) => pattern.test(path)) ? path : null;
}
