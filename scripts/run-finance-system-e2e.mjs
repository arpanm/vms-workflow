import {
  createServer,
} from "node:http";
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
const systemProject = systemSuite === "migration"
  ? "f06-migration-system-chromium"
  : "f05-finance-system-chromium";
const backendPort = await availablePort();
const frontendPort = await availablePort();
const postgresName =
  `vms-${systemSuite}-system-${process.pid}-${Date.now().toString(36)}`;
const postgresPassword = `system-e2e-${process.pid}`;
const isolatedMavenTarget = mkdtempSync(
  join(tmpdir(), `vms-${systemSuite}-system-maven-`),
);
const children = new Set();
let stopped = false;

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
await once(jwksServer, "listening");
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
    spawnSync("docker", ["rm", "-f", postgresName], { stdio: "ignore" });
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
    "--env", "POSTGRES_DB=vms_workflow",
    "--env", "POSTGRES_USER=vms",
    "--env", `POSTGRES_PASSWORD=${postgresPassword}`,
    "postgres:18-alpine",
  ]);
  await waitForPostgres();
  const postgresPort = mappedPostgresPort();

  const tokenEnvironment = Object.fromEntries([
    ["VMS_E2E_TOKEN_USER_ARROW", "user-arrow"],
    ["VMS_E2E_TOKEN_USER_PROCUREMENT", "user-procurement"],
    ["VMS_E2E_TOKEN_USER_FINANCE_AP", "user-finance-ap"],
    ["VMS_E2E_TOKEN_USER_GOVERNANCE", "user-governance"],
    ["VMS_E2E_TOKEN_USER_NORTHSTAR", "user-northstar"],
  ].map(([name, subject]) => [name, jwt(subject)]));

  const migrationLocations = [
    "classpath:db/migration",
    `filesystem:${join(repositoryRoot, "backend/src/test/resources/db/testdata")}`,
    `filesystem:${join(
      repositoryRoot,
      systemSuite === "migration"
        ? "e2e/system/db/migration"
        : "e2e/system/db",
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
    VMS_FINANCE_LOCAL_SCANNER_ENABLED: "true",
    VMS_MIGRATION_LOCAL_SCANNER_ENABLED: "true",
    VMS_FINANCE_CURSOR_SIGNING_SECRET:
      "system-e2e-cursor-signing-secret-with-at-least-32-bytes",
    VMS_FINANCE_MUTATIONS_PER_MINUTE: "1000",
    VMS_FINANCE_DOWNLOADS_PER_MINUTE: "1000",
    VMS_FINANCE_EXPORTS_PER_MINUTE: "1000",
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
  });
  const frontendUrl = `http://127.0.0.1:${frontendPort}`;
  await waitForUrl(frontendUrl, 120_000);

  const result = await runForeground(
    "npx",
    [
      "playwright", "test",
      "--config", "playwright.system.config.ts",
      "--project", systemProject,
    ],
    {
      ...tokenEnvironment,
      VMS_E2E_FRONTEND_URL: frontendUrl,
    },
  );
  if (result !== 0) {
    throw new Error(
      `${systemSuite} system Playwright exited with ${result}.`,
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
  await once(server, "listening");
  const address = server.address();
  if (!address || typeof address === "string") {
    server.close();
    throw new Error("Unable to allocate a local system-test port.");
  }
  const port = address.port;
  server.close();
  await once(server, "close");
  return port;
}

function start(command, args, extraEnvironment) {
  const child = spawn(command, args, {
    cwd: repositoryRoot,
    stdio: "inherit",
    env: { ...process.env, ...extraEnvironment },
  });
  children.add(child);
  child.once("exit", (code, signal) => {
    children.delete(child);
    if (!stopped && code !== 0) {
      process.stderr.write(
        `${command} exited early (${code ?? signal ?? "unknown"}).\n`,
      );
    }
  });
  return child;
}

function runForeground(command, args, extraEnvironment) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: repositoryRoot,
      stdio: "inherit",
      env: { ...process.env, ...extraEnvironment },
    });
    children.add(child);
    child.once("error", reject);
    child.once("exit", (code, signal) => {
      children.delete(child);
      if (signal) {
        reject(new Error(`${command} exited from ${signal}.`));
        return;
      }
      resolve(code ?? 1);
    });
  });
}

function runChecked(command, args) {
  const result = spawnSync(command, args, {
    cwd: repositoryRoot,
    stdio: "inherit",
  });
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(" ")} failed.`);
  }
}

function requireCommand(command, args) {
  const result = spawnSync(command, args, {
    cwd: repositoryRoot,
    stdio: "ignore",
  });
  if (result.status !== 0) {
    throw new Error(`${command} is required for ${systemSuite} system E2E.`);
  }
}

async function waitForPostgres() {
  const deadline = Date.now() + 90_000;
  while (Date.now() < deadline) {
    const result = spawnSync(
      "docker",
      ["exec", postgresName, "pg_isready", "-U", "vms", "-d", "vms_workflow"],
      { stdio: "ignore" },
    );
    if (result.status === 0) return;
    await delay(500);
  }
  throw new Error("PostgreSQL system-E2E container did not become ready.");
}

function mappedPostgresPort() {
  const result = spawnSync(
    "docker",
    ["port", postgresName, "5432/tcp"],
    { encoding: "utf8" },
  );
  if (result.status !== 0) {
    throw new Error("Unable to resolve the PostgreSQL mapped port.");
  }
  const match = result.stdout.trim().match(/:(\d+)$/);
  if (!match) throw new Error("Unexpected docker port output.");
  return Number(match[1]);
}

async function waitForUrl(url, timeout) {
  const deadline = Date.now() + timeout;
  let lastError = "not attempted";
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.ok) return;
      lastError = `HTTP ${response.status}`;
    } catch (error) {
      lastError = error instanceof Error ? error.message : String(error);
    }
    await delay(750);
  }
  throw new Error(`Timed out waiting for ${url}: ${lastError}`);
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function cleanup() {
  if (stopped) return;
  stopped = true;
  for (const child of children) {
    child.kill("SIGTERM");
  }
  await Promise.all(
    [...children].map(async (child) => {
      await Promise.race([once(child, "exit"), delay(5_000)]);
      if (child.exitCode === null) child.kill("SIGKILL");
    }),
  );
  await new Promise((resolve) => jwksServer.close(resolve));
  spawnSync("docker", ["rm", "-f", postgresName], { stdio: "ignore" });
  rmSync(isolatedMavenTarget, { recursive: true, force: true });
}
