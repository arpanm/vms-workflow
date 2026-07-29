const CORRELATION_ID =
  /(?:correlation|request)[-_ ]?id[^0-9a-f]*([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})/i;

export type SafeErrorPresentation = {
  message: string;
  correlationId?: string;
};

export function safeErrorPresentation(error: unknown): SafeErrorPresentation {
  const source = error instanceof Error ? error.message : String(error ?? "");
  const correlationId = source.match(CORRELATION_ID)?.[1]?.toLowerCase();

  return {
    message:
      "The request could not be completed safely. Retry, or contact support if the problem continues.",
    ...(correlationId ? { correlationId } : {}),
  };
}
