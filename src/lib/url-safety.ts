const SAFE_BASE_URL = "https://app.invalid";
const MAX_PATH_LENGTH = 2048;
const MAX_DECODE_PASSES = 10;

function containsForbiddenPathCharacter(value: string) {
  for (const character of value) {
    const codePoint = character.codePointAt(0) ?? 0;
    if (
      character === "\\" ||
      codePoint <= 0x1f ||
      (codePoint >= 0x7f && codePoint <= 0x9f)
    ) {
      return true;
    }
  }
  return false;
}

export function isOriginRelativePath(value: string | undefined): value is string {
  if (!value || value.length > MAX_PATH_LENGTH) return false;

  let decoded = value;
  for (let pass = 0; pass < MAX_DECODE_PASSES; pass += 1) {
    if (
      !decoded.startsWith("/") ||
      decoded.startsWith("//") ||
      containsForbiddenPathCharacter(decoded)
    ) {
      return false;
    }

    let next: string;
    try {
      next = decodeURIComponent(decoded);
    } catch {
      return false;
    }

    if (next === decoded) break;
    if (pass === MAX_DECODE_PASSES - 1) return false;
    decoded = next;
  }

  try {
    return new URL(value, SAFE_BASE_URL).origin === SAFE_BASE_URL;
  } catch {
    return false;
  }
}

export function safeReturnPath(returnTo: string | undefined) {
  return isOriginRelativePath(returnTo) ? returnTo : "/";
}
