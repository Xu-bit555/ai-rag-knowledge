#!/bin/bash
# Phase 4 Vertical Slice: PRD → Generate → Adopt → Create Run → Execute → Record → Get

set -e

ONECASE="http://127.0.0.1:8090"
PLAYWRIGHT="http://127.0.0.1:8931/mcp"
DEMO="http://127.0.0.1:8081/demo/login"
RAG="demo-login"
TS=$(date +%s)

mcp_call() {
    local name=$1 args=$2
    curl -s -X POST $ONECASE/mcp \
        -H "Content-Type: application/json" \
        -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$name\",\"arguments\":$args}}"
}

echo "=========================================="
echo "Phase 4 Vertical Slice"
echo "=========================================="

# 1. Generate
echo ""
echo "[1/6] generate_cases from PRD"
GEN=$(mcp_call "onecase_generate_cases" \
    "{\"ragTag\":\"$RAG\",\"requirement\":\"$(cat docs/demo/login-prd.md | head -100 | sed 's/$/\\\\n/g' | tr -d '\\n')\"}")
echo "$GEN" | python3 -m json.tool | head -30
CASE_ID=$(echo "$GEN" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['result']['cases'][0]['caseId'])")
echo "First caseId: $CASE_ID"

# 2. Validate + Adopt
echo ""
echo "[2/6] validate_case"
mcp_call "onecase_validate_case" "{\"case\":$(echo "$GEN" | python3 -c "import sys,json;print(json.dumps(json.load(sys.stdin)['result']['cases'][0]))")}" \
    | python3 -m json.tool | head -10

echo ""
echo "[3/6] adopt_cases"
ADOPT=$(mcp_call "onecase_adopt_cases" \
    "{\"ragTag\":\"$RAG\",\"cases\":$(echo "$GEN" | python3 -c "import sys,json;print(json.dumps(json.load(sys.stdin)['result']['cases']))")}")
echo "$ADOPT" | python3 -m json.tool
STABLE_CASE_ID=$(echo "$ADOPT" | python3 -c "import sys,json;print(json.load(sys.stdin)['result']['adoptedCaseIds'][0])")

# 3. Create Run
echo ""
echo "[4/6] create_test_run"
RUN=$(mcp_call "onecase_create_test_run" \
    "{\"ragTag\":\"$RAG\",\"name\":\"phase4-slice-$TS\",\"caseIds\":[\"$STABLE_CASE_ID\"],\"targetUrl\":\"$DEMO\"}")
echo "$RUN" | python3 -m json.tool
RUN_ID=$(echo "$RUN" | python3 -c "import sys,json;print(json.load(sys.stdin)['result']['runId'])")

# 4. Browser happy path (manually triggered via Playwright MCP)
echo ""
echo "[5/6] browser_navigate + snapshot + click + verify (simulated Agent)"
NAV=$(curl -s -X POST $PLAYWRIGHT \
    -H "Content-Type: application/json" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"browser_navigate\",\"arguments\":{\"url\":\"$DEMO\"}}}")
echo "Navigate: $(echo "$NAV" | head -c 100)..."

SNAP=$(curl -s -X POST $PLAYWRIGHT \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"browser_snapshot","arguments":{}}}')
echo "Snapshot len: $(echo "$SNAP" | wc -c) bytes"

VERIFY=$(curl -s -X POST $PLAYWRIGHT \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"browser_verify_element_visible","arguments":{"ref":"e17"}}}' 2>/dev/null || echo '{"result":"skipped - no actual ref"}')
echo "Verify (sample): $(echo "$VERIFY" | head -c 150)"

# 5. Screenshot + artifact upload (manual curl)
echo ""
echo "[6/6] screenshot + artifact upload (manual, since Agent not deployed)"
# In real Agent run, screenshot file comes from Playwright MCP's output dir
# For this script we just demonstrate the upload endpoint exists
DEMO_SCREENSHOT="/tmp/demo-screenshot.png"
if command -v screencapture &>/dev/null; then
    screencapture -x "$DEMO_SCREENSHOT" 2>/dev/null || true
fi
if [ ! -f "$DEMO_SCREENSHOT" ]; then
    # 用一个最小 PNG 占位
    printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\rIDATx\x9cc\xfc\xff\xff?\x03\x00\x05\xfe\x02\xfeA\x80\x8c\x04\x00\x00\x00\x00IEND\xaeB`\x82' > "$DEMO_SCREENSHOT"
fi

UPLOAD=$(curl -s -X POST $ONECASE/api/v1/artifact/upload \
    -F "runId=$RUN_ID" \
    -F "caseId=$STABLE_CASE_ID" \
    -F "attemptNumber=1" \
    -F "artifactType=SCREENSHOT" \
    -F "file=@$DEMO_SCREENSHOT")
echo "$UPLOAD" | python3 -m json.tool
ARTIFACT_ID=$(echo "$UPLOAD" | python3 -c "import sys,json;print(json.load(sys.stdin).get('data',{}).get('artifactId',''))")

# 6. Record attempt
echo ""
echo "[6b/6] record_case_attempt with evidence"
RECORD=$(mcp_call "onecase_record_case_attempt" \
    "{\"runId\":\"$RUN_ID\",\"caseId\":\"$STABLE_CASE_ID\",\"outcome\":\"PASSED\",\"durationMs\":5200,\"evidenceRefs\":[\"$ARTIFACT_ID\"]}")
echo "$RECORD" | python3 -m json.tool

# 7. Get run
echo ""
echo "[Final] get_test_run"
GET=$(mcp_call "onecase_get_test_run" "{\"runId\":\"$RUN_ID\"}")
echo "$GET" | python3 -m json.tool

echo ""
echo "=========================================="
echo "Run ID: $RUN_ID"
echo "=========================================="