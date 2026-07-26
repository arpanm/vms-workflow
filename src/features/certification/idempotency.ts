import { ApiError } from "@/lib/api-client";

export type MutationIntent<TInput> = {
  fingerprint: string;
  idempotencyKey: string;
  input: TInput;
};

export function fingerprintMutationInput(input: unknown) {
  return JSON.stringify(input);
}

export function createMutationIntent<TInput>(input: TInput): MutationIntent<TInput> {
  return {
    fingerprint: fingerprintMutationInput(input),
    idempotencyKey: crypto.randomUUID(),
    input,
  };
}

export function shouldRetainMutationIntent(error: unknown) {
  return error instanceof ApiError && (error.status === 0 || error.status >= 500);
}

export class MutationIntentStore<TInput> {
  private current: MutationIntent<TInput> | null = null;

  acquire(input: TInput) {
    const fingerprint = fingerprintMutationInput(input);
    if (this.current?.fingerprint !== fingerprint) {
      this.current = createMutationIntent(input);
    }
    return this.current;
  }

  settle(error?: unknown) {
    if (error === undefined || !shouldRetainMutationIntent(error)) {
      this.current = null;
    }
  }
}
