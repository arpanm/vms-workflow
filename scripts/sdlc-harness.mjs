import { access, readFile } from "node:fs/promises";
import { constants } from "node:fs";
import path from "node:path";
import process from "node:process";

const root = process.cwd();
const configPath = path.join(root, "sdlc", "harness.config.json");
const config = JSON.parse(await readFile(configPath, "utf8"));
const command = process.argv[2] ?? "check";

if (config.codegenModel === config.reviewModel) {
  throw new Error("Harness policy requires different models for code generation and review.");
}

async function exists(file) {
  try {
    await access(file, constants.F_OK);
    return true;
  } catch {
    return false;
  }
}

async function inspectFeature(feature) {
  const required =
    feature.state === "completed"
      ? config.requiredCompletedArtifacts
      : config.requiredPlanningArtifacts;
  const directory = path.join(root, config.featureRoot, feature.directory);
  const results = await Promise.all(
    required.map(async (artifact) => ({
      artifact,
      exists: await exists(path.join(directory, artifact)),
    })),
  );
  return {
    ...feature,
    complete: results.every((result) => result.exists),
    missing: results.filter((result) => !result.exists).map((result) => result.artifact),
  };
}

const features = await Promise.all(config.features.map(inspectFeature));

if (command === "status") {
  console.table(
    features.map(({ id, phase, state, complete, missing }) => ({
      id,
      phase,
      state,
      artifacts: complete ? "ready" : `missing: ${missing.join(", ")}`,
    })),
  );
  process.exitCode = features.every((feature) => feature.complete) ? 0 : 1;
} else if (command === "check") {
  const failures = features.filter((feature) => !feature.complete);
  if (failures.length > 0) {
    for (const failure of failures) {
      console.error(`${failure.id}: missing ${failure.missing.join(", ")}`);
    }
    process.exitCode = 1;
  } else {
    console.log(
      `SDLC harness valid: ${features.length} features; codegen=${config.codegenModel}; review=${config.reviewModel}.`,
    );
  }
} else {
  console.error(`Unknown command "${command}". Use "check" or "status".`);
  process.exitCode = 2;
}
