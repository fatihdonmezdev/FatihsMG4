import { requireSession } from "@/lib/auth";
import { loadOverview, sumCharging } from "@/lib/data";
import { dateTime, duration, money, num, precise } from "@/lib/format";
import { ChargeCurveChart } from "@/components/charts";
import { Row, Shell, Tile } from "@/components/shell";

export const dynamic = "force-dynamic";

export default async function ChargingPage() {
  await requireSession();
  const { installationId, sessions } = await loadOverview(1, 200);

  if (!installationId || sessions.length === 0) {
    return (
      <Shell active="/charging" title="Şarj">
        <div className="card">
          <p className="empty">
            Şarj kaydı yok. Yalnızca 10 kW üstü DC şarjlar ölçülür; AC şarj kaydedilmez.
          </p>
        </div>
      </Shell>
    );
  }

  const total = sumCharging(sessions);

  return (
    <Shell active="/charging" title="Şarj" subtitle={`${sessions.length} DC seans`}>
      <div className="tiles">
        <Tile label="Bataryaya giren net" value={precise(total.energyKwh)} unit="kWh" tone="gold" />
        <Tile label="Şebekeden alınan (+%10)" value={precise(total.gridEnergyKwh)} unit="kWh" tone="gold" />
        <Tile label="Toplam ödenen" value={money(total.cost)} tone="gold" />
        <Tile
          label="Ortalama birim"
          value={total.gridEnergyKwh > 0 ? money(total.cost / total.gridEnergyKwh) : "—"}
          note="kWh başına"
          tone="teal"
        />
        <Tile label="En yüksek güç" value={num(total.peakKw)} unit="kW" tone="teal" />
      </div>

      <section className="card">
        <h2 className="card-title">Seanslar</h2>
        <ul className="list">
          {sessions.map((session) => {
            const gained =
              session.startSocPercent !== undefined && session.endSocPercent !== undefined
                ? session.endSocPercent - session.startSocPercent
                : null;
            const peak = session.curve.reduce((max, point) => Math.max(max, point.powerKw), 0);
            const average =
              session.durationSeconds > 0 ? (session.energyKwh * 3600) / session.durationSeconds : 0;
            return (
              <li key={session.sessionId}>
                <details className="session">
                  <summary>
                    <div>
                      <div className="session-when">{dateTime(session.startedAt)}</div>
                      <div className="session-meta">
                        {duration(session.durationSeconds)}
                        {gained !== null && ` · %${num(session.startSocPercent as number)} → %${num(session.endSocPercent as number)}`}
                      </div>
                    </div>
                    <div className="session-energy">
                      {precise(session.energyKwh)}
                      <span className="session-cost"> kWh</span>
                      <div className="session-cost">{money(session.totalCost)}</div>
                    </div>
                  </summary>
                  <div className="session-body">
                    <ChargeCurveChart
                      curve={session.curve.map((point) => ({
                        timestamp: new Date(point.timestamp).toISOString(),
                        powerKw: point.powerKw,
                        socPercent: point.socPercent,
                      }))}
                    />
                    <div className="rows" style={{ marginTop: 14 }}>
                      <Row label="Başlangıç" value={dateTime(session.startedAt)} />
                      <Row label="Bitiş" value={dateTime(session.endedAt)} />
                      <Row label="Ölçülen süre" value={duration(session.durationSeconds)} />
                      <Row label="Bataryaya giren net" value={`${precise(session.energyKwh)} kWh`} />
                      <Row label="Şebekeden alınan (+%10)" value={`${precise(session.gridEnergyKwh ?? session.energyKwh * 1.1)} kWh`} />
                      <Row label="Kazanılan doluluk" value={gained === null ? "—" : `%${num(gained)}`} />
                      <Row label="Tepe güç" value={peak > 0 ? `${num(peak)} kW` : "—"} />
                      <Row label="Ortalama güç" value={average > 0 ? `${num(average)} kW` : "—"} />
                      <Row label="Birim fiyat" value={`${money(session.pricePerKwh)}/kWh`} />
                      <Row label="Tutar" value={money(session.totalCost)} />
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
