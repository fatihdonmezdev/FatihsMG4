import { MongoClient } from "mongodb";
import { createApp } from "./app.js";

const { MONGODB_URI, API_TOKEN } = process.env;
const databaseName = process.env.MONGODB_DATABASE || "fatihsmg4";
const port = Number(process.env.PORT || 8080);

if (!MONGODB_URI) throw new Error("MONGODB_URI is required");
if (!API_TOKEN) throw new Error("API_TOKEN is required");
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("PORT is invalid");

const client = new MongoClient(MONGODB_URI, { serverSelectionTimeoutMS: 10_000 });
await client.connect();
const database = client.db(databaseName);
const consumptionCollection = database.collection("daily_consumption");
const chargingCollection = database.collection("charging_sessions");
await consumptionCollection.createIndex({ installationId: 1, date: 1 }, { unique: true });
await consumptionCollection.createIndex({ installationId: 1, date: -1 });
await chargingCollection.createIndex({ installationId: 1, sessionId: 1 }, { unique: true });
await chargingCollection.createIndex({ installationId: 1, startedAt: -1 });

const app = createApp({ consumptionCollection, chargingCollection, apiToken: API_TOKEN });
const server = app.listen(port, () => console.log(`FatihsMG4 API listening on ${port}`));

async function shutdown() {
  server.close(async () => {
    await client.close();
    process.exit(0);
  });
}
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
