import { useEffect, useState } from "react";
import { Link } from "@tanstack/react-router";
import { formatMbps, formatMs, speedNumber, type Unit } from "@/lib/link/format";
import { joinGlass, useLink } from "@/lib/link/store";

export function GlassPage({ room }: { room: string }) {
  const unit = useLink((s) => s.unit);
  const setUnit = useLink((s) => s.setUnit);
  const remote = useLink((s) => s.remote);
  const cast = useLink((s) => s.cast);
  const [now, setNow] = useState(0);
  const [failed, setFailed] = useState("");

  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 400);
    return () => window.clearInterval(id);
  }, []);

  useEffect(() => {
    if (!room) return;
    let stop = () => {};
    let dead = false;
    void joinGlass(room)
      .then((fn) => {
        if (dead) fn();
        else stop = fn;
      })
      .catch((error: unknown) => {
        if (!dead) setFailed(error instanceof Error ? error.message : "Could not join");
      });
    return () => {
      dead = true;
      stop();
    };
  }, [room]);

  const age = remote ? now - remote.at : null;
  const live = remote != null && age != null && age < 2500;
  const unitLabel: Unit = unit;
  const integBad = !!(remote?.quarantine || (remote?.level && remote.level !== "clean"));
  const trustPct = remote?.trust != null ? Math.round(remote.trust * 100) : null;

  return (
    <div className="relative flex min-h-dvh flex-col overflow-hidden bg-bg px-6 py-5 text-fg">
      <div
        className="pointer-events-none absolute inset-0 opacity-[0.07]"
        style={{
          backgroundImage:
            "linear-gradient(rgba(240,162,2,0.5) 1px, transparent 1px), linear-gradient(90deg, rgba(240,162,2,0.5) 1px, transparent 1px)",
          backgroundSize: "48px 48px",
        }}
      />
      <div className="relative z-[1] flex items-baseline justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <span className="text-sm font-semibold tracking-[0.2em] text-amber">AETHER</span>
            <span className="font-mono text-[10px] text-muted">GLASS</span>
          </div>
          <div className="mt-1 font-mono text-xs text-muted">{room ? `ROOM ${room}` : "NO ROOM"}</div>
        </div>
        <div className="text-right">
          <div className="flex items-center justify-end gap-2">
            <span className={`live-dot ${!live || integBad ? "alert" : ""}`} />
            <span className={`font-mono text-sm font-medium tracking-wide ${live ? "text-amber mono-glow" : "text-alert"}`}>
              {!room ? "LOCAL" : live ? "FROM PHONE" : cast.status === "joining" ? "LINKING" : "NO LINK"}
            </span>
          </div>
          <div className="font-mono text-[11px] text-muted">
            {live && age != null ? `${Math.max(0, Math.round(age / 100) / 10)}s lag` : "—"}
          </div>
        </div>
      </div>

      <div className="relative z-[1] flex flex-1 flex-col justify-center py-6">
        <div className="hud-panel px-6 py-8">
          <div className="relative z-[1]">
            <div className="text-[11px] font-semibold uppercase tracking-[0.18em] text-muted">Speed</div>
            <div className="hud-speed mt-1 font-mono text-[6.5rem] font-medium leading-none sm:text-8xl">
              {speedNumber(live ? remote?.speedMps ?? null : null, unitLabel)}
            </div>
            <div className="mt-2 text-muted">{unit === "mph" ? "mph" : "km/h"}</div>
            <div className="mt-8 grid grid-cols-2 gap-5 sm:grid-cols-4">
              <GlassStat label="Heading" value={live && remote?.heading != null ? `${Math.round(remote.heading)}°` : "—"} />
              <GlassStat label="Path" value={live ? remote?.transport.toUpperCase() ?? "—" : "—"} />
              <GlassStat label="RTT" value={live ? `${formatMs(remote?.rtt ?? null)} ms` : "—"} />
              <GlassStat label="Down" value={live ? `${formatMbps(remote?.downMbps ?? null)}` : "—"} />
            </div>
            {live && trustPct != null ? (
              <div className="mt-6">
                <div className="mb-1.5 flex justify-between font-mono text-[11px] text-muted">
                  <span>TRUST</span>
                  <span className={integBad ? "text-alert" : "text-amber"}>{trustPct}%</span>
                </div>
                <div className="trust-track">
                  <div
                    className="trust-fill"
                    style={{
                      width: `${trustPct}%`,
                      background: integBad ? "var(--color-alert)" : "var(--color-ok)",
                    }}
                  />
                </div>
              </div>
            ) : null}
          </div>
        </div>

        {live && remote && integBad ? (
          <div className="anim-fade-up mt-4 rounded-xl border border-alert/50 bg-alert/10 px-4 py-3 text-alert">
            <div className="font-mono text-sm font-semibold tracking-wide">
              INTEGRITY · {(remote.level || "WATCH").toUpperCase()}
            </div>
            {remote.reasons?.length ? (
              <ul className="mt-2 space-y-1 text-sm">
                {remote.reasons.map((r) => (
                  <li key={r}>· {r}</li>
                ))}
              </ul>
            ) : null}
            {remote.ledgerHead ? (
              <div className="mt-2 font-mono text-[11px] opacity-70">
                ledger {remote.ledgerCount ?? 0} · {remote.ledgerHead.slice(0, 16)}…
              </div>
            ) : null}
          </div>
        ) : null}
      </div>

      <div className="relative z-[1] flex flex-wrap items-center gap-3">
        <button
          type="button"
          className="h-11 rounded-full border border-line px-4 text-sm transition hover:border-amber/40"
          onClick={() => setUnit(unit === "mph" ? "kmh" : "mph")}
        >
          {unit === "mph" ? "km/h" : "mph"}
        </button>
        <Link to="/" className="inline-flex h-11 items-center text-sm text-muted">
          Phone deck
        </Link>
        {failed ? <span className="text-sm text-alert">{failed}</span> : null}
        {cast.peers.length > 0 ? (
          <span className="font-mono text-[11px] text-muted">
            {cast.peers.map((p) => `${p.name}:${p.connectionState}`).join("  ")}
          </span>
        ) : null}
      </div>
      {!room ? (
        <p className="relative z-[1] mt-4 text-sm text-muted">Open with the room code from the phone Glass tab.</p>
      ) : null}
    </div>
  );
}

function GlassStat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-[11px] font-semibold uppercase tracking-[0.12em] text-muted">{label}</div>
      <div className="mt-0.5 font-mono text-2xl tabular-nums">{value}</div>
    </div>
  );
}
