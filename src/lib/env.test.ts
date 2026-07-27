import { describe, expect, it } from "vitest";

import { validatePublicEnvironment } from "./env";

describe("validatePublicEnvironment", () => {
  it("does not invent an OIDC login provider endpoint", () => {
    expect(validatePublicEnvironment({})).toEqual({
      VITE_API_BASE_URL: "/api/v1",
      VITE_OIDC_LOGOUT_PATH: "/api/v1/auth/logout",
    });
  });

  it("rejects demo mode in production", () => {
    expect(() =>
      validatePublicEnvironment({ VITE_DEMO_MODE: "true" }, true),
    ).toThrow("VITE_DEMO_MODE must be false in production");
  });

  it("rejects the system-E2E token bridge in production", () => {
    expect(() =>
      validatePublicEnvironment({ VITE_E2E_SYSTEM_AUTH: "true" }, true),
    ).toThrow("VITE_E2E_SYSTEM_AUTH must be false in production");
  });

  it("accepts explicit OIDC paths without browser secrets", () => {
    expect(
      validatePublicEnvironment(
        {
          VITE_API_BASE_URL: "https://api.example.test/api/v1",
          VITE_OIDC_LOGIN_PATH: "/oauth2/authorization/workforce",
          VITE_OIDC_LOGOUT_PATH: "/api/v1/auth/logout",
          VITE_DEMO_MODE: "false",
        },
        true,
      ),
    ).toMatchObject({
      VITE_API_BASE_URL: "https://api.example.test/api/v1",
      VITE_DEMO_MODE: "false",
    });
  });

  it("rejects cross-origin or ambiguous OIDC login paths", () => {
    expect(() =>
      validatePublicEnvironment({
        VITE_OIDC_LOGIN_PATH: "https://attacker.example/login",
      }),
    ).toThrow("Expected a same-origin relative path");
    expect(() =>
      validatePublicEnvironment({
        VITE_OIDC_LOGIN_PATH: "/%5c%5cattacker.example",
      }),
    ).toThrow("Expected a same-origin relative path");
  });
});
