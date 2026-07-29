#!/usr/bin/env node

import { randomUUID } from "node:crypto";
import { pathToFileURL } from "node:url";
import {
  isLoopbackUrl,
  parseArgs,
  percentile,
  readJson,
  repoPath,
  safeError,
  stableJson,
  writeJson,
} from "./lib.mjs";

function validateProfile(profile, allowMutations) {
  if (!profile.id || !Array.isArray(profile.requests) || profile.requests.length === 0) {
    throw new Error("profile needs an ID and at least one request");
  }
  if (!Number.isInteger(profile.concurrency) || profile.concurrency < 1 || profile.concurrency > 100) {
    throw new Error("concurrency must be an integer between 1 and 100");
  }
  const iterationProfile =
    Number.isInteger(profile.iterations) && profile.iterations >= 1 && profile.iterations <= 100000;
  const soakProfile =
    Number.isInteger(profile.durationSeconds) &&
    profile.durationSeconds >= 60 &&
    profile.durationSeconds <= 172800 &&
    Number.isInteger(profile.segmentSeconds) &&
    profile.segmentSeconds >= 10 &&
    profile.segmentSeconds <= 3600;
  if (!iterationProfile && !soakProfile) {
    throw new Error(
      "profile needs 1..100000 iterations or durationSeconds plus a 10..3600 second segment",
    );
  }
  if (profile.requiresActors !== undefined &&
      (!Number.isInteger(profile.requiresActors) ||
       profile.requiresActors < 1 ||
       profile.requiresActors > 10000)) {
    throw new Error("requiresActors must be an integer between 1 and 10000");
  }
  if (profile.requiredEnvironment !== undefined &&
      (!Array.isArray(profile.requiredEnvironment) ||
       profile.requiredEnvironment.some((name) => !/^[A-Z][A-Z0-9_]*$/.test(name)))) {
    throw new Error("requiredEnvironment must contain uppercase environment names");
  }
  for (const request of profile.requests) {
    const method = String(request.method ?? "GET").toUpperCase();
    if (request.auth !== undefined && !["actor", "none", "shared"].includes(request.auth)) {
      throw new Error("request auth must be actor, shared, or none");
    }
    if (!["GET", "HEAD"].includes(method) && !allowMutations) {
      throw new Error("mutation profile requires --allow-synthetic-mutations");
    }
    if (request.headers?.authorization || request.headers?.cookie) {
      throw new Error("credentials are forbidden in load profile files");
    }
  }
}

function resolveRuntimeValue(value, context, requiredEnvironment) {
  if (Array.isArray(value)) {
    return value.map((child) => resolveRuntimeValue(child, context, requiredEnvironment));
  }
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([key, child]) => [
        key,
        resolveRuntimeValue(child, context, requiredEnvironment),
      ]),
    );
  }
  if (typeof value !== "string") {
    return value;
  }
  const integerPlaceholder = value.match(/^\$\{int:([A-Z][A-Z0-9_]*)\}$/);
  if (integerPlaceholder) {
    const name = integerPlaceholder[1];
    if (!requiredEnvironment.has(name)) {
      throw new Error(`profile placeholder ${name} is not declared in requiredEnvironment`);
    }
    const resolved = process.env[name];
    if (!/^-?\d+$/.test(resolved ?? "")) {
      throw new Error(`required environment variable ${name} must be an integer`);
    }
    return Number.parseInt(resolved, 10);
  }
  return value.replaceAll(/\$\{([A-Za-z][A-Za-z0-9_]*)\}/g, (_match, name) => {
    if (Object.hasOwn(context, name)) {
      return String(context[name]);
    }
    if (!requiredEnvironment.has(name)) {
      throw new Error(`profile placeholder ${name} is not declared in requiredEnvironment`);
    }
    const resolved = process.env[name];
    if (!resolved) {
      throw new Error(`required environment variable ${name} is absent`);
    }
    return resolved;
  });
}

async function executeOne(baseUrl, rawRequest, timeoutMs, context, authorization) {
  const request = resolveRuntimeValue(
    rawRequest,
    context,
    new Set(context.requiredEnvironment),
  );
  const started = performance.now();
  const method = String(request.method ?? "GET").toUpperCase();
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(new URL(request.path, baseUrl), {
      body: request.body === undefined ? undefined : JSON.stringify(request.body),
      headers: {
        accept: "application/json",
        ...(request.body === undefined ? {} : { "content-type": "application/json" }),
        "idempotency-key": context.idempotencyKey,
        ...(request.headers ?? {}),
        ...(authorization ? { authorization: `Bearer ${authorization}` } : {}),
        "x-correlation-id": randomUUID(),
      },
      method,
      redirect: "error",
      signal: controller.signal,
    });
    await response.arrayBuffer();
    return {
      durationMs: Math.round((performance.now() - started) * 100) / 100,
      ok: response.ok,
      status: response.status,
    };
  } catch (error) {
    return {
      durationMs: Math.round((performance.now() - started) * 100) / 100,
      error: error.name === "AbortError" ? "TIMEOUT" : "REQUEST_FAILED",
      ok: false,
      status: null,
    };
  } finally {
    clearTimeout(timer);
  }
}

function runtimeOptions(profile, options) {
  const actorIds = options.actorIds ?? [];
  const actorBearerTokens = options.actorBearerTokens ?? [];
  if (profile.requiresActors !== undefined && actorIds.length !== profile.requiresActors) {
    throw new Error(`profile requires exactly ${profile.requiresActors} actor IDs`);
  }
  if (actorBearerTokens.length > 0 && actorBearerTokens.length !== actorIds.length) {
    throw new Error("actor bearer token count must equal actor ID count");
  }
  if (actorIds.length > 0 && new Set(actorIds).size !== actorIds.length) {
    throw new Error("actor IDs must be unique");
  }
  return { actorBearerTokens, actorIds };
}

export async function runProfile(baseUrl, profile, allowMutations = false, options = {}) {
  if (!isLoopbackUrl(baseUrl)) {
    throw new Error("load harness only accepts loopback HTTP(S) targets");
  }
  validateProfile(profile, allowMutations);
  const runtime = runtimeOptions(profile, options);
  const runId = randomUUID();
  const resumed = options.resumeState ?? null;
  if (resumed && resumed.profileId !== profile.id) {
    throw new Error("resume state belongs to another profile");
  }
  const targetDurationMs = profile.durationSeconds === undefined
    ? null
    : profile.durationSeconds * 1000;
  const completedDurationMs = resumed?.completion?.completedDurationMs ?? 0;
  const remainingDurationMs = targetDurationMs === null
    ? null
    : Math.max(0, targetDurationMs - completedDurationMs);
  const segmentDurationMs = remainingDurationMs === null
    ? null
    : Math.min(profile.segmentSeconds * 1000, remainingDurationMs);
  let nextIndex = 0;
  const results = [];
  const timeoutMs = profile.requestTimeoutMs ?? 5000;
  const started = performance.now();
  const takeNext = () => {
    if (profile.iterations !== undefined) {
      if (nextIndex >= profile.iterations) {
        return null;
      }
    } else if (performance.now() - started >= segmentDurationMs) {
      return null;
    }
    const current = nextIndex;
    nextIndex += 1;
    return current;
  };
  const workers = Array.from({ length: profile.concurrency }, async () => {
    while (true) {
      const index = takeNext();
      if (index === null) {
        return;
      }
      const request = profile.requests[index % profile.requests.length];
      const actorIndex = runtime.actorIds.length === 0
        ? null
        : index % runtime.actorIds.length;
      const context = {
        actorId: actorIndex === null ? "" : runtime.actorIds[actorIndex],
        actorIndex: actorIndex === null ? "" : actorIndex,
        idempotencyKey: `${profile.id}:${runId}:${index}`,
        iteration: index,
        requiredEnvironment: profile.requiredEnvironment ?? [],
        runId,
      };
      const authMode = request.auth ?? (actorIndex === null ? "shared" : "actor");
      const authorization = authMode === "none"
        ? undefined
        : authMode === "shared"
          ? options.bearerToken
          : runtime.actorBearerTokens[actorIndex] ?? options.bearerToken;
      results.push(await executeOne(baseUrl, request, timeoutMs, context, authorization));
    }
  });
  const startedAt = new Date().toISOString();
  await Promise.all(workers);
  const elapsedMs = Math.round(performance.now() - started);
  const durations = results.map((result) => result.durationMs);
  const failures = results.filter((result) => !result.ok);
  const p95Ms = percentile(durations, 95);
  const budget = profile.budget ?? {};
  const errorRate = results.length === 0 ? 1 : failures.length / results.length;
  const previousSegments = resumed?.segments ?? [];
  const segment = {
    elapsedMs,
    errorRate,
    failures: failures.length,
    finishedAt: new Date().toISOString(),
    p50Ms: percentile(durations, 50),
    p95Ms,
    p99Ms: percentile(durations, 99),
    requests: results.length,
    startedAt,
  };
  const segments = [...previousSegments, segment];
  const totalRequests = segments.reduce((total, value) => total + value.requests, 0);
  const totalFailures = segments.reduce((total, value) => total + value.failures, 0);
  const overallP95Ms = Math.max(...segments.map((value) => value.p95Ms));
  const overallErrorRate = totalRequests === 0 ? 1 : totalFailures / totalRequests;
  const findings = [];
  if (budget.p95Ms !== undefined && overallP95Ms > budget.p95Ms) {
    findings.push(`p95 ${overallP95Ms}ms exceeds ${budget.p95Ms}ms`);
  }
  if (budget.maximumErrorRate !== undefined &&
      overallErrorRate > budget.maximumErrorRate) {
    findings.push(
      `error rate ${overallErrorRate} exceeds ${budget.maximumErrorRate}`,
    );
  }
  const newCompletedDurationMs = targetDurationMs === null
    ? elapsedMs
    : Math.min(targetDurationMs, completedDurationMs + elapsedMs);
  const incompleteSoak =
    targetDurationMs !== null && newCompletedDurationMs < targetDurationMs;
  if (incompleteSoak) {
    findings.push(
      `long soak incomplete: ${newCompletedDurationMs}ms of ${targetDurationMs}ms; resume required`,
    );
  }
  return {
    baseUrl: new URL(baseUrl).origin,
    completion: {
      actionRequired: incompleteSoak
        ? "ACTION_REQUIRED_LONG_SOAK_NOT_COMPLETE"
        : null,
      completedDurationMs: newCompletedDurationMs,
      resumable: targetDurationMs !== null,
      targetDurationMs,
    },
    elapsedMs: segments.reduce((total, value) => total + value.elapsedMs, 0),
    errorRate: overallErrorRate,
    externalVerification: profile.externalApproval ?? null,
    failures: totalFailures,
    finishedAt: segment.finishedAt,
    p50Ms: Math.max(...segments.map((value) => value.p50Ms)),
    p95Ms: overallP95Ms,
    p99Ms: Math.max(...segments.map((value) => value.p99Ms)),
    profileId: profile.id,
    requests: totalRequests,
    result: incompleteSoak ? "ACTION_REQUIRED" : findings.length === 0 ? "PASS" : "FAIL",
    segments,
    startedAt: segments[0].startedAt,
    thresholdFindings: findings,
  };
}

function commaSeparatedEnvironment(name, label) {
  if (!name) {
    return [];
  }
  const value = process.env[String(name)];
  if (!value) {
    throw new Error(`${label} environment variable ${name} is absent`);
  }
  return value.split(",").map((item) => item.trim()).filter(Boolean);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.profile || !args["base-url"]) {
    throw new Error("--profile and --base-url are required");
  }
  const profile = await readJson(repoPath(args.profile, "profile"));
  const bearerToken = args["bearer-token-env"]
    ? process.env[String(args["bearer-token-env"])]
    : undefined;
  if (args["bearer-token-env"] && !bearerToken) {
    throw new Error(`bearer token environment variable ${args["bearer-token-env"]} is absent`);
  }
  const resumeState = args["resume-from"]
    ? await readJson(repoPath(args["resume-from"], "resume state"))
    : undefined;
  const report = await runProfile(
    String(args["base-url"]),
    profile,
    Boolean(args["allow-synthetic-mutations"]),
    {
      actorBearerTokens: commaSeparatedEnvironment(
        args["actor-bearer-tokens-env"],
        "actor bearer tokens",
      ),
      actorIds: commaSeparatedEnvironment(args["actor-ids-env"], "actor IDs"),
      bearerToken,
      resumeState,
    },
  );
  if (args.output) {
    await writeJson(repoPath(args.output, "output"), report);
  }
  process.stdout.write(stableJson(report));
  process.exitCode = report.result === "PASS" ? 0 : 1;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(`F07 load harness failed safely: ${safeError(error)}\n`);
    process.exitCode = 1;
  });
}
