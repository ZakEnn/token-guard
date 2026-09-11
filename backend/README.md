# TokenGuard SaaS backend (Supabase + Stripe)

Deploy artifacts for the remote tier (plan §5.3, §4.2, §6). **This is secondary
to the local engine** — the binary runs fully (free-local mode) without any of
this.

## Layout
- `migrations/` — Postgres schema + RLS + helper functions (apply in order).
- `functions/` — Supabase Edge Functions (Deno/TypeScript).
- `dev-keys/` — DEV-ONLY entitlement signing key; replace before production.

## One-time setup
1. `supabase init && supabase link --project-ref <ref>`
2. `supabase db push` (applies `migrations/` in order).
3. Set function secrets:
   ```
   supabase secrets set \
     ENTITLEMENT_SIGNING_KEY="<base64 Ed25519 PKCS8 private key>" \
     STRIPE_SECRET_KEY=sk_live_... \
     STRIPE_WEBHOOK_SECRET=whsec_... \
     SUPABASE_ANON_KEY=<anon>
   ```
   (`SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `SUPABASE_JWT_SECRET` are
   provided by the platform.)
4. `supabase functions deploy activate entitlement telemetry team-cache audit stripe-webhook`
5. In Stripe: create 3 products (personal/team/enterprise), set each price's
   `metadata.tier`, and point a webhook at `/v1/stripe-webhook`.

## Endpoints (§5.3)
| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /v1/activate` | license key + fingerprint | validate, seat-check, issue JWT + entitlement token |
| `POST /v1/entitlement/refresh` | JWT | re-issue entitlement token (24 h heartbeat) |
| `POST /v1/telemetry` | JWT | daily usage rollups (upsert) |
| `GET/PUT /v1/team-cache/{hmac_key}` | JWT (team) | encrypted shared cache; server sees only ciphertext |
| `POST /v1/audit` | JWT (enterprise) | hash-chained, hashes-only audit batches |
| `POST /v1/stripe-webhook` | Stripe sig | subscription lifecycle -> license status |

## Security posture (traceable to plan §6)
- Raw license keys never stored — only `sha256(key)` (§6.2.2).
- Team cache payloads are client-side AES-256-GCM; the platform cannot read code (§6.2.4).
- Cloud keys are per-team HMACs, not plain hashes — not dictionary-attackable (§6.2.4).
- RLS on every table; service role only in `activate` + `stripe-webhook` (§4.2).
- Fixed-window rate limiting in Postgres, no extra vendor (§6.5.1).
- Audit trail carries hashes, never argument/script content (§6.6.3).
