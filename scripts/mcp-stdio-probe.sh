#!/usr/bin/env bash
# Drive `jia mcp` as a separate OS process over real stdio, exactly as an MCP client would:
# no in-JVM test harness, no shared objects — JSON-RPC lines in, JSON-RPC lines out.
set -uo pipefail
cd "$(dirname "$0")/.."
JAR=target/jia.jar
[ -f "$JAR" ] || { echo "build target/jia.jar first" >&2; exit 2; }

DUMP="$(pwd)/corpus/incident-deadlock/threads.dump"

{
  printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"stdio-probe","version":"0"}}}'
  printf '%s\n' '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  printf '%s\n' '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
  node -e '
    const fs=require("fs");
    const dump=fs.readFileSync(process.argv[1],"utf8");
    process.stdout.write(JSON.stringify({jsonrpc:"2.0",id:3,method:"tools/call",
      params:{name:"analyze_snapshot",arguments:{content:dump,fileName:"pasted-from-chat.dump"}}})+"\n");
  ' "$DUMP"
  sleep 3
} | java -jar "$JAR" mcp 2>/dev/null > target/mcp-probe.out

node -e '
  const fs=require("fs");
  const lines=fs.readFileSync("target/mcp-probe.out","utf8").split("\n").filter(l=>l.trim());
  console.log("responses received:", lines.length);
  const byId={};
  for (const l of lines){ const j=JSON.parse(l); if(!j.id) continue; byId[j.id]=j; }
  const init=byId[1].result;
  console.log("  initialize -> protocol", init.protocolVersion, "| server", init.serverInfo.name, init.serverInfo.version);
  const tools=byId[2].result.tools;
  console.log("  tools/list ->", tools.map(t=>t.name).join(", "));
  console.log("  schema for analyze_snapshot:", Object.keys(tools[0].inputSchema.properties).join("/"));
  const call=JSON.parse(byId[3].result.content[0].text);
  console.log("  tools/call analyze_snapshot ->");
  console.log("    schema:", call.schema, "| findings:", call.findings.length, "| hypotheses:", call.hypotheses.length);
  call.findings.forEach(f=>console.log("      ", f.ruleId, f.severity, Math.round(f.confidence*100)+"%",
      f.evidence.length, "evidence lines, first:", f.evidence[0].file+":"+f.evidence[0].startLine));
  console.log("    top hypothesis:", call.hypotheses[0].id);
  const ok = init.protocolVersion && tools.length===3 && call.findings[0].ruleId==="TDA001"
    && call.hypotheses[0].id==="H-DEADLOCK" && call.findings[0].evidence[0].file==="pasted-from-chat.dump";
  console.log(ok ? "\nRESULT: PASS — real stdio client round trip works" : "\nRESULT: FAIL");
  process.exit(ok?0:1);
'
