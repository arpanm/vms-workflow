import { describe, expect, it } from "vitest";

import { MemoryAccessTokenProvider } from "./access-token";
import { safeReturnPath } from "./session-client";

describe("OIDC session helpers", () => {
  it("accepts only same-origin application return paths", () => {
    expect(safeReturnPath("/requirements?month=2026-07")).toBe(
      "/requirements?month=2026-07",
    );
    expect(safeReturnPath("https://attacker.example")).toBe("/");
    expect(safeReturnPath("//attacker.example")).toBe("/");
    expect(safeReturnPath("/\\attacker.example")).toBe("/");
    expect(safeReturnPath("/%5c%5cattacker.example")).toBe("/");
    expect(safeReturnPath("/%255c%255cattacker.example")).toBe("/");
    expect(safeReturnPath("/%0a/attacker.example")).toBe("/");
    expect(safeReturnPath("/\u0000attacker.example")).toBe("/");
    expect(safeReturnPath("requirements")).toBe("/");
  });

  it("keeps optional bearer tokens in memory", async () => {
    const provider = new MemoryAccessTokenProvider();
    provider.setAccessToken("token");
    await expect(provider.getAccessToken()).resolves.toBe("token");
    provider.clear();
    await expect(provider.getAccessToken()).resolves.toBeNull();
  });
});
