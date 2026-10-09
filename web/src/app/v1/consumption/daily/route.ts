import { NextResponse } from "next/server";
import { handle, parseJson, requireIngest } from "@/lib/api";
import { consumptionCollection, ensureIndexes } from "@/lib/mongo";
import { validateConsumption } from "@/lib/validate";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/**
 * Upsert on (installationId, date), incrementally. The car sends a 2-minute delta chunk
 * (not a cumulative total), so each PUT adds to the day's running totals rather than
 * overwriting them. Lifetime is a snapshot — the latest one wins, so it is $set not $inc.
 *
 * $setOnInsert seeds the whole `day` subdocument as a nested object (not dotted paths)
 * because $inc and $setOnInsert cannot touch the same field — MongoDB rejects that with
 * a 500, which is what held the 20 cached chunks back.
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
    if (record.lifetime) $set.lifetime = record.lifetime;
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
          installationId: record.installationId,
          date: record.date,
          day: { km: 0, kwh: 0, hours: 0, socDrop: 0 },
        },
      },
      { upsert: true },
    );
    return new NextResponse(null, { status: 204 });
  });
}

