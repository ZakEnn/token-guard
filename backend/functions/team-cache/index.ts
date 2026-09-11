// GET/PUT /v1/team-cache/{hmac_key} (plan §5.3 / §6.2.4). The server stores and
// returns ONLY ciphertext + size metadata — it cannot read cached code. RLS
// partitions strictly by team; PUT enforces size + per-team storage quotas.
import { json, rateLimit, serviceClient } from "../_shared/util.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { SUPABASE_URL } from "../_shared/util.ts";

const MAX_ENTRY_BYTES = 1024 * 1024;         // §6.5.1
const TEAM_STORAGE_QUOTA = 10 * 1024 * 1024 * 1024;

Deno.serve(async (req) => {
  const auth = req.headers.get("authorization");
  if (!auth?.startsWith("Bearer ")) return json({ error: "unauthorized" }, 401);
  const jwt = auth.slice(7);
  const claims = decodeClaims(jwt);
  if (!claims?.team_id) return json({ error: "not a team license" }, 403);

  const db = createClient(SUPABASE_URL, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: `Bearer ${jwt}` } }, auth: { persistSession: false },
  });
  const hmacKey = new URL(req.url).pathname.split("/").pop()!;
  const ip = req.headers.get("x-forwarded-for") ?? "unknown";

  if (req.method === "GET") {
    if (!await rateLimit(serviceClient(), `device:${claims.device_id}`, "tc-get", 1200, 3600))
      return json({ error: "rate" }, 429);
    const { data } = await db.from("team_cache_entries")
      .select("payload_ciphertext, payload_nonce, result_tokens")
      .eq("team_id", claims.team_id).eq("hmac_key", hexToBytes(hmacKey))
      .gt("expires_at", new Date().toISOString()).maybeSingle();
    if (!data) return json({ error: "not found" }, 404);
    await db.rpc("bump_team_cache_hit", { p_team: claims.team_id, p_key: hexToBytes(hmacKey) })
      .catch(() => {});
    return json({
      payload_ciphertext: bytesToB64(data.payload_ciphertext),
      payload_nonce: bytesToB64(data.payload_nonce),
      result_tokens: data.result_tokens,
    });
  }

  if (req.method === "PUT") {
    if (!await rateLimit(serviceClient(), `device:${claims.device_id}`, "tc-put", 300, 3600))
      return json({ error: "rate" }, 429);
    const body = await req.json().catch(() => null);
    if (!body?.payload_ciphertext || !body?.payload_nonce) return json({ error: "bad body" }, 400);
    const cipher = b64ToBytes(body.payload_ciphertext);
    if (cipher.length > MAX_ENTRY_BYTES) return json({ error: "entry too large" }, 413);

    // Per-team storage quota (§6.5.2, server-authoritative)
    const svc = serviceClient();
    const { data: used } = await svc.rpc("team_cache_bytes", { p_team: claims.team_id });
    if ((used ?? 0) + cipher.length > TEAM_STORAGE_QUOTA) return json({ error: "quota" }, 507);

    const ttlDays = 30;
    const { error } = await db.from("team_cache_entries").upsert({
      team_id: claims.team_id, hmac_key: hexToBytes(hmacKey),
      payload_ciphertext: cipher, payload_nonce: b64ToBytes(body.payload_nonce),
      result_tokens: body.result_tokens ?? 0, created_by_device: claims.device_id,
      expires_at: new Date(Date.now() + ttlDays * 86400_000).toISOString(),
    }, { onConflict: "team_id,hmac_key" });
    return error ? json({ error: error.message }, 403) : json({ ok: true });
  }
  return json({ error: "method" }, 405);
});

function decodeClaims(jwt: string): Record<string, string> | null {
  try { return JSON.parse(atob(jwt.split(".")[1].replace(/-/g, "+").replace(/_/g, "/"))); }
  catch { return null; }
}
function hexToBytes(hex: string): string { return `\\x${hex}`; }               // bytea literal
function bytesToB64(v: string): string { return btoa(v); }                     // pgrst returns \x hex; client re-decodes
function b64ToBytes(b64: string): string { return `\\x${[...atob(b64)].map((c)=>c.charCodeAt(0).toString(16).padStart(2,"0")).join("")}`; }
