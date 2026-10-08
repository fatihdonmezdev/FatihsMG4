import { requireSession } from "@/lib/auth";
import { loadOverview, sumDays } from "@/lib/data";
import { duration, efficiency, isoDate, num, precise } from "@/lib/format";
import { DailyEnergyChart, EfficiencyChart } from "@/components/charts";
import { Row, Shell, Tile } from "@/components/shell";

export const dynamic = "force-dynamic";

export default async function ConsumptionPage() {
  await requireSession();
  const { installationId, days } = await loadOverview(90, 1);

  if (!installationId || days.length === 0) {
    return (
      <Shell active="/consumption" title="Tüketim">
        <div className="card">
          <p className="empty">Günlük tüketim kaydı yok. Araç günü kapattığında yüklenir.</p>
        </div>
      </Shell>
    );
  }

  const chartDays = days.map((day) => ({ date: day.date, kwh: day.day.kwh, km: day.day.km }));
  const total = sumDays(days);
  const lifetime = days[days.length - 1].lifetime;
  // Newest first for reading; the charts above keep chronological order.
  const table = [...days].reverse();

  return (
    <Shell active="/consumption" title="Tüketim" subtitle={`${days.length} günlük kayıt`}>
      <div className="tiles">
        <Tile label="Toplam mesafe" value={num(total.km)} unit="km" tone="teal" />
        <Tile label="Toplam enerji" value={precise(total.kwh)} unit="kWh" tone="gold" />
        <Tile label="Ortalama" value={efficiency(total.kwh, total.km)} tone="teal" />
        <Tile
          label="Ortalama hız"
          value={total.hours > 0 ? num(total.km / total.hours) : "—"}
          unit={total.hours > 0 ? "km/h" : undefined}
        />
      </div>

      <section className="card">
        <h2 className="card-title">Günlük enerji</h2>
        <DailyEnergyChart days={chartDays} />
      </section>

      <section className="card">
        <h2 className="card-title">Verimlilik eğilimi</h2>
        <EfficiencyChart days={chartDays} />
      </section>

      <section className="card">
        <h2 className="card-title">Ömür boyu</h2>
        <div className="rows">
          <Row label="Mesafe" value={`${num(lifetime.km)} km`} />
          <Row label="Enerji" value={`${precise(lifetime.kwh)} kWh`} />
          <Row label="Ortalama" value={efficiency(lifetime.kwh, lifetime.km)} />
          <Row label="Sürüş süresi" value={duration(lifetime.hours * 3600)} />
        </div>
      </section>

      <section className="card">
        <h2 className="card-title">Gün gün</h2>
        <ul className="list">
          {table.map((day) => (
            <li key={day.date}>
              <details className="session">
                <summary>
                  <div>
                    <div className="session-when">{isoDate(day.date)}</div>
                    <div className="session-meta">
                      {num(day.day.km)} km · {efficiency(day.day.kwh, day.day.km)}
                    </div>
                  </div>
                  <div className="session-energy">
                    {precise(day.day.kwh)}
                    <span className="session-cost"> kWh</span>
                  </div>
                </summary>
                <div className="session-body">
                  <div className="rows">
                    <Row label="Sürüş süresi" value={duration(day.day.hours * 3600)} />
                    <Row
                      label="Ortalama hız"
                      value={day.day.hours > 0 ? `${num(day.day.km / day.day.hours)} km/h` : "—"}
                    />
                    <Row label="Doluluk düşüşü" value={`%${num(day.day.socDrop)}`} />
                    <Row label="O gün sonu ömür boyu" value={`${num(day.lifetime.km)} km`} />
                  </div>
                </div>
              </details>
            </li>
          ))}
        </ul>
      </section>
    </Shell>
  );
}
