# ADR-0001: hand-written MCP transport instead of the official Java SDK

- **Status:** accepted
- **Date:** 2026-09-20

## Context

`jia mcp` has to speak MCP so that Claude, Qoder or any other agent can call the analyzer
directly. The official SDK was checked first, as the design document required:
`io.modelcontextprotocol.sdk:mcp` exists on Maven Central, current at **2.0.1**
(alongside `mcp-core` and `mcp-bom`).

The protocol surface this tool needs is three methods: `initialize`, `tools/list`, `tools/call`.

## Decision

Ship a hand-written, newline-framed JSON-RPC 2.0 loop (`dev.jingyu.jia.mcp.McpServer`,
~250 lines) and depend only on `jackson-databind` and `picocli`.

## Consequences

**Positive**

- The distribution stays a single ~3 MB jar. The SDK brings `reactor-core` and its
  transitive stack into a command-line tool whose entire job is reading files.
- Cold start matters for a stdio server an agent spawns per conversation; there is no
  reactive pipeline to initialise.
- The wire behaviour is auditable in one file, which is exactly the property this project
  sells: everything you can check by reading it.

**Negative**

- Protocol revisions are our problem. MCP version negotiation is pinned to `2024-11-05`
  in `McpServer.PROTOCOL_VERSION`; a client asking for a newer version needs a code change
  rather than a dependency bump.
- No free features we do not use: sampling, roots, elicitation, progress notifications,
  resource subscriptions.

## Why not the alternatives

- **Official SDK (2.0.1):** correct, but buys a dependency tree to serve three methods.
- **HTTP/SSE transport:** needs a port, a supervisor and a security story. Stdio is what
  MCP clients actually spawn locally, and it keeps "nothing leaves this machine" true.
- **Spring AI MCP:** ruled out by the design document's own constraint — no Spring in an
  8 MB CLI.

## Revisit when

The tool grows beyond `tools/*` — resources, prompts, sampling, or a remote transport. Then
adopting the SDK costs less than continuing to hand-maintain negotiation.
