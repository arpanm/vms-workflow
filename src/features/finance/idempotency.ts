import { ApiError } from "@/lib/api-client";

type MutationIntent<TInput> = {
  fingerprint: string;
  idempotencyKey: string;
};

function fingerprint(input: unknown) {
  if (
    typeof input === "object" &&
    input !== null &&
    "file" in input &&
    (input as { file?: unknown }).file instanceof File
  ) {
    const { file, ...rest } = input as { file: File; [key: string]: unknown };
    return JSON.stringify({
      ...rest,
      file: { name: file.name, size: file.size, type: file.type, lastModified: file.lastModified },
    });
  }
  return JSON.stringify(input);
}

function retainAfter(error: unknown) {
  if (!(error instanceof ApiError)) return false;
  return (
    error.status === 0 ||
    error.status === 408 ||
    error.status === 425 ||
    error.status === 429 ||
    error.status >= 500
  );
}

export class FinanceMutationIntentStore<TInput> {
  private current: MutationIntent<TInput> | null = null;

  acquire(input: TInput) {
    const nextFingerprint = fingerprint(input);
    if (this.current?.fingerprint !== nextFingerprint) {
      this.current = { fingerprint: nextFingerprint, idempotencyKey: crypto.randomUUID() };
    }
    return this.current;
  }

  settle(error?: unknown) {
    if (error === undefined || !retainAfter(error)) this.current = null;
  }
}
