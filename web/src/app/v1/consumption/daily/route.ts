import { NextResponse } from "next/server";
import { handle, parseJson, requireIngest } from "@/lib/api";
import { consumptionCollection, ensureIndexes } from "@/lib/mongo";
import { validateConsumption } from "@/lib/validate";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/**
 * Upsert on (installationId, date), incrementally. The car sends a 2-minute delta chunk
 * (not a cumulative total), so each PUT adds to the day's running totals rather than
 * overwriting them. The app does NOT send lifetime — the backend derives it as the sum of
 * all days' totals when history is read, so an APK wipe that zeroes the local counter can
 * never corrupt the cloud's lifetime.
 *
 * $setOnInsert must not touch `day` at all: $inc on `day.km` and $setOnInsert on `day`
 * conflict in MongoDB. On an insert, $inc treats a missing field as 0 and adds to it.
 */
export async function PUT(request: Request) {
  const denied = requireIngest(request);
  if (denied) return denied;
  return handle(async () => {
    const record = validateConsumption(await parseJson(request));
    await ensureIndexes();
    const collection = await consumptionCollection();
    const updatedAt = new Date();
    const $set: Record<string, unknown> = { updatedAt };
    if (record.sohPercent !== undefined) $set.sohPercent = record.sohPercent;
    await collection.updateOne(
      { installationId: record.installationId, date: record.date },
      {
        $inc: {
          "day.km": record.day.km,
          "day.kwh": record.day.kwh,
          "day.hours": record.day.hours,
          "day.socDrop": record.day.socDrop,
        },
        $set,
        $setOnInsert: {
          createdAt: updatedAt,
        },
      },
      { upsert: true },
    );
    return new NextResponse(null, { status: 204 });
  });
}

