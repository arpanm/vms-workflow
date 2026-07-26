import type { RefObject } from "react";

export function focusValidationSummary(ref: RefObject<HTMLElement | null>) {
  window.requestAnimationFrame(() => ref.current?.focus());
}
