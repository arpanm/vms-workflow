#!/usr/bin/env node

import { mkdir, readFile } from "node:fs/promises";
import { basename, relative, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import {
  assertNoSymlinkTraversal,
  existingRepoPath,
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
import { requiredCiLanes } from "./evidence-policy.mjs";
import {
  collectJUnitReports,
  deriveVerifiedRecords,
  parseLaneMachineReport,
} from "./machine-reports.mjs";

export function commandText(command) {
  return command
    .map((part) => (/^[A-Za-z0-9_./:=@,+-]+$/.test(part) ? part : JSON.stringify(part)))
    .join(" ");
}

export function commandExecutionSucceeded(execution) {
  return (
    execution.status === 0 &&
    execution.signal === null &&
    execution.error === undefined &&
    execution.timedOut === false
  );
}

export function commandOutputProvesSuccess(output) {
  return (
    output !== null &&
    typeof output === "object" &&
    !Array.isArray(output) &&
    stableJson(Object.keys(output).sort()) ===
      stableJson(
        [
          "errorCode",
          "exitCode",
          "signal",
          "stderrSha256",
          "stdoutSha256",
          "timedOut",
        ].sort(),
      ) &&
    output.exitCode === 0 &&
    output.errorCode === null &&
    output.signal === null &&
    output.timedOut === false &&
    /^[0-9a-f]{64}$/.test(output.stdoutSha256 ?? "") &&
    /^[0-9a-f]{64}$/.test(output.stderrSha256 ?? "")
  );
}

async function runCommand(argv) {
  const delimiter = argv.indexOf("--");
  if (delimiter < 0 || delimiter === argv.length - 1) {
    throw new Error("command evidence requires -- followed by an executable command");
  }
  const args = parseArgs(argv.slice(0, delimiter));
  const command = argv.slice(delimiter + 1);
  if (!args.id || !/^[A-Z0-9-]+$/.test(args.id) || !["task", "test"].includes(args.kind)) {
    throw new Error("--id and --kind task|test are required");
  }
  if (!args.output || !args.environment) {
    throw new Error("--output and --environment are required");
  }
  const lane = requiredCiLanes[args.id];
  const renderedCommand = commandText(command);
  if (
    !lane ||
    args.environment !== "github-actions" ||
    (lane.command !== undefined && renderedCommand !== lane.command) ||
    (lane.commandPattern !== undefined && !lane.commandPattern.test(renderedCommand))
  ) {
    throw new Error("command evidence ID, environment and command must match canonical CI policy");
  }
  const startedAtMs = Date.now();
  const started = performance.now();
  const execution = run(command[0], command.slice(1), {
    timeoutMs: 60 * 60 * 1000,
  });
  const succeeded = commandExecutionSucceeded(execution);
  process.stdout.write(execution.stdout);
  process.stderr.write(execution.stderr);
  let machineReportRaw = null;
  if (succeeded) {
    if (lane.evidenceParser === "junit") {
      machineReportRaw = await collectJUnitReports(
        [
          resolve(repoRoot, "backend/target/surefire-reports"),
          resolve(repoRoot, "backend/target/failsafe-reports"),
        ],
        startedAtMs,
      );
    } else if (lane.evidenceParser !== "none") {
      machineReportRaw = execution.stdout;
    }
  }
  const parsedMachineReport = parseLaneMachineReport(lane, machineReportRaw);
  const observedCases = parsedMachineReport.cases;
  const verifiedResultIds = deriveVerifiedRecords(lane, observedCases);
  const report = {
    commandOutput: {
      errorCode: execution.error?.code ?? null,
      exitCode: execution.status,
      signal: execution.signal,
      stderrSha256: sha256Bytes(execution.stderr),
      stdoutSha256: sha256Bytes(execution.stdout),
      timedOut: execution.timedOut,
    },
    machineReportRaw,
    machineReportSha256: sha256Bytes(stableJson(machineReportRaw)),
    observedCases,
    runner: {
      name: "scripts/f07/command-evidence.mjs",
      schemaVersion: 1,
    },
    releaseCommit: gitMetadata().commit,
    record: {
      command: renderedCommand,
      durationMs: Math.max(0, Math.round(performance.now() - started)),
      environment: String(args.environment),
      id: String(args.id),
      kind: String(args.kind),
      result: succeeded ? "PASS" : "FAIL",
    },
    suiteId: lane.suiteId,
    schemaVersion: 1,
    verifiedResultIds,
  };
  if (parsedMachineReport.structuredResult) {
    report.structuredResult = parsedMachineReport.structuredResult;
  }
  await writeJson(resolve(args.output), report);
  process.exitCode = succeeded ? 0 : 1;
}

async function bindReports(argv) {
  const args = parseArgs(argv);
  if (!args.raw || !args.provenance || !args["output-dir"]) {
    throw new Error("bind requires --raw, --provenance and --output-dir");
  }
  const provenancePath = await existingRepoPath(args.provenance, "provenance");
  const provenance = await readJson(provenancePath);
  const commit = gitMetadata().commit;
  if (
    provenance.commit !== commit ||
    provenance.worktreeDirty !== false ||
    provenance.schemaVersion !== 1
  ) {
    throw new Error("provenance is not clean and bound to the current commit");
  }
  const provenanceReference = {
    path: relative(repoRoot, provenancePath),
    sha256: await sha256File(provenancePath),
  };
  const rawPaths = String(args.raw)
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
  const outputDirectory = repoPath(args["output-dir"], "bound evidence output");
  await assertNoSymlinkTraversal(outputDirectory);
  await mkdir(outputDirectory, { recursive: true, mode: 0o700 });
  const entries = [];
  const seenIds = new Set();
  for (const rawPath of rawPaths) {
    const raw = await readJson(resolve(rawPath));
    const lane = requiredCiLanes[raw.record?.id];
    if (
      raw.schemaVersion !== 1 ||
      raw.releaseCommit !== commit ||
      raw.runner?.name !== "scripts/f07/command-evidence.mjs" ||
      raw.runner?.schemaVersion !== 1 ||
      raw.record?.result !== "PASS" ||
      !commandOutputProvesSuccess(raw.commandOutput) ||
      !raw.record?.id ||
      !lane ||
      raw.suiteId !== lane.suiteId ||
      (lane.command !== undefined && raw.record?.command !== lane.command) ||
      (lane.commandPattern !== undefined &&
        !lane.commandPattern.test(raw.record?.command ?? "")) ||
      seenIds.has(raw.record.id)
    ) {
      throw new Error(`raw command evidence did not pass or has wrong commit: ${rawPath}`);
    }
    const reparsed = parseLaneMachineReport(lane, raw.machineReportRaw ?? null);
    const observedCases = reparsed.cases;
    const verifiedResultIds = deriveVerifiedRecords(lane, observedCases);
    if (
      stableJson(raw.observedCases ?? []) !== stableJson(observedCases) ||
      raw.machineReportSha256 !==
        sha256Bytes(stableJson(raw.machineReportRaw ?? null)) ||
      (typeof raw.machineReportRaw === "string" &&
        raw.commandOutput.stdoutSha256 !== sha256Bytes(raw.machineReportRaw)) ||
      stableJson(raw.verifiedResultIds ?? []) !== stableJson(verifiedResultIds) ||
      stableJson(raw.structuredResult ?? null) !==
        stableJson(reparsed.structuredResult ?? null)
    ) {
      throw new Error(`raw command evidence does not match its machine report: ${rawPath}`);
    }
    seenIds.add(raw.record.id);
    const bound = {
      commandOutput: raw.commandOutput,
      provenance: provenanceReference,
      record: raw.record,
      releaseCommit: commit,
      runner: raw.runner,
      machineReportRaw: raw.machineReportRaw,
      machineReportSha256: raw.machineReportSha256,
      observedCases,
      suiteId: raw.suiteId,
      schemaVersion: 1,
      structuredResult: raw.structuredResult,
      verifiedResultIds,
    };
    const output = resolve(outputDirectory, `${raw.record.id}.json`);
    await writeJson(output, bound);
    entries.push({
      ...raw.record,
      commandOutput: raw.commandOutput,
      evidence: {
        path: relative(repoRoot, output),
        sha256: await sha256File(output),
      },
      machineReportRaw: raw.machineReportRaw,
      machineReportSha256: raw.machineReportSha256,
      suiteId: raw.suiteId,
      observedCases,
      structuredResult: raw.structuredResult,
      verifiedResultIds,
    });
  }
  const bundle = {
    entries: entries.sort((left, right) => left.id.localeCompare(right.id)),
    releaseCommit: commit,
    schemaVersion: 1,
  };
  await writeJson(resolve(outputDirectory, "ci-evidence-bundle.json"), bundle);
  process.stdout.write(stableJson(bundle));
}

async function main() {
  const [subcommand, ...argv] = process.argv.slice(2);
  if (subcommand === "run") {
    await runCommand(argv);
  } else if (subcommand === "bind") {
    await bindReports(argv);
  } else {
    throw new Error("usage: command-evidence.mjs <run|bind> ...");
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(`F07 command evidence failed safely: ${safeError(error)}\n`);
    process.exitCode = 1;
  });
}
