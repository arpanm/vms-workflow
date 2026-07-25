import { describe, expect, it } from "vitest";

import { getStatusPresentation } from "./status-presentation";

describe("getStatusPresentation", () => {
  it("does not present non-human legacy outcomes as valid approvals", () => {
    expect(
      getStatusPresentation(["auto", "approved"].join("_")),
    ).toMatchObject({
      label: "Legacy status: explicit review required",
      styleKey: "legacy_unverified",
    });
    expect(
      getStatusPresentation(["deemed", "accepted"].join("_")),
    ).toMatchObject({
      label: "Legacy status: explicit review required",
    });
  });

  it("keeps ordinary statuses human readable", () => {
    expect(getStatusPresentation("in_progress")).toEqual({
      label: "in progress",
      styleKey: "in_progress",
    });
  });
});
