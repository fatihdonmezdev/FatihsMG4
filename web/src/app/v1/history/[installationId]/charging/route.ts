import { NextResponse } from "next/server";
import { handle, requireReader } from "@/lib/api";
import { chargingCollection } from "@/lib/mongo";
import { validateUuid } from "@/lib/validate";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(request: Request, context: { params: Promise<{ installationId: string }> }) {
  const denied = await requireReader(request);
  if (denied) return denied;
  return handle(async () => {
    const { installationId } = await context.params;
    const collection = await chargingCollection();
    const records = await collection
      .find({ installationId: validateUuid(installationId, "installationId") }, { projection: { _id: 0 } })
      .sort({ startedAt: -1 })
      .limit(200)
      .toArray();
    return NextResponse.json({ records });
  });
}
