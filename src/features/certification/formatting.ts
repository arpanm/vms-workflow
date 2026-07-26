export function formatDateTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat(undefined, {
        dateStyle: "medium",
        timeStyle: "short",
      }).format(date);
}

function pad(value: number) {
  return String(value).padStart(2, "0");
}

export function toDateTimeLocalValue(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return [
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`,
    `${pad(date.getHours())}:${pad(date.getMinutes())}`,
  ].join("T");
}

export function localDateTimeToInstant(value: string) {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

export function browserTimeZoneLabel() {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || "browser local time";
}

export function formatElapsedSeconds(value: number) {
  const totalSeconds = Math.max(0, Math.floor(value));
  const units = [
    { label: "day", seconds: 86_400 },
    { label: "hour", seconds: 3_600 },
    { label: "minute", seconds: 60 },
  ];
  let remaining = totalSeconds;
  const parts: string[] = [];
  for (const unit of units) {
    const count = Math.floor(remaining / unit.seconds);
    if (count > 0) {
      parts.push(`${count} ${unit.label}${count === 1 ? "" : "s"}`);
      remaining %= unit.seconds;
    }
    if (parts.length === 2) break;
  }
  return parts.length > 0
    ? parts.join(" ")
    : `${totalSeconds} second${totalSeconds === 1 ? "" : "s"}`;
}

export function formatLabel(value: string) {
  return value
    .toLowerCase()
    .split("_")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}
