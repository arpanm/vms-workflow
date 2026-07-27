import { beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api-client";

import { FinanceMutationIntentStore } from "./idempotency";

describe("finance mutation intents", () => {
  beforeEach(() => {
    vi.stubGlobal("crypto", { randomUUID: vi.fn()
      .mockReturnValueOnce("key-1")
      .mockReturnValueOnce("key-2")
      .mockReturnValueOnce("key-3") });
  });

  it.each([0, 408, 425, 429, 500, 503])(
    "[F05-UNIT-API-001] retains one key after ambiguous HTTP status %s",
    (status) => {
      const store = new FinanceMutationIntentStore<{ expectedVersion: number }>();
      const input = { expectedVersion: 3 };
      const first = store.acquire(input);
      store.settle(new ApiError("Ambiguous outcome.", { status }));

      expect(store.acquire(input).idempotencyKey).toBe(first.idempotencyKey);
    },
  );

  it("[F05-UNIT-API-002] clears after a confirmed response or definitive rejection", () => {
    const store = new FinanceMutationIntentStore<{ decision: string }>();
    const input = { decision: "APPROVE" };
    const first = store.acquire(input);
    store.settle(new ApiError("Validation failed.", { status: 422 }));
    expect(store.acquire(input).idempotencyKey).not.toBe(first.idempotencyKey);

    const second = store.acquire(input);
    store.settle();
    expect(store.acquire(input).idempotencyKey).not.toBe(second.idempotencyKey);
  });

  it("[F05-UNIT-API-003] starts a new intent when the user changes the payload", () => {
    const store = new FinanceMutationIntentStore<{ comment: string }>();
    const first = store.acquire({ comment: "first" });
    const changed = store.acquire({ comment: "corrected" });
    expect(changed.idempotencyKey).not.toBe(first.idempotencyKey);
  });
});
