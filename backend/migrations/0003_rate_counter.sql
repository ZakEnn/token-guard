-- Atomic fixed-window counter increment (§6.5.1): one round trip, returns the
-- new count so the Edge Function can compare against the limit.
create or replace function incr_rate_counter(
  p_scope text, p_endpoint text, p_window_start timestamptz
) returns int language plpgsql security definer as $$
declare new_count int;
begin
  insert into rate_limit_counters(scope, endpoint, window_start, count)
    values (p_scope, p_endpoint, p_window_start, 1)
  on conflict (scope, endpoint, window_start)
    do update set count = rate_limit_counters.count + 1
  returning count into new_count;
  return new_count;
end $$;
