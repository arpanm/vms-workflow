import { describe, expect, it } from "vitest";

import {
  canUseCoreAdministrativeScope,
  hasPermission,
} from "./permissions";

describe("permission-derived presentation", () => {
  it("does not infer permission from a demo persona or role label", () => {
    expect(hasPermission(["catalog.read"], "contacts.manage")).toBe(false);
    expect(
      hasPermission(["catalog.read"], ["contacts.manage", "engagement.read"]),
    ).toBe(false);
  });

  it("supports any-of navigation capabilities from server permissions", () => {
    expect(
      hasPermission(
        ["catalog.read", "engagement.read"],
        ["engagement.read", "engagement.update"],
      ),
    ).toBe(true);
  });
});

describe("canUseCoreAdministrativeScope", () => {
  it("does not activate administration scope for catalog-only users", () => {
    expect(canUseCoreAdministrativeScope(["catalog.read"])).toBe(false);
  });

  it("activates administration scope for a governed administration action", () => {
    expect(canUseCoreAdministrativeScope(["approval.request.act"])).toBe(true);
  });
});
