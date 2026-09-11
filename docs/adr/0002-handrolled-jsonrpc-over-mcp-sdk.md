# ADR 0002 — Hand-rolled stdio JSON-RPC instead of the MCP Java SDK

**Status:** accepted (M0)

Plan §2 preferred the official MCP Java SDK, with a pre-authorized fallback to a
hand-rolled implementation if the SDK failed native-image validation in M0. That
validation could not be run in the build environment (no local GraalVM), and the
SDK carries exactly the dependencies the plan flags as native-image risk:
Reactor and POJO-reflection Jackson.

Decision: implement the fallback as primary. The frontend is ~400 lines
(`StdioTransport`, `McpServer`), uses Jackson tree-model only (zero reflection
metadata for our classes), and protocol conformance is covered by a test suite
(handshake, version negotiation, error codes, batch rejection, notification
semantics).

**Revisit if:** MCP adds protocol features we need (elicitation, sampling,
streaming tool output) whose reimplementation cost exceeds the SDK's
native-image porting cost.
