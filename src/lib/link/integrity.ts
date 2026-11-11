/**
 * AETHER LINK — Integrity / Anti-Spoof core (v1)
 *
 * Ported concepts from NSCB v84 AntiSpoofFinal + DualPathDisagreement:
 *   - Hash-chained telemetry ledger (SHA-384)
 *   - GPS jump / teleport / impossible kinematics
 *   - Dual-path disagreement (GPS vs OBD vs network RTT envelope)
 *   - Trust score that only decays on evidence
 *   - Quarantine flag for the active fix
 *
 * No heavy ONNX required for the phone path. Selected formula-style
 * gates can be attached later as narrative assets.
 */

import { haversineM } from "./format";

export type IntegrityLevel = "clean" | "watch" | "degraded" | "quarantine";

export type IntegrityFlags = {
  gpsJump: boolean;
  impossibleSpeed: boolean;
  headingGlitch: boolean;
  accuracyCollapse: boolean;
  pathDisagree: boolean; // GPS vs OBD
  rttEnvelope: boolean; // claimed transport vs measured RTT
  satelliteInconsistent: boolean;
};

export type IntegrityState = {
  trust: number; // 0.05 .. 0.99
  level: IntegrityLevel;
  flags: IntegrityFlags;
  reasons: string[];
  lastEventAt: number | null;
  ledgerHead: string; // latest SHA-384
  ledgerCount: number;
  quarantine: boolean;
};

export type LedgerEntry = {
  t: number;
  type: string;
  trust: number;
  level: IntegrityLevel;
  flags: string[];
  lat?: number | null;
  lon?: number | null;
  speedMps?: number | null;
  prev: string;
  hash: string;
};

const EMPTY_FLAGS: IntegrityFlags = {
  gpsJump: false,
  impossibleSpeed: false,
  headingGlitch: false,
  accuracyCollapse: false,
  pathDisagree: false,
  rttEnvelope: false,
  satelliteInconsistent: false,
};

export function emptyIntegrity(): IntegrityState {
  return {
    trust: 0.92,
    level: "clean",
    flags: { ...EMPTY_FLAGS },
    reasons: [],
    lastEventAt: null,
    ledgerHead: "GENESIS",
    ledgerCount: 0,
    quarantine: false,
  };
}

// --- Hash-chained ledger (SHA-384 via Web Crypto) ---

async function sha384Hex(text: string): Promise<string> {
  const data = new TextEncoder().encode(text);
  const buf = await crypto.subtle.digest("SHA-384", data);
  return Array.from(new Uint8Array(buf))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

const ledger: LedgerEntry[] = [];
const MAX_LEDGER = 256;

export async function appendLedger(
  type: string,
  state: IntegrityState,
  extra: { lat?: number | null; lon?: number | null; speedMps?: number | null } = {},
): Promise<{ head: string; count: number; entry: LedgerEntry }> {
  const prev = state.ledgerHead || "GENESIS";
  const payload = {
    t: Date.now(),
    type,
    trust: Math.round(state.trust * 1000) / 1000,
    level: state.level,
    flags: Object.entries(state.flags)
      .filter(([, v]) => v)
      .map(([k]) => k),
    lat: extra.lat ?? null,
    lon: extra.lon ?? null,
    speedMps: extra.speedMps ?? null,
    prev,
  };
  const hash = await sha384Hex(JSON.stringify(payload));
  const entry: LedgerEntry = { ...payload, hash };
  ledger.push(entry);
  if (ledger.length > MAX_LEDGER) ledger.shift();
  return { head: hash, count: ledger.length, entry };
}

export function exportLedger(): LedgerEntry[] {
  return ledger.slice();
}

export function verifyLedger(entries: LedgerEntry[]): { ok: boolean; brokenAt: number; detail: string } {
  let prev = "GENESIS";
  for (let i = 0; i < entries.length; i++) {
    const e = entries[i]!;
    if (e.prev !== prev) {
      return { ok: false, brokenAt: i, detail: `chain break at ${i}: expected prev ${prev.slice(0, 12)}…` };
    }
    prev = e.hash;
  }
  return { ok: true, brokenAt: -1, detail: "chain intact" };
}

// --- Physics / spoof detectors ---

const MAX_PLAUSIBLE_MPS = 90; // ~200 mph absolute ceiling for road use
const JUMP_METERS = 180; // single-fix teleport threshold
const JUMP_TIME_MS = 4000;
const ACCURACY_COLLAPSE_M = 80;
const PATH_SPEED_DELTA_MPS = 7; // ~15 mph disagreement GPS vs OBD
const RTT_CELLULAR_MAX_MS = 280;
const RTT_WIFI_MAX_MS = 120;
const RTT_SAT_MIN_MS = 400; // LEO/MEO envelope floor when claiming satellite

type Sample = {
  lat: number | null;
  lon: number | null;
  speedMps: number | null;
  heading: number | null;
  accuracy: number | null;
  at: number;
  transport: string;
  rtt: number | null;
  obdSpeedMps: number | null;
  satelliteTransport: boolean;
};

let prevSample: Sample | null = null;

function levelFromTrust(trust: number, quarantine: boolean): IntegrityLevel {
  if (quarantine || trust < 0.28) return "quarantine";
  if (trust < 0.5) return "degraded";
  if (trust < 0.75) return "watch";
  return "clean";
}

/**
 * Evaluate one telemetry sample. Returns new IntegrityState.
 * Trust only decays on evidence; clean samples slowly recover.
 */
export function evaluateSample(
  sample: Sample,
  current: IntegrityState,
): IntegrityState {
  const flags: IntegrityFlags = { ...EMPTY_FLAGS };
  const reasons: string[] = [];
  let hit = 0;

  // 1. Impossible speed
  if (sample.speedMps != null && sample.speedMps > MAX_PLAUSIBLE_MPS) {
    flags.impossibleSpeed = true;
    reasons.push(`speed ${sample.speedMps.toFixed(1)} m/s exceeds road envelope`);
    hit += 0.35;
  }

  // 2. GPS jump / teleport
  if (
    prevSample &&
    sample.lat != null &&
    sample.lon != null &&
    prevSample.lat != null &&
    prevSample.lon != null
  ) {
    const dt = sample.at - prevSample.at;
    if (dt > 0 && dt < JUMP_TIME_MS) {
      const dist = haversineM(prevSample.lat, prevSample.lon, sample.lat, sample.lon);
      const maxPossible = (MAX_PLAUSIBLE_MPS * dt) / 1000 + 40; // +slack
      if (dist > JUMP_METERS && dist > maxPossible) {
        flags.gpsJump = true;
        reasons.push(`position jump ${dist.toFixed(0)} m in ${(dt / 1000).toFixed(1)} s`);
        hit += 0.4;
      }
    }
  }

  // 3. Heading glitch (large change with near-zero speed)
  if (
    prevSample &&
    sample.heading != null &&
    prevSample.heading != null &&
    sample.speedMps != null &&
    sample.speedMps < 1.5
  ) {
    let d = Math.abs(sample.heading - prevSample.heading);
    if (d > 180) d = 360 - d;
    if (d > 45) {
      flags.headingGlitch = true;
      reasons.push(`heading swing ${d.toFixed(0)}° while nearly stopped`);
      hit += 0.12;
    }
  }

  // 4. Accuracy collapse while reporting motion
  if (
    sample.accuracy != null &&
    sample.accuracy > ACCURACY_COLLAPSE_M &&
    sample.speedMps != null &&
    sample.speedMps > 5
  ) {
    flags.accuracyCollapse = true;
    reasons.push(`accuracy ±${sample.accuracy.toFixed(0)} m while moving`);
    hit += 0.18;
  }

  // 5. Dual-path: GPS speed vs OBD speed
  if (
    sample.speedMps != null &&
    sample.obdSpeedMps != null &&
    Number.isFinite(sample.obdSpeedMps)
  ) {
    const delta = Math.abs(sample.speedMps - sample.obdSpeedMps);
    if (delta > PATH_SPEED_DELTA_MPS) {
      flags.pathDisagree = true;
      reasons.push(
        `GPS/OBD speed disagree by ${(delta * 2.23694).toFixed(0)} mph`,
      );
      hit += 0.28;
    }
  }

  // 6. RTT envelope vs claimed transport
  if (sample.rtt != null && sample.rtt > 0) {
    const t = sample.transport;
    if (t === "cellular" && sample.rtt > RTT_CELLULAR_MAX_MS) {
      flags.rttEnvelope = true;
      reasons.push(`cellular RTT ${sample.rtt} ms outside envelope`);
      hit += 0.1;
    } else if (t === "wifi" && sample.rtt > RTT_WIFI_MAX_MS) {
      flags.rttEnvelope = true;
      reasons.push(`wifi RTT ${sample.rtt} ms outside envelope`);
      hit += 0.08;
    } else if (sample.satelliteTransport && sample.rtt < RTT_SAT_MIN_MS) {
      flags.satelliteInconsistent = true;
      reasons.push(`satellite claim but RTT ${sample.rtt} ms too low`);
      hit += 0.22;
    }
  }

  // Trust update — only decay on evidence, slow recovery when clean
  let trust = current.trust;
  if (hit > 0) {
    trust = Math.max(0.05, trust - hit * 0.55);
  } else {
    trust = Math.min(0.99, trust + 0.008);
  }

  const quarantine =
    trust < 0.28 ||
    flags.gpsJump ||
    flags.impossibleSpeed ||
    (flags.pathDisagree && flags.accuracyCollapse);

  const level = levelFromTrust(trust, quarantine);

  prevSample = { ...sample };

  return {
    trust,
    level,
    flags,
    reasons: reasons.slice(0, 6),
    lastEventAt: hit > 0 ? sample.at : current.lastEventAt,
    ledgerHead: current.ledgerHead,
    ledgerCount: current.ledgerCount,
    quarantine,
  };
}

/** Reset previous sample (e.g. after GPS stop). */
export function resetIntegrityTrack(): void {
  prevSample = null;
}

/** Human label for UI. */
export function integrityLabel(level: IntegrityLevel): string {
  switch (level) {
    case "clean":
      return "CLEAN";
    case "watch":
      return "WATCH";
    case "degraded":
      return "DEGRADED";
    case "quarantine":
      return "QUARANTINE";
  }
}
