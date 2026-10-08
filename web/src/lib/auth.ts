/**
 * The Next-aware half of authentication. Everything cryptographic lives in
 * {@link ./session}, which imports nothing from Next and is therefore unit-testable
 * without a request, a build, or a running server.
 */
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { SESSION_COOKIE, isValidSessionValue } from "./session";

export * from "./session";

export async function hasSession(): Promise<boolean> {
  const store = await cookies();
  return isValidSessionValue(store.get(SESSION_COOKIE)?.value);
}

/** For pages: send an unauthenticated visitor to the password screen. */
export async function requireSession(): Promise<void> {
  if (!(await hasSession())) redirect("/login");
}
