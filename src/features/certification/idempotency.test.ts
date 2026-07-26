import { describe, expect, it } from "vitest";

import { ApiError } from "@/lib/api-client";

import { MutationIntentStore } from "./idempotency";

describe("certification mutation intents", () => {
  it("[F04-UNIT-API-004] retains one key after a lost response and clears it only after a definitive result", () => {
    const store = new MutationIntentStore<{
      expectedRequestVersion: number;
      decision: "CONFIRM";
    }>();
    const input = {
      expectedRequestVersion: 2,
      decision: "CONFIRM" as const,
    };

    const committedButLost = store.acquire(input);
    store.settle(
      new ApiError("The committed response was lost.", {
        status: 0,
      }),
    );
    const explicitRetry = store.acquire(input);

    expect(explicitRetry.idempotencyKey).toBe(committedButLost.idempotencyKey);

    store.settle();
    const nextCompletedIntent = store.acquire(input);
    expect(nextCompletedIntent.idempotencyKey).not.toBe(committedButLost.idempotencyKey);
  });

  it("[F04-UNIT-API-005] starts a new key for changed input or a definitive rejection", () => {
    const store = new MutationIntentStore<{ decision: string; comment?: string }>();
    const first = store.acquire({ decision: "REJECT", comment: "Initial reason" });
    const changed = store.acquire({ decision: "REJECT", comment: "Corrected reason" });
    expect(changed.idempotencyKey).not.toBe(first.idempotencyKey);

    store.settle(
      new ApiError("The server definitively rejected the version.", {
        status: 412,
        code: "VERSION_CONFLICT",
      }),
    );
    const reviewedRetry = store.acquire({
      decision: "REJECT",
      comment: "Corrected reason",
    });
    expect(reviewedRetry.idempotencyKey).not.toBe(changed.idempotencyKey);
  });
});
