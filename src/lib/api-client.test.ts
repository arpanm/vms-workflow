import { describe, expect, it, vi } from "vitest";

import { createApiClient } from "./api-client";

describe("createApiClient", () => {
  it("calls the Java API with credentials and a bearer token when supplied", async () => {
    const fetchMock = vi.fn<typeof fetch>(async (_input, _init) =>
      new Response(JSON.stringify([{ id: "eng-1" }]), {
        headers: { "content-type": "application/json" },
      }),
    );
    const client = createApiClient({
      baseUrl: "/api/v1",
      fetch: fetchMock,
      accessTokenProvider: {
        getAccessToken: async () => "oidc-access-token",
      },
    });

    await expect(client.get("/legacy/engagements")).resolves.toEqual([
      { id: "eng-1" },
    ]);
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/legacy/engagements",
      expect.objectContaining({
        credentials: "include",
        method: "GET",
      }),
    );
    const headers = fetchMock.mock.calls[0]?.[1]?.headers as Headers;
    expect(headers.get("authorization")).toBe("Bearer oidc-access-token");
  });

  it("maps backend failures to a typed ApiError with correlation evidence", async () => {
    const client = createApiClient({
      fetch: (async () =>
        new Response(
          JSON.stringify({ message: "Access denied", code: "FORBIDDEN" }),
          {
            status: 403,
            headers: {
              "content-type": "application/json",
              "x-correlation-id": "corr-123",
            },
          },
        )) as typeof fetch,
    });

    await expect(client.get("/organizations")).rejects.toMatchObject({
      name: "ApiError",
      status: 403,
      code: "FORBIDDEN",
      correlationId: "corr-123",
    });
  });

  it("uses RFC 7807 detail text returned by the Java backend", async () => {
    const client = createApiClient({
      fetch: (async () =>
        new Response(
          JSON.stringify({
            title: "Conflict",
            detail: "An open attendance session already exists.",
          }),
          {
            status: 409,
            headers: { "content-type": "application/problem+json" },
          },
        )) as typeof fetch,
    });

    await expect(client.post("/attendance/punches", {})).rejects.toMatchObject({
      status: 409,
      message: "An open attendance session already exists.",
    });
  });
});
