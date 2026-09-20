#!/usr/bin/env bash
# render-rules.sh - regenerate docs/rules.md from the rules that are compiled
# into the jar, so the published catalogue cannot drift from Rule.doc().
#
# The single source of truth is dev.jingyu.jia.analyze.Rules: this script asks
# the CLI for `rules --format json` (id, title, artifact, doc) and re-renders it
# as Markdown. Nothing here is hand-written per rule, so adding a rule and
# re-running this script is all the documentation work a rule ever needs.
#
# It resolves to exactly
#   java -Dfile.encoding=UTF-8 -jar target/jia.jar rules --format json
# through bin/jia, which also builds the jar with ./mvnw when it is missing
# (pass JIA_MAVEN_REPO=/some/dir to keep those downloads out of ~/.m2).
#
# Usage:
#   scripts/render-rules.sh              # rewrite docs/rules.md
#   scripts/render-rules.sh --stdout     # print it instead of writing the file
#
# Exit codes: 0 ok, 3 environment problem (no java, build failed, the JSON did
# not look like a rule catalogue).

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JIA="$ROOT/bin/jia"
OUT="$ROOT/docs/rules.md"

TO_STDOUT=0
case "${1:-}" in
  -h|--help) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  --stdout)  TO_STDOUT=1 ;;
  "")        ;;
  *) echo "render-rules.sh: unknown argument '$1' (try --help)" >&2; exit 3 ;;
esac

[ -f "$JIA" ] || { echo "render-rules.sh: missing $JIA" >&2; exit 3; }

VERSION=$(sh "$JIA" --version 2>/dev/null) \
  || { echo "render-rules.sh: cannot run '$JIA --version' - is java installed?" >&2; exit 3; }

JSON=$(mktemp) || exit 3
BODY=$(mktemp) || exit 3
trap 'rm -f "$JSON" "$BODY"' EXIT

sh "$JIA" rules --format json > "$JSON" \
  || { echo "render-rules.sh: '$JIA rules --format json' failed" >&2; exit 3; }
[ -s "$JSON" ] || { echo "render-rules.sh: the CLI returned an empty catalogue" >&2; exit 3; }

# ---- the Jackson pretty-printed array -> Markdown ---------------------
# One record per object, four keys, and `doc` is a single-line JSON string whose
# newlines are \n escapes. The unescaping below is character-by-character so a
# literal backslash survives and an unknown escape is passed through verbatim.
awk -v version="$VERSION" '
function unesc(s,   out, i, c, e, len) {
  out = ""; i = 1; len = length(s)
  while (i <= len) {
    c = substr(s, i, 1)
    if (c == "\\") {
      e = substr(s, i + 1, 1)
      if      (e == "n")  { out = out "\n";  i += 2 }
      else if (e == "t")  { out = out "\t";  i += 2 }
      else if (e == "r")  { out = out "\r";  i += 2 }
      else if (e == "\"") { out = out "\"";  i += 2 }
      else if (e == "\\") { out = out "\\";  i += 2 }
      else if (e == "/")  { out = out "/";   i += 2 }
      else                { out = out c;     i += 1 }
      continue
    }
    out = out c; i += 1
  }
  return out
}

# the string on a `"key" : "value"` line: cut before the first colon (keys hold
# no colon), drop the trailing comma the pretty printer adds, then the closing
# quote, then unescape
function value(line,   s) {
  s = line
  sub(/^[^:]*: "/, "", s)
  sub(/,$/, "", s)
  sub(/"$/, "", s)
  return unesc(s)
}

function record() {
  if (id == "") return
  n++
  ids[n] = id; titles[n] = title; artifacts[n] = artifact; docs[n] = doc
}

function cell(s) { gsub(/\|/, "\\|", s); return s }

/^  "id" : /       { record(); id = value($0); title = ""; artifact = ""; doc = ""; next }
/^  "title" : /    { title = value($0); next }
/^  "artifact" : / { artifact = value($0); next }
/^  "doc" : /      { doc = value($0); next }

END {
  record()
  if (n == 0) { print "render-rules.sh: no rules found in that JSON" > "/dev/stderr"; exit 3 }

  print "<!-- GENERATED FILE - do not edit by hand. Regenerate with scripts/render-rules.sh. -->"
  print ""
  print "# Rule catalogue"
  print ""
  printf "%d rules ship in %s.\n\n", n, version
  print "Every finding they raise quotes `file:line` evidence from the artifact that was read,"
  print "and the text of each section is the same `doc()` the CLI serves from Java:"
  print ""
  print "```bash"
  print "jia explain TDA001     # one rule, straight from the jar"
  print "jia rules              # the short form of the index below"
  print "```"
  print ""
  print "## Index"
  print ""
  print "| ID | Artifact | Detects |"
  print "| --- | --- | --- |"
  for (i = 1; i <= n; i++)
    printf "| [%s](#%s) | %s | %s |\n", ids[i], tolower(ids[i]), cell(artifacts[i]), cell(titles[i])
  print ""
  print "## Rules"
  print ""
  for (i = 1; i <= n; i++) {
    printf "## %s\n\n", ids[i]
    printf "**%s** · %s · `jia explain %s`\n\n", cell(titles[i]), artifacts[i], ids[i]
    body = docs[i]
    sub(/^#[^\n]*\n+/, "", body)   # the H1 inside doc() would duplicate our heading
    gsub(/\n+$/, "\n", body)
    printf "%s\n", body
    print "---"
    print ""
  }
  printf "_%d rules, rendered from `%s rules --format json` by scripts/render-rules.sh._\n", n, version
}' "$JSON" > "$BODY" \
  || { echo "render-rules.sh: that JSON did not look like a rule catalogue" >&2; exit 3; }

SECTIONS=$(grep -c '^## [A-Z]\{3\}[0-9]\{3\}$' "$BODY")
RECORDS=$(grep -c '^  "id" : ' "$JSON")
if [ "$SECTIONS" != "$RECORDS" ]; then
  echo "render-rules.sh: rendered $SECTIONS sections for $RECORDS rules - refusing to write" >&2
  exit 3
fi

if [ "$TO_STDOUT" = 1 ]; then
  cat "$BODY"
  exit 0
fi

mkdir -p "$(dirname "$OUT")"
cat "$BODY" > "$OUT" || { echo "render-rules.sh: cannot write $OUT" >&2; exit 3; }
echo "render-rules.sh: wrote $OUT ($RECORDS rules, $VERSION)"
