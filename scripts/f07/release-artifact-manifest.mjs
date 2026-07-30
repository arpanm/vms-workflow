#!/usr/bin/env node

import { lstat, mkdtemp, readdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, relative, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { canonicalProvenanceInputs } from "./evidence-policy.mjs";
import {
  gitMetadata,
  parseArgs,
  readJson,
  repoPath,
  repoRoot,
  run,
  safeError,
  sha256Bytes,
  sha256File,
  stableJson,
  writeJson,
} from "./lib.mjs";
import { createProvenance } from "./provenance.mjs";

const artifactPaths = {
  backend: "backend/target/workflow-backend-0.1.0-SNAPSHOT.jar",
  frontend: "dist",
};
const readinessEndpoints = [
  "/actuator/health",
  "/actuator/health/liveness",
  "/actuator/health/readiness",
];

async function collectDirectory(path, base = path) {
  const entries = [];
  for (const child of (await readdir(path)).sort()) {
    const childPath = resolve(path, child);
    const details = await lstat(childPath);
    if (details.isSymbolicLink()) {
      throw new Error(`release artifacts cannot contain symbolic links: ${childPath}`);
    }
    if (details.isDirectory()) {
      entries.push(...await collectDirectory(childPath, base));
    } else if (details.isFile()) {
      entries.push({
        path: relative(base, childPath),
        sha256: await sha256File(childPath),
        size: details.size,
      });
    }
  }
  return entries;
}

async function describeAbsoluteArtifact(absolute, path) {
  const details = await lstat(absolute);
  if (details.isSymbolicLink()) {
    throw new Error(`release artifact cannot be a symbolic link: ${path}`);
  }
  if (details.isFile()) {
    return {
      fileCount: 1,
      path,
      sha256: await sha256File(absolute),
      size: details.size,
      type: "FILE",
    };
  }
  if (!details.isDirectory()) {
    throw new Error(`release artifact must be a file or directory: ${path}`);
  }
  const files = await collectDirectory(absolute);
  if (files.length === 0) {
    throw new Error(`release artifact directory is empty: ${path}`);
  }
  return {
    fileCount: files.length,
    path,
    sha256: sha256Bytes(stableJson(files)),
    size: files.reduce((total, file) => total + file.size, 0),
    type: "DIRECTORY",
  };
}

async function describeArtifact(path) {
  return describeAbsoluteArtifact(repoPath(path, "release artifact"), path);
}

export async function resolveDatabaseCompatibility() {
  const names = await readdir(repoPath(
    "backend/src/main/resources/db/migration",
    "migration directory",
  ));
  const versions = names
    .map((name) => name.match(/^V([1-9]\d*)__/))
    .filter(Boolean)
    .map((match) => Number.parseInt(match[1], 10));
  const unique = [...new Set(versions)].sort((left, right) => left - right);
  if (unique.length < 2) {
    throw new Error("release database compatibility needs two Flyway versions");
  }
  const current = unique.at(-1);
  const minimum = unique.at(-2);
  return {
    currentMigration: `V${current}`,
    maximumMigration: `V${current}`,
    minimumMigration: `V${minimum}`,
    policy: "CURRENT_AND_IMMEDIATELY_PREVIOUS_ADDITIVE_SCHEMA",
  };
}

async function sbomReferences(reportDirectory, summary) {
  const expected = [artifactPaths.frontend, artifactPaths.backend];
  if (
    summary.kind !== "supply-chain-v1" ||
    summary.result !== "PASS" ||
    stableJson(summary.artifacts?.map((entry) => entry.artifact)) !==
      stableJson(expected) ||
    summary.artifacts.some((entry) => entry.result !== "PASS") ||
    !summary.cases?.some((entry) =>
      entry.id === "SUPPLY-SBOM-LICENSES" && entry.status === "PASSED") ||
    !summary.cases?.some((entry) =>
      entry.id === "SUPPLY-ARTIFACTS" && entry.status === "PASSED")
  ) {
    throw new Error("release artifact manifest requires a passing artifact/SBOM scan");
  }
  const references = {};
  for (const [index, artifact] of expected.entries()) {
    const path = resolve(reportDirectory, `artifact-${index + 1}-sbom.cdx.json`);
    const sbom = await readJson(path);
    if (
      sbom.bomFormat !== "CycloneDX" ||
      !["1.4", "1.5", "1.6"].includes(sbom.specVersion) ||
      !Array.isArray(sbom.components)
    ) {
      throw new Error(`release artifact SBOM is invalid for ${artifact}`);
    }
    references[artifact === artifactPaths.frontend ? "frontend" : "backend"] = {
      format: "CycloneDX",
      path: relative(repoRoot, path),
      sha256: await sha256File(path),
      specVersion: sbom.specVersion,
    };
  }
  return references;
}

export async function validateReleaseArtifactManifest(
  manifest,
  expectedCommit,
  options = {},
) {
  if (
    manifest?.schemaVersion !== 1 ||
    manifest?.kind !== "release-artifact-manifest-v1" ||
    manifest?.result !== "PASS" ||
    manifest?.releaseCommit !== expectedCommit ||
    manifest?.provenance?.commit !== expectedCommit ||
    manifest?.provenance?.worktreeDirty !== false ||
    !Array.isArray(manifest?.provenance?.artifacts) ||
    manifest.provenance.artifacts.length === 0 ||
    stableJson(manifest?.readinessEndpoints) !== stableJson(readinessEndpoints)
  ) {
    throw new Error("release artifact manifest metadata/provenance is invalid");
  }
  if (
    stableJson(manifest.databaseCompatibility) !==
      stableJson(await resolveDatabaseCompatibility())
  ) {
    throw new Error("release artifact database compatibility is stale");
  }
  for (const name of ["frontend", "backend"]) {
    const artifact = manifest.artifacts?.[name];
    const sbom = manifest.sboms?.[name];
    if (
      artifact?.path !== artifactPaths[name] ||
      !["FILE", "DIRECTORY"].includes(artifact?.type) ||
      !Number.isInteger(artifact?.fileCount) ||
      artifact.fileCount < 1 ||
      !Number.isInteger(artifact?.size) ||
      artifact.size < 1 ||
      !/^[0-9a-f]{64}$/.test(artifact?.sha256 ?? "") ||
      sbom?.format !== "CycloneDX" ||
      !["1.4", "1.5", "1.6"].includes(sbom?.specVersion) ||
      !/^[0-9a-f]{64}$/.test(sbom?.sha256 ?? "")
    ) {
      throw new Error(`release artifact/SBOM metadata is invalid for ${name}`);
    }
    if (options.verifyFiles !== false) {
      if (stableJson(await describeArtifact(artifact.path)) !== stableJson(artifact)) {
        throw new Error(`release artifact checksum drifted for ${name}`);
      }
      if (await sha256File(repoPath(sbom.path, `${name} SBOM`)) !== sbom.sha256) {
        throw new Error(`release artifact SBOM checksum drifted for ${name}`);
      }
    }
  }
  if (
    manifest.buildMetadata?.sourceDateEpoch !==
      manifest.provenance.buildEnvironment?.sourceDateEpoch ||
    manifest.buildMetadata?.commitTimestamp !== manifest.provenance.commitTimestamp ||
    manifest.buildMetadata?.frontendCommand !== "npm run build" ||
    manifest.buildMetadata?.backendCommand !==
      "mvn -B -f backend/pom.xml -DskipTests package" ||
    manifest.reproducibility?.isolatedOutputs !== true ||
    manifest.reproducibility?.sourceDateEpoch !==
      manifest.buildMetadata.sourceDateEpoch ||
    manifest.reproducibility?.frontend?.matchesRelease !== true ||
    manifest.reproducibility?.backend?.matchesRelease !== true ||
    manifest.reproducibility?.frontend?.releaseSha256 !==
      manifest.artifacts.frontend.sha256 ||
    manifest.reproducibility?.frontend?.rebuildSha256 !==
      manifest.artifacts.frontend.sha256 ||
    manifest.reproducibility?.backend?.releaseSha256 !==
      manifest.artifacts.backend.sha256 ||
    manifest.reproducibility?.backend?.rebuildSha256 !==
      manifest.artifacts.backend.sha256 ||
    manifest.reproducibility?.frontend?.command !==
      "npm run build -- --outDir <isolated-output>" ||
    manifest.reproducibility?.backend?.command !==
      "mvn -B -f backend/pom.xml -DskipTests " +
        "-Dproject.build.outputTimestamp=<source-date-epoch> " +
        "-Dvms.build.directory=<isolated-output> package"
  ) {
    throw new Error("release artifact build/reproducibility metadata is incomplete");
  }
  return true;
}

async function reproduceArtifacts(sourceDateEpoch, releaseArtifacts) {
  const temporary = await mkdtemp(join(tmpdir(), "f07-release-repro-"));
  const frontendOutput = resolve(temporary, "frontend");
  const backendOutput = resolve(temporary, "backend");
  try {
    const environment = {
      ...process.env,
      SOURCE_DATE_EPOCH: sourceDateEpoch,
    };
    const frontend = run(
      "npm",
      ["run", "build", "--", "--outDir", frontendOutput],
      { env: environment, timeoutMs: 10 * 60 * 1000 },
    );
    if (frontend.status !== 0 || frontend.error || frontend.signal) {
      throw new Error(`isolated frontend rebuild failed: ${safeError(
        frontend.error ?? frontend.stderr,
      )}`);
    }
    const backend = run(
      "mvn",
      [
        "-B",
        "-f",
        "backend/pom.xml",
        "-DskipTests",
        `-Dproject.build.outputTimestamp=${sourceDateEpoch}`,
        `-Dvms.build.directory=${backendOutput}`,
        "package",
      ],
      { env: environment, timeoutMs: 10 * 60 * 1000 },
    );
    if (backend.status !== 0 || backend.error || backend.signal) {
      throw new Error(`isolated backend rebuild failed: ${safeError(
        backend.error ?? backend.stderr,
      )}`);
    }
    const rebuiltFrontend = await describeAbsoluteArtifact(
      frontendOutput,
      artifactPaths.frontend,
    );
    const rebuiltBackend = await describeAbsoluteArtifact(
      resolve(backendOutput, "workflow-backend-0.1.0-SNAPSHOT.jar"),
      artifactPaths.backend,
    );
    const result = {
      backend: {
        command:
          "mvn -B -f backend/pom.xml -DskipTests " +
          "-Dproject.build.outputTimestamp=<source-date-epoch> " +
          "-Dvms.build.directory=<isolated-output> package",
        matchesRelease:
          rebuiltBackend.sha256 === releaseArtifacts.backend.sha256,
        rebuildSha256: rebuiltBackend.sha256,
        releaseSha256: releaseArtifacts.backend.sha256,
      },
      frontend: {
        command: "npm run build -- --outDir <isolated-output>",
        matchesRelease:
          rebuiltFrontend.sha256 === releaseArtifacts.frontend.sha256,
        rebuildSha256: rebuiltFrontend.sha256,
        releaseSha256: releaseArtifacts.frontend.sha256,
      },
      isolatedOutputs: true,
      sourceDateEpoch,
    };
    if (!result.backend.matchesRelease || !result.frontend.matchesRelease) {
      throw new Error("isolated rebuild differs from the release artifacts");
    }
    return result;
  } finally {
    await rm(temporary, { force: true, recursive: true });
  }
}

export async function verifyCurrentArtifactReproducibility(sourceDateEpoch) {
  const artifacts = {
    backend: await describeArtifact(artifactPaths.backend),
    frontend: await describeArtifact(artifactPaths.frontend),
  };
  return reproduceArtifacts(String(sourceDateEpoch), artifacts);
}

export async function createReleaseArtifactManifest(options) {
  const started = Date.now();
  const git = gitMetadata();
  if (!options.expectedCommit || git.commit !== options.expectedCommit) {
    throw new Error("release artifact manifest requires the exact current commit");
  }
  const provenance = await createProvenance(canonicalProvenanceInputs, {
    expectedCommit: options.expectedCommit,
    requireClean: true,
  });
  const reportDirectory = repoPath(options.supplyReportDir, "supply report directory");
  const artifacts = {
    backend: await describeArtifact(artifactPaths.backend),
    frontend: await describeArtifact(artifactPaths.frontend),
  };
  const manifest = {
    artifacts,
    buildMetadata: {
      backendCommand: "mvn -B -f backend/pom.xml -DskipTests package",
      commitTimestamp: provenance.commitTimestamp,
      frontendCommand: "npm run build",
      sourceDateEpoch: provenance.buildEnvironment.sourceDateEpoch,
    },
    cases: [{
      durationMs: Date.now() - started,
      id: "RELEASE-ARTIFACT-MANIFEST",
      source: "node:scripts/f07/release-artifact-manifest.mjs",
      status: "PASSED",
    }],
    databaseCompatibility: await resolveDatabaseCompatibility(),
    kind: "release-artifact-manifest-v1",
    provenance,
    readinessEndpoints,
    releaseCommit: git.commit,
    reproducibility: await reproduceArtifacts(
      provenance.buildEnvironment.sourceDateEpoch,
      artifacts,
    ),
    result: "PASS",
    sboms: await sbomReferences(
      reportDirectory,
      await readJson(resolve(reportDirectory, "summary.json")),
    ),
    schemaVersion: 1,
  };
  await validateReleaseArtifactManifest(manifest, git.commit);
  return manifest;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args["expected-commit"] || !args["supply-report-dir"] || !args.output) {
    throw new Error(
      "--expected-commit, --supply-report-dir and --output are required",
    );
  }
  const manifest = await createReleaseArtifactManifest({
    expectedCommit: String(args["expected-commit"]),
    supplyReportDir: String(args["supply-report-dir"]),
  });
  await writeJson(repoPath(args.output, "release artifact manifest"), manifest);
  process.stdout.write(stableJson(manifest));
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(
      `F07 release artifact manifest failed safely: ${safeError(error)}\n`,
    );
    process.exitCode = 1;
  });
}
