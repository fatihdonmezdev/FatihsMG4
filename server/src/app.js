import crypto from "node:crypto";
import express from "express";

export function createApp({ consumptionCollection, chargingCollection, apiToken }) {
  if (!consumptionCollection || !chargingCollection) throw new Error("collections are required");
  if (!apiToken || apiToken.length < 24) throw new Error("API_TOKEN must contain at least 24 characters");
  const app = express();
  app.disable("x-powered-by");
  app.use(express.json({ limit: "256kb", strict: true }));
  const auth = authenticate(apiToken);
  app.get("/health", (_request, response) => response.json({ status: "ok" }));

  app.put("/v1/consumption/daily", auth, route(async (request, response) => {
    const record = validateConsumption(request.body);
    const updatedAt = new Date();
    await consumptionCollection.updateOne(
      { installationId: record.installationId, date: record.date },
      { $set: { ...record, updatedAt }, $setOnInsert: { createdAt: updatedAt } }, { upsert: true });
    response.status(204).end();
  }));

  app.put("/v1/charging-sessions/:sessionId", auth, route(async (request, response) => {
    const record = validateChargingSession(request.body, request.params.sessionId);
    const updatedAt = new Date();
    await chargingCollection.updateOne(
      { installationId: record.installationId, sessionId: record.sessionId },
      { $set: { ...record, updatedAt }, $setOnInsert: { createdAt: updatedAt } }, { upsert: true });
    response.status(204).end();
  }));

  app.get("/v1/consumption/:installationId", auth, route(async (request, response) => {
    const installationId = validateUuid(request.params.installationId, "installationId");
    const records = await consumptionCollection.find({ installationId }).sort({ date: -1 }).limit(400).toArray();
    response.json({ records });
  }));
  app.get("/v1/charging-sessions/:installationId", auth, route(async (request, response) => {
    const installationId = validateUuid(request.params.installationId, "installationId");
    const records = await chargingCollection.find({ installationId }).sort({ startedAt: -1 }).limit(200).toArray();
    response.json({ records });
  }));

  app.use((error, _request, response, _next) => {
    if (error instanceof ValidationError || error?.type === "entity.parse.failed" || error?.type === "entity.too.large") {
      response.status(400).json({ error: error.message || "invalid request" });
      return;
    }
    console.error("Request failed", error);
    response.status(500).json({ error: "internal server error" });
  });
  return app;
}

function route(handler) { return (request, response, next) => Promise.resolve(handler(request, response)).catch(next); }

function authenticate(expected) {
  const expectedBytes = Buffer.from(expected);
  return (request, response, next) => {
    const header = request.get("authorization") ?? "";
    const supplied = header.startsWith("Bearer ") ? header.slice(7) : "";
    const suppliedBytes = Buffer.from(supplied);
    if (suppliedBytes.length !== expectedBytes.length || !crypto.timingSafeEqual(suppliedBytes, expectedBytes)) {
      response.status(401).json({ error: "unauthorized" }); return;
    }
    next();
  };
}

class ValidationError extends Error {}

function validateConsumption(body) {
  validateBody(body);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(body.date ?? "")) throw new ValidationError("date is invalid");
  return {
    installationId: validateUuid(body.installationId, "installationId"), date: body.date,
    recordedAt: validateDate(body.recordedAt, "recordedAt"), day: validateTotals(body.day, "day"),
    lifetime: validateTotals(body.lifetime, "lifetime"),
    ...(body.sohPercent === undefined ? {} : { sohPercent: bounded(body.sohPercent, 0, 100, "sohPercent") })
  };
}

function validateChargingSession(body, pathSessionId) {
  validateBody(body);
  const sessionId = validateUuid(body.sessionId, "sessionId");
  if (sessionId !== pathSessionId) throw new ValidationError("sessionId does not match path");
  if (!Array.isArray(body.curve) || body.curve.length > 720) throw new ValidationError("curve is invalid");
  const curve = body.curve.map((point) => ({
    timestamp: validateDate(point.timestampMs, "curve.timestampMs"),
    powerKw: bounded(point.powerKw, 0, 1000, "curve.powerKw"),
    ...(point.socPercent === undefined ? {} : { socPercent: bounded(point.socPercent, 0, 100, "curve.socPercent") })
  }));
  return {
    installationId: validateUuid(body.installationId, "installationId"), sessionId,
    startedAt: validateDate(body.startedAt, "startedAt"), endedAt: validateDate(body.endedAt, "endedAt"),
    durationSeconds: bounded(body.durationSeconds, 0, 604800, "durationSeconds"),
    energyKwh: bounded(body.energyKwh, 0, 1000, "energyKwh"),
    pricePerKwh: bounded(body.pricePerKwh, 0, 1_000_000, "pricePerKwh"),
    totalCost: bounded(body.totalCost, 0, 1_000_000_000, "totalCost"),
    ...(body.startSocPercent === undefined ? {} : { startSocPercent: bounded(body.startSocPercent, 0, 100, "startSocPercent") }),
    ...(body.endSocPercent === undefined ? {} : { endSocPercent: bounded(body.endSocPercent, 0, 100, "endSocPercent") }), curve
  };
}

function validateBody(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) throw new ValidationError("body is required");
}
function validateUuid(value, name) {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value ?? ""))
    throw new ValidationError(`${name} is invalid`);
  return value;
}
function validateDate(value, name) {
  if (!Number.isSafeInteger(value) || value < 0) throw new ValidationError(`${name} is invalid`);
  return new Date(value);
}
function bounded(value, min, max, name) {
  if (typeof value !== "number" || !Number.isFinite(value) || value < min || value > max)
    throw new ValidationError(`${name} is invalid`);
  return value;
}
function validateTotals(value, name) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new ValidationError(`${name} is invalid`);
  const result = {};
  for (const field of ["km", "kwh", "hours", "socDrop"]) {
    const number = value[field];
    if (typeof number !== "number" || !Number.isFinite(number) || Math.abs(number) > 10_000_000)
      throw new ValidationError(`${name}.${field} is invalid`);
    result[field] = number;
  }
  return result;
}
