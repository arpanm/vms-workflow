#!/usr/bin/env node

import { lstat, readdir, readFile } from "node:fs/promises";
import { basename, relative, resolve } from "node:path";
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
  stableJson,
  writeJson,
} from "./lib.mjs";

const defaultInputs = [
  "package.json",
  "package-lock.json",
  "backend/pom.xml",
  "backend/compose.yaml",
  "scripts/f07/bootstrap-database-roles.sql",
  "backend/src/main/resources/db/migration",
  "dist",
  "backend/target",
];

async function composeImageInventory() {
  const composePath = repoPath("backend/compose.yaml");
  const compose = await readFile(composePath, "utf8");
  const images = [...compose.matchAll(/^\s*image:\s*["']?([^"'\s]+)["']?\s*$/gm)].map(
    (match) => match[1],
  );
  if (images.length === 0 || images.some((image) => !/@sha256:[0-9a-f]{64}$/.test(image))) {
    throw new Error("every configured compose image must be pinned by sha256 digest");
  }
  return images.sort();
}

async function collectFiles(path, output) {
  const details = await lstat(path);
  if (details.isSymbolicLink()) {
    throw new Error(`symbolic links are not accepted as release inputs: ${path}`);
  }
  if (details.isFile()) {
    output.push(path);
    return;
  }
  if (!details.isDirectory()) {
    return;
  }
  for (const child of (await readdir(path)).sort()) {
    if (["surefire-reports", "failsafe-reports", "test-classes", "classes"].includes(child)) {
      continue;
    }
    await collectFiles(resolve(path, child), output);
  }
}

export async function createProvenance(inputs, options = {}) {
  if (!Array.isArray(inputs) || inputs.length === 0) {
    throw new Error("at least one explicit expected provenance input is required");
  }
  if (!inputs.includes("backend/compose.yaml")) {
    throw new Error("expected provenance inputs must include backend/compose.yaml");
  }
  if (
    !inputs.includes("scripts/f07/bootstrap-database-roles.sql") &&
    !inputs.includes("scripts/f07")
  ) {
    throw new Error(
      "expected provenance inputs must include scripts/f07/bootstrap-database-roles.sql",
    );
  }
  if (!options.expectedCommit || !/^[0-9a-f]{40}$/.test(options.expectedCommit)) {
    throw new Error("an exact 40-character --expected-commit is required");
  }
  const git = gitMetadata();
  if (git.commit !== options.expectedCommit) {
    throw new Error(`current commit ${git.commit} does not match expected release commit`);
  }
  if (options.requireClean && git.worktreeDirty) {
    throw new Error("release provenance requires a clean tracked and untracked worktree");
  }
  const files = [];
  for (const input of inputs) {
    const path = repoPath(input, "provenance input");
    try {
      await collectFiles(path, files);
    } catch (error) {
      if (error.code === "ENOENT") {
        throw new Error(`expected release input is missing: ${input}`);
      }
      throw error;
    }
  }
  const uniqueFiles = [...new Set(files)].sort();
  if (uniqueFiles.length === 0) {
    throw new Error("expected release inputs produced an empty artifact set");
  }
  const artifacts = [];
  for (const path of uniqueFiles) {
    const contents = await readFile(path);
    artifacts.push({
      path: relative(repoRoot, path),
      sha256: await sha256File(path),
      size: contents.byteLength,
    });
  }
  const sourceDateEpoch =
    process.env.SOURCE_DATE_EPOCH ??
    String(Math.floor(new Date(git.commitTimestamp).getTime() / 1000));
  return {
    architecture: "Java 25 / Spring Boot 4.1.0 / Maven / PostgreSQL / Vite React",
    artifacts,
    buildEnvironment: {
      sourceDateEpoch,
    },
    commit: git.commit,
    commitTimestamp: git.commitTimestamp,
    composeImages: await composeImageInventory(),
    predicateType: "https://slsa.dev/provenance/v1",
    schemaVersion: 1,
    expectedInputs: [...inputs].sort(),
    worktreeDirty: git.worktreeDirty,
  };
}

export async function verifyProvenance(manifestPath) {
  const manifest = await readJson(manifestPath);
  const drift = [];
  for (const artifact of manifest.artifacts ?? []) {
    try {
      const actual = await sha256File(
        await existingRepoPath(artifact.path, "artifact"),
      );
      if (actual !== artifact.sha256) {
        drift.push(`${artifact.path}: checksum drift`);
      }
    } catch (error) {
      drift.push(`${artifact.path}: ${safeError(error)}`);
    }
  }
  return {
    checked: manifest.artifacts?.length ?? 0,
    drift: drift.sort(),
    result: drift.length === 0 ? "PASS" : "FAIL",
  };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.verify) {
    const verification = await verifyProvenance(repoPath(args.verify, "manifest"));
    process.stdout.write(stableJson(verification));
    process.exitCode = verification.result === "PASS" ? 0 : 1;
    return;
  }
  if (!args.input) {
    throw new Error(
      `--input is required; expected release inputs are typically ${defaultInputs.join(",")}`,
    );
  }
  const inputs = String(args.input)
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
  const manifest = await createProvenance(inputs, {
    expectedCommit: args["expected-commit"],
    requireClean: Boolean(args["require-clean"]),
  });
  if (args.output) {
    await writeJson(repoPath(args.output, "output"), manifest);
  } else {
    process.stdout.write(stableJson(manifest));
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(`F07 provenance failed safely: ${safeError(error)}\n`);
    process.exitCode = 1;
  });
}
