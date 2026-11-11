import { useEffect, useState, type ReactNode } from "react";
import QRCode from "qrcode";
import {
  appPath,
  clock,
  formatMbps,
  formatMiles,
  formatMs,
  glassHref,
  speedNumber,
} from "@/lib/link/format";
import { integrityLabel, type IntegrityLevel } from "@/lib/link/integrity";
import { presentScreen, useLink } from "@/lib/link/store";

function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-xl border border-line bg-surface/90 p-4 shadow-[inset_0_1px_0_rgba(240,162,2,0.06)]">
      <h2 className="mb-3 text-[11px] font-semibold uppercase tracking-[0.14em] text-muted">{title}</h2>
      {children}
    </section>
  );
}

function Stat({ label, value, unit }: { label: string; value: string; unit?: string }) {
  return (
    <div className="min-w-0">
      <div className="text-sm text-muted">{label}</div>
      <div className="font-mono text-2xl font-medium tabular-nums text-fg">
        {value}
        {unit ? <span className="ml-1 text-sm text-muted">{unit}</span> : null}
      </div>
    </div>
  );
}

function Primary({ children, onClick, disabled }: { children: ReactNode; onClick: () => void; disabled?: boolean }) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className="h-12 rounded-full bg-amber px-5 text-sm font-semibold text-ink shadow-[0_0_24px_rgba(240,162,2,0.25)] transition active:scale-[0.98] disabled:opacity-50"
    >
      {children}
    </button>
  );
}

function Ghost({ children, onClick }: { children: ReactNode; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className="h-12 rounded-full border border-line px-4 text-sm font-medium">
      {children}
    </button>
  );
}


function IntegrityBanner() {
  const integrity = useLink((s) => s.integrity);
  if (integrity.level === "clean" && !integrity.quarantine) return null;
  const color =
    integrity.level === "quarantine"
      ? "border-alert/60 bg-alert/15 text-alert anim-pulse-alert"
      : integrity.level === "degraded"
        ? "border-amber/50 bg-amber/10 text-amber"
        : "border-line bg-raised text-muted";
  return (
    <div className={`rounded-xl border px-4 py-3 ${color}`}>
      <div className="flex items-center justify-between gap-3">
        <div className="font-mono text-sm font-semibold tracking-[0.12em]">
          INTEGRITY · {integrityLabel(integrity.level as IntegrityLevel)}
        </div>
        <div className="font-mono text-sm tabular-nums">TRUST {(integrity.trust * 100).toFixed(0)}%</div>
      </div>
      {integrity.reasons.length > 0 ? (
        <ul className="mt-2 space-y-1 text-sm">
          {integrity.reasons.map((r) => (
            <li key={r}>· {r}</li>
          ))}
        </ul>
      ) : null}
      {integrity.ledgerCount > 0 ? (
        <div className="mt-2 font-mono text-xs opacity-70">
          ledger {integrity.ledgerCount} · {integrity.ledgerHead.slice(0, 16)}…
        </div>
      ) : null}
    </div>
  );
}

export function DriveScreen() {
  const unit = useLink((s) => s.unit);
  const setUnit = useLink((s) => s.setUnit);
  const gps = useLink((s) => s.gps);
  const gpsError = useLink((s) => s.gpsError);
  const fix = useLink((s) => s.fix);
  const tripM = useLink((s) => s.tripM);
  const maxMps = useLink((s) => s.maxMps);
  const wake = useLink((s) => s.wake);
  const toggleGps = useLink((s) => s.toggleGps);
  const running = gps === "watching" || gps === "fix";

  return (
    <>
      <IntegrityBanner />
      <div className="hud-panel px-5 py-7">
        <div className="relative z-[1]">
          <div className="flex items-center justify-between gap-2">
            <div className="text-[11px] font-semibold uppercase tracking-[0.16em] text-muted">Speed · phone GPS</div>
            <div className="font-mono text-[11px] text-amber">{running ? "LIVE" : "IDLE"}</div>
          </div>
          <div className="hud-speed mt-2 font-mono text-8xl font-medium leading-none text-fg">
            {speedNumber(fix?.speedMps ?? null, unit)}
          </div>
          <div className="mt-2 text-sm text-muted">{unit === "mph" ? "miles per hour" : "kilometers per hour"}</div>
          <div className="mt-7 grid grid-cols-3 gap-3 border-t border-line/80 pt-5">
            <Stat label="Heading" value={fix?.heading == null ? "—" : `${Math.round(fix.heading)}°`} />
            <Stat label="GPS" value={fix?.accuracy == null ? "—" : `±${Math.round(fix.accuracy)}`} unit={fix?.accuracy == null ? undefined : "m"} />
            <Stat label="Trip" value={formatMiles(tripM, unit)} unit={unit === "mph" ? "mi" : "km"} />
          </div>
        </div>
      </div>
      <div className="flex flex-wrap gap-2">
        <Primary onClick={toggleGps}>{running ? "Stop GPS" : "Use GPS"}</Primary>
        <Ghost onClick={() => setUnit(unit === "mph" ? "kmh" : "mph")}>{unit === "mph" ? "Show km/h" : "Show mph"}</Ghost>
      </div>
      <Card title="Fix">
        <dl className="grid grid-cols-2 gap-3 text-sm">
          <div>
            <dt className="text-muted">State</dt>
            <dd className="font-mono">{gps === "fix" ? "locked" : gps}</dd>
          </div>
          <div>
            <dt className="text-muted">Screen</dt>
            <dd className="font-mono">{wake === "held" ? "awake" : wake === "blocked" ? "wake blocked" : "can sleep"}</dd>
          </div>
          <div>
            <dt className="text-muted">Latitude</dt>
            <dd className="font-mono">{fix ? fix.lat.toFixed(6) : "—"}</dd>
          </div>
          <div>
            <dt className="text-muted">Longitude</dt>
            <dd className="font-mono">{fix ? fix.lon.toFixed(6) : "—"}</dd>
          </div>
          <div>
            <dt className="text-muted">Altitude</dt>
            <dd className="font-mono">{fix?.altitude == null ? "—" : `${Math.round(fix.altitude)} m`}</dd>
          </div>
          <div>
            <dt className="text-muted">Max</dt>
            <dd className="font-mono">
              {maxMps > 0 ? speedNumber(maxMps, unit) : "—"} {maxMps > 0 ? unit : ""}
            </dd>
          </div>
        </dl>
        {gpsError ? <p className="mt-3 text-sm text-alert">{gpsError}</p> : null}
        {gps === "off" ? (
          <p className="mt-3 text-sm text-muted">No fix yet. Use GPS and allow location. A denied prompt stays denied until you change the site permission.</p>
        ) : null}
        {fix && fix.accuracy != null && fix.accuracy > 50 ? (
          <p className="mt-3 text-sm text-alert">Accuracy is worse than 50 m. Speed from this fix is not trustworthy.</p>
        ) : null}
      </Card>
    </>
  );
}

function Spark({ rows }: { rows: { ms: number | null }[] }) {
  const vals = rows.map((r) => r.ms).filter((n): n is number => n != null);
  if (vals.length < 2) return <p className="text-sm text-muted">Need two successful round trips before the trace means anything.</p>;
  const max = Math.max(...vals);
  const min = Math.min(...vals);
  const span = Math.max(1, max - min);
  const d = vals
    .map((v, i) => {
      const x = (i / (vals.length - 1)) * 100;
      const y = 28 - ((v - min) / span) * 24;
      return `${i === 0 ? "M" : "L"}${x.toFixed(2)} ${y.toFixed(2)}`;
    })
    .join(" ");
  return (
    <svg viewBox="0 0 100 32" className="h-16 w-full text-amber" role="img" aria-label="Round trip history">
      <path d={d} fill="none" stroke="currentColor" strokeWidth="1.6" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

export function PathScreen() {
  const transport = useLink((s) => s.transport);
  const netType = useLink((s) => s.netType);
  const online = useLink((s) => s.online);
  const rtt = useLink((s) => s.rtt);
  const rows = useLink((s) => s.rows);
  const lossFails = useLink((s) => s.lossFails);
  const lossSamples = useLink((s) => s.lossSamples);
  const downMbps = useLink((s) => s.downMbps);
  const upMbps = useLink((s) => s.upMbps);
  const estimateDown = useLink((s) => s.estimateDown);
  const estimateRtt = useLink((s) => s.estimateRtt);
  const pathError = useLink((s) => s.pathError);
  const measuring = useLink((s) => s.measuring);
  const measure = useLink((s) => s.measureThroughput);
  const booted = useLink((s) => s.booted);

  return (
    <>
      <IntegrityBanner />
      <div className="grid grid-cols-2 gap-3">
        <Card title="Radio the browser can see">
          <div className="font-mono text-2xl">{booted ? (online ? transport.toUpperCase() : "OFFLINE") : "…"}</div>
          <p className="mt-1 text-sm text-muted">Reported type: {netType || "—"}</p>
        </Card>
        <Card title="Round trip">
          <div className="font-mono text-2xl tabular-nums">
            {formatMs(rtt)} <span className="text-sm text-muted">ms</span>
          </div>
          <p className="mt-1 text-sm text-muted">
            {lossSamples === 0 ? "No samples yet" : `${lossFails} failed of last ${lossSamples}`}
          </p>
        </Card>
      </div>
      <Card title="Measured to this app">
        <Spark rows={rows} />
        <div className="mt-3 grid grid-cols-2 gap-3">
          <Stat label="Down" value={formatMbps(downMbps)} unit="Mb/s" />
          <Stat label="Up" value={formatMbps(upMbps)} unit="Mb/s" />
        </div>
        <div className="mt-4">
          <Primary onClick={measure} disabled={measuring}>
            {measuring ? "Measuring…" : "Measure throughput"}
          </Primary>
        </div>
        <p className="mt-3 text-sm text-muted">
          Throughput is a 256 KB download and a 64 KB upload, only when you tap. It is not running in the background, so it will not chew a metered or satellite plan.
        </p>
        {pathError ? <p className="mt-2 text-sm text-alert">{pathError}</p> : null}
      </Card>
      <Card title="Browser estimate, not a measurement">
        <p className="font-mono text-sm">
          {estimateDown == null ? "down —" : `down ${estimateDown} Mb/s`}
          {" · "}
          {estimateRtt == null ? "rtt —" : `rtt ${estimateRtt} ms`}
        </p>
      </Card>
      <Card title="What this phone cannot do">
        <p className="text-sm leading-relaxed text-fg">
          There is no API to open a satellite modem, and none to start a Wi-Fi hotspot. Android keeps both inside the system and the carrier. If a satellite service is already carrying your data, it shows up here as cellular. The round trip above is the real path from this phone to this app. It is not a signal-to-noise figure, and it is not a bird in the sky.
        </p>
      </Card>
      <Card title="Recent probes">
        <ul className="space-y-1 font-mono text-sm">
          {rows.slice(-6).reverse().map((row) => (
            <li key={row.t} className={row.ms == null ? "text-alert" : "text-fg"}>
              {clock(row.t)} {row.ms == null ? row.error || "failed" : `${Math.round(row.ms)} ms`}
            </li>
          ))}
          {rows.length === 0 ? <li className="text-muted">Waiting on the first probe.</li> : null}
        </ul>
      </Card>
    </>
  );
}

export function GlassScreen() {
  const room = useLink((s) => s.room);
  const ensureRoom = useLink((s) => s.ensureRoom);
  const cast = useLink((s) => s.cast);
  const toggleCast = useLink((s) => s.toggleCast);
  const [link, setLink] = useState("");
  const [qr, setQr] = useState("");
  const [note, setNote] = useState("");
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    const code = room || ensureRoom();
    const href = glassHref(code);
    setLink(href);
    let dead = false;
    void QRCode.toDataURL(href, {
      margin: 1,
      width: 320,
      color: { dark: "#f3efe4", light: "#16150f" },
    }).then((url) => {
      if (!dead) setQr(url);
    });
    return () => {
      dead = true;
    };
  }, [room, ensureRoom]);

  return (
    <>
      <IntegrityBanner />
      <Card title="Second screen">
        <p className="text-sm leading-relaxed">
          This sends the live speed and path to another browser: a passenger phone, a tablet, or a head unit that has its own browser. It does not appear inside Android Auto. The car app is on the Car tab.
        </p>
        <div className="mt-4 font-mono text-3xl tracking-widest text-amber">{room || "····"}</div>
        <div className="mt-4 flex flex-wrap gap-2">
          <Primary onClick={toggleCast}>{cast.on ? "Stop sending" : "Send to glass"}</Primary>
          <Ghost
            onClick={() => {
              void navigator.clipboard.writeText(link).then(
                () => {
                  setCopied(true);
                  window.setTimeout(() => setCopied(false), 1500);
                },
                () => setNote("Clipboard blocked. Select the link below."),
              );
            }}
          >
            {copied ? "Copied" : "Copy link"}
          </Ghost>
          <Ghost
            onClick={() => {
              void presentScreen(link).then(setNote);
            }}
          >
            System cast
          </Ghost>
        </div>
        {note ? <p className="mt-3 text-sm text-muted">{note}</p> : null}
        <p className="mt-3 break-all font-mono text-xs text-muted">{link}</p>
      </Card>
      <Card title={cast.on ? `Signaling ${cast.status}` : "Not sending"}>
        {cast.detail ? <p className="mb-2 text-sm text-alert">{cast.detail}</p> : null}
        {cast.peers.length === 0 ? (
          <p className="text-sm text-muted">No screen has joined this code.</p>
        ) : (
          <ul className="space-y-2 text-sm">
            {cast.peers.map((peer) => (
              <li key={peer.id} className="font-mono">
                {peer.name || peer.id} · {peer.connectionState}
                {peer.rttMs != null ? ` · ${Math.round(peer.rttMs)} ms` : ""}
                {peer.candidateType ? ` · ${peer.candidateType}` : ""}
              </li>
            ))}
          </ul>
        )}
        <p className="mt-3 text-sm text-muted">
          Direct peer connection after the handshake. If it stays on “connecting”, one side is behind a strict NAT and this pair cannot punch through. The state above is the real WebRTC state, not a timer pretending to pair.
        </p>
      </Card>
      {qr ? (
        <Card title="Scan on the other screen">
          <img src={qr} alt="QR code for the glass link" className="w-full max-w-xs rounded-lg" />
        </Card>
      ) : null}
    </>
  );
}

function pid(value: number | null, digits = 0): string {
  if (value == null || !Number.isFinite(value)) return "—";
  return digits === 0 ? Math.round(value).toString() : value.toFixed(digits);
}

function BusCell({ label, value, unit }: { label: string; value: string; unit?: string }) {
  return (
    <div className="rounded-lg border border-line bg-surface px-3 py-2">
      <div className="text-xs text-muted">{label}</div>
      <div className="font-mono text-lg tabular-nums">
        {value}
        {unit ? <span className="ml-1 text-xs text-muted">{unit}</span> : null}
      </div>
    </div>
  );
}

export function BusScreen() {
  const obd = useLink((s) => s.obd);
  const connectBus = useLink((s) => s.connectBus);
  const disconnectBus = useLink((s) => s.disconnectBus);
  const age = obd.updatedAt == null ? null : Date.now() - obd.updatedAt;
  const [, setTick] = useState(0);
  useEffect(() => {
    if (obd.phase !== "live") return;
    const id = window.setInterval(() => setTick((n) => n + 1), 1000);
    return () => window.clearInterval(id);
  }, [obd.phase]);

  const r = obd.reading;
  const runtime =
    r.runtimeSec == null
      ? "—"
      : r.runtimeSec >= 3600
        ? `${Math.floor(r.runtimeSec / 3600)}h ${Math.floor((r.runtimeSec % 3600) / 60)}m`
        : `${Math.floor(r.runtimeSec / 60)}m ${r.runtimeSec % 60}s`;

  return (
    <>
      <Card title="OBD-II · TOPDON / ELM over BLE">
        <p className="text-sm leading-relaxed">
          2010 Chevy HHR style Mode 01 grid + VIN + DTCs. Adapter must be BLE (most TOPDON units advertise while powered from the OBD port). Classic HC-05 serial dongles will not work in the browser. Ignition on.
        </p>
        <div className="mt-4 flex flex-wrap gap-2">
          {obd.phase === "live" ? (
            <Primary onClick={disconnectBus}>Disconnect</Primary>
          ) : (
            <Primary onClick={connectBus} disabled={obd.phase === "asking" || !obd.supported}>
              {obd.phase === "asking" ? "Pick adapter…" : "Connect BLE OBD"}
            </Primary>
          )}
        </div>
        {!obd.supported ? (
          <p className="mt-3 text-sm text-alert">This browser has no Web Bluetooth. Use Chrome on Android.</p>
        ) : null}
        {obd.error ? <p className="mt-3 text-sm text-alert">{obd.error}</p> : null}
        {obd.name ? <p className="mt-2 font-mono text-sm text-muted">{obd.name}</p> : null}
      </Card>

      {r.vin ? (
        <Card title="VIN">
          <div className="font-mono text-xl tracking-wide">{r.vin}</div>
        </Card>
      ) : null}

      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
        <BusCell label="RPM" value={pid(r.rpm)} />
        <BusCell label="Speed" value={pid(r.kph)} unit="km/h" />
        <BusCell label="Coolant" value={pid(r.coolantC)} unit="°C" />
        <BusCell label="IAT" value={pid(r.iatC)} unit="°C" />
        <BusCell label="MAP" value={pid(r.mapKpa)} unit="kPa" />
        <BusCell label="MAF" value={pid(r.mafGps, 2)} unit="g/s" />
        <BusCell label="Throttle" value={pid(r.throttlePct)} unit="%" />
        <BusCell label="Load" value={pid(r.loadPct)} unit="%" />
        <BusCell label="Timing" value={pid(r.timingDeg, 1)} unit="°" />
        <BusCell label="Module V" value={pid(r.volts, 2)} unit="V" />
        <BusCell label="Fuel level" value={pid(r.fuelLevelPct)} unit="%" />
        <BusCell label="Ambient" value={pid(r.ambientC)} unit="°C" />
        <BusCell label="Baro" value={pid(r.baroKpa)} unit="kPa" />
        <BusCell label="STFT B1" value={pid(r.stft1Pct, 1)} unit="%" />
        <BusCell label="LTFT B1" value={pid(r.ltft1Pct, 1)} unit="%" />
        <BusCell label="STFT B2" value={pid(r.stft2Pct, 1)} unit="%" />
        <BusCell label="LTFT B2" value={pid(r.ltft2Pct, 1)} unit="%" />
        <BusCell label="Fuel status" value={r.fuelStatus ?? "—"} />
        <BusCell label="Abs load" value={pid(r.absLoadPct)} unit="%" />
        <BusCell label="Cmd λ" value={pid(r.cmdEquiv, 3)} />
        <BusCell label="Rel throttle" value={pid(r.relativeThrottlePct)} unit="%" />
        <BusCell label="Pedal D" value={pid(r.accelPedalDPct)} unit="%" />
        <BusCell label="Pedal E" value={pid(r.accelPedalEPct)} unit="%" />
        <BusCell label="Fuel rail" value={pid(r.fuelRailKpa)} unit="kPa" />
        <BusCell label="Runtime" value={runtime} />
        <BusCell label="MIL dist" value={pid(r.milDistanceKm)} unit="km" />
      </div>

      <Card title="Stored DTCs (Mode 03)">
        {r.dtcs.length === 0 ? (
          <p className="text-sm text-muted">None reported (or not read yet).</p>
        ) : (
          <ul className="flex flex-wrap gap-2 font-mono text-sm">
            {r.dtcs.map((c) => (
              <li key={c} className="rounded border border-alert px-2 py-1 text-alert">
                {c}
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Card title="Pending DTCs (Mode 07)">
        {r.pendingDtcs.length === 0 ? (
          <p className="text-sm text-muted">None reported (or not read yet).</p>
        ) : (
          <ul className="flex flex-wrap gap-2 font-mono text-sm">
            {r.pendingDtcs.map((c) => (
              <li key={c} className="rounded border border-amber px-2 py-1 text-amber">
                {c}
              </li>
            ))}
          </ul>
        )}
      </Card>

      <p className="text-sm text-muted">
        {obd.updatedAt == null
          ? "No ECU frame yet. Empty cells stay empty — not simulated."
          : `Last frame ${age == null ? "—" : `${Math.max(0, Math.round(age / 1000))}s`} ago. NO DATA PIDs are normal if the HHR ECU does not support them.`}
      </p>
      <Card title="Adapter log">
        <ul className="max-h-48 space-y-1 overflow-y-auto font-mono text-xs">
          {obd.log.length === 0 ? <li className="text-muted">No commands sent.</li> : null}
          {obd.log.slice(-16).map((line, i) => (
            <li key={`${obd.log.length - i}-${line.slice(0, 16)}`}>{line}</li>
          ))}
        </ul>
      </Card>
    </>
  );
}

const HOTSPOT = "intent:#Intent;component=com.android.settings/.TetherSettings;end";
const AUTO = "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=com.google.android.projection.gearhead;end";

export function CarScreen() {
  const [apk, setApk] = useState<"unknown" | "ready" | "missing">("unknown");
  const [hint, setHint] = useState("");
  useEffect(() => {
    const href = appPath("/aether-link.apk");
    void fetch(href, { method: "HEAD" }).then(
      (res) => setApk(res.ok ? "ready" : "missing"),
      () => setApk("missing"),
    );
  }, []);

  const openIntent = (url: string, label: string) => {
    const android = /Android/i.test(navigator.userAgent);
    if (!android) {
      setHint(`${label} only opens from Chrome on the phone. This screen is not that phone.`);
      return;
    }
    window.location.href = url;
  };

  return (
    <>
      <Card title="Why the car stayed blank">
        <p className="text-sm leading-relaxed">
          Android Auto does not show a web page. It only shows an app installed on the phone that registers a car service. The last build did not. It drew a fake bridge in the browser, so the head unit had nothing to open.
        </p>
      </Card>
      <Card title="Install on the phone">
        {apk === "ready" ? (
          <a href={appPath("/aether-link.apk")} download="aether-link.apk" className="inline-flex h-12 items-center rounded-full bg-amber px-5 text-sm font-semibold text-ink">
            Download AETHER APK
          </a>
        ) : (
          <p className="text-sm text-muted">{apk === "missing" ? "The APK is not in this build." : "Checking the install file…"}</p>
        )}
        <ol className="mt-4 list-decimal space-y-2 pl-5 text-sm leading-relaxed">
          <li>Pair the phone to the car with normal Android Auto first. Maps has to work before this will. This app does not create that wireless link. Google does.</li>
          <li>Install the APK. Allow install from the browser when Android asks.</li>
          <li>Open AETHER on the phone and tap Start measuring. Allow location.</li>
          <li>In the Android Auto app, open Settings, find Version at the bottom, and tap it 10 times until developer mode turns on.</li>
          <li>Open the Android Auto menu, Developer settings, and turn on Unknown sources.</li>
          <li>Reconnect to the car. AETHER is in the car’s app list. It shows GPS speed and the measured uplink.</li>
        </ol>
      </Card>
      <div className="flex flex-wrap gap-2">
        <Ghost onClick={() => openIntent(HOTSPOT, "Hotspot settings")}>Hotspot settings</Ghost>
        <Ghost onClick={() => openIntent(AUTO, "Android Auto")}>Android Auto</Ghost>
      </div>
      {hint ? <p className="text-sm text-muted">{hint}</p> : null}
      <Card title="Hard limits, on purpose">
        <p className="text-sm leading-relaxed">
          The car screen cannot start a hotspot and cannot open a satellite radio. Those switches are system-only. The APK reads whatever uplink the phone already has, including carrier satellite data if the carrier is already using it, and it says so. It will not invent a lock, a bird, or a bitrate.
        </p>
      </Card>
    </>
  );
}
