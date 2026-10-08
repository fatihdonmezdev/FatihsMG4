import { requireSession } from "@/lib/auth";
import { loadOverview, sumCharging, sumDays } from "@/lib/data";
import { dateTime, duration, efficiency, money, num, precise } from "@/lib/format";
import { DailyEnergyChart } from "@/components/charts";
import { Row, Shell, Tile } from "@/components/shell";

export const dynamic = "force-dynamic";

export default async function OverviewPage() {
  await requireSession();
  const { installationId, installationCount, days, sessions } = await loadOverview(30, 200);

  if (!installationId) {
    return (
      <Shell active="/" title="Özet">
        <div className="card">
          <p className="empty">
            Henüz veri yok. Araç ilk günlük kaydı gönderdiğinde burada görünecek.
          </p>
        </div>
      </Shell>
    );
  }

  const charge = sumCharging(sessions);
  const recent = sumDays(days);
  const lifetime = days.length > 0 ? days[days.length - 1].lifetime : null;
  const soh = [...days].reverse().find((day) => day.sohPercent !== undefined)?.sohPercent;
  const lastSession = sessions[0];

  return (
    <Shell
      active="/"
      title="Özet"
      subtitle={installationCount > 1 ? `${installationCount} araçtan en günceli` : "MG4"}
    >
      <div className="tiles">
        <Tile
          label="Şebekeden alınan"
          value={precise(charge.gridEnergyKwh)}
          unit="kWh"
          note={`${charge.sessions} DC şarj`}
          tone="gold"
        />
        <Tile
          label="Ödenen"
          value={money(charge.cost)}
          note={charge.gridEnergyKwh > 0 ? `${money(charge.cost / charge.gridEnergyKwh)}/kWh ort.` : undefined}
          tone="gold"
        />
        <Tile label="Son 30 gün" value={num(recent.km)} unit="km" note={efficiency(recent.kwh, recent.km)} tone="teal" />
        <Tile
          label="Ömür boyu"
          value={lifetime ? num(lifetime.km) : "—"}
          unit="km"
          note={lifetime ? efficiency(lifetime.kwh, lifetime.km) : undefined}
          tone="teal"
        />
      </div>

      <section className="card">
        <h2 className="card-title">Son 30 gün</h2>
        <DailyEnergyChart days={days.map((day) => ({ date: day.date, kwh: day.day.kwh, km: day.day.km }))} />
      </section>

      {lastSession && (
        <section className="card">
          <h2 className="card-title">Son şarj</h2>
          <div className="rows">
            <Row label="Tarih" value={dateTime(lastSession.startedAt)} />
            <Row label="Bataryaya giren net" value={`${precise(lastSession.energyKwh)} kWh`} />
            <Row label="Şebekeden alınan (+%10)" value={`${precise(lastSession.gridEnergyKwh ?? lastSession.energyKwh * 1.1)} kWh`} />
            <Row
              label="Doluluk"
              value={
                lastSession.startSocPercent !== undefined && lastSession.endSocPercent !== undefined
                  ? `%${num(lastSession.startSocPercent)} → %${num(lastSession.endSocPercent)}`
                  : "—"
              }
            />
            <Row label="Süre" value={duration(lastSession.durationSeconds)} />
            <Row label="Ücret" value={money(lastSession.totalCost)} />
          </div>
        </section>
      )}

      <section className="card">
        <h2 className="card-title">Batarya ve toplamlar</h2>
        <div className="rows">
          <Row label="Sağlık (SOH)" value={soh === undefined ? "—" : `%${num(soh)}`} />
          <Row label="Toplam şarj süresi" value={duration(charge.seconds)} />
          <Row label="Görülen en yüksek güç" value={charge.peakKw > 0 ? `${num(charge.peakKw)} kW` : "—"} />
          <Row label="Şarjla kazanılan doluluk" value={charge.socGained > 0 ? `%${num(charge.socGained)}` : "—"} />
          <Row label="Kayıtlı gün" value={`${days.length}`} />
        </div>
      </section>
    </Shell>
  );
}
