import { previewPathPrefix } from "@/lib/preview-path-prefix";

export type Unit = "mph" | "kmh";

export function appPath(path: string): string {
  if (typeof window === "undefined") return path;
  const prefix = previewPathPrefix(window.location.hostname, window.location.pathname) ?? "";
  return `${prefix}${path}`;
}

export function glassHref(room: string): string {
  if (typeof window === "undefined") return `/glass?room=${encodeURIComponent(room)}`;
  const url = new URL(window.location.href);
  const prefix = previewPathPrefix(url.hostname, url.pathname) ?? "";
  url.pathname = `${prefix}/glass`;
  url.search = room ? `?room=${encodeURIComponent(room)}` : "";
  url.hash = "";
  return url.toString();
}

export function haversineM(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const r = 6371000;
  const p1 = (lat1 * Math.PI) / 180;
  const p2 = (lat2 * Math.PI) / 180;
  const dp = ((lat2 - lat1) * Math.PI) / 180;
  const dl = ((lon2 - lon1) * Math.PI) / 180;
  const a = Math.sin(dp / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) ** 2;
  return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
}

export function bearingDeg(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const p1 = (lat1 * Math.PI) / 180;
  const p2 = (lat2 * Math.PI) / 180;
  const dl = ((lon2 - lon1) * Math.PI) / 180;
  const y = Math.sin(dl) * Math.cos(p2);
  const x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
  return (Math.atan2(y, x) * 180) / Math.PI;
}

export function wrap360(deg: number): number {
  return ((deg % 360) + 360) % 360;
}

const MPH = 2.2369362920544;
const KMH = 3.6;

export function speedNumber(mps: number | null, unit: Unit): string {
  if (mps == null || !Number.isFinite(mps)) return "—";
  const v = unit === "mph" ? mps * MPH : mps * KMH;
  if (v < 10) return v.toFixed(1);
  return Math.round(v).toString();
}

export function formatMiles(meters: number, unit: Unit): string {
  const v = unit === "mph" ? meters / 1609.344 : meters / 1000;
  return v < 10 ? v.toFixed(2) : v.toFixed(1);
}

export function formatMs(ms: number | null): string {
  if (ms == null || !Number.isFinite(ms)) return "—";
  return Math.round(ms).toString();
}

export function formatMbps(mbps: number | null): string {
  if (mbps == null || !Number.isFinite(mbps)) return "—";
  if (mbps < 10) return mbps.toFixed(2);
  return mbps.toFixed(1);
}

export function clock(ts: number): string {
  const d = new Date(ts);
  return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

export function makeCode(): string {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const bytes = new Uint8Array(4);
  crypto.getRandomValues(bytes);
  let s = "";
  for (const b of bytes) s += alphabet[b % alphabet.length]!;
  return s;
}

export function peerId(role: "phone" | "glass"): string {
  const key = `aether-peer-${role}`;
  const existing = sessionStorage.getItem(key);
  if (existing && /^[a-zA-Z0-9_-]{1,64}$/.test(existing)) return existing;
  const id = `${role === "phone" ? "h" : "g"}${Math.random().toString(36).slice(2, 12)}`;
  sessionStorage.setItem(key, id);
  return id;
}
