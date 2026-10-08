import crypto from "node:crypto";

/**
 * Two separate credentials, deliberately.
 *
 * The car holds {@link INGEST_TOKEN} and can only write. It is baked into a build that
 * lives on a head unit and is published as an APK, so it must be assumed extractable —
 * and an extracted write token buys nothing but the ability to post junk under an
 * installation id the attacker would have to guess.
 *
 * Reading the history needs the dashboard password, which never leaves the browser's
 * cookie jar and is never compiled into anything.
 */
export const SESSION_COOKIE = "fatihsmg4_session";
const SESSION_TTL_SECONDS = 60 * 60 * 24 * 30;

/** Constant-time compare that does not leak length through an early return. */
function sameSecret(supplied: string, expected: string): boolean {
  const a = crypto.createHash("sha256").update(supplied).digest();
  const b = crypto.createHash("sha256").update(expected).digest();
  return crypto.timingSafeEqual(a, b);
}

function requireEnv(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not configured`);
  return value;
}

/** Does this request carry the car's write token? */
export function hasIngestToken(request: Request): boolean {
  const expected = process.env.INGEST_TOKEN;
  if (!expected || expected.length < 24) return false;
  const header = request.headers.get("authorization") ?? "";
  if (!header.startsWith("Bearer ")) return false;
  return sameSecret(header.slice(7), expected);
}

export function checkDashboardPassword(supplied: string): boolean {
  const expected = process.env.DASHBOARD_PASSWORD;
  if (!expected || expected.length < 8) return false;
  return sameSecret(supplied, expected);
}

/**
 * `<expiry>.<hmac>` — no server-side session store, which a serverless deployment has
 * nowhere to keep anyway. The expiry is inside the signed payload, so a browser that
 * keeps the cookie past its date cannot use it.
 */
export function issueSessionValue(): string {
  const expiresAt = Math.floor(Date.now() / 1000) + SESSION_TTL_SECONDS;
  return `${expiresAt}.${sign(String(expiresAt))}`;
}

function sign(payload: string): string {
  return crypto.createHmac("sha256", requireEnv("SESSION_SECRET")).update(payload).digest("hex");
}

export function isValidSessionValue(value: string | undefined): boolean {
  if (!value) return false;
  const separator = value.lastIndexOf(".");
  if (separator <= 0) return false;
  const expiresAt = Number(value.slice(0, separator));
  if (!Number.isSafeInteger(expiresAt) || expiresAt * 1000 < Date.now()) return false;
  try {
    const expected = Buffer.from(sign(String(expiresAt)), "hex");
    const supplied = Buffer.from(value.slice(separator + 1), "hex");
    return expected.length === supplied.length && crypto.timingSafeEqual(expected, supplied);
  } catch {
    return false;
  }
}

export const sessionCookieOptions = {
  httpOnly: true,
  secure: process.env.NODE_ENV === "production",
  sameSite: "lax",
  path: "/",
  maxAge: SESSION_TTL_SECONDS,
} as const;
