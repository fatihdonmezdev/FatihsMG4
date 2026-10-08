import Link from "next/link";
import type { ReactNode } from "react";

const TABS = [
  { href: "/", label: "Özet", icon: "◈" },
  { href: "/consumption", label: "Tüketim", icon: "▤" },
  { href: "/charging", label: "Şarj", icon: "⚡" },
] as const;

export type Tab = (typeof TABS)[number]["href"];

/**
 * The phone frame and the bottom tab bar.
 *
 * `active` is passed in rather than read from the pathname so every page stays a server
 * component — the whole dashboard renders without shipping a line of client JavaScript.
 */
export function Shell({
  active,
  title,
  subtitle,
  children,
}: {
  active: Tab;
  title: string;
  subtitle?: string;
  children: ReactNode;
}) {
  return (
    <>
      <main className="shell">
        <header className="header">
          <div>
            <h1 className="title">{title}</h1>
            {subtitle && <p className="subtitle">{subtitle}</p>}
          </div>
          <Link className="signout" href="/logout">
            Çıkış
          </Link>
        </header>
        {children}
      </main>
      <nav className="nav">
        <div className="nav-inner">
          {TABS.map((tab) => (
            <Link key={tab.href} href={tab.href} aria-current={tab.href === active ? "page" : undefined}>
              <span className="nav-icon" aria-hidden>
                {tab.icon}
              </span>
              {tab.label}
            </Link>
          ))}
        </div>
      </nav>
    </>
  );
}

export function Tile({
  label,
  value,
  unit,
  note,
  tone,
}: {
  label: string;
  value: string;
  unit?: string;
  note?: string;
  tone?: "gold" | "teal";
}) {
  return (
    <div className={`tile${tone ? ` ${tone}` : ""}`}>
      <p className="tile-label">{label}</p>
      <p className="tile-value">
        {value}
        {unit && <span className="tile-unit">{unit}</span>}
      </p>
      {note && <p className="tile-note">{note}</p>}
    </div>
  );
}

export function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="row">
      <span className="row-key">{label}</span>
      <span className="row-value">{value}</span>
    </div>
  );
}
