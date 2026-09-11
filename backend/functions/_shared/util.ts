// Shared helpers for TokenGuard Edge Functions (plan §5.3).
// Deno runtime; Supabase Edge Functions.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { create, getNumericDate } from "https://deno.land/x/djwt@v3.0.2/mod.ts";

export const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
export const SERVICE_ROLE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
export const JWT_SECRET = Deno.env.get("SUPABASE_JWT_SECRET")!;
// Ed25519 PKCS8 private key (base64) for entitlement-token signing (§4.3).
export const ENTITLEMENT_SIGNING_KEY = Deno.env.get("ENTITLEMENT_SIGNING_KEY")!;

export function serviceClient() {
  return createClient(SUPABASE_URL, SERVICE_ROLE, { auth: { persistSession: false } });
}

export async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

// Short-lived HS256 JWT the binary presents on subsequent calls; RLS keys off
// its claims (§6.3.4). Minted with Supabase's own JWT secret so PostgREST/RLS
// accept it natively.
export async function mintJwt(claims: Record<string, unknown>): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(JWT_SECRET),
    { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return await create({ alg: "HS256", typ: "JWT" },
    { ...claims, role: "authenticated", exp: getNumericDate(60 * 60) }, key);
}

// Ed25519-signed entitlement token: b64url(payload).b64url(sig) (§4.3).
export async function signEntitlement(payload: Record<string, unknown>): Promise<string> {
  const pkcs8 = Uint8Array.from(atob(ENTITLEMENT_SIGNING_KEY), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", pkcs8, { name: "Ed25519" }, false, ["sign"]);
  const bytes = new TextEncoder().encode(JSON.stringify(payload));
  const sig = new Uint8Array(await crypto.subtle.sign("Ed25519", key, bytes));
  return `${b64url(bytes)}.${b64url(sig)}`;
}

function b64url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

// Fixed-window rate limiter backed by Postgres (§6.5.1) — no extra vendor.
export async function rateLimit(
  db: ReturnType<typeof serviceClient>, scope: string, endpoint: string,
  limit: number, windowSeconds: number,
): Promise<boolean> {
  const windowStart = new Date(Math.floor(Date.now() / (windowSeconds * 1000)) * windowSeconds * 1000);
  const { data } = await db.rpc("incr_rate_counter", {
    p_scope: scope, p_endpoint: endpoint, p_window_start: windowStart.toISOString(),
  });
  return (data ?? 0) <= limit;
}
