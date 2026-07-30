import { generateKeyPairSync, sign } from "node:crypto";
import { spawn, spawnSync } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { createServer as createHttpServer } from "node:http";
import { createServer as createNetServer } from "node:net";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const composeFile = join(repositoryRoot, "backend", "compose.yaml");
const composeProject = "vms-workflow-local";
const localDatabase = "vms_workflow_local";
const argumentsSet = new Set(process.argv.slice(2));
const dependenciesOnly = argumentsSet.has("--dependencies-only");
const down = argumentsSet.has("--down");
const children = new Set();
let shuttingDown = false;
let jwksServer;

process.on("uncaughtException", (error) => {
  void failStartup(error);
});
process.on("unhandledRejection", (error) => {
  void failStartup(error);
});

if (down) {
  const result = runSync("docker", [
    "compose",
    "--project-name", composeProject,
    "-f", composeFile,
    "down",
  ]);
  process.exit(result.status ?? 1);
}

requireCommand("docker", ["version"]);
requireCommand("docker", ["compose", "version"]);
if (!dependenciesOnly) {
  requireCommand("mvn", ["-version"]);
  requireCommand("npm", ["--version"]);
}

const reservedPorts = new Set();
const existingPostgresPort = composePort();
const postgresPort = await startPostgres(
  existingPostgresPort
    ?? numberEnvironment("VMS_POSTGRES_PORT", 5432),
  existingPostgresPort !== null,
);
reservedPorts.add(postgresPort);

await waitForPostgres();
await ensureLocalDatabase();

if (dependenciesOnly) {
  await persistRuntimeEnvironment({
    VMS_DATABASE_URL:
      `jdbc:postgresql://127.0.0.1:${postgresPort}/${localDatabase}`,
    VMS_DATABASE_USERNAME: "vms",
    VMS_DATABASE_PASSWORD: "vms_local",
    VMS_POSTGRES_PORT: String(postgresPort),
  });
  console.log("");
  console.log(`PostgreSQL is ready on 127.0.0.1:${postgresPort}.`);
  console.log("Runtime settings: .local-dev/runtime.env");
  console.log("Start every service with: npm run dev:all");
  process.exit(0);
}

const jwks = await startJwksServerAtOrAbove(
  numberEnvironment("VMS_LOCAL_JWKS_PORT", 9000),
);
const jwksPort = jwks.port;
reservedPorts.add(jwksPort);

const issuer = `http://127.0.0.1:${jwksPort}`;
jwksServer = jwks.server;
const localAccessToken = localJwt(jwks.privateKey, issuer);

const baseRuntimeEnvironment = {
  VMS_DATABASE_PASSWORD: "vms_local",
  VMS_DATABASE_URL:
    `jdbc:postgresql://127.0.0.1:${postgresPort}/${localDatabase}`,
  VMS_DATABASE_USERNAME: "vms",
  VMS_LOCAL_JWKS_PORT: String(jwksPort),
  VMS_OIDC_AUDIENCE: "vms-api",
  VMS_OIDC_ISSUER: issuer,
  VMS_OIDC_JWKS_URI: `${issuer}/jwks`,
  VMS_POSTGRES_PORT: String(postgresPort),
};

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    void shutdown(0);
  });
}

const backendResult = await startServiceAtOrAbove({
  label: "Spring backend",
  initialPort: numberEnvironment("VMS_BACKEND_PORT", 8080),
  timeoutMilliseconds: 180_000,
  url: (port) => `http://127.0.0.1:${port}/actuator/health`,
  command: "mvn",
  args: () => ["-f", "backend/pom.xml", "spring-boot:run"],
  environment: (port) => ({
    ...baseRuntimeEnvironment,
    SERVER_ADDRESS: "127.0.0.1",
    SERVER_PORT: String(port),
    SPRING_FLYWAY_LOCATIONS: [
      "classpath:db/migration",
      `filesystem:${join(
        repositoryRoot,
        "backend/src/test/resources/db/testdata",
      )}`,
    ].join(","),
    SPRING_PROFILES_ACTIVE: "local",
    VMS_FINANCE_CURSOR_SIGNING_SECRET:
      "local-development-cursor-signing-secret-32-bytes",
    VMS_FINANCE_LOCAL_SCANNER_ENABLED: "true",
    VMS_MIGRATION_LOCAL_SCANNER_ENABLED: "true",
  }),
});
const backendPort = backendResult.port;
watchChild("backend", backendResult.child);

const frontendResult = await startServiceAtOrAbove({
  label: "Vite frontend",
  initialPort: numberEnvironment("VMS_FRONTEND_PORT", 3000),
  timeoutMilliseconds: 120_000,
  url: (port) => `http://127.0.0.1:${port}`,
  command: "npm",
  args: (port) => [
    "run", "dev", "--",
    "--host", "127.0.0.1",
    "--port", String(port),
    "--strictPort",
  ],
  environment: (port) => ({
    ...baseRuntimeEnvironment,
    VMS_BACKEND_PORT: String(backendPort),
    VMS_FRONTEND_PORT: String(port),
    VITE_BACKEND_DEV_URL: `http://127.0.0.1:${backendPort}`,
    VITE_API_BASE_URL: "/api/v1",
    VITE_DEMO_MODE: "true",
    VITE_LOCAL_DEV_AUTH: "true",
    VITE_LOCAL_DEV_ACCESS_TOKEN: localAccessToken,
  }),
});
const frontendPort = frontendResult.port;
watchChild("frontend", frontendResult.child);

const runtimeEnvironment = {
  ...baseRuntimeEnvironment,
  VMS_BACKEND_PORT: String(backendPort),
  VMS_FRONTEND_PORT: String(frontendPort),
  VITE_BACKEND_DEV_URL: `http://127.0.0.1:${backendPort}`,
};
await persistRuntimeEnvironment(runtimeEnvironment);

console.log("");
console.log("Cadence local stack is ready:");
console.log(`  Frontend:   http://127.0.0.1:${frontendPort}`);
console.log(`  Backend:    http://127.0.0.1:${backendPort}`);
console.log(`  Swagger:    http://127.0.0.1:${backendPort}/swagger-ui.html`);
console.log(`  PostgreSQL: 127.0.0.1:${postgresPort}`);
console.log(`  Local JWKS: ${issuer}/jwks`);
console.log("  Settings:   .local-dev/runtime.env");
console.log("");
console.log("Press Ctrl+C to stop the frontend/backend; PostgreSQL remains available.");

await new Promise(() => {});

function numberEnvironment(name, fallback) {
  const raw = process.env[name];
  if (raw === undefined || raw === "") return fallback;
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 1 || value > 65535) {
    throw new Error(`${name} must be an integer between 1 and 65535.`);
  }
  return value;
}

async function nextAvailablePort(start) {
  for (let port = start; port <= 65535; port += 1) {
    if (reservedPorts.has(port)) continue;
    if (await portAvailable(port)) return port;
  }
  throw new Error(`No available TCP port exists at or above ${start}.`);
}

function portAvailable(port) {
  return new Promise((resolve) => {
    const server = createNetServer();
    server.unref();
    server.once("error", () => resolve(false));
    server.listen(port, "127.0.0.1", () => {
      server.close(() => resolve(true));
    });
  });
}

function composePort() {
  const result = spawnSync("docker", [
    "compose",
    "--project-name", composeProject,
    "-f", composeFile,
    "port", "postgres", "5432",
  ], {
    cwd: repositoryRoot,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "ignore"],
    timeout: 10_000,
  });
  if (result.status !== 0) return null;
  const match = result.stdout.trim().match(/:(\d+)$/);
  return match ? Number(match[1]) : null;
}

async function startPostgres(initialPort, existing) {
  let port = initialPort;
  while (port <= 65535) {
    const environment = {
      ...process.env,
      VMS_POSTGRES_PORT: String(port),
    };
    const result = spawnSync("docker", [
      "compose",
      "--project-name", composeProject,
      "-f", composeFile,
      "up", "-d", "--wait", "--force-recreate", "postgres",
    ], {
      cwd: repositoryRoot,
      encoding: "utf8",
      env: environment,
      maxBuffer: 10 * 1024 * 1024,
      timeout: 5 * 60_000,
    });
    if (result.status === 0) {
      if (result.stdout) process.stdout.write(result.stdout);
      if (result.stderr) process.stderr.write(result.stderr);
      return port;
    }
    const diagnostic = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
    if (result.stdout) process.stdout.write(result.stdout);
    if (result.stderr) process.stderr.write(result.stderr);
    const portConflict =
      /port is already allocated|address already in use|Bind for .* failed/i
        .test(diagnostic);
    if (!portConflict || existing) {
      throw new Error(
        `Unable to start PostgreSQL on port ${port}: `
          + (portConflict
            ? "the existing local Compose mapping is occupied"
            : "Docker Compose failed"),
      );
    }
    port += 1;
    if (port > 65535) break;
    console.log(`PostgreSQL port ${port - 1} is occupied; trying ${port}.`);
  }
  throw new Error(`No usable PostgreSQL port exists at or above ${initialPort}.`);
}

async function waitForPostgres() {
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    const result = spawnSync("docker", [
      "compose",
      "--project-name", composeProject,
      "-f", composeFile,
      "exec", "-T", "postgres",
      "pg_isready", "-U", "vms", "-d", "vms_workflow",
    ], {
      cwd: repositoryRoot,
      encoding: "utf8",
      stdio: "ignore",
      timeout: 10_000,
    });
    if (result.status === 0) return;
    await delay(500);
  }
  throw new Error("PostgreSQL did not become ready within 180 seconds.");
}

function ensureLocalDatabase() {
  const exists = spawnSync("docker", [
    "compose", "--project-name", composeProject, "-f", composeFile,
    "exec", "-T", "postgres", "psql", "-U", "vms", "-d", "vms_workflow",
    "-tAc", `SELECT 1 FROM pg_database WHERE datname='${localDatabase}'`,
  ], {
    cwd: repositoryRoot,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "inherit"],
    timeout: 30_000,
  });
  if (exists.status !== 0) {
    throw new Error("Unable to inspect the local PostgreSQL database.");
  }
  if (exists.stdout.trim() === "1") return;
  runChecked("docker", [
    "compose", "--project-name", composeProject, "-f", composeFile,
    "exec", "-T", "postgres", "createdb", "-U", "vms", localDatabase,
  ]);
}

async function startJwksServerAtOrAbove(initialPort) {
  const { privateKey, publicKey } = generateKeyPairSync("rsa", {
    modulusLength: 2048,
  });
  const publicJwk = publicKey.export({ format: "jwk" });
  for (let port = initialPort; port <= 65535; port += 1) {
    const server = createHttpServer((request, response) => {
      if (request.url !== "/jwks") {
        response.writeHead(404).end();
        return;
      }
      response.writeHead(200, {
        "Cache-Control": "no-store",
        "Content-Type": "application/json",
      });
      response.end(JSON.stringify({
        keys: [{
          ...publicJwk,
          alg: "RS256",
          kid: "vms-local-development",
          use: "sig",
        }],
      }));
    });
    const started = await new Promise((resolve, reject) => {
      server.once("error", (error) => {
        if (error.code === "EADDRINUSE") resolve(false);
        else reject(error);
      });
      server.listen(port, "127.0.0.1", () => resolve(true));
    });
    if (started) return { port, privateKey, server };
    server.close();
  }
  throw new Error(`No available local JWKS port exists at or above ${initialPort}.`);
}

function localJwt(privateKey, issuer) {
  const encode = (value) =>
    Buffer.from(JSON.stringify(value)).toString("base64url");
  const header = encode({
    alg: "RS256",
    kid: "vms-local-development",
    typ: "JWT",
  });
  const now = Math.floor(Date.now() / 1000);
  const payload = encode({
    aud: "vms-api",
    exp: now + 12 * 60 * 60,
    iat: now,
    iss: issuer,
    sub: process.env.VMS_LOCAL_SUBJECT ?? "user-reliance",
  });
  const unsigned = `${header}.${payload}`;
  const signature = sign("RSA-SHA256", Buffer.from(unsigned), privateKey)
    .toString("base64url");
  return `${unsigned}.${signature}`;
}

function start(command, args, environment) {
  const logs = [];
  const child = spawn(command, args, {
    cwd: repositoryRoot,
    detached: process.platform !== "win32",
    env: { ...process.env, ...environment },
    stdio: ["ignore", "pipe", "pipe"],
  });
  const forward = (stream, destination) => {
    stream.on("data", (chunk) => {
      destination.write(chunk);
      logs.push(chunk.toString());
      if (logs.length > 500) logs.shift();
    });
  };
  forward(child.stdout, process.stdout);
  forward(child.stderr, process.stderr);
  child.once("error", (error) => {
    child.startError = error;
  });
  children.add(child);
  return { child, logs };
}

function watchChild(label, child) {
  child.once("error", (error) => {
    children.delete(child);
    if (!shuttingDown) {
      void failStartup(
        new Error(`${label} could not start: ${error.message}`),
      );
    }
  });
  child.once("exit", (code, signal) => {
    children.delete(child);
    if (!shuttingDown) {
      console.error(
        `${label} stopped unexpectedly (${signal ?? `exit ${code ?? 1}`}).`,
      );
      void shutdown(code || 1);
    }
  });
}

async function startServiceAtOrAbove({
  label,
  initialPort,
  timeoutMilliseconds,
  url,
  command,
  args,
  environment,
}) {
  let port = initialPort;
  while (port <= 65535) {
    if (reservedPorts.has(port) || !(await portAvailable(port))) {
      port += 1;
      continue;
    }
    const attempt = start(command, args(port), environment(port));
    const outcome = await waitForUrlOrExit(
      url(port), attempt.child, timeoutMilliseconds,
    );
    if (outcome === "ready") {
      reservedPorts.add(port);
      return { port, child: attempt.child };
    }
    children.delete(attempt.child);
    const diagnostic = attempt.logs.join("");
    const portConflict =
      /address already in use|port (?:\d+ )?was already in use|port is already in use|EADDRINUSE/i
        .test(diagnostic);
    if (!portConflict) {
      throw new Error(
        `${label} exited before becoming ready on port ${port}.`,
      );
    }
    console.log(`${label} port ${port} is occupied; trying ${port + 1}.`);
    port += 1;
  }
  throw new Error(`No usable ${label} port exists at or above ${initialPort}.`);
}

async function waitForUrlOrExit(url, child, timeoutMilliseconds) {
  const deadline = Date.now() + timeoutMilliseconds;
  while (Date.now() < deadline) {
    if (
      child.startError ||
      child.exitCode !== null ||
      child.signalCode !== null
    ) {
      await waitForChildOutput(child);
      return "exited";
    }
    try {
      const response = await fetch(url, {
        signal: AbortSignal.timeout(2_000),
      });
      if (response.ok) return "ready";
    } catch {
      // The bounded retry below reports one useful terminal error.
    }
    await delay(500);
  }
  signalProcessTree(child, "SIGTERM");
  throw new Error(`Service did not become ready at ${url}.`);
}

function waitForChildOutput(child) {
  if (child.stdout?.readableEnded && child.stderr?.readableEnded) {
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    child.once("close", resolve);
    setTimeout(resolve, 2_000).unref();
  });
}

async function persistRuntimeEnvironment(values) {
  const directory = join(repositoryRoot, ".local-dev");
  await mkdir(directory, { recursive: true, mode: 0o700 });
  const body = Object.entries(values)
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, value]) => `${key}=${value}`)
    .join("\n");
  await writeFile(join(directory, "runtime.env"), `${body}\n`, {
    encoding: "utf8",
    mode: 0o600,
  });
}

function requireCommand(command, args) {
  const result = spawnSync(command, args, {
    cwd: repositoryRoot,
    stdio: "ignore",
    timeout: 15_000,
  });
  if (result.status !== 0) {
    throw new Error(`${command} is required but unavailable.`);
  }
}

function runChecked(command, args, environment = process.env) {
  const result = runSync(command, args, environment);
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(" ")} failed.`);
  }
}

function runSync(command, args, environment = process.env) {
  return spawnSync(command, args, {
    cwd: repositoryRoot,
    env: environment,
    stdio: "inherit",
    timeout: 5 * 60_000,
  });
}

async function shutdown(exitCode) {
  if (shuttingDown) return;
  shuttingDown = true;
  const exits = [...children].map((child) => new Promise((resolve) => {
    if (child.exitCode !== null || child.signalCode !== null) {
      resolve();
      return;
    }
    child.once("close", resolve);
    setTimeout(() => {
      if (child.exitCode === null && child.signalCode === null) {
        signalProcessTree(child, "SIGKILL");
      }
      resolve();
    }, 5_000).unref();
  }));
  for (const child of children) {
    signalProcessTree(child, "SIGTERM");
  }
  await Promise.all(exits);
  if (jwksServer?.listening) {
    await new Promise((resolve) => jwksServer.close(resolve));
  }
  process.exit(exitCode);
}

function signalProcessTree(child, signal) {
  try {
    if (process.platform !== "win32" && child.pid) {
      process.kill(-child.pid, signal);
    } else {
      child.kill(signal);
    }
  } catch (error) {
    if (error.code !== "ESRCH") throw error;
  }
}

async function failStartup(error) {
  if (shuttingDown) return;
  console.error(
    error instanceof Error ? error.stack ?? error.message : String(error),
  );
  try {
    await shutdown(1);
  } catch (cleanupError) {
    console.error(
      cleanupError instanceof Error
        ? cleanupError.stack ?? cleanupError.message
        : String(cleanupError),
    );
    process.exit(1);
  }
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
