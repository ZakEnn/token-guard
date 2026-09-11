-- Row-level security (plan §4.2). Binary JWTs carry claims license_id / team_id
-- / tier; policies key off them. request.jwt.claims is set by Supabase from the
-- verified bearer token. The service role bypasses RLS and is used only by the
-- /activate and stripe-webhook functions.

alter table teams               enable row level security;
alter table licenses            enable row level security;
alter table devices             enable row level security;
alter table team_cache_entries  enable row level security;
alter table usage_events        enable row level security;
alter table audit_log           enable row level security;
alter table rate_limit_counters enable row level security;

create or replace function jwt_license_id() returns uuid language sql stable as $$
  select nullif(current_setting('request.jwt.claims', true)::jsonb ->> 'license_id','')::uuid
$$;

create or replace function jwt_team_id() returns uuid language sql stable as $$
  select nullif(current_setting('request.jwt.claims', true)::jsonb ->> 'team_id','')::uuid
$$;

create or replace function jwt_tier() returns text language sql stable as $$
  select current_setting('request.jwt.claims', true)::jsonb ->> 'tier'
$$;

-- A device sees only its own license row and its own devices
create policy license_self on licenses for select
  using (id = jwt_license_id());
create policy devices_self on devices for select
  using (license_id = jwt_license_id());

-- Team cache: strictly partitioned by team (§6.3.3). Server stores ciphertext
-- only, so even a full table read leaks nothing readable (§6.2.4).
create policy team_cache_read on team_cache_entries for select
  using (team_id = jwt_team_id());
create policy team_cache_write on team_cache_entries for insert
  with check (team_id = jwt_team_id());
create policy team_cache_update on team_cache_entries for update
  using (team_id = jwt_team_id());

-- Usage rollups: a device writes/reads only its own license's rows
create policy usage_self_ins on usage_events for insert
  with check (license_id = jwt_license_id());
create policy usage_self_upd on usage_events for update
  using (license_id = jwt_license_id());
create policy usage_self_sel on usage_events for select
  using (license_id = jwt_license_id());

-- Audit log: enterprise tier only, append-only, own team only
create policy audit_ent_ins on audit_log for insert
  with check (team_id = jwt_team_id() and jwt_tier() = 'enterprise');
create policy audit_ent_sel on audit_log for select
  using (team_id = jwt_team_id() and jwt_tier() = 'enterprise');

-- teams / rate_limit_counters: no policies => only the service role touches them
