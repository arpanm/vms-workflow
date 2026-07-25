export const FEATURE_FLAG_NAMES = [
  "legacyFixedCost",
  "workforceGovernance",
  "greytHR",
  "linear",
  "emailReplyIngestion",
] as const;

export type FeatureFlagName = (typeof FEATURE_FLAG_NAMES)[number];
export type FeatureFlags = Readonly<Record<FeatureFlagName, boolean>>;

type EnvSource = Record<string, string | boolean | undefined>;

function parseBoolean(value: string | boolean | undefined, fallback: boolean): boolean {
  if (typeof value === "boolean") return value;
  if (value === undefined || value === "") return fallback;
  if (value === "true") return true;
  if (value === "false") return false;
  throw new Error(`Expected a boolean feature flag but received "${value}".`);
}

export function readFeatureFlags(env: EnvSource, production = false): FeatureFlags {
  const demoMode = parseBoolean(env.VITE_DEMO_MODE, !production);

  if (production && demoMode) {
    throw new Error("VITE_DEMO_MODE must be false in production.");
  }

  return Object.freeze({
    legacyFixedCost: parseBoolean(
      env.VITE_FEATURE_LEGACY_FIXED_COST ?? env.FEATURE_LEGACY_FIXED_COST,
      true,
    ),
    workforceGovernance: parseBoolean(
      env.VITE_FEATURE_WORKFORCE_GOVERNANCE ?? env.FEATURE_WORKFORCE_GOVERNANCE,
      false,
    ),
    greytHR: parseBoolean(env.VITE_FEATURE_GREYTHR ?? env.FEATURE_GREYTHR, false),
    linear: parseBoolean(env.VITE_FEATURE_LINEAR ?? env.FEATURE_LINEAR, false),
    emailReplyIngestion: parseBoolean(
      env.VITE_FEATURE_EMAIL_REPLY_INGESTION ?? env.FEATURE_EMAIL_REPLY_INGESTION,
      false,
    ),
  });
}

export function readSafeDemoMode(env: EnvSource, production = false) {
  return !production && parseBoolean(env.VITE_DEMO_MODE, false);
}

const clientEnv: EnvSource =
  typeof import.meta !== "undefined" && import.meta.env
    ? (import.meta.env as EnvSource)
    : {};

const production = clientEnv.PROD === true || clientEnv.PROD === "true";

export const safeDemoMode = readSafeDemoMode(clientEnv, production);
export const featureFlags = readFeatureFlags(clientEnv, production);
