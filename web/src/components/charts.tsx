/*
 * Hand-rolled SVG charts.
 *
 * A charting library would be the larger part of the bundle for three small plots, and
 * these are server-rendered: no hydration, no client JavaScript, and they draw correctly
 * before anything loads. Each one states its own units in the legend rather than relying
 * on a tooltip, because a tooltip needs a pointer and this is read on a phone.
 */

const GOLD = "#d8c39a";
const CYAN = "#a0e2e0";
const GRID = "#363b3d";
const MUTED = "#bdc4c6";

interface Plot {
  width: number;
  height: number;
  padTop: number;
  padRight: number;
  padBottom: number;
  padLeft: number;
}

const PLOT: Plot = { width: 320, height: 150, padTop: 10, padRight: 8, padBottom: 20, padLeft: 34 };

function innerWidth(plot: Plot) {
  return plot.width - plot.padLeft - plot.padRight;
}
function innerHeight(plot: Plot) {
  return plot.height - plot.padTop - plot.padBottom;
}

/** A rounded axis maximum, so the gridline labels are readable numbers. */
function niceMax(value: number): number {
  if (!(value > 0)) return 1;
  const magnitude = 10 ** Math.floor(Math.log10(value));
  const normalised = value / magnitude;
  const step = normalised <= 1 ? 1 : normalised <= 2 ? 2 : normalised <= 5 ? 5 : 10;
  return step * magnitude;
}

function Grid({ plot, max, format }: { plot: Plot; max: number; format: (value: number) => string }) {
  const lines = [0, 0.5, 1];
  return (
    <g>
      {lines.map((fraction) => {
        const y = plot.padTop + innerHeight(plot) * (1 - fraction);
        return (
          <g key={fraction}>
            <line
              x1={plot.padLeft}
              x2={plot.width - plot.padRight}
              y1={y}
              y2={y}
              stroke={GRID}
              strokeWidth={1}
              strokeDasharray={fraction === 0 ? undefined : "2 4"}
            />
            <text x={plot.padLeft - 6} y={y + 3.5} fill={MUTED} fontSize={9} textAnchor="end">
              {format(max * fraction)}
            </text>
          </g>
        );
      })}
    </g>
  );
}

export interface DayDatum {
  date: string;
  kwh: number;
  km: number;
}

/**
 * Daily energy as bars. Days with no record are absent rather than drawn as zero — the
 * car was not driven, which is not the same as a day of zero consumption, and the same
 * distinction the telemetry side makes between a failed read and a zero.
 */
export function DailyEnergyChart({ days }: { days: DayDatum[] }) {
  if (days.length === 0) return <p className="empty">Henüz günlük kayıt yok</p>;
  const plot = PLOT;
  const max = niceMax(Math.max(...days.map((day) => day.kwh)));
  const slot = innerWidth(plot) / days.length;
  const barWidth = Math.max(1.5, Math.min(14, slot * 0.62));
  return (
    <>
      <svg className="chart" viewBox={`0 0 ${plot.width} ${plot.height}`} role="img"
           aria-label="Gün gün tüketilen enerji">
        <Grid plot={plot} max={max} format={(value) => value.toFixed(0)} />
        {days.map((day, index) => {
          const height = (day.kwh / max) * innerHeight(plot);
          const x = plot.padLeft + slot * (index + 0.5) - barWidth / 2;
          return (
            <rect
              key={day.date}
              x={x}
              y={plot.padTop + innerHeight(plot) - height}
              width={barWidth}
              height={Math.max(height, day.kwh > 0 ? 1 : 0)}
              rx={2}
              fill={GOLD}
              opacity={0.92}
            >
              <title>{`${day.date} — ${day.kwh.toFixed(2)} kWh, ${day.km.toFixed(1)} km`}</title>
            </rect>
          );
        })}
        <text x={plot.padLeft} y={plot.height - 6} fill={MUTED} fontSize={9}>
          {shortDate(days[0].date)}
        </text>
        <text x={plot.width - plot.padRight} y={plot.height - 6} fill={MUTED} fontSize={9} textAnchor="end">
          {shortDate(days[days.length - 1].date)}
        </text>
      </svg>
      <div className="legend">
        <span>
          <span className="swatch" style={{ background: GOLD }} />
          Günlük enerji (kWh)
        </span>
      </div>
    </>
  );
}

/** Efficiency over time. Days under 1 km are dropped: the ratio is meaningless there. */
export function EfficiencyChart({ days }: { days: DayDatum[] }) {
  const points = days
    .filter((day) => day.km >= 1 && day.kwh > 0)
    .map((day) => ({ date: day.date, value: (day.kwh * 100) / day.km }));
  if (points.length < 2) return <p className="empty">Grafik için yeterli gün yok</p>;
  const plot = PLOT;
  const max = niceMax(Math.max(...points.map((point) => point.value)));
  const step = innerWidth(plot) / Math.max(1, points.length - 1);
  const path = points
    .map((point, index) => {
      const x = plot.padLeft + step * index;
      const y = plot.padTop + innerHeight(plot) * (1 - point.value / max);
      return `${index === 0 ? "M" : "L"}${x.toFixed(2)} ${y.toFixed(2)}`;
    })
    .join(" ");
  const average = points.reduce((total, point) => total + point.value, 0) / points.length;
  const averageY = plot.padTop + innerHeight(plot) * (1 - average / max);
  return (
    <>
      <svg className="chart" viewBox={`0 0 ${plot.width} ${plot.height}`} role="img"
           aria-label="Gün gün ortalama tüketim">
        <Grid plot={plot} max={max} format={(value) => value.toFixed(0)} />
        <line
          x1={plot.padLeft}
          x2={plot.width - plot.padRight}
          y1={averageY}
          y2={averageY}
          stroke={GOLD}
          strokeWidth={1}
          strokeDasharray="4 4"
          opacity={0.7}
        />
        <path d={path} fill="none" stroke={CYAN} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
        {points.map((point, index) => (
          <circle
            key={point.date}
            cx={plot.padLeft + step * index}
            cy={plot.padTop + innerHeight(plot) * (1 - point.value / max)}
            r={1.8}
            fill={CYAN}
          >
            <title>{`${point.date} — ${point.value.toFixed(1)} kWh/100 km`}</title>
          </circle>
        ))}
      </svg>
      <div className="legend">
        <span>
          <span className="swatch" style={{ background: CYAN }} />
          kWh/100 km
        </span>
        <span>
          <span className="swatch" style={{ background: GOLD }} />
          Ortalama {average.toFixed(1)}
        </span>
      </div>
    </>
  );
}

export interface CurveDatum {
  timestamp: string;
  powerKw: number;
  socPercent?: number;
}

/**
 * Charge curve: power against time, with SOC on its own 0–100 scale.
 *
 * Two quantities on one plot because the shape of a DC charge is the relationship between
 * them — the taper is only legible next to the percentage that caused it.
 */
export function ChargeCurveChart({ curve }: { curve: CurveDatum[] }) {
  if (curve.length < 2) return <p className="empty">Bu seans için eğri kaydı yok</p>;
  const plot = { ...PLOT, padRight: 26 };
  const times = curve.map((point) => new Date(point.timestamp).getTime());
  const start = times[0];
  const span = Math.max(1, times[times.length - 1] - start);
  const maxPower = niceMax(Math.max(...curve.map((point) => point.powerKw)));

  const x = (time: number) => plot.padLeft + (innerWidth(plot) * (time - start)) / span;
  const yPower = (kw: number) => plot.padTop + innerHeight(plot) * (1 - kw / maxPower);
  const ySoc = (percent: number) => plot.padTop + innerHeight(plot) * (1 - percent / 100);

  const powerPath = curve
    .map((point, index) => `${index === 0 ? "M" : "L"}${x(times[index]).toFixed(2)} ${yPower(point.powerKw).toFixed(2)}`)
    .join(" ");
  const areaPath = `${powerPath} L${x(times[times.length - 1]).toFixed(2)} ${(plot.padTop + innerHeight(plot)).toFixed(2)} L${x(start).toFixed(2)} ${(plot.padTop + innerHeight(plot)).toFixed(2)} Z`;

  const socPoints = curve
    .map((point, index) => ({ point, index }))
    .filter((entry) => typeof entry.point.socPercent === "number");
  const socPath = socPoints
    .map((entry, order) =>
      `${order === 0 ? "M" : "L"}${x(times[entry.index]).toFixed(2)} ${ySoc(entry.point.socPercent as number).toFixed(2)}`)
    .join(" ");

  return (
    <>
      <svg className="chart" viewBox={`0 0 ${plot.width} ${plot.height}`} role="img"
           aria-label="Şarj gücü ve doluluk eğrisi">
        <Grid plot={plot} max={maxPower} format={(value) => value.toFixed(0)} />
        <defs>
          <linearGradient id="powerFill" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stopColor={GOLD} stopOpacity={0.32} />
            <stop offset="100%" stopColor={GOLD} stopOpacity={0.02} />
          </linearGradient>
        </defs>
        <path d={areaPath} fill="url(#powerFill)" stroke="none" />
        <path d={powerPath} fill="none" stroke={GOLD} strokeWidth={2} strokeLinejoin="round" />
        {socPath && <path d={socPath} fill="none" stroke={CYAN} strokeWidth={1.6} strokeDasharray="5 3" />}
        <text x={plot.width - plot.padRight + 4} y={plot.padTop + 4} fill={MUTED} fontSize={9}>
          100%
        </text>
        <text x={plot.width - plot.padRight + 4} y={plot.padTop + innerHeight(plot)} fill={MUTED} fontSize={9}>
          0%
        </text>
        <text x={plot.padLeft} y={plot.height - 6} fill={MUTED} fontSize={9}>
          0 dk
        </text>
        <text x={plot.width - plot.padRight} y={plot.height - 6} fill={MUTED} fontSize={9} textAnchor="end">
          {Math.round(span / 60000)} dk
        </text>
      </svg>
      <div className="legend">
        <span>
          <span className="swatch" style={{ background: GOLD }} />
          Güç (kW)
        </span>
        <span>
          <span className="swatch" style={{ background: CYAN }} />
          Doluluk (%)
        </span>
      </div>
    </>
  );
}

function shortDate(iso: string): string {
  const [, month, day] = iso.split("-");
  return `${day}.${month}`;
}
