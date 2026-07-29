import { describe, expect, it } from "vitest";

import { safeErrorPresentation } from "@/lib/safe-error";

describe("safeErrorPresentation", () => {
  it("never renders raw SQL, tokens, provider payloads or personal data", () => {
    const result = safeErrorPresentation(
      new Error(
        "SELECT * FROM users; Bearer eyJhbGciOiJIUzI1NiJ9.secret; alice@example.test",
      ),
    );

    expect(result.message).toBe(
      "The request could not be completed safely. Retry, or contact support if the problem continues.",
    );
    expect(JSON.stringify(result)).not.toMatch(/SELECT|Bearer|alice@|eyJ/i);
  });

  it("retains only a syntactically valid support correlation identifier", () => {
    expect(
      safeErrorPresentation(
        new Error(
          "provider failure; correlation-id: 2A6D75A0-69D5-4B11-8B05-3CF1AC963821; secret=hidden",
        ),
      ),
    ).toEqual({
      message:
        "The request could not be completed safely. Retry, or contact support if the problem continues.",
      correlationId: "2a6d75a0-69d5-4b11-8b05-3cf1ac963821",
    });
  });
});
