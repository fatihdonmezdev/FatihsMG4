import { NextResponse } from "next/server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** Unauthenticated on purpose: it reveals nothing and the car has no other probe. */
export function GET() {
  return NextResponse.json({ status: "ok" });
}
