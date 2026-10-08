/**
 * Request validation for the ingest endpoints — a direct port of the Express service's
 * rules, kept pure so it can be unit-tested without a database or a request.
 *
 * The car is the only writer and it is trusted, but a bad field here is written once and
 * read forever. Every number is bounded; an out-of-range value is rejected rather than
 * stored and quietly plotted.
 */
import type { ChargingSession, CurvePoint, DailyConsumption, Totals } from "./mongo";

export class ValidationError extends Error {}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

export type ConsumptionInput = Omit<DailyConsumption, "createdAt" | "updatedAt">;
export type ChargingInput = Omit<ChargingSession, "createdAt" | "updatedAt">;

export function validateConsumption(body: unknown): ConsumptionInput {
  const record = asObject(body);
  const date = record.date;
  if (typeof date !== "string" || !ISO_DATE.test(date)) throw new ValidationError("date is invalid");
  const sohPercent = record.sohPercent;
  return {
    installationId: validateUuid(record.installationId, "installationId"),
    date,
    recordedAt: validateDate(record.recordedAt, "recordedAt"),
    day: validateTotals(record.day, "day"),
    lifetime: validateTotals(record.lifetime, "lifetime"),
    ...(sohPercent === undefined ? {} : { sohPercent: bounded(sohPercent, 0, 100, "sohPercent") }),
  };
}

export function validateChargingSession(body: unknown, pathSessionId: string): ChargingInput {
  const record = asObject(body);
  const sessionId = validateUuid(record.sessionId, "sessionId");
  if (sessionId !== pathSessionId) throw new ValidationError("sessionId does not match path");
  if (!Array.isArray(record.curve) || record.curve.length > 720) {
    throw new ValidationError("curve is invalid");
  }
  const curve: CurvePoint[] = record.curve.map((entry) => {
    const point = asObject(entry);
    const socPercent = point.socPercent;
    return {
      timestamp: validateDate(point.timestampMs, "curve.timestampMs"),
      powerKw: bounded(point.powerKw, 0, 1000, "curve.powerKw"),
      ...(socPercent === undefined ? {} : { socPercent: bounded(socPercent, 0, 100, "curve.socPercent") }),
    };
  });
  const startSocPercent = record.startSocPercent;
  const endSocPercent = record.endSocPercent;
  const energyKwh = bounded(record.energyKwh, 0, 1000, "energyKwh");
  const pricePerKwh = bounded(record.pricePerKwh, 0, 1_000_000, "pricePerKwh");
  const chargingLossPercent = 10;
  const gridEnergyKwh = energyKwh * 1.1;
  return {
    installationId: validateUuid(record.installationId, "installationId"),
    sessionId,
    startedAt: validateDate(record.startedAt, "startedAt"),
    endedAt: validateDate(record.endedAt, "endedAt"),
    durationSeconds: bounded(record.durationSeconds, 0, 604800, "durationSeconds"),
    energyKwh,
    chargingLossPercent,
    gridEnergyKwh,
    pricePerKwh,
    totalCost: gridEnergyKwh * pricePerKwh,
    ...(startSocPercent === undefined ? {} : { startSocPercent: bounded(startSocPercent, 0, 100, "startSocPercent") }),
    ...(endSocPercent === undefined ? {} : { endSocPercent: bounded(endSocPercent, 0, 100, "endSocPercent") }),
    curve,
  };
}

export function validateUuid(value: unknown, name: string): string {
  if (typeof value !== "string" || !UUID.test(value)) throw new ValidationError(`${name} is invalid`);
  return value;
}

function asObject(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new ValidationError("body is required");
  }
  return value as Record<string, unknown>;
}

/** Epoch milliseconds on the wire; a Date in the database. */
function validateDate(value: unknown, name: string): Date {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
    throw new ValidationError(`${name} is invalid`);
  }
  return new Date(value);
}

function bounded(value: unknown, min: number, max: number, name: string): number {
  if (typeof value !== "number" || !Number.isFinite(value) || value < min || value > max) {
    throw new ValidationError(`${name} is invalid`);
  }
  return value;
}

function validateTotals(value: unknown, name: string): Totals {
  const record = asObject(value);
  const result = {} as Totals;
  for (const field of ["km", "kwh", "hours", "socDrop"] as const) {
    const number = record[field];
    if (typeof number !== "number" || !Number.isFinite(number) || Math.abs(number) > 10_000_000) {
      throw new ValidationError(`${name}.${field} is invalid`);
    }
    result[field] = number;
  }
  return result;
}
