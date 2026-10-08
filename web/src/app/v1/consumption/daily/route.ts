import { NextResponse } from "next/server";
import { handle, parseJson, requireIngest } from "@/lib/api";
import { consumptionCollection, ensureIndexes } from "@/lib/mongo";
import { validateConsumption } from "@/lib/validate";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/**
 * Upsert on (installationId, date). The car retries a day it could not confirm, so the
 * same day arrives more than once and must land on the same document rather than a
 * duplicate — the unique index is what guarantees that under a concurrent retry.
 */
export async function PUT(request: Request) {
  const denied = requireIngest(request);
  if (denied) return denied;
  return handle(async () => {
    const record = validateConsumption(await parseJson(request));
    await ensureIndexes();
    const collection = await consumptionCollection();
    const updatedAt = new Date();
    await collection.updateOne(
      { installationId: record.installationId, date: record.date },
      { $set: { ...record, updatedAt }, $setOnInsert: { createdAt: updatedAt } },
      { upsert: true },
    );
    return new NextResponse(null, { status: 204 });
  });
}
