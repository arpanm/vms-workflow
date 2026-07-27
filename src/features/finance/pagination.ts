import { useState } from "react";

export function advanceCursor(history: Array<string | null>, nextCursor: string | null) {
  if (!nextCursor || history.at(-1) === nextCursor) return history;
  return [...history, nextCursor];
}

export function retreatCursor(history: Array<string | null>) {
  return history.length > 1 ? history.slice(0, -1) : history;
}

export function useCursorPager() {
  const [history, setHistory] = useState<Array<string | null>>([null]);
  return {
    cursor: history.at(-1) ?? null,
    hasPrevious: history.length > 1,
    next: (nextCursor: string | null) =>
      setHistory((current) => advanceCursor(current, nextCursor)),
    previous: () => setHistory(retreatCursor),
    reset: () => setHistory([null]),
  };
}
