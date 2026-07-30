import { z } from "zod";
import { isOriginRelativePath } from "./url-safety";

const originRelativePath = z
  .string()
  .trim()
  .min(1)
  .refine(isOriginRelativePath, "Expected a same-origin relative path.");

const publicEnvironmentSchema = z.object({
  VITE_API_BASE_URL: z.string().trim().min(1).default("/api/v1"),
  VITE_OIDC_LOGIN_PATH: originRelativePath.optional(),
  VITE_OIDC_LOGOUT_PATH: originRelativePath.default("/api/v1/auth/logout"),
  VITE_DEMO_MODE: z.enum(["true", "false"]).optional(),
  VITE_E2E_SYSTEM_AUTH: z.enum(["true", "false"]).optional(),
  VITE_LOCAL_DEV_AUTH: z.enum(["true", "false"]).optional(),
  VITE_LOCAL_DEV_ACCESS_TOKEN: z.string().trim().min(1).optional(),
});

export type PublicEnvironment = z.infer<typeof publicEnvironmentSchema>;

export function validatePublicEnvironment(
  input: Record<string, string | boolean | undefined>,
  production = false,
): PublicEnvironment {
  const normalized = Object.fromEntries(
    Object.entries(input).map(([key, value]) => [
      key,
      typeof value === "boolean" ? String(value) : value,
    ]),
  );
  const parsed = publicEnvironmentSchema.parse(normalized);

  if (production && parsed.VITE_DEMO_MODE === "true") {
    throw new Error("VITE_DEMO_MODE must be false in production.");
  }
  if (production && parsed.VITE_E2E_SYSTEM_AUTH === "true") {
    throw new Error("VITE_E2E_SYSTEM_AUTH must be false in production.");
  }
  if (
    production &&
    (parsed.VITE_LOCAL_DEV_AUTH === "true" ||
      parsed.VITE_LOCAL_DEV_ACCESS_TOKEN !== undefined)
  ) {
    throw new Error(
      "Local development authentication must not be configured in production.",
    );
  }

  return parsed;
}

const rawClientEnvironment =
  typeof import.meta !== "undefined" && import.meta.env
    ? (import.meta.env as Record<string, string | boolean | undefined>)
    : {};

const production = rawClientEnvironment.PROD === true || rawClientEnvironment.PROD === "true";

export const publicEnvironment = validatePublicEnvironment(
  rawClientEnvironment,
  production,
);
