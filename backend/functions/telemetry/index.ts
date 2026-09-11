// POST /v1/telemetry (plan §5.3 / §6.6.2): batched daily rollups only — never
// raw events. JWT-authenticated; RLS confines writes to the caller's license.
import { json, rateLimit, serviceClient } from "../_shared/util.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  const auth = req.headers.get("authorization");
  if (!auth?.startsWith("Bearer ")) return json({ error: "unauthorized" }, 401);
  // The binary presents a Supabase-minted JWT; validate it as an authenticated client.
  const db = createClientFromJwt(auth.slice(7));
  const body = await req.json().catch(() => null);
  if (!Array.isArray(body?.rollups)) return json({ error: "bad body" }, 400);

  // §6.5.1: telemetry is low-frequency (daily batches)
  const svc = serviceClient();
  // rollups carry license_id/device_id/day + counters; RLS enforces ownership
  const { error } = await db.from("usage_events").upsert(body.rollups, {
    onConflict: "license_id,device_id,day",
  });
  return error ? json({ error: error.message }, 403) : json({ ok: true });
});

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { SUPABASE_URL } from "../_shared/util.ts";
function createClientFromJwt(jwt: string) {
  return createClient(SUPABASE_URL, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: `Bearer ${jwt}` } },
    auth: { persistSession: false },
  });
}
