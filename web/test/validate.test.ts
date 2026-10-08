import assert from "node:assert/strict";
import { test } from "node:test";
import { ValidationError, validateChargingSession, validateConsumption } from "../src/lib/validate.ts";

const INSTALLATION = "6f1a2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b";
const SESSION = "11111111-2222-4333-8444-555555555555";

const day = { km: 42.5, kwh: 7.25, hours: 0.9, socDrop: 11.5 };

test("accepts a well-formed daily record", () => {
  const record = validateConsumption({
    installationId: INSTALLATION,
    date: "2026-10-07",
    recordedAt: 1_760_000_000_000,
    day,
    lifetime: { km: 12000, kwh: 2100, hours: 240, socDrop: 3400 },
    sohPercent: 97.5,
  });
  assert.equal(record.date, "2026-10-07");
  assert.equal(record.day.kwh, 7.25);
  assert.ok(record.recordedAt instanceof Date);
});

test("a missing SOH is omitted rather than defaulted", () => {
  // The car omits a field it could not read. Writing 0 here would be a battery at
  // nothing, which is the same class of lie the telemetry side refuses to send.
  const record = validateConsumption({
    installationId: INSTALLATION,
    date: "2026-10-07",
    recordedAt: 1,
    day,
    lifetime: day,
  });
  assert.equal("sohPercent" in record, false);
});

test("rejects malformed identifiers, dates and out-of-range numbers", () => {
  const base = { installationId: INSTALLATION, date: "2026-10-07", recordedAt: 1, day, lifetime: day };
  assert.throws(() => validateConsumption({ ...base, installationId: "nope" }), ValidationError);
  assert.throws(() => validateConsumption({ ...base, date: "07.10.2026" }), ValidationError);
  assert.throws(() => validateConsumption({ ...base, sohPercent: 140 }), ValidationError);
  assert.throws(() => validateConsumption({ ...base, day: { ...day, kwh: "7" } }), ValidationError);
  assert.throws(() => validateConsumption({ ...base, recordedAt: -1 }), ValidationError);
  assert.throws(() => validateConsumption(null), ValidationError);
});

test("a charging session must match the id in its path", () => {
  const body = {
    installationId: INSTALLATION,
    sessionId: SESSION,
    startedAt: 1_760_000_000_000,
    endedAt: 1_760_002_000_000,
    durationSeconds: 1800,
    energyKwh: 31.4,
    chargingLossPercent: 10,
    gridEnergyKwh: 34.54,
    pricePerKwh: 7.5,
    totalCost: 235.5,
    isDemo: true,
    startSocPercent: 18,
    endSocPercent: 72,
    curve: [{ timestampMs: 1_760_000_060_000, powerKw: 84.2, socPercent: 20 }],
  };
  const record = validateChargingSession(body, SESSION);
  assert.equal(record.curve.length, 1);
  assert.equal(record.curve[0].powerKw, 84.2);
  assert.equal(record.chargingLossPercent, 10);
  assert.equal(record.gridEnergyKwh, 34.54);
  assert.equal(record.totalCost, 259.05);
  assert.equal(record.isDemo, true);
  assert.throws(() => validateChargingSession(body, "11111111-2222-4333-8444-555555555556"), ValidationError);
});

test("refuses a curve longer than the car can produce", () => {
  const curve = Array.from({ length: 721 }, (_unused, index) => ({
    timestampMs: 1_760_000_000_000 + index * 60_000,
    powerKw: 50,
  }));
  assert.throws(
    () =>
      validateChargingSession(
        {
          installationId: INSTALLATION,
          sessionId: SESSION,
          startedAt: 1,
          endedAt: 2,
          durationSeconds: 1,
          energyKwh: 1,
          chargingLossPercent: 10,
          gridEnergyKwh: 1.1,
          pricePerKwh: 1,
          totalCost: 1,
          curve,
        },
        SESSION,
      ),
    ValidationError,
  );
});
