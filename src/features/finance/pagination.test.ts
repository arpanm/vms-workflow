import { describe, expect, it } from "vitest";

import { advanceCursor, retreatCursor } from "./pagination";

describe("finance cursor navigation", () => {
  it("[F05-UNIT-PAGE-001] advances opaque cursors without decoding them", () => {
    expect(advanceCursor([null], "opaque.page+2")).toEqual([null, "opaque.page+2"]);
    expect(advanceCursor([null, "opaque.page+2"], "opaque.page+2")).toEqual([
      null,
      "opaque.page+2",
    ]);
    expect(advanceCursor([null], null)).toEqual([null]);
  });

  it("[F05-UNIT-PAGE-002] retreats to the prior cursor without underflow", () => {
    expect(retreatCursor([null, "page-2", "page-3"])).toEqual([null, "page-2"]);
    expect(retreatCursor([null])).toEqual([null]);
  });
});
