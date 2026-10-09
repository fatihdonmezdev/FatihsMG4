import Link from "next/link";
import { requireSession } from "@/lib/auth";
import { loadOverview, sumDays } from "@/lib/data";
import { duration, efficiency, isoDate, num, precise } from "@/lib/format";
import { DailyEnergyChart, EfficiencyChart } from "@/components/charts";
import { Row, Shell, Tile } from "@/components/shell";

export const dynamic = "force-dynamic";

export default async function ConsumptionPage({
  searchParams,
}: {
  searchParams: Promise<{ date?: string }>;
}) {
  await requireSession();
  const { date: selectedDate } = await searchParams;
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
  // Lifetime is derived: the sum of every day's totals, same as the backend computes.
  const lifetime = { km: total.km, kwh: total.kwh, hours: total.hours, socDrop: 0 };
  // Newest first for reading; the charts above keep chronological order.
  const table = [...days].reverse();

  // Find the selected day's record (if any). table is newest-first; a Map would be overkill
  // for ≤90 entries.
  const selected = selectedDate ? table.find((d) => d.date === selectedDate) : null;
  const hasFilter = !!selectedDate;
  const validFilter = hasFilter && !!selected;

  // Date input bounds: oldest record to today.
  const minDate = days[0].date; // days is chronological (oldest first after loadOverview reverse)
  const maxDate = new Date().toISOString().slice(0, 10);

  return (
    <Shell
      active="/consumption"
      title="Tüketim"
      subtitle={hasFilter ? (validFilter ? isoDate(selectedDate!) : "Seçili gün kaydı yok") : `${days.length} günlük kayıt`}
    >
      {/* Date filter */}
      <section className="card">
        <form className="filter-form" method="get" action="/consumption">
          <label className="filter-label" htmlFor="date-picker">
            Tarihe göre filtrele
          </label>
          <div className="filter-row">
            <input
              id="date-picker"
              type="date"
              name="date"
              defaultValue={selectedDate ?? ""}
              min={minDate}
              max={maxDate}
              className="field date-input"
            />
            <button className="button filter-button" type="submit">Filtrele</button>
            {hasFilter && (
              <Link className="filter-clear" href="/consumption">Temizle</Link>
            )}
          </div>
        </form>
      </section>

      {/* Selected day detail — shown above the period tiles when a filter is active */}
      {hasFilter && (
        <section className="card">
          <h2 className="card-title">{isoDate(selectedDate!)}</h2>
          {validFilter && selected ? (
            <>
              <div className="tiles">
                <Tile label="Mesafe" value={num(selected.day.km)} unit="km" tone="teal" />
                <Tile label="Enerji" value={precise(selected.day.kwh)} unit="kWh" tone="gold" />
                <Tile label="Ortalama" value={efficiency(selected.day.kwh, selected.day.km)} tone="teal" />
                <Tile
                  label="Ortalama hız"
                  value={selected.day.hours > 0 ? num(selected.day.km / selected.day.hours) : "—"}
                  unit={selected.day.hours > 0 ? "km/h" : undefined}
                />
              </div>
              <div className="rows">
                <Row label="Sürüş süresi" value={duration(selected.day.hours * 3600)} />
                <Row label="Doluluk düşüşü" value={`%${num(selected.day.socDrop)}`} />
                <Row label="O gün sonu ömür boyu" value={selected.lifetime ? `${num(selected.lifetime.km)} km` : "—"} />
              </div>
            </>
          ) : (
            <p className="empty">Bu tarih için tüketim kaydı yok.</p>
          )}
        </section>
      )}

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
          {table.map((day) => {
            const isSelected = hasFilter && day.date === selectedDate;
            return (
              <li key={day.date}>
                <details className={`session${isSelected ? " selected" : ""}`} open={isSelected}>
                  <summary>
                    <div>
                      <div className="session-when">
                        <Link href={`/consumption?date=${day.date}`} className="day-link">
                          {isoDate(day.date)}
                        </Link>
                      </div>
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
                      <Row label="O gün sonu ömür boyu" value={day.lifetime ? `${num(day.lifetime.km)} km` : "—"} />
                    </div>
                  </div>
                </details>
              </li>
            );
          })}
        </ul>
      </section>
    </Shell>
  );
}
