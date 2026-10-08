import { NextResponse } from "next/server";
import { SESSION_COOKIE, checkDashboardPassword, issueSessionValue, sessionCookieOptions } from "@/lib/session";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/**
 * Deliberately slow to fail. The password is the only thing between the open internet and
 * the history, and a quarter second is unnoticeable once but ruinous for anyone working
 * through a list.
 */
export async function POST(request: Request) {
  const form = await request.formData();
  const password = String(form.get("password") ?? "");
  if (!checkDashboardPassword(password)) {
    await new Promise((resolve) => setTimeout(resolve, 250));
    return NextResponse.redirect(new URL("/login?error=1", request.url), { status: 303 });
  }
  const response = NextResponse.redirect(new URL("/", request.url), { status: 303 });
  // Set on the response rather than through the cookies() store: a freshly constructed
  // redirect does not reliably carry what that store recorded.
  response.cookies.set(SESSION_COOKIE, issueSessionValue(), sessionCookieOptions);
  return response;
}
