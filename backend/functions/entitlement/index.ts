// POST /v1/entitlement/refresh (plan §5.3): the 24 h heartbeat. Re-issues a
// signed entitlement token from current license state without re-checking the
// raw key (the JWT already proves prior activation, §6.3.4).
import { json, serviceClient, signEntitlement, mintJwt } from "../_shared/util.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  const auth = req.headers.get("authorization");
  if (!auth?.startsWith("Bearer ")) return json({ error: "unauthorized" }, 401);
  const claims = JSON.parse(atob(auth.slice(7).split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
  if (!claims?.license_id) return json({ error: "bad token" }, 401);

  const db = serviceClient();
  const { data: lic } = await db.from("licenses").select("*").eq("id", claims.license_id).maybeSingle();
  if (!lic) return json({ error: "unknown license" }, 403);

  const now = Math.floor(Date.now() / 1000);
  const cacheMb = { free: 100, personal: 2048, team: 4096, enterprise: 8192 }[lic.tier] ?? 100;
  const entitlement = await signEntitlement({
    lic: lic.id, tier: lic.tier, team: lic.team_id, status: lic.status,
    limits: { cache_mb: cacheMb, cloud: lic.tier === "team" || lic.tier === "enterprise",
              audit: lic.tier === "enterprise" },
    iat: now, exp: now + 7 * 86400,
  });
  const jwt = await mintJwt({ license_id: lic.id, team_id: lic.team_id, tier: lic.tier,
                              device_id: claims.device_id });
  return json({ jwt, entitlement_token: entitlement });
});
