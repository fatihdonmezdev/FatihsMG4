import { NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/session";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/**
 * POST only, deliberately.
 *
 * This used to answer GET behind a `<Link>`, so Next's viewport prefetch called it on
 * every render and cleared the cookie before the reader had clicked anything — which
 * presented as the session expiring on every navigation. Anything that changes state has
 * to be unreachable by a prefetch, a crawler or a preloading browser, and that means not
 * answering GET.
 */
export async function POST(request: Request) {
  const response = NextResponse.redirect(new URL("/login", request.url), { status: 303 });
  // Cleared on the response itself rather than through the cookies() store: a freshly
  // constructed redirect does not reliably carry what that store recorded.
  response.cookies.set(SESSION_COOKIE, "", { path: "/", maxAge: 0 });
  return response;
}
