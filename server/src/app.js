import crypto from "node:crypto";
import express from "express";

const MAX_BODY_BYTES = "16kb";

export function createApp({ collection, apiToken }) {
  if (!collection) throw new Error("collection is required");
  if (!apiToken || apiToken.length < 24) throw new Error("API_TOKEN must contain at least 24 characters");

  const app = express();
  app.disable("x-powered-by");
  app.use(express.json({ limit: MAX_BODY_BYTES, strict: true }));

  app.get("/health", (_request, response) => response.json({ status: "ok" }));

  app.put("/v1/consumption", authenticate(apiToken), async (request, response, next) => {
    try {
      const record = validateRecord(request.body);
      const updatedAt = new Date();
      await collection.updateOne(
        { installationId: record.installationId },
        {
          $set: { ...record, updatedAt },
          $setOnInsert: { createdAt: updatedAt }
        },
        { upsert: true }
      );
      response.status(204).end();
    } catch (error) {
      if (error instanceof ValidationError) {
        response.status(400).json({ error: error.message });
        return;
      }
      next(error);
    }
  });

  app.use((error, _request, response, _next) => {
    if (error?.type === "entity.parse.failed" || error?.type === "entity.too.large") {
      response.status(400).json({ error: "invalid JSON body" });
      return;
    }
    console.error("Request failed", error);
    response.status(500).json({ error: "internal server error" });
  });
  return app;
}

function authenticate(expected) {
  const expectedBytes = Buffer.from(expected);
  return (request, response, next) => {
    const header = request.get("authorization") ?? "";
    const supplied = header.startsWith("Bearer ") ? header.slice(7) : "";
    const suppliedBytes = Buffer.from(supplied);
    if (suppliedBytes.length !== expectedBytes.length ||
        !crypto.timingSafeEqual(suppliedBytes, expectedBytes)) {
      response.status(401).json({ error: "unauthorized" });
      return;
    }
    next();
  };
}

class ValidationError extends Error {}

function validateRecord(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) throw new ValidationError("body is required");
  if (!/^[0-9a-f]{8}-[0-9a-f-]{27}$/i.test(body.installationId ?? "")) {
    throw new ValidationError("installationId is invalid");
  }
  if (!Number.isSafeInteger(body.recordedAt) || body.recordedAt < 0) {
    throw new ValidationError("recordedAt is invalid");
  }
  return {
    installationId: body.installationId,
    recordedAt: new Date(body.recordedAt),
    lifetime: validateTotals(body.lifetime, "lifetime"),
    ...(body.sohPercent === undefined ? {} : { sohPercent: validateSoh(body.sohPercent) })
  };
}

function validateSoh(value) {
  if (typeof value !== "number" || !Number.isFinite(value) || value <= 0 || value > 100) {
    throw new ValidationError("sohPercent is invalid");
  }
  return value;
}

function validateTotals(value, name) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new ValidationError(`${name} is invalid`);
  const result = {};
  for (const field of ["km", "kwh", "hours", "socDrop"]) {
    const number = value[field];
    if (typeof number !== "number" || !Number.isFinite(number) || Math.abs(number) > 10_000_000) {
      throw new ValidationError(`${name}.${field} is invalid`);
    }
    result[field] = number;
  }
  return result;
}
