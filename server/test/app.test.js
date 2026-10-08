import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { createApp } from "../src/app.js";

const token = "a-secure-test-token-with-24-chars";
const consumptionWrites = [], chargingWrites = [];
const readable = (writes) => ({
  async updateOne(filter, update, options) { writes.push({ filter, update, options }); },
  find() { return { sort() { return this; }, limit() { return this; }, async toArray() { return []; } }; }
});
let server, baseUrl;

before(async () => {
  const app = createApp({ consumptionCollection: readable(consumptionWrites),
    chargingCollection: readable(chargingWrites), apiToken: token });
  await new Promise((resolve) => { server = app.listen(0, "127.0.0.1", resolve); });
  baseUrl = `http://127.0.0.1:${server.address().port}`;
});
after(() => new Promise((resolve) => server.close(resolve)));

const auth = { "content-type": "application/json", authorization: `Bearer ${token}` };
const installationId = "123e4567-e89b-42d3-a456-426614174000";
const consumption = { installationId, recordedAt: 1791417600000, date: "2026-10-08",
  day: { km: 12, kwh: 2.1, hours: .4, socDrop: 4 },
  lifetime: { km: 1000, kwh: 170, hours: 30, socDrop: 400 }, sohPercent: 91.5 };

test("rejects requests without the API token", async () => {
  const response = await fetch(`${baseUrl}/v1/consumption/daily`, { method: "PUT", body: JSON.stringify(consumption), headers: { "content-type": "application/json" } });
  assert.equal(response.status, 401);
});

test("upserts one daily consumption record", async () => {
  const response = await fetch(`${baseUrl}/v1/consumption/daily`, { method: "PUT", headers: auth, body: JSON.stringify(consumption) });
  assert.equal(response.status, 204);
  assert.deepEqual(consumptionWrites.at(-1).filter, { installationId, date: "2026-10-08" });
  assert.equal(consumptionWrites.at(-1).update.$set.day.kwh, 2.1);
});

test("upserts a complete charging session with its curve and price", async () => {
  const sessionId = "223e4567-e89b-42d3-a456-426614174001";
  const session = { installationId, sessionId, startedAt: 1791417600000, endedAt: 1791421200000,
    durationSeconds: 3600, energyKwh: 50, pricePerKwh: 8.5, totalCost: 425,
    startSocPercent: 10, endSocPercent: 90,
    curve: [{ timestampMs: 1791417600000, socPercent: 10, powerKw: 72 }] };
  const response = await fetch(`${baseUrl}/v1/charging-sessions/${sessionId}`,
    { method: "PUT", headers: auth, body: JSON.stringify(session) });
  assert.equal(response.status, 204);
  assert.deepEqual(chargingWrites.at(-1).filter, { installationId, sessionId });
  assert.equal(chargingWrites.at(-1).update.$set.totalCost, 425);
  assert.equal(chargingWrites.at(-1).update.$set.curve[0].powerKw, 72);
});

test("read APIs are authenticated and mobile-client ready", async () => {
  const response = await fetch(`${baseUrl}/v1/charging-sessions/${installationId}`, { headers: { authorization: `Bearer ${token}` } });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { records: [] });
});
