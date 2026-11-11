import { useEffect, useState } from "react";
import { Car, Gauge, Monitor, Radio, Bluetooth } from "lucide-react";
import { clock, formatMs } from "@/lib/link/format";
import { integrityLabel } from "@/lib/link/integrity";
import { startLink, useLink, type View } from "@/lib/link/store";
import { BusScreen, CarScreen, DriveScreen, GlassScreen, PathScreen } from "./screens";

const NAV: { id: View; label: string; icon: typeof Gauge }[] = [
  { id: "drive", label: "Drive", icon: Gauge },
  { id: "path", label: "Path", icon: Radio },
  { id: "glass", label: "Glass", icon: Monitor },
  { id: "bus", label: "Bus", icon: Bluetooth },
  { id: "car", label: "Car", icon: Car },
];

function transportLabel(transport: string, online: boolean, booted: boolean): string {
  if (!booted) return "…";
  if (!online || transport === "offline") return "OFFLINE";
  if (transport === "wifi") return "WI-FI";
  if (transport === "cellular") return "CELLULAR";
  if (transport === "ethernet") return "ETHERNET";
  if (transport === "other") return "OTHER";
  return "UNSTATED";
}

export function App() {
  const view = useLink((s) => s.view);
  const setView = useLink((s) => s.setView);
  const transport = useLink((s) => s.transport);
  const online = useLink((s) => s.online);
  const booted = useLink((s) => s.booted);
  const rtt = useLink((s) => s.rtt);
  const integrity = useLink((s) => s.integrity);
  const gps = useLink((s) => s.gps);
  const [now, setNow] = useState<number | null>(null);

  useEffect(() => startLink(), []);
  useEffect(() => {
    const tick = () => setNow(Date.now());
    tick();
    const id = window.setInterval(tick, 1000);
    return () => window.clearInterval(id);
  }, []);

  const live = gps === "watching" || gps === "fix";
  const integBad = integrity.quarantine || integrity.level === "degraded";
  const trustPct = Math.round(integrity.trust * 100);
  const trustColor =
    integrity.level === "quarantine" || integrity.level === "degraded"
      ? "var(--color-alert)"
      : integrity.level === "watch"
        ? "var(--color-watch)"
        : "var(--color-ok)";

  return (
    <div className="flex h-dvh flex-col bg-bg text-fg">
      <header className="header-bar px-4 py-3">
        <div className="flex items-center gap-3">
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <span className="text-lg font-semibold tracking-[0.12em]">AETHER</span>
              <span className="rounded border border-amber/40 bg-amber/10 px-1.5 py-0.5 font-mono text-[10px] font-semibold tracking-widest text-amber">
                LINK
              </span>
            </div>
            <div className="mt-0.5 flex items-center gap-2 text-[11px] text-muted">
              <span className={`live-dot ${integBad ? "alert" : ""}`} aria-hidden />
              <span className="font-mono">
                {live ? "PROBE LIVE" : "PROBE IDLE"} · {integrityLabel(integrity.level)}
              </span>
            </div>
          </div>
          <div className="ml-auto text-right">
            <div className={`font-mono text-sm font-medium tracking-wide ${online ? "text-amber mono-glow" : "text-alert"}`}>
              {transportLabel(transport, online, booted)}
            </div>
            <div className="font-mono text-[11px] text-muted">
              {formatMs(rtt)} ms
              {now ? <span className="ml-2">{clock(now)}</span> : null}
            </div>
          </div>
        </div>
        <div className="mt-2.5 flex items-center gap-3">
          <div className="trust-track min-w-0 flex-1">
            <div className="trust-fill" style={{ width: `${trustPct}%`, background: trustColor }} />
          </div>
          <div className="shrink-0 font-mono text-[11px] tabular-nums text-muted">
            TRUST {trustPct}%
          </div>
        </div>
      </header>

      <main className="min-h-0 flex-1 overflow-y-auto">
        <div key={view} className="anim-fade-up mx-auto flex w-full max-w-3xl flex-col gap-4 p-4 pb-8">
          {view === "drive" && <DriveScreen />}
          {view === "path" && <PathScreen />}
          {view === "glass" && <GlassScreen />}
          {view === "bus" && <BusScreen />}
          {view === "car" && <CarScreen />}
        </div>
      </main>

      <nav
        aria-label="Sections"
        className="grid shrink-0 grid-cols-5 border-t border-line bg-surface pb-[env(safe-area-inset-bottom)]"
      >
        {NAV.map((item) => {
          const Icon = item.icon;
          const on = view === item.id;
          return (
            <button
              key={item.id}
              type="button"
              aria-current={on ? "page" : undefined}
              onClick={() => setView(item.id)}
              className={`flex min-h-14 flex-col items-center justify-center gap-1 text-xs font-medium transition-colors ${on ? "nav-active" : "text-muted"}`}
            >
              <Icon className="size-5" strokeWidth={on ? 2 : 1.75} aria-hidden="true" />
              {item.label}
            </button>
          );
        })}
      </nav>
    </div>
  );
}
