import { chargingCollection, consumptionCollection, type ChargingSession, type DailyConsumption } from "./mongo";

/**
 * Reads for the dashboard.
 *
 * Every query is scoped to one installation id. There is one car today, but the id is in
 * the schema and in the unique indexes, so scoping now costs nothing and means a second
 * head unit does not silently merge its history into the first one's charts.
 */

export interface Overview {
  installationId: string | null;
  installationCount: number;
  days: DailyConsumption[];
  sessions: ChargingSession[];
}

/** The installation that reported most recently — the car in use, if there are ever two. */
export async function latestInstallationId(): Promise<{ id: string | null; count: number }> {
  const collection = await consumptionCollection();
  const ids = await collection.distinct("installationId");
  if (ids.length === 0) {
    const charging = await chargingCollection();
    const chargingIds = await charging.distinct("installationId");
    return { id: chargingIds[0] ?? null, count: chargingIds.length };
  }
  if (ids.length === 1) return { id: ids[0], count: 1 };
  const newest = await collection.find({}, { projection: { installationId: 1 } })
    .sort({ date: -1 }).limit(1).next();
  return { id: newest?.installationId ?? ids[0], count: ids.length };
}

export async function loadOverview(dayLimit = 60, sessionLimit = 60): Promise<Overview> {
  const { id, count } = await latestInstallationId();
  if (!id) return { installationId: null, installationCount: 0, days: [], sessions: [] };
  const [consumption, charging] = await Promise.all([consumptionCollection(), chargingCollection()]);
  const [days, sessions] = await Promise.all([
    consumption.find({ installationId: id }, { projection: { _id: 0 } })
      .sort({ date: -1 }).limit(dayLimit).toArray(),
    charging.find({ installationId: id }, { projection: { _id: 0 } })
      .sort({ startedAt: -1 }).limit(sessionLimit).toArray(),
  ]);
  // Charts read left-to-right in time; the queries sort newest-first so the limit keeps
  // the recent end rather than the beginning of history.
  return { installationId: id, installationCount: count, days: days.reverse(), sessions };
}

export interface ChargeTotals {
  energyKwh: number;
  gridEnergyKwh: number;
  cost: number;
  sessions: number;
  seconds: number;
  socGained: number;
  peakKw: number;
}

export function sumCharging(sessions: ChargingSession[]): ChargeTotals {
  return sessions.reduce<ChargeTotals>(
    (total, session) => ({
      energyKwh: total.energyKwh + session.energyKwh,
      gridEnergyKwh: total.gridEnergyKwh + (session.gridEnergyKwh ?? session.energyKwh * 1.1),
      cost: total.cost + session.totalCost,
      sessions: total.sessions + 1,
      seconds: total.seconds + session.durationSeconds,
      socGained:
        total.socGained +
        (session.startSocPercent !== undefined && session.endSocPercent !== undefined
          ? Math.max(0, session.endSocPercent - session.startSocPercent)
          : 0),
      peakKw: Math.max(total.peakKw, ...session.curve.map((point) => point.powerKw), 0),
    }),
    { energyKwh: 0, gridEnergyKwh: 0, cost: 0, sessions: 0, seconds: 0, socGained: 0, peakKw: 0 },
  );
}

export function sumDays(days: DailyConsumption[]) {
  return days.reduce(
    (total, record) => ({
      km: total.km + record.day.km,
      kwh: total.kwh + record.day.kwh,
      hours: total.hours + record.day.hours,
    }),
    { km: 0, kwh: 0, hours: 0 },
  );
}
