-- TokenGuard cloud schema (plan §4.2). Every table has RLS enabled; binaries
-- authenticate with a short-lived JWT carrying license_id/team_id claims
-- (§6.3.4). Edge Functions use the service role ONLY for /activate and the
-- Stripe webhook.

create extension if not exists pgcrypto;

create table teams (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  stripe_customer_id text unique,
  team_cache_key_wrapped bytea,           -- team AES key wrapped w/ platform KMS key (§6.2.4, A9)
  hmac_secret bytea not null,             -- per-team secret for cache-key derivation (§6.2.4)
  created_at timestamptz default now()
);

create table licenses (
  id uuid primary key default gen_random_uuid(),
  key_hash bytea not null unique,         -- sha256(key); raw key never stored (§6.2.2)
  team_id uuid references teams(id),
  tier text not null check (tier in ('free','personal','team','enterprise')),
  status text not null check (status in ('trialing','active','past_due','expired','revoked')),
  stripe_subscription_id text,
  seat_limit int not null default 1,
  trial_ends_at timestamptz,
  created_at timestamptz default now()
);

create table devices (
  id uuid primary key default gen_random_uuid(),
  license_id uuid not null references licenses(id),
  fingerprint_hash bytea not null,        -- §6.3.2
  first_seen timestamptz default now(),
  last_seen timestamptz default now(),
  binary_version text,
  os text,
  unique (license_id, fingerprint_hash)
);

create table team_cache_entries (
  team_id uuid not null references teams(id),
  hmac_key bytea not null,                -- HMAC-SHA256(team.hmac_secret, canonical cache key)
  payload_ciphertext bytea not null,      -- AES-256-GCM under team key; server CANNOT read (§6.2.4)
  payload_nonce bytea not null,
  result_tokens int not null,             -- clear metadata: sizes, not content
  hit_count bigint not null default 0,
  created_by_device uuid references devices(id),
  created_at timestamptz default now(),
  expires_at timestamptz not null,
  primary key (team_id, hmac_key)
);

create table usage_events (               -- pre-aggregated daily rollups, not raw events (§6.6.2)
  license_id uuid not null references licenses(id),
  device_id uuid references devices(id),
  day date not null,
  tool_calls bigint default 0,
  cache_hits bigint default 0,
  cache_misses bigint default 0,
  tokens_saved_est bigint default 0,
  scripts_run bigint default 0,
  primary key (license_id, device_id, day)
);

create table audit_log (                  -- enterprise tier only (§6.6.3)
  id bigint generated always as identity primary key,
  team_id uuid not null references teams(id),
  device_id uuid references devices(id),
  ts timestamptz not null,
  event_kind text not null,
  tool text,
  args_hash bytea,                        -- hash only; never argument content
  script_hash bytea,
  prev_hash bytea not null                -- hash chain for tamper evidence
);

create table rate_limit_counters (        -- fixed-window counters (§6.5.1)
  scope text not null,
  endpoint text not null,
  window_start timestamptz not null,
  count int not null default 0,
  primary key (scope, endpoint, window_start)
);
