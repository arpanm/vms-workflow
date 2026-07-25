import type { AccessTokenProvider } from "./auth/access-token";
import { browserAccessTokenProvider } from "./auth/access-token";
import { publicEnvironment } from "./env";

export type ApiErrorDetails = {
  message?: string;
  code?: string;
  correlationId?: string;
  [key: string]: unknown;
};

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly correlationId?: string;
  readonly details?: ApiErrorDetails;

  constructor(
    message: string,
    options: {
      status: number;
      code?: string;
      correlationId?: string;
      details?: ApiErrorDetails;
    },
  ) {
    super(message);
    this.name = "ApiError";
    this.status = options.status;
    this.code = options.code;
    this.correlationId = options.correlationId;
    this.details = options.details;
  }
}

type RequestOptions = Omit<RequestInit, "body"> & {
  body?: unknown;
};

type ApiClientOptions = {
  baseUrl?: string;
  fetch?: typeof fetch;
  accessTokenProvider?: AccessTokenProvider;
};

function resolveUrl(baseUrl: string, path: string) {
  if (!path.startsWith("/")) {
    throw new Error(`API path must start with "/": ${path}`);
  }
  return `${baseUrl.replace(/\/$/, "")}${path}`;
}

async function parseBody(response: Response): Promise<unknown> {
  if (response.status === 204) return undefined;
  const contentType = response.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) {
    return response.json();
  }
  const text = await response.text();
  return text || undefined;
}

export function createApiClient(options: ApiClientOptions = {}) {
  const baseUrl = options.baseUrl ?? publicEnvironment.VITE_API_BASE_URL;
  const fetchImpl = options.fetch ?? fetch;
  const accessTokenProvider =
    options.accessTokenProvider ?? browserAccessTokenProvider;

  async function request<T>(path: string, requestOptions: RequestOptions = {}) {
    const headers = new Headers(requestOptions.headers);
    headers.set("Accept", "application/json");

    const accessToken = await accessTokenProvider.getAccessToken();
    if (accessToken) {
      headers.set("Authorization", `Bearer ${accessToken}`);
    }

    let body: BodyInit | undefined;
    if (requestOptions.body !== undefined) {
      headers.set("Content-Type", "application/json");
      body = JSON.stringify(requestOptions.body);
    }

    let response: Response;
    try {
      response = await fetchImpl(resolveUrl(baseUrl, path), {
        ...requestOptions,
        body,
        credentials: "include",
        headers,
      });
    } catch (cause) {
      throw new ApiError("The API could not be reached.", {
        status: 0,
        details: { cause: cause instanceof Error ? cause.message : String(cause) },
      });
    }

    const payload = await parseBody(response);
    if (!response.ok) {
      const details =
        payload && typeof payload === "object" && !Array.isArray(payload)
          ? (payload as ApiErrorDetails)
          : undefined;
      const correlationId =
        response.headers.get("x-correlation-id") ??
        details?.correlationId ??
        undefined;
      throw new ApiError(
        details?.message ??
          (typeof payload === "string" ? payload : `API request failed (${response.status}).`),
        {
          status: response.status,
          code: details?.code,
          correlationId,
          details,
        },
      );
    }

    return payload as T;
  }

  return {
    request,
    get: <T>(path: string, options?: RequestOptions) =>
      request<T>(path, { ...options, method: "GET" }),
    post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
      request<T>(path, { ...options, method: "POST", body }),
  };
}

export const apiClient = createApiClient();
