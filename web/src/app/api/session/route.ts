import { NextResponse } from "next/server";
import { cookies } from "next/headers";
import { SESSION_COOKIE, checkDashboardPassword, issueSessionValue, sessionCookieOptions } from "@/lib/auth";

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
  const store = await cookies();
  store.set(SESSION_COOKIE, issueSessionValue(), sessionCookieOptions);
  return NextResponse.redirect(new URL("/", request.url), { status: 303 });
}
