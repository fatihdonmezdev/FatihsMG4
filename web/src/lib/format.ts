/** Turkish formatting for every user-facing number and date. */

const NUMBER = new Intl.NumberFormat("tr-TR", { maximumFractionDigits: 1 });
const PRECISE = new Intl.NumberFormat("tr-TR", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const CURRENCY = new Intl.NumberFormat("tr-TR", { style: "currency", currency: "TRY", maximumFractionDigits: 2 });
const DATE = new Intl.DateTimeFormat("tr-TR", { day: "numeric", month: "long", year: "numeric" });
const DATE_TIME = new Intl.DateTimeFormat("tr-TR", {
  day: "numeric",
  month: "short",
  hour: "2-digit",
  minute: "2-digit",
});

export function num(value: number): string {
  return NUMBER.format(value);
}

export function precise(value: number): string {
  return PRECISE.format(value);
}

export function money(value: number): string {
  return CURRENCY.format(value);
}

export function isoDate(iso: string): string {
  const [year, month, day] = iso.split("-").map(Number);
  return DATE.format(new Date(Date.UTC(year, month - 1, day)));
}

export function dateTime(value: Date | string): string {
  return DATE_TIME.format(new Date(value));
}

/** `2 sa 05 dk` — the same shape the car's charging pane uses. */
export function duration(seconds: number): string {
  const total = Math.max(0, Math.round(seconds));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  if (hours === 0) return `${minutes} dk`;
  return `${hours} sa ${String(minutes).padStart(2, "0")} dk`;
}

/** Energy per 100 km, or a dash — never a ratio computed from a distance of nothing. */
export function efficiency(kwh: number, km: number): string {
  if (km < 1) return "—";
  return `${num((kwh * 100) / km)} kWh/100 km`;
}
