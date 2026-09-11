# Per-Client Validation Matrix (P2.3)

> **Status: PENDING EMPIRICAL RUN.** This campaign requires real installs of
> the six hosts (external dependency — see enhancement plan §5). The
> measurement substrate is ready: every tool call lands in `interaction_trace`
> (schema v2) with client attribution from the MCP `clientInfo` handshake.
> Fill each row with the SQL evidence below; then update
> `PLATFORM_AGNOSTIC_PLAN.md` §4 Open Questions 1–3, 5–7, 9.

**Evidence query (run after each session):**

```sql
SELECT client_name, client_version, tool, engine, optimization,
       COUNT(*) AS calls, SUM(raw_tokens) AS raw, SUM(returned_tokens) AS returned
FROM interaction_trace
WHERE session_id = (SELECT session_id FROM interaction_trace
                    ORDER BY ts DESC LIMIT 1)
GROUP BY client_name, tool ORDER BY calls DESC;
```

| Client | Version tested | clientInfo sent? (Q5) | Tools visible | Name shadowing vs built-ins (Q1/Q3/Q7) | Facade behavior | Unique tools used implicitly? | Evidence pasted |
|---|---|---|---|---|---|---|---|
| Claude Desktop | — | — | — | n/a (no built-in file tools) | — | — | ☐ |
| Claude Code CLI | — | — | — | — | — | — | ☐ |
| Cursor | — | — | — | — | — | — | ☐ |
| VS Code + Copilot | — | — | — | — | — | — | ☐ |
| VS Code + Cline | — | — | — | — | — | — | ☐ |
| Windsurf | — | — | — | — (100-tool cap: verify facade stays at +3) | — | — | ☐ |

**Protocols:** use the per-client scripts in `PLATFORM_AGNOSTIC_PLAN.md` §1.6
verbatim; run each on Windows **and** one Unix platform.
