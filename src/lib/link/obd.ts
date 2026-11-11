/**
 * OBD-II over Web Bluetooth (ELM327-style).
 * Expanded Mode 01 set tuned for GM / 2010 Chevy HHR era ECUs + VIN + DTCs.
 * Unsupported PIDs return NO DATA and are skipped — nothing is simulated.
 */

export type ObdReading = {
  rpm: number | null;
  kph: number | null;
  coolantC: number | null;
  throttlePct: number | null;
  volts: number | null;
  loadPct: number | null;
  timingDeg: number | null;
  iatC: number | null;
  mafGps: number | null;
  mapKpa: number | null;
  fuelLevelPct: number | null;
  ambientC: number | null;
  baroKpa: number | null;
  stft1Pct: number | null;
  ltft1Pct: number | null;
  stft2Pct: number | null;
  ltft2Pct: number | null;
  fuelStatus: string | null;
  absLoadPct: number | null;
  cmdEquiv: number | null;
  relativeThrottlePct: number | null;
  accelPedalDPct: number | null;
  accelPedalEPct: number | null;
  fuelRailKpa: number | null;
  runtimeSec: number | null;
  milDistanceKm: number | null;
  vin: string | null;
  dtcs: string[];
  pendingDtcs: string[];
};

export function emptyReading(): ObdReading {
  return {
    rpm: null,
    kph: null,
    coolantC: null,
    throttlePct: null,
    volts: null,
    loadPct: null,
    timingDeg: null,
    iatC: null,
    mafGps: null,
    mapKpa: null,
    fuelLevelPct: null,
    ambientC: null,
    baroKpa: null,
    stft1Pct: null,
    ltft1Pct: null,
    stft2Pct: null,
    ltft2Pct: null,
    fuelStatus: null,
    absLoadPct: null,
    cmdEquiv: null,
    relativeThrottlePct: null,
    accelPedalDPct: null,
    accelPedalEPct: null,
    fuelRailKpa: null,
    runtimeSec: null,
    milDistanceKm: null,
    vin: null,
    dtcs: [],
    pendingDtcs: [],
  };
}

const OPTIONAL = [
  "0000fff0-0000-1000-8000-00805f9b34fb",
  "0000ffe0-0000-1000-8000-00805f9b34fb",
  "000018f0-0000-1000-8000-00805f9b34fb",
];

const CYCLE = [
  "010C", "010D", "0105", "010F", "010B", "0110", "0111", "0104", "010E", "0142",
  "012F", "0146", "0133", "0106", "0107", "0108", "0109", "0103", "0143", "0144",
  "0145", "0149", "014A", "0123", "011F", "0121",
] as const;

const FUEL_STATUS: Record<number, string> = {
  0: "—",
  1: "Open loop",
  2: "Closed loop",
  4: "Open loop — drive",
  8: "Open loop — fault",
  16: "Closed loop — fault",
};

function hexBytes(response: string, pid: string): number[] | null {
  const compact = response.replace(/[^0-9A-Fa-f]/g, "").toUpperCase();
  const key = `41${pid.slice(2)}`;
  const at = compact.indexOf(key);
  if (at < 0) return null;
  const rest = compact.slice(at + key.length);
  const out: number[] = [];
  for (let i = 0; i + 1 < rest.length && out.length < 6; i += 2) {
    const n = Number.parseInt(rest.slice(i, i + 2), 16);
    if (Number.isNaN(n)) break;
    out.push(n);
  }
  return out;
}

function decodeDtc(a: number, b: number): string | null {
  if (a === 0 && b === 0) return null;
  const systems = ["P", "C", "B", "U"] as const;
  const sys = systems[(a >> 6) & 3]!;
  const d1 = (a >> 4) & 3;
  const d2 = a & 0xf;
  const d3 = (b >> 4) & 0xf;
  const d4 = b & 0xf;
  return `${sys}${d1}${d2.toString(16).toUpperCase()}${d3.toString(16).toUpperCase()}${d4.toString(16).toUpperCase()}`;
}

export function parsePid(cmd: string, response: string): Partial<ObdReading> | null {
  const upper = response.toUpperCase();
  if (upper.includes("NO DATA") || upper.includes("UNABLE") || upper.includes("ERROR") || upper.includes("?")) {
    return null;
  }

  if (cmd === "03" || cmd === "07") {
    const compact = response.replace(/[^0-9A-Fa-f]/g, "").toUpperCase();
    const marker = cmd === "03" ? "43" : "47";
    const at = compact.indexOf(marker);
    if (at < 0) return cmd === "03" ? { dtcs: [] } : { pendingDtcs: [] };
    const rest = compact.slice(at + 2);
    const codes: string[] = [];
    let i = rest.length >= 2 && Number.parseInt(rest.slice(0, 2), 16) <= 16 ? 2 : 0;
    for (; i + 3 < rest.length && codes.length < 8; i += 4) {
      const a = Number.parseInt(rest.slice(i, i + 2), 16);
      const b = Number.parseInt(rest.slice(i + 2, i + 4), 16);
      if (Number.isNaN(a) || Number.isNaN(b)) break;
      const code = decodeDtc(a, b);
      if (code) codes.push(code);
    }
    return cmd === "03" ? { dtcs: codes } : { pendingDtcs: codes };
  }

  if (cmd === "0902") {
    const compact = response.replace(/[^0-9A-Fa-f]/g, "").toUpperCase();
    const at = compact.indexOf("4902");
    if (at < 0) return null;
    const hex = compact.slice(at + 4);
    let vin = "";
    for (let i = 0; i + 1 < hex.length; i += 2) {
      const n = Number.parseInt(hex.slice(i, i + 2), 16);
      if (Number.isNaN(n)) break;
      if (n >= 32 && n < 127) vin += String.fromCharCode(n);
    }
    vin = vin.replace(/[^A-Za-z0-9]/g, "");
    if (vin.length >= 8) return { vin: vin.slice(0, 17) };
    return null;
  }

  const bytes = hexBytes(response, cmd);
  if (!bytes || bytes.length < 1) return null;
  const a = bytes[0] ?? 0;
  const b = bytes[1] ?? 0;

  switch (cmd) {
    case "010C": return bytes.length >= 2 ? { rpm: (a * 256 + b) / 4 } : null;
    case "010D": return { kph: a };
    case "0105": return { coolantC: a - 40 };
    case "010F": return { iatC: a - 40 };
    case "010B": return { mapKpa: a };
    case "0110": return bytes.length >= 2 ? { mafGps: (a * 256 + b) / 100 } : null;
    case "0111": return { throttlePct: (a * 100) / 255 };
    case "0104": return { loadPct: (a * 100) / 255 };
    case "010E": return { timingDeg: a / 2 - 64 };
    case "0142": return bytes.length >= 2 ? { volts: (a * 256 + b) / 1000 } : null;
    case "012F": return { fuelLevelPct: (a * 100) / 255 };
    case "0146": return { ambientC: a - 40 };
    case "0133": return { baroKpa: a };
    case "0106": return { stft1Pct: ((a - 128) * 100) / 128 };
    case "0107": return { ltft1Pct: ((a - 128) * 100) / 128 };
    case "0108": return { stft2Pct: ((a - 128) * 100) / 128 };
    case "0109": return { ltft2Pct: ((a - 128) * 100) / 128 };
    case "0103": return { fuelStatus: FUEL_STATUS[a] ?? `raw ${a}` };
    case "0143": return bytes.length >= 2 ? { absLoadPct: ((a * 256 + b) * 100) / 255 } : null;
    case "0144": return bytes.length >= 2 ? { cmdEquiv: (a * 256 + b) / 32768 } : null;
    case "0145": return { relativeThrottlePct: (a * 100) / 255 };
    case "0149": return { accelPedalDPct: (a * 100) / 255 };
    case "014A": return { accelPedalEPct: (a * 100) / 255 };
    case "0123": return bytes.length >= 2 ? { fuelRailKpa: (a * 256 + b) * 10 } : null;
    case "011F": return bytes.length >= 2 ? { runtimeSec: a * 256 + b } : null;
    case "0121": return bytes.length >= 2 ? { milDistanceKm: a * 256 + b } : null;
    default: return null;
  }
}

export type ObdSession = {
  name: string;
  close: () => void;
  loop: (
    onReading: (reading: Partial<ObdReading>) => void,
    onLog: (line: string) => void,
  ) => Promise<void>;
};

export async function connectObd(): Promise<ObdSession> {
  const bluetooth = navigator.bluetooth;
  if (!bluetooth) {
    throw new Error("This browser has no Bluetooth API. Chrome on Android does. iPhone does not.");
  }
  const device = await bluetooth.requestDevice({
    acceptAllDevices: true,
    optionalServices: OPTIONAL,
  });
  const server = device.gatt;
  if (!server) throw new Error("This device has no GATT server.");
  const connected = await server.connect();
  const services = await connected.getPrimaryServices();
  let writeChar: BluetoothRemoteGATTCharacteristic | null = null;
  let notifyChar: BluetoothRemoteGATTCharacteristic | null = null;

  for (const service of services) {
    const chars = await service.getCharacteristics();
    for (const ch of chars) {
      if (ch.properties.write || ch.properties.writeWithoutResponse) writeChar = ch;
      if (ch.properties.notify || ch.properties.indicate) notifyChar = ch;
    }
  }
  if (!writeChar) throw new Error("No writable characteristic on this adapter.");
  const write = writeChar;
  if (notifyChar) await notifyChar.startNotifications();

  const waiters: Array<(msg: string) => void> = [];
  let buffer = "";
  let stopped = false;
  let chain: Promise<void> = Promise.resolve();

  const onValue = (ev: Event) => {
    const target = ev.target as BluetoothRemoteGATTCharacteristic;
    const value = target.value;
    if (!value) return;
    buffer += new TextDecoder().decode(value.buffer);
    if (buffer.includes(">") || buffer.includes("\r")) {
      const msg = buffer;
      buffer = "";
      const waiter = waiters.shift();
      if (waiter) waiter(msg);
    }
  };
  notifyChar?.addEventListener("characteristicvaluechanged", onValue);

  const command = (text: string, timeoutMs: number) =>
    new Promise<string>((resolve, reject) => {
      const run = chain.then(
        () =>
          new Promise<string>((res, rej) => {
            const timer = window.setTimeout(() => {
              const i = waiters.indexOf(finish);
              if (i >= 0) waiters.splice(i, 1);
              rej(new Error(`No reply to ${text}`));
            }, timeoutMs);
            const finish = (msg: string) => {
              window.clearTimeout(timer);
              res(msg);
            };
            waiters.push(finish);
            const payload = new TextEncoder().encode(`${text}\r`);
            const send = write.properties.write
              ? write.writeValue(payload)
              : write.writeValueWithoutResponse(payload);
            send.catch((err: unknown) => {
              window.clearTimeout(timer);
              const i = waiters.indexOf(finish);
              if (i >= 0) waiters.splice(i, 1);
              rej(err instanceof Error ? err : new Error(String(err)));
            });
          }),
      );
      chain = run.then(
        () => undefined,
        () => undefined,
      );
      return run.then(resolve, reject);
    });

  const onGone = () => {
    stopped = true;
  };
  device.addEventListener("gattserverdisconnected", onGone);

  return {
    name: device.name ?? "OBD",
    close: () => {
      stopped = true;
      device.removeEventListener("gattserverdisconnected", onGone);
      notifyChar?.removeEventListener("characteristicvaluechanged", onValue);
      device.gatt?.disconnect();
    },
    loop: async (onReading, onLog) => {
      for (const [cmd, ms] of [
        ["ATZ", 5000],
        ["ATE0", 2000],
        ["ATL0", 1500],
        ["ATS0", 1500],
        ["ATH0", 1500],
        ["ATSP0", 2500],
      ] as const) {
        try {
          const msg = await command(cmd, ms);
          onLog(`${cmd} → ${msg.replace(/\s+/g, " ").slice(0, 96) || "(empty)"}`);
        } catch (e) {
          onLog(`${cmd} → ${e instanceof Error ? e.message : String(e)}`);
        }
      }

      for (const [cmd, ms] of [
        ["0902", 4000],
        ["03", 3000],
        ["07", 3000],
      ] as const) {
        try {
          const msg = await command(cmd, ms);
          onLog(`${cmd} → ${msg.replace(/\s+/g, " ").slice(0, 120) || "(empty)"}`);
          const parsed = parsePid(cmd, msg);
          if (parsed) onReading(parsed);
        } catch (e) {
          onLog(`${cmd} → ${e instanceof Error ? e.message : String(e)}`);
        }
      }

      let i = 0;
      let misses = 0;
      while (!stopped) {
        const cmd = CYCLE[i % CYCLE.length]!;
        i += 1;
        try {
          const msg = await command(cmd, 1800);
          onLog(`${cmd} → ${msg.replace(/\s+/g, " ").slice(0, 96) || "(empty)"}`);
          const parsed = parsePid(cmd, msg);
          if (parsed) {
            misses = 0;
            onReading(parsed);
          } else if (/NO DATA|UNABLE|ERROR|STOPPED|\?/i.test(msg)) {
            misses += 1;
            if (misses >= 12) onLog("Many NO DATA — ECU may not support those PIDs (normal for some). Ignition on?");
          }
        } catch (error) {
          if (stopped) return;
          misses += 1;
          onLog(error instanceof Error ? error.message : String(error));
        }
      }
    },
  };
}
