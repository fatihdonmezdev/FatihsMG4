import { MongoClient, type Collection, type Db } from "mongodb";

/**
 * One MongoClient per warm lambda, cached on globalThis.
 *
 * A serverless function is re-entered far more often than it is cold-started, and a fresh
 * MongoClient per request opens a fresh pool per request — Atlas starts refusing
 * connections long before the traffic justifies it. The global survives module reloads in
 * dev, where Next re-evaluates modules on every edit.
 */
const globalForMongo = globalThis as unknown as { mongoClient?: Promise<MongoClient> };

export interface Totals {
  km: number;
  kwh: number;
  hours: number;
  socDrop: number;
}

export interface DailyConsumption {
  installationId: string;
  date: string;
  recordedAt: Date;
  day: Totals;
  lifetime: Totals;
  sohPercent?: number;
  createdAt: Date;
  updatedAt: Date;
}

export interface CurvePoint {
  timestamp: Date;
  powerKw: number;
  socPercent?: number;
}

export interface ChargingSession {
  installationId: string;
  sessionId: string;
  startedAt: Date;
  endedAt: Date;
  durationSeconds: number;
  energyKwh: number;
  chargingLossPercent: number;
  gridEnergyKwh: number;
  pricePerKwh: number;
  totalCost: number;
  startSocPercent?: number;
  endSocPercent?: number;
  curve: CurvePoint[];
  createdAt: Date;
  updatedAt: Date;
}

function clientPromise(): Promise<MongoClient> {
  const uri = process.env.MONGODB_URI;
  if (!uri) throw new Error("MONGODB_URI is not configured");
  if (!globalForMongo.mongoClient) {
    globalForMongo.mongoClient = new MongoClient(uri, {
      serverSelectionTimeoutMS: 10_000,
      maxPoolSize: 10,
    }).connect();
  }
  return globalForMongo.mongoClient;
}

async function db(): Promise<Db> {
  const client = await clientPromise();
  return client.db(process.env.MONGODB_DATABASE || "fatihsmg4");
}

export async function consumptionCollection(): Promise<Collection<DailyConsumption>> {
  return (await db()).collection<DailyConsumption>("daily_consumption");
}

export async function chargingCollection(): Promise<Collection<ChargingSession>> {
  return (await db()).collection<ChargingSession>("charging_sessions");
}

/**
 * Creates the indexes the Express service used to create at boot.
 *
 * There is no boot here — a lambda starts on a request — so this runs on the first write
 * of each warm instance and is skipped afterwards. `createIndex` is idempotent, but it is
 * still a round trip, and the uniqueness constraints are what make the upserts safe.
 */
let indexesEnsured = false;
export async function ensureIndexes(): Promise<void> {
  if (indexesEnsured) return;
  const [consumption, charging] = await Promise.all([consumptionCollection(), chargingCollection()]);
  await Promise.all([
    consumption.createIndex({ installationId: 1, date: 1 }, { unique: true }),
    consumption.createIndex({ installationId: 1, date: -1 }),
    charging.createIndex({ installationId: 1, sessionId: 1 }, { unique: true }),
    charging.createIndex({ installationId: 1, startedAt: -1 }),
  ]);
  indexesEnsured = true;
}
