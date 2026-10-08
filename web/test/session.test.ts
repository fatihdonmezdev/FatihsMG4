import assert from "node:assert/strict";
import { test } from "node:test";

process.env.SESSION_SECRET = "0123456789abcdef0123456789abcdef";
process.env.DASHBOARD_PASSWORD = "correct-horse-battery";
process.env.INGEST_TOKEN = "a".repeat(64);

const { checkDashboardPassword, hasIngestToken, isValidSessionValue, issueSessionValue } =
  await import("../src/lib/session.ts");

test("a freshly issued session validates and a tampered one does not", () => {
  const value = issueSessionValue();
  assert.equal(isValidSessionValue(value), true);
  const [expiry, signature] = value.split(".");
  // Moving the expiry out invalidates the signature, so a cookie cannot extend itself.
  assert.equal(isValidSessionValue(`${Number(expiry) + 86_400}.${signature}`), false);
  assert.equal(isValidSessionValue(`${expiry}.${"0".repeat(signature.length)}`), false);
  assert.equal(isValidSessionValue(undefined), false);
  assert.equal(isValidSessionValue("garbage"), false);
});

test("an expired session is refused even with a valid signature", async () => {
  const past = Math.floor(Date.now() / 1000) - 10;
  // Signed by the same secret, so only the embedded expiry rejects it.
  const crypto = await import("node:crypto");
  const signature = crypto.createHmac("sha256", process.env.SESSION_SECRET as string)
    .update(String(past)).digest("hex");
  assert.equal(isValidSessionValue(`${past}.${signature}`), false);
});

test("the dashboard password is exact", () => {
  assert.equal(checkDashboardPassword("correct-horse-battery"), true);
  assert.equal(checkDashboardPassword("correct-horse-batter"), false);
  assert.equal(checkDashboardPassword(""), false);
});

test("the ingest token is only accepted as a bearer header", () => {
  const token = "a".repeat(64);
  const bearer = (value: string) => new Request("https://x/v1", { headers: { authorization: value } });
  assert.equal(hasIngestToken(bearer(`Bearer ${token}`)), true);
  assert.equal(hasIngestToken(bearer(token)), false);
  assert.equal(hasIngestToken(bearer(`Bearer ${"b".repeat(64)}`)), false);
  assert.equal(hasIngestToken(new Request("https://x/v1")), false);
});
