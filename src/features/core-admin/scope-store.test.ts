import { describe, expect, it } from "vitest";

import {
  ACTIVE_SCOPE_STORAGE_KEY,
  EMPTY_SCOPE,
  authorityFingerprint,
  readPersistedScope,
  reconcileScope,
  writePersistedScope,
} from "./scope-store";

function memoryStorage() {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    values,
  };
}

describe("active scope persistence", () => {
  it("restores only a selection bound to the exact current authority", () => {
    const storage = memoryStorage();
    const first = authorityFingerprint({
      id: "user-1",
      organizationIds: ["org-a"],
      permissions: ["catalog.read"],
    });
    const changed = authorityFingerprint({
      id: "user-1",
      organizationIds: ["org-a"],
      permissions: ["catalog.read", "contacts.manage"],
    });
    const selection = {
      organizationId: "org-a",
      engagementId: "eng-a",
      monthId: "month-a",
    };

    writePersistedScope(storage, first, selection);

    expect(readPersistedScope(storage, first)).toEqual(selection);
    expect(readPersistedScope(storage, changed)).toEqual(EMPTY_SCOPE);
  });

  it("fails closed for malformed browser storage", () => {
    const storage = memoryStorage();
    storage.values.set(ACTIVE_SCOPE_STORAGE_KEY, "{not-json");
    expect(readPersistedScope(storage, "authority")).toEqual(EMPTY_SCOPE);
  });

  it("continues when browser policy rejects persisted scope writes", () => {
    expect(() =>
      writePersistedScope(
        {
          setItem() {
            throw new DOMException("Storage disabled by policy", "SecurityError");
          },
        },
        "authority",
        EMPTY_SCOPE,
      ),
    ).not.toThrow();
  });

  it("drops stale child IDs when the authorized catalog changes", () => {
    expect(
      reconcileScope(
        {
          organizationId: "removed-org",
          engagementId: "removed-engagement",
          monthId: "removed-month",
        },
        [{ id: "org-a" }],
        [{ id: "eng-a" }],
        [{ id: "month-a" }],
      ),
    ).toEqual({
      organizationId: "org-a",
      engagementId: "eng-a",
      monthId: "month-a",
    });
  });

  it("includes effective membership and permission changes in authority identity", () => {
    const baseline = {
      id: "user-1",
      memberships: [
        {
          organizationId: "org-a",
          roleCode: "ENGAGEMENT_ADMIN",
          validFrom: "2026-01-01",
          validTo: null,
        },
      ],
      permissions: ["catalog.read"],
      organizationIds: ["org-a"],
    };
    expect(authorityFingerprint(baseline)).not.toBe(
      authorityFingerprint({
        ...baseline,
        memberships: [
          {
            ...baseline.memberships[0],
            validTo: "2026-07-31",
          },
        ],
      }),
    );
  });
});
