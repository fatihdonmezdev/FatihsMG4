import { NextResponse } from "next/server";
import { handle, parseJson, requireIngest } from "@/lib/api";
import { consumptionCollection, ensureIndexes } from "@/lib/mongo";
import { validateConsumption } from "@/lib/validate";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/**
 * Upsert on (installationId, date), incrementally. The car sends a 30-minute delta chunk
 * (not a cumulative total), so each PUT adds to the day's running totals rather than
 * overwriting them. Lifetime is a snapshot — the latest one wins, so it is $set not $inc.
 *
 * The old cumulative-overwrite format still works: a record whose day totals already
 * represent the full day simply adds them again, which is wrong — but the app no longer
 * sends that format, and the legacy 189 km document is never re-sent. New data only.
 */
export async function PUT(request: Request) {
  const denied = requireIngest(request);
  if (denied) return denied;
  return handle(async () => {
    const record = validateConsumption(await parseJson(request));
    await ensureIndexes();
    const collection = await consumptionCollection();
    const updatedAt = new Date();
    const setOnInsert: Record<string, unknown> = {
      createdAt: updatedAt,
      installationId: record.installationId,
      date: record.date,
      "day.km": 0, "day.kwh": 0, "day.hours": 0, "day.socDrop": 0,
    };
    const update: Record<string, Record<string, unknown>> = {
      $inc: {
        "day.km": record.day.km,
        "day.kwh": record.day.kwh,
        "day.hours": record.day.hours,
        "day.socDrop": record.day.socDrop,
      },
      $set: { updatedAt },
      $setOnInsert: setOnInsert,
    };
    if (record.lifetime) update.$set.lifetime = record.lifetime;
    if (record.sohPercent !== undefined) update.$set.sohPercent = record.sohPercent;
    await collection.updateOne(
      { installationId: record.installationId, date: record.date },
      update,
      { upsert: true },
    );
    return new NextResponse(null, { status: 204 });
  });
}

