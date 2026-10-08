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
const collection = client.db(databaseName).collection("consumption");
await collection.createIndex({ installationId: 1 }, { unique: true });
await collection.createIndex({ updatedAt: -1 });

const app = createApp({ collection, apiToken: API_TOKEN });
const server = app.listen(port, () => console.log(`FatihsMG4 API listening on ${port}`));

async function shutdown() {
  server.close(async () => {
    await client.close();
    process.exit(0);
  });
}
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
