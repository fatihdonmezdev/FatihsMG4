import { NextResponse } from "next/server";
import { handle, requireIngest } from "@/lib/api";
import { consumptionCollection } from "@/lib/mongo";
import { validateUuid } from "@/lib/validate";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/**
 * Ingest-token-authenticated history read for the head-unit app.
 *
 * The dashboard history endpoints under /v1/history use the dashboard password (a
 * browser-only credential). The head unit only holds the ingest token, which was
 * write-only by design — but the car's consumption UI now reads back its own uploaded
 * totals so an APK wipe does not blank the display. The installationId is a fixed UUID
 * baked into the APK, so an extracted token plus a guessed installationId is the only
 * exposure, and the data revealed is the owner's own consumption history.
 */
export async function GET(request: Request, context: { params: Promise<{ installationId: string }> }) {
  const denied = requireIngest(request);
  if (denied) return denied;
  return handle(async () => {
    const { installationId } = await context.params;
    const collection = await consumptionCollection();
    const records = await collection
      .find({ installationId: validateUuid(installationId, "installationId") }, { projection: { _id: 0 } })
      .sort({ date: -1 })
      .limit(400)
      .toArray();
    return NextResponse.json({ records });
  });
}
