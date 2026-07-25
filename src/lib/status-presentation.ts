export type StatusPresentation = {
  label: string;
  styleKey: string;
};

export function getStatusPresentation(status: string): StatusPresentation {
  const normalized = status.trim().toLowerCase();
  const tokens = normalized.split("_");
  const representsUnverifiedApproval =
    (tokens.includes("auto") && tokens.includes("approved")) ||
    tokens.includes("deemed");

  if (representsUnverifiedApproval) {
    return {
      label: "Legacy status: explicit review required",
      styleKey: "legacy_unverified",
    };
  }

  return {
    label: normalized.replace(/_/g, " "),
    styleKey: normalized,
  };
}
