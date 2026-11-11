import { createFileRoute } from "@tanstack/react-router";

const MAX = 262144;

function sizeOf(request: Request): number {
  const n = Number(new URL(request.url).searchParams.get("bytes") ?? "0");
  if (!Number.isFinite(n) || n <= 0) return 0;
  return Math.min(MAX, Math.floor(n));
}

export const Route = createFileRoute("/api/probe")({
  server: {
    handlers: {
      GET: ({ request }: { request: Request }) => {
        const n = sizeOf(request);
        const body = n === 0 ? new Uint8Array([111, 107]) : new Uint8Array(n);
        return new Response(body, {
          headers: {
            "content-type": "application/octet-stream",
            "cache-control": "no-store",
            "x-probe-bytes": String(body.byteLength),
          },
        });
      },
      POST: async ({ request }: { request: Request }) => {
        const buf = await request.arrayBuffer();
        return Response.json({ received: buf.byteLength, t: Date.now() });
      },
    },
  },
});
