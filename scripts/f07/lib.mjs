import { createHash, randomUUID } from "node:crypto";
import {
  link,
  lstat,
  mkdir,
  open,
  readFile,
  realpath,
  stat,
  unlink,
} from "node:fs/promises";
import { dirname, isAbsolute, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

export const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");

export function parseArgs(argv) {
  const result = { _: [] };
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token.startsWith("--")) {
      result._.push(token);
      continue;
    }
    const [rawKey, inlineValue] = token.slice(2).split("=", 2);
    if (inlineValue !== undefined) {
      result[rawKey] = inlineValue;
    } else if (argv[index + 1] && !argv[index + 1].startsWith("--")) {
      result[rawKey] = argv[index + 1];
      index += 1;
    } else {
      result[rawKey] = true;
    }
  }
  return result;
}

export function stableJson(value) {
  return `${JSON.stringify(sortValue(value), null, 2)}\n`;
}

function sortValue(value) {
  if (Array.isArray(value)) {
    return value.map(sortValue);
  }
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value)
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([key, child]) => [key, sortValue(child)]),
    );
  }
  return value;
}

export async function readJson(path) {
  return JSON.parse(await readFile(path, "utf8"));
}

export async function writeJson(path, value) {
  const parent = dirname(path);
  await assertNoSymlinkTraversal(parent);
  await mkdir(parent, { recursive: true, mode: 0o700 });
  await assertNoSymlinkTraversal(parent);
  try {
    await lstat(path);
    throw new Error(`refusing to overwrite existing evidence: ${path}`);
  } catch (error) {
    if (error.code !== "ENOENT") {
      throw error;
    }
  }
  const temporary = `${path}.tmp-${process.pid}-${randomUUID()}`;
  const handle = await open(temporary, "wx", 0o600);
  try {
    await handle.writeFile(stableJson(value));
    await handle.sync();
  } finally {
    await handle.close();
  }
  try {
    await link(temporary, path);
  } finally {
    await unlink(temporary).catch(() => {});
  }
}

export function sha256Bytes(value) {
  return createHash("sha256").update(value).digest("hex");
}

export async function sha256File(path) {
  return sha256Bytes(await readFile(path));
}

export function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd ?? repoRoot,
    encoding: "utf8",
    env: options.env ?? process.env,
    maxBuffer: options.maxBuffer ?? 50 * 1024 * 1024,
    killSignal: options.killSignal ?? "SIGKILL",
    stdio: options.stdio ?? "pipe",
    timeout: options.timeoutMs ?? 10 * 60 * 1000,
  });
  return {
    command: [command, ...args].join(" "),
    status: result.status,
    signal: result.signal,
    stdout: result.stdout ?? "",
    stderr: result.stderr ?? "",
    error: result.error,
    timedOut: result.error?.code === "ETIMEDOUT",
  };
}

export function commandExists(command) {
  const result = run("sh", ["-c", 'command -v "$1" >/dev/null 2>&1', "sh", command]);
  return result.status === 0;
}

export function repoPath(input, label = "path") {
  const resolved = resolve(repoRoot, input);
  const child = relative(repoRoot, resolved);
  if (child === "" || child.startsWith(`..${sep}`) || child === ".." || isAbsolute(child)) {
    throw new Error(`${label} must be a file or directory below the repository root`);
  }
  return resolved;
}

export async function existingRepoPath(input, label = "path") {
  const path = repoPath(input, label);
  await assertNoSymlinkTraversal(path);
  const canonicalRoot = await realpath(repoRoot);
  const canonicalPath = await realpath(path);
  const child = relative(canonicalRoot, canonicalPath);
  if (child.startsWith(`..${sep}`) || child === ".." || isAbsolute(child)) {
    throw new Error(`${label} resolves outside the repository root`);
  }
  const details = await lstat(canonicalPath);
  if (!details.isFile()) {
    throw new Error(`${label} must resolve to a regular file`);
  }
  return canonicalPath;
}

export async function assertNewDirectory(path) {
  await assertNoSymlinkTraversal(dirname(path));
  try {
    await lstat(path);
    throw new Error(`refusing to overwrite existing output directory: ${path}`);
  } catch (error) {
    if (error.code !== "ENOENT") {
      throw error;
    }
  }
  const parent = dirname(path);
  const parentStats = await stat(parent);
  if (!parentStats.isDirectory() || parentStats.isSymbolicLink()) {
    throw new Error(`output parent must be a real directory: ${parent}`);
  }
}

export function gitMetadata() {
  const commit = run("git", ["rev-parse", "HEAD"]);
  const timestamp = run("git", ["show", "-s", "--format=%cI", "HEAD"]);
  const dirty = run("git", ["status", "--porcelain", "--untracked-files=all"]);
  if (commit.status !== 0 || timestamp.status !== 0 || dirty.status !== 0) {
    throw new Error("unable to resolve Git release metadata");
  }
  return {
    commit: commit.stdout.trim(),
    commitTimestamp: timestamp.stdout.trim(),
    worktreeDirty: dirty.stdout.trim().length > 0,
  };
}

export async function assertNoSymlinkTraversal(path) {
  const absolute = resolve(path);
  const parsedRoot = resolve(absolute, "/");
  const parts = relative(parsedRoot, absolute).split(sep).filter(Boolean);
  let cursor = parsedRoot;
  for (const part of parts) {
    cursor = resolve(cursor, part);
    try {
      const details = await lstat(cursor);
      if (details.isSymbolicLink()) {
        throw new Error(`symbolic-link traversal is forbidden for outputs: ${cursor}`);
      }
    } catch (error) {
      if (error.code === "ENOENT") {
        return;
      }
      throw error;
    }
  }
}

export function percentile(values, percentileValue) {
  if (values.length === 0) {
    return null;
  }
  const sorted = [...values].sort((left, right) => left - right);
  const rank = Math.max(0, Math.ceil((percentileValue / 100) * sorted.length) - 1);
  return sorted[rank];
}

export function safeError(error) {
  return error instanceof Error ? error.message : String(error);
}

export function isLoopbackUrl(rawUrl) {
  const url = new URL(rawUrl);
  return (
    (url.protocol === "http:" || url.protocol === "https:") &&
    ["127.0.0.1", "localhost", "::1", "[::1]"].includes(url.hostname)
  );
}

export function redactDatabaseUrl(rawUrl) {
  const url = new URL(rawUrl);
  return {
    database: url.pathname.replace(/^\//, ""),
    host: url.hostname,
    port: url.port || "5432",
    protocol: url.protocol,
  };
}
