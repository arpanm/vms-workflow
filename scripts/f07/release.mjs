#!/usr/bin/env node

import { pathToFileURL } from "node:url";
import { repoRoot, run, safeError } from "./lib.mjs";

const SCHEMA_CHECK_TIMEOUT_MS = 180_000;

function execute(script, args = [], timeoutMs = SCHEMA_CHECK_TIMEOUT_MS) {
  const result = run(process.execPath, [`scripts/f07/${script}`, ...args], {
    cwd: repoRoot,
    stdio: "inherit",
    timeoutMs,
  });
  if (result.error || result.signal || result.status !== 0) {
    process.stderr.write(
      `F07 ${script} failed or exceeded its ${timeoutMs}ms deadline\n`,
    );
    process.exitCode = Number.isInteger(result.status) ? result.status : 1;
    return false;
  }
  return true;
}

function main() {
  const command = process.argv[2];
  if (command === "self-test") {
    execute("self-test.mjs");
    return;
  }
  if (command === "schema") {
    // The self-test already performs the migration, rollout, operations and
    // release-gate schema checks, including their negative fixtures. Running
    // those entry points again multiplied decision-time provenance and
    // migration work without adding coverage.
    execute("self-test.mjs");
    return;
  }
  if (command === "gate" || command === "release") {
    execute("release-gate.mjs", process.argv.slice(3));
    return;
  }
  process.stderr.write(
    "usage: node scripts/f07/release.mjs <self-test|schema|gate|release>\n" +
      "'release' and 'gate' exit zero only for a complete GO manifest.\n",
  );
  process.exitCode = 2;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    main();
  } catch (error) {
    process.stderr.write(`F07 release command failed safely: ${safeError(error)}\n`);
    process.exitCode = 1;
  }
}
