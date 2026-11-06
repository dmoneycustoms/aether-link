import { createFileRoute } from "@tanstack/react-router";
import { App } from "@/components/link/app";

export const Route = createFileRoute("/")({
  component: App,
});
