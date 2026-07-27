export interface AccessTokenProvider {
  getAccessToken(): Promise<string | null>;
}

export class MemoryAccessTokenProvider implements AccessTokenProvider {
  #accessToken: string | null = null;

  setAccessToken(accessToken: string | null) {
    this.#accessToken = accessToken;
  }

  clear() {
    this.#accessToken = null;
  }

  async getAccessToken() {
    return this.#accessToken;
  }
}

/**
 * OIDC libraries may populate this provider after an authorization-code + PKCE
 * exchange. It intentionally never persists tokens to localStorage.
 */
export const browserAccessTokenProvider = new MemoryAccessTokenProvider();

const systemTestAuthEnabled =
  import.meta.env.DEV && import.meta.env.VITE_E2E_SYSTEM_AUTH === "true";
const systemTestTokenKey = "__vms_system_e2e_access_token";

if (systemTestAuthEnabled && typeof window !== "undefined") {
  browserAccessTokenProvider.setAccessToken(
    window.sessionStorage.getItem(systemTestTokenKey),
  );
}

/**
 * Non-production browser-system-test hook. It is excluded from production by
 * both the Vite DEV check and environment validation, and never accepts a
 * token unless the dedicated system-E2E flag was present when Vite started.
 */
export function setSystemTestAccessToken(accessToken: string | null) {
  if (!systemTestAuthEnabled || typeof window === "undefined") {
    throw new Error("System-test token injection is disabled.");
  }
  if (accessToken) {
    window.sessionStorage.setItem(systemTestTokenKey, accessToken);
  } else {
    window.sessionStorage.removeItem(systemTestTokenKey);
  }
  browserAccessTokenProvider.setAccessToken(accessToken);
}
