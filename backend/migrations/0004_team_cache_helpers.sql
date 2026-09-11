-- Helpers used by the team-cache function (§6.5.2 quota + hit counting).
create or replace function bump_team_cache_hit(p_team uuid, p_key bytea)
returns void language sql security definer as $$
  update team_cache_entries set hit_count = hit_count + 1
   where team_id = p_team and hmac_key = p_key
$$;

create or replace function team_cache_bytes(p_team uuid)
returns bigint language sql stable security definer as $$
  select coalesce(sum(octet_length(payload_ciphertext)), 0)
    from team_cache_entries where team_id = p_team
$$;
