// POST /v1/audit (plan §5.3 / §6.6.3): enterprise-only, hash-chained,
// hashes-only audit batches. Argument content never appears — only args_hash /
// script_hash, so the audit trail can confirm "this exact known script ran"
// without copying proprietary code off the machine.
import { json, serviceClient } from "../_shared/util.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { SUPABASE_URL } from "../_shared/util.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  const auth = req.headers.get("authorization");
  if (!auth?.startsWith("Bearer ")) return json({ error: "unauthorized" }, 401);
  const jwt = auth.slice(7);
  const claims = JSON.parse(atob(jwt.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
  if (claims.tier !== "enterprise") return json({ error: "enterprise only" }, 403);

  const db = createClient(SUPABASE_URL, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: `Bearer ${jwt}` } }, auth: { persistSession: false },
  });
  const body = await req.json().catch(() => null);
  if (!Array.isArray(body?.entries)) return json({ error: "bad body" }, 400);

  // RLS enforces team_id + enterprise tier on insert; prev_hash chain is
  // computed client-side and stored verbatim for later tamper verification.
  const rows = body.entries.map((e: Record<string, unknown>) => ({
    team_id: claims.team_id, device_id: claims.device_id, ...e,
  }));
  const { error } = await db.from("audit_log").insert(rows);
  return error ? json({ error: error.message }, 403) : json({ ok: true, accepted: rows.length });
});
