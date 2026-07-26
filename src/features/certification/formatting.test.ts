import { describe, expect, it } from "vitest";

import {
  browserTimeZoneLabel,
  formatDateTime,
  formatElapsedSeconds,
  formatLabel,
  localDateTimeToInstant,
  toDateTimeLocalValue,
} from "./formatting";

describe("certification formatting", () => {
  it("[F04-UNIT-FMT-001] preserves an invalid server date instead of inventing a timestamp", () => {
    expect(formatDateTime("not-a-date")).toBe("not-a-date");
  });

  it("[F04-UNIT-FMT-002] formats machine labels without changing the underlying status", () => {
    expect(formatLabel("MORE_INFORMATION_REQUIRED")).toBe("More Information Required");
    expect(formatLabel("NOT_CONFIGURED")).toBe("Not Configured");
  });

  it("[F04-UNIT-FMT-003] presents a valid offset instant as a human-readable date", () => {
    const value = formatDateTime("2026-08-31T18:30:00+05:30");
    expect(value).not.toBe("2026-08-31T18:30:00+05:30");
    expect(value).not.toMatch(/Invalid Date/i);
  });

  it("[F04-UNIT-FMT-004] preserves the instant while converting an offset default to a local input", () => {
    const source = "2026-08-31T18:30:00+05:30";
    const local = toDateTimeLocalValue(source);
    expect(local).toMatch(/^2026-\d{2}-\d{2}T\d{2}:\d{2}$/);
    expect(localDateTimeToInstant(local)).toBe(new Date(source).toISOString());
  });

  it("[F04-UNIT-FMT-005] rejects invalid local due values and names the browser timezone", () => {
    expect(toDateTimeLocalValue("not-a-date")).toBe("");
    expect(localDateTimeToInstant("not-a-date")).toBeNull();
    expect(browserTimeZoneLabel()).toBeTruthy();
  });

  it("[F04-UNIT-FMT-006] renders only the server-supplied elapsed seconds", () => {
    expect(formatElapsedSeconds(172_800)).toBe("2 days");
    expect(formatElapsedSeconds(5_400)).toBe("1 hour 30 minutes");
    expect(formatElapsedSeconds(0)).toBe("0 seconds");
  });
});
