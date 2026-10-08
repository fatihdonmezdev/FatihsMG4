import { NextResponse } from "next/server";
import { hasIngestToken, hasSession, checkDashboardPassword } from "./auth";
import { ValidationError } from "./validate";

/** Every endpoint touches MongoDB, which rules out the edge runtime. */
export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export function unauthorized(): NextResponse {
  return NextResponse.json({ error: "unauthorized" }, { status: 401 });
}

/**
 * Wraps a handler so a validation failure is a 400 and anything else is a 500 whose
 * detail stays in the server log. An error message is allowed to name a bad field; it is
 * never allowed to carry a connection string.
 */
export async function handle(work: () => Promise<NextResponse>): Promise<NextResponse> {
  try {
    return await work();
  } catch (error) {
    if (error instanceof ValidationError) {
      return NextResponse.json({ error: error.message }, { status: 400 });
    }
    console.error("Request failed", error);
    return NextResponse.json({ error: "internal server error" }, { status: 500 });
  }
}

export function requireIngest(request: Request): NextResponse | null {
  return hasIngestToken(request) ? null : unauthorized();
}

/**
 * Read access: the dashboard's own session cookie, or the dashboard password as a bearer
 * token so a native client can read the same history without a browser.
 */
export async function requireReader(request: Request): Promise<NextResponse | null> {
  if (await hasSession()) return null;
  const header = request.headers.get("authorization") ?? "";
  if (header.startsWith("Bearer ") && checkDashboardPassword(header.slice(7))) return null;
  return unauthorized();
}

export async function parseJson(request: Request): Promise<unknown> {
  const raw = await request.text();
  if (raw.length > 512 * 1024) throw new ValidationError("body is too large");
  try {
    return JSON.parse(raw);
  } catch {
    throw new ValidationError("body is not valid JSON");
  }
}
