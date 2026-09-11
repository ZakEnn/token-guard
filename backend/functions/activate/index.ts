// POST /v1/activate (plan §5.3). Validates the license key, registers the
// device, enforces seat limits (§6.3.2), and returns a JWT + signed
// entitlement token (+ team keys for team tiers). Uses the service role: this
// is the one endpoint that reads across licenses.
import { json, rateLimit, serviceClient, sha256, mintJwt, signEntitlement } from "../_shared/util.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  const db = serviceClient();
  const ip = req.headers.get("x-forwarded-for") ?? "unknown";
  const body = await req.json().catch(() => null);
  if (!body?.license_key || !body?.device_fingerprint) return json({ error: "missing fields" }, 400);

  // §6.5.1: 30/h per IP, 10/h per key — noise control (128-bit keys are unguessable)
  if (!await rateLimit(db, `ip:${ip}`, "activate", 30, 3600)) return json({ error: "rate" }, 429);

  const keyHash = await sha256(new TextEncoder().encode(body.license_key));
  if (!await rateLimit(db, `key:${b64(keyHash)}`, "activate", 10, 3600)) return json({ error: "rate" }, 429);

  const { data: lic } = await db.from("licenses").select("*").eq("key_hash", keyHash).maybeSingle();
  if (!lic) return json({ error: "invalid license" }, 403);

  // Seat enforcement (§6.3.2): register/refresh this device, count distinct
  // devices in the trailing 30 days. Existing seats keep working; only NEW
  // devices beyond the limit are refused.
  const fpHash = await sha256(new TextEncoder().encode(body.device_fingerprint));
  const { data: existing } = await db.from("devices").select("id")
    .eq("license_id", lic.id).eq("fingerprint_hash", fpHash).maybeSingle();
  if (!existing) {
    const since = new Date(Date.now() - 30 * 86400_000).toISOString();
    const { count } = await db.from("devices").select("id", { count: "exact", head: true })
      .eq("license_id", lic.id).gte("last_seen", since);
    if ((count ?? 0) >= lic.seat_limit && lic.status !== "trialing")
      return json({ error: "seat_limit", seat_limit: lic.seat_limit }, 429);
  }
  const { data: device } = await db.from("devices").upsert({
    license_id: lic.id, fingerprint_hash: fpHash, last_seen: new Date().toISOString(),
    binary_version: body.binary_version, os: body.os,
  }, { onConflict: "license_id,fingerprint_hash" }).select("id").single();

  const now = Math.floor(Date.now() / 1000);
  const cacheMb = { free: 100, personal: 2048, team: 4096, enterprise: 8192 }[lic.tier] ?? 100;
  const entitlement = await signEntitlement({
    lic: lic.id, tier: lic.tier, team: lic.team_id, status: lic.status,
    limits: { cache_mb: cacheMb, cloud: lic.tier === "team" || lic.tier === "enterprise",
              audit: lic.tier === "enterprise" },
    iat: now, exp: now + 7 * 86400,
  });
  const jwt = await mintJwt({ license_id: lic.id, team_id: lic.team_id, tier: lic.tier,
                              device_id: device?.id });

  const resp: Record<string, unknown> = { jwt, entitlement_token: entitlement };
  if (lic.team_id) {
    const { data: team } = await db.from("teams").select("hmac_secret, team_cache_key_wrapped")
      .eq("id", lic.team_id).maybeSingle();
    if (team) {
      resp.team_hmac_secret = b64(team.hmac_secret);
      // A9: v1 returns the unwrapped team key to members; true E2E is a roadmap item
      resp.team_cache_key = b64(team.team_cache_key_wrapped);
    }
  }
  return json(resp);
});

function b64(bytes: Uint8Array | string): string {
  const b = typeof bytes === "string"
    ? Uint8Array.from(atob(bytes.replace(/^\\x/, "")), (c) => c.charCodeAt(0))
    : bytes;
  return btoa(String.fromCharCode(...b));
}
