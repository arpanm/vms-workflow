import { ApiError, apiClient } from "../api-client";
import { publicEnvironment } from "../env";
import { safeReturnPath } from "../url-safety";
import { z } from "zod";

export { safeReturnPath } from "../url-safety";

export type AuthenticatedUser = {
  id: string;
  email: string;
  displayName: string;
  organizationIds?: string[];
  permissions?: string[];
};

const authenticatedUserSchema = z.object({
  id: z.string().min(1),
  email: z.string().email(),
  displayName: z.string().min(1),
  organizationIds: z.array(z.string()).optional(),
  permissions: z.array(z.string()).optional(),
});

export const sessionClient = {
  async getCurrentUser(): Promise<AuthenticatedUser | null> {
    try {
      return authenticatedUserSchema.parse(
        await apiClient.get<unknown>("/me"),
      );
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        return null;
      }
      throw error;
    }
  },

  beginLogin(returnTo?: string) {
    const loginPath = publicEnvironment.VITE_OIDC_LOGIN_PATH;
    if (!loginPath) {
      throw new Error(
        "VITE_OIDC_LOGIN_PATH must identify a configured same-origin BFF login endpoint.",
      );
    }
    const loginUrl = new URL(
      loginPath,
      window.location.origin,
    );
    loginUrl.searchParams.set("returnTo", safeReturnPath(returnTo));
    window.location.assign(loginUrl);
  },

  isLoginConfigured() {
    return Boolean(publicEnvironment.VITE_OIDC_LOGIN_PATH);
  },

  async logout() {
    const response = await fetch(publicEnvironment.VITE_OIDC_LOGOUT_PATH, {
      method: "POST",
      credentials: "include",
      headers: { Accept: "application/json" },
    });
    if (!response.ok && response.status !== 401) {
      throw new ApiError("Sign out failed.", { status: response.status });
    }
  },
};
