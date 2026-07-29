#!/usr/bin/env node

import { relative } from "node:path";
import { pathToFileURL } from "node:url";
import {
  existingRepoPath,
  gitMetadata,
  parseArgs,
  readJson,
  repoPath,
  repoRoot,
  safeError,
  sha256File,
  writeJson,
} from "./lib.mjs";

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.bundle || !args.output || !args["run-id"]) {
    throw new Error("--bundle, --output and --run-id are required");
  }
  if (!/^[A-Za-z0-9._-]+$/.test(args["run-id"])) {
    throw new Error("run ID contains unsafe characters");
  }
  const manifest = await readJson(
    repoPath("docs/features/07-hardening-go-live/release-evidence.json"),
  );
  const bundlePath = await existingRepoPath(args.bundle, "CI evidence bundle");
  manifest.release.commit = gitMetadata().commit;
  manifest.release.version = `${manifest.release.version}+${args["run-id"]}`;
  manifest.ciEvidenceBundle = {
    path: relative(repoRoot, bundlePath),
    sha256: await sha256File(bundlePath),
  };
  await writeJson(repoPath(args.output, "candidate release manifest"), manifest);
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(`F07 candidate manifest preparation failed: ${safeError(error)}\n`);
    process.exitCode = 1;
  });
}
