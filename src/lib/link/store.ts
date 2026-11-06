import { create } from "zustand";
import { P2PRoom, type PeerInfo } from "@/lib/multiplayer";
import { connectObd, emptyReading, type ObdReading, type ObdSession } from "./obd";
import {
  appPath,
  bearingDeg,
  haversineM,
  makeCode,
  peerId,
  wrap360,
  type Unit,
} from "./format";
import {
  appendLedger,
  emptyIntegrity,
  evaluateSample,
  exportLedger,
  resetIntegrityTrack,
  type IntegrityState,
} from "./integrity";

export type View = "drive" | "path" | "glass" | "bus" | "car";
export type Transport = "offline" | "wifi" | "cellular" | "ethernet" | "other" | "unknown";

export type ProbeRow = { t: number; ms: number | null; error: string | null };

export type Fix = {
  lat: number;
  lon: number;
  accuracy: number | null;
  altitude: number | null;
  speedMps: number | null;
  heading: number | null;
  at: number;
};

export type RemoteFix = {
  lat: number | null;
  lon: number | null;
  accuracy: number | null;
  altitude: number | null;
  speedMps: number | null;
  heading: number | null;
  at: number;
  tripM: number;
  rtt: number | null;
  lossFails: number;
  lossSamples: number;
  downMbps: number | null;
  upMbps: number | null;
  transport: Transport;
  online: boolean;
  from: string;
  trust: number | null;
  level: string | null;
  quarantine: boolean;
  ledgerHead: string | null;
  ledgerCount: number | null;
  reasons: string[];
};

type CastState = {
  on: boolean;
  status: "idle" | "joining" | "up" | "error";
  detail: string;
  peers: PeerInfo[];
};

type ObdState = {
  supported: boolean;
  phase: "idle" | "asking" | "live" | "error";
  name: string;
  error: string;
  log: string[];
  reading: ObdReading;
  updatedAt: number | null;
};

type LinkState = {
  view: View;
  unit: Unit;
  booted: boolean;
  online: boolean;
  transport: Transport;
  netType: string;
  estimateDown: number | null;
  estimateRtt: number | null;
  rtt: number | null;
  rows: ProbeRow[];
  lossFails: number;
  lossSamples: number;
  downMbps: number | null;
  upMbps: number | null;
  downAt: number | null;
  upAt: number | null;
  pathError: string;
  measuring: boolean;
  gps: "off" | "watching" | "fix" | "denied" | "unsupported";
  gpsError: string;
  fix: Fix | null;
  tripM: number;
  tripStarted: number | null;
  maxMps: number;
  wake: "off" | "held" | "blocked";
  room: string;
  cast: CastState;
  remote: RemoteFix | null;
  obd: ObdState;
  integrity: IntegrityState;
  setView: (view: View) => void;
  setUnit: (unit: Unit) => void;
  ensureRoom: () => string;
  toggleGps: () => void;
  measureThroughput: () => void;
  toggleCast: () => void;
  connectBus: () => void;
  disconnectBus: () => void;
};

function transportOf(online: boolean, type: string | undefined): Transport {
  if (!online) return "offline";
  if (type === "wifi") return "wifi";
  if (type === "cellular") return "cellular";
  if (type === "ethernet") return "ethernet";
  if (type === "none") return "offline";
  if (!type || type === "unknown") return "unknown";
  return "other";
}

type Conn = {
  type?: string;
  effectiveType?: string;
  downlink?: number;
  rtt?: number;
  addEventListener?: (name: string, fn: () => void) => void;
  removeEventListener?: (name: string, fn: () => void) => void;
};

function connection(): Conn | null {
  const nav = navigator as Navigator & { connection?: Conn };
  return nav.connection ?? null;
}

let boots = 0;
let stopBoot: Array<() => void> = [];
let watchId: number | null = null;
let lastPoint: { lat: number; lon: number; at: number } | null = null;
let wakeLock: WakeLockSentinel | null = null;
let castRoom: P2PRoom | null = null;
let castTimer: ReturnType<typeof setInterval> | null = null;
let obdSession: ObdSession | null = null;
let obdGen = 0;

function readNet() {
  const conn = connection();
  const online = navigator.onLine;
  const type = conn?.type ?? conn?.effectiveType;
  useLink.setState({
    booted: true,
    online,
    transport: transportOf(online, conn?.type),
    netType: type || (online ? "unspecified" : "offline"),
    estimateDown: typeof conn?.downlink === "number" ? conn.downlink : null,
    estimateRtt: typeof conn?.rtt === "number" ? conn.rtt : null,
  });
}

async function pingOnce() {
  const started = performance.now();
  const ctrl = new AbortController();
  const timer = window.setTimeout(() => ctrl.abort(), 4000);
  try {
    const res = await fetch(appPath(`/api/probe?bytes=0&t=${Date.now()}`), {
      cache: "no-store",
      signal: ctrl.signal,
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    await res.arrayBuffer();
    const ms = performance.now() - started;
    pushRow({ t: Date.now(), ms, error: null }, ms);
  } catch (error) {
    const message = error instanceof Error ? error.name : "failed";
    pushRow({ t: Date.now(), ms: null, error: message === "AbortError" ? "timeout" : message }, null);
  } finally {
    window.clearTimeout(timer);
  }
}

function pushRow(row: ProbeRow, ms: number | null) {
  const rows = [...useLink.getState().rows, row].slice(-30);
  const windowRows = rows.slice(-20);
  const lossFails = windowRows.filter((r) => r.ms == null).length;
  useLink.setState({
    rows,
    rtt: ms,
    lossFails,
    lossSamples: windowRows.length,
    pathError: row.error ?? "",
  });
}

async function transfer(kind: "down" | "up") {
  useLink.setState({ measuring: true, pathError: "" });
  const ctrl = new AbortController();
  const timer = window.setTimeout(() => ctrl.abort(), 15000);
  try {
    const started = performance.now();
    if (kind === "down") {
      const res = await fetch(appPath(`/api/probe?bytes=262144&t=${Date.now()}`), {
        cache: "no-store",
        signal: ctrl.signal,
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const buf = await res.arrayBuffer();
      const seconds = (performance.now() - started) / 1000;
      const mbps = (buf.byteLength * 8) / seconds / 1e6;
      useLink.setState({ downMbps: mbps, downAt: Date.now(), measuring: false });
    } else {
      const body = new Uint8Array(65536);
      crypto.getRandomValues(body.subarray(0, 16));
      const res = await fetch(appPath("/api/probe"), {
        method: "POST",
        body,
        cache: "no-store",
        signal: ctrl.signal,
        headers: { "content-type": "application/octet-stream" },
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      await res.json();
      const seconds = (performance.now() - started) / 1000;
      const mbps = (body.byteLength * 8) / seconds / 1e6;
      useLink.setState({ upMbps: mbps, upAt: Date.now(), measuring: false });
    }
  } catch (error) {
    useLink.setState({
      measuring: false,
      pathError: error instanceof Error ? error.message : "throughput failed",
    });
  } finally {
    window.clearTimeout(timer);
  }
}

function onPosition(pos: GeolocationPosition) {
  const { latitude, longitude, accuracy, altitude, speed, heading } = pos.coords;
  const at = Date.now();
  let speedMps = speed != null && Number.isFinite(speed) && speed >= 0 ? speed : null;
  let headingDeg = heading != null && Number.isFinite(heading) && heading >= 0 ? heading : null;
  let tripAdd = 0;
  if (lastPoint) {
    const d = haversineM(lastPoint.lat, lastPoint.lon, latitude, longitude);
    const dt = (at - lastPoint.at) / 1000;
    if (dt > 0.3 && dt < 20 && d / dt < 80) {
      if (speedMps == null) speedMps = d / dt;
      if (headingDeg == null && d > 8) headingDeg = wrap360(bearingDeg(lastPoint.lat, lastPoint.lon, latitude, longitude));
      const acc = accuracy != null && Number.isFinite(accuracy) ? accuracy : 30;
      if (d > Math.max(5, acc * 0.6) || (speedMps ?? 0) > 1.5) tripAdd = d;
    }
  }
  lastPoint = { lat: latitude, lon: longitude, at };
  const prev = useLink.getState();
  const tripM = prev.tripM + tripAdd;
  const maxMps = Math.max(prev.maxMps, speedMps ?? 0);
  const accVal = accuracy != null && Number.isFinite(accuracy) ? accuracy : null;
  const altVal = altitude != null && Number.isFinite(altitude) ? altitude : null;
  const obdKph = prev.obd.reading.kph;
  const obdSpeedMps = obdKph != null && Number.isFinite(obdKph) ? obdKph / 3.6 : null;
  const nextIntegrity = evaluateSample(
    {
      lat: latitude,
      lon: longitude,
      speedMps,
      heading: headingDeg,
      accuracy: accVal,
      at,
      transport: prev.transport,
      rtt: prev.rtt,
      obdSpeedMps,
      satelliteTransport: prev.transport === "other" && /sat/i.test(prev.netType),
    },
    prev.integrity,
  );
  useLink.setState({
    gps: "fix",
    gpsError: "",
    tripM,
    tripStarted: prev.tripStarted ?? at,
    maxMps,
    fix: {
      lat: latitude,
      lon: longitude,
      accuracy: accVal,
      altitude: altVal,
      speedMps,
      heading: headingDeg,
      at,
    },
    integrity: nextIntegrity,
  });
  // Async ledger append when something interesting happened
  if (nextIntegrity.reasons.length || nextIntegrity.quarantine) {
    void appendLedger("gps", nextIntegrity, { lat: latitude, lon: longitude, speedMps }).then((r) => {
      useLink.setState((s) => ({
        integrity: { ...s.integrity, ledgerHead: r.head, ledgerCount: r.count },
      }));
    });
  }
}

async function syncWake() {
  const need = useLink.getState().gps !== "off" || useLink.getState().cast.on;
  if (!need) {
    if (wakeLock) {
      try {
        await wakeLock.release();
      } catch {
        /* already released */
      }
      wakeLock = null;
    }
    if (useLink.getState().wake !== "off") useLink.setState({ wake: "off" });
    return;
  }
  if (wakeLock && !wakeLock.released) return;
  if (!("wakeLock" in navigator)) {
    useLink.setState({ wake: "blocked" });
    return;
  }
  try {
    wakeLock = await navigator.wakeLock.request("screen");
    wakeLock.addEventListener("release", () => {
      wakeLock = null;
    });
    useLink.setState({ wake: "held" });
  } catch {
    useLink.setState({ wake: "blocked" });
  }
}

function telemetry() {
  const s = useLink.getState();
  const i = s.integrity;
  return {
    v: 1 as const,
    t: Date.now(),
    speedMps: s.fix?.speedMps ?? null,
    heading: s.fix?.heading ?? null,
    lat: s.fix?.lat ?? null,
    lon: s.fix?.lon ?? null,
    accuracy: s.fix?.accuracy ?? null,
    altitude: s.fix?.altitude ?? null,
    at: s.fix?.at ?? Date.now(),
    tripM: s.tripM,
    rtt: s.rtt,
    lossFails: s.lossFails,
    lossSamples: s.lossSamples,
    downMbps: s.downMbps,
    upMbps: s.upMbps,
    transport: s.transport,
    online: s.online,
    // integrity / anti-spoof payload for glass peers
    trust: i.trust,
    level: i.level,
    quarantine: i.quarantine,
    ledgerHead: i.ledgerHead,
    ledgerCount: i.ledgerCount,
    flags: i.flags,
    reasons: i.reasons,
  };
}

function isTelemetry(data: unknown): data is ReturnType<typeof telemetry> {
  if (!data || typeof data !== "object") return false;
  return (data as { v?: number }).v === 1;
}

export function startLink(): () => void {
  boots += 1;
  if (boots === 1) {
    const stored = localStorage.getItem("aether-unit");
    const room = localStorage.getItem("aether-room") || "";
    useLink.setState({
      unit: stored === "kmh" ? "kmh" : "mph",
      room,
      obd: { ...useLink.getState().obd, supported: "bluetooth" in navigator },
    });
    readNet();
    const onNet = () => readNet();
    window.addEventListener("online", onNet);
    window.addEventListener("offline", onNet);
    const conn = connection();
    conn?.addEventListener?.("change", onNet);
    const onVis = () => {
      if (document.visibilityState === "visible") void syncWake();
    };
    document.addEventListener("visibilitychange", onVis);
    void pingOnce();
    const timer = window.setInterval(() => void pingOnce(), 3000);
    stopBoot = [
      () => window.removeEventListener("online", onNet),
      () => window.removeEventListener("offline", onNet),
      () => conn?.removeEventListener?.("change", onNet),
      () => document.removeEventListener("visibilitychange", onVis),
      () => window.clearInterval(timer),
    ];
  }
  return () => {
    boots -= 1;
    if (boots > 0) return;
    for (const stop of stopBoot) stop();
    stopBoot = [];
  };
}

function startGps() {
  if (!("geolocation" in navigator)) {
    useLink.setState({ gps: "unsupported", gpsError: "This browser has no GPS API." });
    return;
  }
  if (watchId != null) return;
  useLink.setState({ gps: "watching", gpsError: "" });
  watchId = navigator.geolocation.watchPosition(onPosition, (err) => {
    useLink.setState({
      gps: err.code === err.PERMISSION_DENIED ? "denied" : "watching",
      gpsError: err.message || "Location failed",
    });
  }, { enableHighAccuracy: true, maximumAge: 1000, timeout: 12000 });
  void syncWake();
}

function stopGps() {
  if (watchId != null) {
    navigator.geolocation.clearWatch(watchId);
    watchId = null;
  }
  lastPoint = null;
  useLink.setState({ gps: "off" });
  void syncWake();
}

async function openCast(code: string) {
  castRoom?.close();
  if (castTimer) clearInterval(castTimer);
  const room = new P2PRoom({
    room: code,
    selfId: peerId("phone"),
    name: "phone",
    onConnected: () => useLink.setState({ cast: { ...useLink.getState().cast, status: "up", detail: "" } }),
    onPeersChanged: (peers) => useLink.setState({ cast: { ...useLink.getState().cast, peers } }),
    onMessage: () => undefined,
  });
  castRoom = room;
  useLink.setState({
    cast: { on: true, status: "joining", detail: "", peers: [] },
  });
  await room.join();
  castTimer = setInterval(() => {
    if (castRoom) castRoom.broadcast(telemetry());
  }, 500);
  void syncWake();
}

function closeCast() {
  castRoom?.close();
  castRoom = null;
  if (castTimer) clearInterval(castTimer);
  castTimer = null;
  useLink.setState({ cast: { on: false, status: "idle", detail: "", peers: [] } });
  void syncWake();
}

export const useLink = create<LinkState>((set, get) => ({
  view: "drive",
  unit: "mph",
  booted: false,
  online: true,
  transport: "unknown",
  netType: "",
  estimateDown: null,
  estimateRtt: null,
  rtt: null,
  rows: [],
  lossFails: 0,
  lossSamples: 0,
  downMbps: null,
  upMbps: null,
  downAt: null,
  upAt: null,
  pathError: "",
  measuring: false,
  gps: "off",
  gpsError: "",
  fix: null,
  tripM: 0,
  tripStarted: null,
  maxMps: 0,
  wake: "off",
  room: "",
  cast: { on: false, status: "idle", detail: "", peers: [] },
  remote: null,
  obd: {
    supported: false,
    phase: "idle",
    name: "",
    error: "",
    log: [],
    reading: emptyReading(),
    updatedAt: null,
  },
  integrity: emptyIntegrity(),
  setView: (view) => set({ view }),
  setUnit: (unit) => {
    localStorage.setItem("aether-unit", unit);
    set({ unit });
  },
  ensureRoom: () => {
    const existing = get().room;
    if (existing) return existing;
    const code = localStorage.getItem("aether-room") || makeCode();
    localStorage.setItem("aether-room", code);
    set({ room: code });
    return code;
  },
  toggleGps: () => {
    if (get().gps === "off" || get().gps === "denied" || get().gps === "unsupported") startGps();
    else stopGps();
  },
  measureThroughput: () => {
    if (get().measuring) return;
    void transfer("down").then(() => transfer("up"));
  },
  toggleCast: () => {
    if (get().cast.on) {
      closeCast();
      return;
    }
    const code = get().ensureRoom();
    void openCast(code).catch((error: unknown) => {
      set({
        cast: {
          on: true,
          status: "error",
          detail: error instanceof Error ? error.message : "Cast signaling failed",
          peers: [],
        },
      });
    });
  },
  connectBus: () => {
    if (obdSession || get().obd.phase === "asking") return;
    const gen = ++obdGen;
    set({ obd: { ...get().obd, phase: "asking", error: "", name: "" } });
    void connectObd()
      .then((session) => {
        if (gen !== obdGen) {
          session.close();
          return;
        }
        obdSession = session;
        set({ obd: { ...get().obd, phase: "live", name: session.name, error: "" } });
        return session.loop(
          (reading) => {
            if (gen !== obdGen) return;
            const prev = get().obd;
            set({
              obd: {
                ...prev,
                reading: {
                ...prev.reading,
                ...reading,
                dtcs: reading.dtcs ?? prev.reading.dtcs,
                pendingDtcs: reading.pendingDtcs ?? prev.reading.pendingDtcs,
              },
                updatedAt: Date.now(),
                phase: "live",
              },
            });
          },
          (line) => {
            if (gen !== obdGen) return;
            const prev = get().obd;
            set({ obd: { ...prev, log: [...prev.log, line].slice(-24) } });
          },
        );
      })
      .catch((error: unknown) => {
        if (gen !== obdGen) return;
        obdSession = null;
        const message = error instanceof Error ? error.message : "Bluetooth failed";
        if (/cancel/i.test(message)) {
          set({ obd: { ...get().obd, phase: "idle", name: "", error: "" } });
          return;
        }
        set({ obd: { ...get().obd, phase: "error", error: message, name: "" } });
      });
  },
  disconnectBus: () => {
    obdGen += 1;
    obdSession?.close();
    obdSession = null;
    set({
      obd: {
        ...get().obd,
        phase: "idle",
        name: "",
        error: "",
        reading: emptyReading(),
        updatedAt: null,
      },
    });
  },
}));

export function applyRemote(from: string, data: unknown) {
  if (!isTelemetry(data)) return;
  const d = data as ReturnType<typeof telemetry> & { trust?: number; level?: string; quarantine?: boolean; ledgerHead?: string; ledgerCount?: number; reasons?: string[] };
  useLink.setState({
    remote: {
      lat: d.lat,
      lon: d.lon,
      accuracy: d.accuracy,
      altitude: d.altitude,
      speedMps: d.speedMps,
      heading: d.heading,
      at: d.t,
      tripM: d.tripM,
      rtt: d.rtt,
      lossFails: d.lossFails,
      lossSamples: d.lossSamples,
      downMbps: d.downMbps,
      upMbps: d.upMbps,
      transport: d.transport,
      online: d.online,
      from,
      trust: d.trust ?? null,
      level: d.level ?? null,
      quarantine: !!d.quarantine,
      ledgerHead: d.ledgerHead ?? null,
      ledgerCount: d.ledgerCount ?? null,
      reasons: Array.isArray(d.reasons) ? d.reasons : [],
    },
  });
}

/** Export the local integrity ledger for glass peers or offline dump. */
export function dumpIntegrityLedger() {
  return exportLedger();
}

let glassRoom: P2PRoom | null = null;

export async function joinGlass(code: string): Promise<() => void> {
  glassRoom?.close();
  const room = new P2PRoom({
    room: code,
    selfId: peerId("glass"),
    name: "glass",
    onPeersChanged: (peers) => useLink.setState({ cast: { ...useLink.getState().cast, peers, on: true } }),
    onMessage: (from, data) => applyRemote(from, data),
    onConnected: () =>
      useLink.setState({ cast: { ...useLink.getState().cast, on: true, status: "up", detail: "" } }),
  });
  glassRoom = room;
  useLink.setState({ cast: { on: true, status: "joining", detail: "", peers: [] }, remote: null });
  await room.join();
  return () => {
    if (glassRoom === room) {
      room.close();
      glassRoom = null;
    }
  };
}

export async function presentScreen(url: string): Promise<string> {
  const Ctor = (window as unknown as { PresentationRequest?: new (urls: string[]) => { start: () => Promise<unknown> } })
    .PresentationRequest;
  if (!Ctor) return "This browser has no cast API. Open the glass link on the other screen.";
  try {
    const req = new Ctor([url]);
    await req.start();
    return "Cast dialog accepted. If the car is not a Cast receiver, nothing will show there.";
  } catch (error) {
    return error instanceof Error ? error.message : "Cast was dismissed.";
  }
}
