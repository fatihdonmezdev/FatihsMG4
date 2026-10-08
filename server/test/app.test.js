import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { createApp } from "../src/app.js";

const token = "a-secure-test-token-with-24-chars";
const writes = [];
const collection = {
  async updateOne(filter, update, options) { writes.push({ filter, update, options }); }
};
let server;
let baseUrl;

before(async () => {
  const app = createApp({ collection, apiToken: token });
  await new Promise((resolve) => { server = app.listen(0, "127.0.0.1", resolve); });
  baseUrl = `http://127.0.0.1:${server.address().port}`;
});
after(() => new Promise((resolve) => server.close(resolve)));

const validBody = {
  installationId: "123e4567-e89b-12d3-a456-426614174000",
  recordedAt: 1791417600000,
  lifetime: { km: 1000, kwh: 170, hours: 30, socDrop: 400 },
  sohPercent: 91.5
};

test("rejects requests without the API token", async () => {
  const response = await fetch(`${baseUrl}/v1/consumption`, { method: "PUT", body: JSON.stringify(validBody), headers: { "content-type": "application/json" } });
  assert.equal(response.status, 401);
});

test("validates malformed all-time totals", async () => {
  const response = await fetch(`${baseUrl}/v1/consumption`, {
    method: "PUT",
    headers: { "content-type": "application/json", authorization: `Bearer ${token}` },
    body: JSON.stringify({ ...validBody, lifetime: { ...validBody.lifetime, km: "12" } })
  });
  assert.equal(response.status, 400);
});

test("upserts one all-time record per installation", async () => {
  const response = await fetch(`${baseUrl}/v1/consumption`, {
    method: "PUT",
    headers: { "content-type": "application/json", authorization: `Bearer ${token}` },
    body: JSON.stringify(validBody)
  });
  assert.equal(response.status, 204);
  assert.deepEqual(writes.at(-1).filter, { installationId: validBody.installationId });
  assert.equal(writes.at(-1).options.upsert, true);
  assert.equal(writes.at(-1).update.$set.lifetime.kwh, 170);
  assert.equal(writes.at(-1).update.$set.sohPercent, 91.5);
});
