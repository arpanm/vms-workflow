import {
  createServer,
} from "node:http";
import {
  createConnection,
} from "node:net";
import {
  generateKeyPairSync,
  sign,
} from "node:crypto";
import {
  spawn,
  spawnSync,
} from "node:child_process";
import {
  once,
} from "node:events";
import {
  mkdtempSync,
  rmSync,
} from "node:fs";
import {
  tmpdir,
} from "node:os";
import {
  dirname,
  join,
} from "node:path";
import {
  fileURLToPath,
} from "node:url";

const repositoryRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const systemSuite = process.env.VMS_E2E_SYSTEM_SUITE ?? "finance";
const systemProject = process.env.VMS_E2E_SYSTEM_PROJECT
  ?? (systemSuite === "migration"
    ? "f06-migration-system-chromium"
    : systemSuite === "f07"
      ? "f07-real-system-chromium"
      : "f05-finance-system-chromium");
const overallDeadline = performance.now() + 15 * 60 * 1000;
const children = new Set();
let stopped = false;
let backgroundFailure = null;
const backendPort = await availablePort();
const frontendPort = await availablePort();
const postgresName =
  `vms-${systemSuite}-system-${process.pid}-${Date.now().toString(36)}`;
const postgresPassword = `system-e2e-${process.pid}`;
const isolatedMavenTarget = mkdtempSync(
  join(tmpdir(), `vms-${systemSuite}-system-maven-`),
);

const { privateKey, publicKey } = generateKeyPairSync("rsa", {
  modulusLength: 2048,
});
const publicJwk = publicKey.export({ format: "jwk" });
const keyId = "vms-finance-system-e2e";
const jwksServer = createServer((request, response) => {
  if (request.url !== "/jwks") {
    response.writeHead(404).end();
    return;
  }
  response.writeHead(200, {
    "Content-Type": "application/json",
    "Cache-Control": "no-store",
  });
  response.end(JSON.stringify({
    keys: [{
      ...publicJwk,
      kid: keyId,
      use: "sig",
      alg: "RS256",
    }],
  }));
});
jwksServer.listen(0, "127.0.0.1");
await once(jwksServer, "listening", {
  signal: AbortSignal.timeout(remainingMilliseconds("JWKS startup", 10_000)),
});
const jwksAddress = jwksServer.address();
if (!jwksAddress || typeof jwksAddress === "string") {
  throw new Error("Unable to bind the local JWKS server.");
}
const issuer = `http://127.0.0.1:${jwksAddress.port}`;

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, async () => {
    await cleanup();
    process.exit(130);
  });
}
process.on("exit", () => {
  if (!stopped) {
    spawnSync("docker", ["rm", "-f", postgresName], {
      killSignal: "SIGKILL",
      stdio: "ignore",
      timeout: cleanupTimeout(2_000),
    });
  }
});

try {
  requireCommand("docker", ["version"]);
  requireCommand("mvn", ["-version"]);
  requireCommand("npx", ["playwright", "--version"]);

  runChecked("docker", [
    "run", "--rm", "--detach",
    "--name", postgresName,
    "--publish", "127.0.0.1::5432",
    "--tmpfs", "/var/lib/postgresql:rw,nosuid,nodev,size=1g",
    "--env", "POSTGRES_DB=vms_workflow",
    "--env", "POSTGRES_USER=vms",
    "--env", `POSTGRES_PASSWORD=${postgresPassword}`,
    "postgres:18-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15",
  ], 60_000);
  const postgresPort = mappedPostgresPort();
  await waitForPostgres(postgresPort);

  const tokenEnvironment = Object.fromEntries([
    ["VMS_E2E_TOKEN_USER_ARROW", "user-arrow"],
    ["VMS_E2E_TOKEN_USER_PROCUREMENT", "user-procurement"],
    ["VMS_E2E_TOKEN_USER_FINANCE_AP", "user-finance-ap"],
    ["VMS_E2E_TOKEN_USER_GOVERNANCE", "user-governance"],
    ["VMS_E2E_TOKEN_USER_NORTHSTAR", "user-northstar"],
    ["VMS_E2E_TOKEN_USER_EMPLOYEE", "user-employee"],
    ["VMS_E2E_TOKEN_USER_E2E_EMPLOYEE", "user-e2e-employee"],
    ["VMS_E2E_TOKEN_USER_RELIANCE", "user-reliance"],
    ["VMS_E2E_TOKEN_USER_APPROVER", "user-approver"],
    ["VMS_E2E_TOKEN_USER_INBOUND", "service-inbound"],
    ["VMS_E2E_TOKEN_USER_REVIEWER", "user-reviewer"],
  ].map(([name, subject]) => [name, jwt(subject)]));

  const migrationLocations = [
    "classpath:db/migration",
    `filesystem:${join(repositoryRoot, "backend/src/test/resources/db/testdata")}`,
    `filesystem:${join(
      repositoryRoot,
      systemSuite === "migration"
        ? "e2e/system/db/migration"
        : systemSuite === "f07"
          ? "e2e/system/db/f07"
          : "e2e/system/db/finance",
    )}`,
  ].join(",");
  start("mvn", [
    "-B", "-f", "backend/pom.xml", "spring-boot:run",
    `-Dvms.build.directory=${isolatedMavenTarget}`,
    "-Dspring-boot.run.arguments=--vms.finance.worker-initial-delay=PT0.5S --vms.finance.worker-delay=PT0.5S",
  ], {
    SERVER_PORT: String(backendPort),
    VMS_DATABASE_URL:
      `jdbc:postgresql://127.0.0.1:${postgresPort}/vms_workflow`,
    VMS_DATABASE_USERNAME: "vms",
    VMS_DATABASE_PASSWORD: postgresPassword,
    VMS_OIDC_JWKS_URI: `${issuer}/jwks`,
    VMS_OIDC_ISSUER: issuer,
    VMS_OIDC_AUDIENCE: "vms-api",
    SPRING_PROFILES_ACTIVE:
      systemSuite === "f07" ? "system-e2e" : "",
    VMS_CLOCK_FIXED_INSTANT:
      systemSuite === "f07" ? "2026-07-29T10:00:00Z" : "",
    VMS_FINANCE_LOCAL_SCANNER_ENABLED: "true",
    VMS_MIGRATION_LOCAL_SCANNER_ENABLED: "true",
    VMS_FINANCE_CURSOR_SIGNING_SECRET:
      "system-e2e-cursor-signing-secret-with-at-least-32-bytes",
    VMS_FINANCE_MUTATIONS_PER_MINUTE: "1000",
    VMS_FINANCE_DOWNLOADS_PER_MINUTE: "1000",
    VMS_FINANCE_EXPORTS_PER_MINUTE: "1000",
    VMS_LINEAR_WEBHOOK_SECRET_SET:
      '{"secret://local-fixture/linear/webhook":{"current":"test-webhook-secret"},'
        + '"secret://local-fixture/linear/webhook-b":{"current":"test-webhook-secret-b"}}',
    VMS_DELIVERY_COMMITMENT_WORKER_ENABLED:
      systemSuite === "f07" ? "true" : "false",
    VMS_DELIVERY_COMMITMENT_WORKER_DELAY: "PT0.2S",
    VMS_DELIVERY_COMMITMENT_WORKER_INITIAL_DELAY: "PT0.2S",
    VMS_DELIVERY_COMMITMENT_RETRY_DELAY: "PT0.2S",
    VMS_DELIVERY_COMMITMENT_PROVIDER_STATUS:
      systemSuite === "f07" ? "CONFIGURED" : "NOT_CONFIGURED",
    VMS_CERTIFICATION_WORKER_ENABLED:
      systemSuite === "f07" ? "true" : "false",
    VMS_CERTIFICATION_WORKER_DELAY: "PT0.2S",
    VMS_CERTIFICATION_WORKER_INITIAL_DELAY: "PT0.2S",
    VMS_CERTIFICATION_INBOUND_SIGNING_SECRET:
      "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    VMS_CERTIFICATION_TOKEN_HANDOFF_KEY:
      "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    VMS_CERTIFICATION_EMAIL_PROVIDER_STATUS: "CONFIGURED",
    VMS_CERTIFICATION_F05_HANDOFF_STATUS: "CONFIGURED",
    SPRING_FLYWAY_LOCATIONS: migrationLocations,
  });
  await waitForUrl(`http://127.0.0.1:${backendPort}/actuator/health`, 180_000);

  start("npm", [
    "run", "dev", "--", "--host", "127.0.0.1",
    "--port", String(frontendPort), "--strictPort",
  ], {
    VITE_API_BASE_URL: "/api/v1",
    VITE_BACKEND_DEV_URL: `http://127.0.0.1:${backendPort}`,
    VITE_DEMO_MODE: "false",
    VITE_E2E_SYSTEM_AUTH: "true",
    VITE_FEATURE_LEGACY_FIXED_COST: "true",
    VITE_FEATURE_WORKFORCE_GOVERNANCE:
      systemSuite === "f07" ? "true" : "false",
    VITE_FEATURE_GREYTHR: systemSuite === "f07" ? "true" : "false",
    VITE_FEATURE_LINEAR: systemSuite === "f07" ? "true" : "false",
  });
  const frontendUrl = `http://127.0.0.1:${frontendPort}`;
  await waitForUrl(frontendUrl, 120_000);

  const result = await runForeground(
    "npx",
    [
      "playwright", "test",
      "--config", "playwright.system.config.ts",
      "--project", systemProject,
      "--reporter=json",
    ],
    {
      ...tokenEnvironment,
      VMS_E2E_FRONTEND_URL: frontendUrl,
      VMS_E2E_POSTGRES_CONTAINER: postgresName,
    },
  );
  // This command is consumed by the F07 machine-report parser. All service
  // diagnostics are sent to stderr so stdout remains exactly one Playwright
  // JSON document and cannot be confused with self-declared test evidence.
  // Emit the machine report even when Playwright fails so the failing
  // assertion remains inspectable instead of being replaced by a wrapper
  // error and an empty evidence artifact.
  let playwrightReport;
  try {
    playwrightReport = JSON.parse(result.stdout);
  } catch {
    throw new Error(
      `${systemSuite} system Playwright did not emit one JSON report `
        + `(prefix=${JSON.stringify(result.stdout.slice(0, 80))}).`,
    );
  }
  process.stdout.write(JSON.stringify(playwrightReport));
  if (result.code !== 0) {
    throw new Error(
      `${systemSuite} system Playwright exited with ${result.code}.`,
    );
  }
} finally {
  await cleanup();
}

function jwt(subject) {
  const now = Math.floor(Date.now() / 1000);
  const header = encode({ alg: "RS256", typ: "JWT", kid: keyId });
  const payload = encode({
    iss: issuer,
    sub: subject,
    aud: ["vms-api"],
    iat: now,
    nbf: now - 5,
    exp: now + 3600,
    jti: `${subject}-${now}-${process.pid}`,
  });
  const signingInput = `${header}.${payload}`;
  const signature = sign(
    "RSA-SHA256",
    Buffer.from(signingInput),
    privateKey,
  ).toString("base64url");
  return `${signingInput}.${signature}`;
}

function encode(value) {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

async function availablePort() {
  const server = createServer();
  server.listen(0, "127.0.0.1");
  await once(server, "listening", {
    signal: AbortSignal.timeout(
      remainingMilliseconds("system-E2E port allocation", 10_000),
    ),
  });
  const address = server.address();
  if (!address || typeof address === "string") {
    server.close();
    throw new Error("Unable to allocate a local system-test port.");
  }
  const port = address.port;
  const closed = once(server, "close", {
    signal: AbortSignal.timeout(
      remainingMilliseconds("system-E2E port release", 10_000),
    ),
  });
  server.close();
  await closed;
  return port;
}

function start(command, args, extraEnvironment) {
  const child = spawn(command, args, {
    cwd: repositoryRoot,
    stdio: ["ignore", "pipe", "pipe"],
    env: { ...process.env, ...extraEnvironment },
  });
  child.stdout.pipe(process.stderr);
  child.stderr.pipe(process.stderr);
  children.add(child);
  const deadlineTimer = setTimeout(
    () => terminateChild(child),
    remainingMilliseconds(`${command} service lifetime`),
  );
  child.once("exit", (code, signal) => {
    clearTimeout(deadlineTimer);
    children.delete(child);
    if (!stopped && code !== 0) {
      process.stderr.write(
        `${command} exited early (${code ?? signal ?? "unknown"}).\n`,
      );
    }
  });
  child.once("error", (error) => {
    clearTimeout(deadlineTimer);
    children.delete(child);
    backgroundFailure = new Error(`${command} failed to start: ${error.message}`);
  });
  return child;
}

function runForeground(command, args, extraEnvironment) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: repositoryRoot,
      stdio: ["ignore", "pipe", "pipe"],
      env: { ...process.env, ...extraEnvironment },
    });
    let stdout = "";
    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    child.stderr.pipe(process.stderr);
    children.add(child);
    let settled = false;
    const finish = (action) => {
      if (settled) return;
      settled = true;
      clearTimeout(deadlineTimer);
      action();
    };
    const deadlineTimer = setTimeout(() => {
      terminateChild(child);
      finish(() =>
        reject(new Error(`${command} exceeded the overall system-E2E deadline.`)),
      );
    }, remainingMilliseconds(`${command} foreground execution`));
    child.once("error", (error) => {
      children.delete(child);
      finish(() => reject(error));
    });
    child.once("exit", (code, signal) => {
      children.delete(child);
      if (signal) {
        finish(() => reject(new Error(`${command} exited from ${signal}.`)));
        return;
      }
      finish(() => resolve({ code: code ?? 1, stdout }));
    });
  });
}

function boundedSpawnSync(command, args, options, label, capMilliseconds) {
  const result = spawnSync(command, args, {
    ...options,
    cwd: repositoryRoot,
    killSignal: "SIGKILL",
    timeout: remainingMilliseconds(label, capMilliseconds),
  });
  if (
    result.status !== 0 ||
    result.signal !== null ||
    result.error !== undefined
  ) {
    const detail = result.error?.code ?? result.signal ?? result.status ?? "unknown";
    throw new Error(`${label} failed (${detail}).`);
  }
  return result;
}

function runChecked(command, args, capMilliseconds = 30_000) {
  try {
    boundedSpawnSync(
      command,
      args,
      // Docker detach prints its container ID on stdout. Keep stdout reserved
      // for the single Playwright JSON report consumed by the release parser.
      { stdio: ["ignore", 2, 2] },
      `${command} ${args.join(" ")}`,
      capMilliseconds,
    );
  } catch (error) {
    throw new Error(`${command} ${args.join(" ")} failed.`);
  }
}

function requireCommand(command, args) {
  try {
    boundedSpawnSync(
      command,
      args,
      { stdio: "ignore" },
      `${command} availability check`,
      15_000,
    );
  } catch {
    throw new Error(`${command} is required for ${systemSuite} system E2E.`);
  }
}

async function waitForPostgres(postgresPort) {
  const deadline = Math.min(overallDeadline, performance.now() + 180_000);
  let lastProbeFailure = "not attempted";
  while (performance.now() < deadline) {
    if (backgroundFailure) throw backgroundFailure;
    try {
      await waitForTcpConnection(
        "127.0.0.1",
        postgresPort,
        Math.min(
          1_000,
          Math.max(1, Math.floor(deadline - performance.now())),
        ),
      );
      return;
    } catch (error) {
      lastProbeFailure = error instanceof Error
        ? error.message : String(error);
      const remaining = deadline - performance.now();
      if (remaining <= 0) break;
      await delay(Math.min(500, remaining));
    }
  }
  let containerState = "unavailable";
  let databaseLog = "unavailable";
  try {
    containerState = boundedSpawnSync(
      "docker",
      ["inspect", "--format", "{{json .State}}", postgresName],
      { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] },
      "PostgreSQL failure-state inspection",
      10_000,
    ).stdout.trim();
  } catch (error) {
    containerState = error instanceof Error ? error.message : String(error);
  }
  try {
    const logResult = boundedSpawnSync(
      "docker",
      ["logs", "--tail", "80", postgresName],
      { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] },
      "PostgreSQL failure-log inspection",
      10_000,
    );
    databaseLog = [logResult.stdout, logResult.stderr]
      .filter(Boolean)
      .join("\n")
      .trim();
  } catch (error) {
    databaseLog = error instanceof Error ? error.message : String(error);
  }
  process.stderr.write(
    `PostgreSQL readiness failed: lastProbe=${lastProbeFailure}; `
      + `state=${containerState}; logTail=${databaseLog}\n`,
  );
  throw new Error(
    "PostgreSQL system-E2E container did not become ready within 180 seconds.",
  );
}

function waitForTcpConnection(host, port, timeoutMilliseconds) {
  return new Promise((resolve, reject) => {
    const socket = createConnection({ host, port });
    const timer = setTimeout(() => {
      socket.destroy();
      reject(new Error(
        `PostgreSQL TCP readiness probe timed out after ${timeoutMilliseconds}ms.`,
      ));
    }, timeoutMilliseconds);
    socket.once("connect", () => {
      clearTimeout(timer);
      socket.destroy();
      resolve();
    });
    socket.once("error", (error) => {
      clearTimeout(timer);
      socket.destroy();
      reject(error);
    });
  });
}

function mappedPostgresPort() {
  const result = boundedSpawnSync(
    "docker",
    ["port", postgresName, "5432/tcp"],
    { encoding: "utf8" },
    "PostgreSQL mapped-port lookup",
    10_000,
  );
  const match = result.stdout.trim().match(/:(\d+)$/);
  if (!match) throw new Error("Unexpected docker port output.");
  return Number(match[1]);
}

async function waitForUrl(url, timeout) {
  const deadline = Math.min(overallDeadline, performance.now() + timeout);
  let lastError = "not attempted";
  while (performance.now() < deadline) {
    if (backgroundFailure) throw backgroundFailure;
    try {
      const response = await fetch(url, {
        signal: AbortSignal.timeout(
          Math.max(
            1,
            Math.min(5_000, Math.floor(deadline - performance.now())),
          ),
        ),
      });
      if (response.ok) return;
      lastError = `HTTP ${response.status}`;
    } catch (error) {
      lastError = error instanceof Error ? error.message : String(error);
    }
    const remaining = deadline - performance.now();
    if (remaining > 0) {
      await delay(Math.min(750, remaining));
    }
  }
  throw new Error(`Timed out waiting for ${url}: ${lastError}`);
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function remainingMilliseconds(label, capMilliseconds = Number.POSITIVE_INFINITY) {
  const remaining = Math.floor(overallDeadline - performance.now());
  if (remaining <= 0) {
    throw new Error(`${label} cannot start because the overall system-E2E deadline expired.`);
  }
  return Math.max(1, Math.min(remaining, capMilliseconds));
}

function cleanupTimeout(capMilliseconds) {
  return Math.max(
    1,
    Math.min(capMilliseconds, Math.floor(overallDeadline - performance.now())),
  );
}

function terminateChild(child) {
  if (child.exitCode !== null || child.signalCode !== null) return;
  child.kill("SIGTERM");
  const killTimer = setTimeout(() => {
    if (child.exitCode === null && child.signalCode === null) {
      child.kill("SIGKILL");
    }
  }, cleanupTimeout(2_000));
  killTimer.unref();
}

async function cleanup() {
  if (stopped) return;
  stopped = true;
  for (const child of children) {
    terminateChild(child);
  }
  await Promise.all(
    [...children].map(async (child) => {
      await Promise.race([
        once(child, "exit"),
        delay(cleanupTimeout(5_000)),
      ]);
      if (child.exitCode === null) child.kill("SIGKILL");
    }),
  );
  const jwksClosed = once(jwksServer, "close");
  jwksServer.close();
  await Promise.race([
    jwksClosed,
    delay(cleanupTimeout(2_000)),
  ]);
  jwksServer.closeAllConnections();
  spawnSync("docker", ["rm", "-f", postgresName], {
    killSignal: "SIGKILL",
    stdio: "ignore",
    timeout: cleanupTimeout(5_000),
  });
  rmSync(isolatedMavenTarget, { recursive: true, force: true });
}
