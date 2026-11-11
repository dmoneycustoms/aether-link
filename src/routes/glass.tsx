import { createFileRoute } from "@tanstack/react-router";
import { GlassPage } from "@/components/link/glass-page";

type Search = { room: string };

export const Route = createFileRoute("/glass")({
  validateSearch: (search: Record<string, unknown>): Search => ({
    room: typeof search.room === "string" ? search.room.replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 16) : "",
  }),
  component: function GlassRoute() {
    const { room } = Route.useSearch();
    return <GlassPage room={room} />;
  },
});
