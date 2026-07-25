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
