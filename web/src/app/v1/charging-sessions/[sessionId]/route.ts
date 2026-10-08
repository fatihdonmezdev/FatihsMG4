import { NextResponse } from "next/server";
import { handle, parseJson, requireIngest } from "@/lib/api";
import { chargingCollection, ensureIndexes } from "@/lib/mongo";
import { validateChargingSession } from "@/lib/validate";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function PUT(request: Request, context: { params: Promise<{ sessionId: string }> }) {
  const denied = requireIngest(request);
  if (denied) return denied;
  return handle(async () => {
    const { sessionId } = await context.params;
    const record = validateChargingSession(await parseJson(request), sessionId);
    await ensureIndexes();
    const collection = await chargingCollection();
    const updatedAt = new Date();
    await collection.updateOne(
      { installationId: record.installationId, sessionId: record.sessionId },
      { $set: { ...record, updatedAt }, $setOnInsert: { createdAt: updatedAt } },
      { upsert: true },
    );
    return new NextResponse(null, { status: 204 });
  });
}
