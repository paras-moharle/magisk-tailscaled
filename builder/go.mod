// Pin file for CI only — the real tailscale module's go.mod is fetched from
// upstream at build time. Upstream Tailscale 1.104.x requires Go >= 1.27.1.
// Bump this when a future upstream release raises its minimum.
go 1.27.1