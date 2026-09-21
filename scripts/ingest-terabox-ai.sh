#!/bin/bash
# OneCase — TeraBox AI 内容入库脚本
#
# 作用:
#   1. 把 content/terabox-ai/prd/*.md 上传到 OneCase 知识库 (ragTag=terabox-ai)
#   2. 把 content/terabox-ai/cases/*.json 通过 MCP onecase_adopt_cases 采纳到用例库
#
# 用法:
#   ./scripts/ingest-terabox-ai.sh
#
# 前置:
#   - OneCase 服务在 localhost:8090 运行
#   - PG 中 rag_test_case / spring_ai_vectors 表已建 (V2/V3 SQL)
#   - ragTag 'terabox-ai' 可用 (首次会自动创建)

set -e

BASE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PRD_DIR="$BASE_DIR/content/terabox-ai/prd"
CASES_DIR="$BASE_DIR/content/terabox-ai/cases"
RAG_TAG="terabox-ai"
ONECASE_URL="http://localhost:8090"

echo "=== TeraBox AI 内容入库 ==="
echo "BASE: $BASE_DIR"
echo "RAG_TAG: $RAG_TAG"
echo ""

# ----- 1. PRD Markdown 上传到知识库 -----
echo "--- 1. 上传 PRD 到知识库 (POST /api/v1/knowledge/file/upload) ---"
shopt -s nullglob
prd_count=0
for f in "$PRD_DIR"/*.md; do
    fname=$(basename "$f")
    echo -n "  $fname ... "
    # multipart upload
    resp=$(curl -s -X POST "$ONECASE_URL/api/v1/knowledge/file/upload" \
        -F "ragTag=$RAG_TAG" \
        -F "file=@$f")
    code=$(echo "$resp" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('code','?'))" 2>/dev/null || echo "ERR")
    echo "code=$code"
    prd_count=$((prd_count+1))
done
echo "  uploaded $prd_count PRD files"
echo ""

# 等几秒让 Redis Stream consumer 把 chunks 写进 spring_ai_vectors
echo "--- 等待异步 ingestion 6s ---"
sleep 6
echo ""

# ----- 2. DSL cases 通过 MCP onecase_adopt_cases 采纳 -----
echo "--- 2. 通过 MCP 采纳 DSL cases ---"
SID=$(curl -s -X POST "$ONECASE_URL/mcp" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -D /tmp/h.txt \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"ingest","version":"1"}}}' \
    > /dev/null && grep -i mcp-session-id /tmp/h.txt | sed 's/.*: //;s/\r//')
echo "  MCP session: $SID"
echo ""

cases_count=0
for f in "$CASES_DIR"/*.json; do
    fname=$(basename "$f")
    echo "  $fname"
    # 整个 DSL 对象作为 'dsl' 参数 (CanonicalTestCaseDSL = {schemaVersion, summary, cases, ...})
    # 透传给 Python 直接读文件即可
    args=$(python3 -c "
import json
with open('$f') as fp:
    dsl = json.load(fp)
print(json.dumps({'ragTag': '$RAG_TAG', 'dsl': dsl}, ensure_ascii=False))
")

    resp=$(curl -s -X POST "$ONECASE_URL/mcp" \
        -H "Content-Type: application/json" \
        -H "Accept: application/json, text/event-stream" \
        -H "mcp-session-id: $SID" \
        -d "$(python3 -c "
import json, sys
args = json.loads(sys.argv[1])
print(json.dumps({'jsonrpc':'2.0','id':2,'method':'tools/call','params':{'name':'onecase_adopt_cases','arguments':args}}, ensure_ascii=False))
" "$args")")

    # 解析响应: Streamable HTTP 返回 SSE 格式 (data: {...JSON...}), 需提取 data 行
    adopted=$(echo "$resp" | python3 -c "
import sys, json, re
try:
    data_lines = [l[5:].strip() for l in sys.stdin if l.startswith('data:')]
    if not data_lines:
        print('no data: line')
        sys.exit(0)
    payload = data_lines[-1]
    d = json.loads(payload)
    if 'error' in d:
        print('ERROR:', d['error'].get('message','')[:200])
    elif 'result' in d:
        result = d['result']
        # Spring AI tool 包装: result.content[0].text 含 JSON 字符串 (return Map<String,Object> 序列化后)
        content = result.get('content', [])
        if content and isinstance(content, list):
            text = content[0].get('text', '')
            try:
                inner = json.loads(text)
                ids = inner.get('adoptedCaseIds', [])
                print(f'adopted={len(ids)}, first={ids[0] if ids else None}')
            except Exception:
                # 也可能是 isError 等
                print('text (first 200):', text[:200])
        elif isinstance(result, dict):
            # 直出格式
            ids = result.get('adoptedCaseIds', [])
            print(f'adopted={len(ids)}, first={ids[0] if ids else None}')
        else:
            print('unknown result shape:', str(result)[:200])
    else:
        print('unknown response:', str(d)[:200])
except Exception as e:
    print('parse error:', str(e)[:120])
")
    echo "    $adopted"
    cases_count=$((cases_count+1))
done
echo "  processed $cases_count case files"
echo ""

# ----- 3. DB 验证 -----
echo "--- 3. DB 验证 ---"
echo "spring_ai_vectors (terabox-ai knowledge):"
docker exec onecase-postgres psql -U postgres -d onecase -c \
    "SELECT count(*) AS knowledge_chunks FROM spring_ai_vectors WHERE metadata->>'knowledge' = '$RAG_TAG' AND metadata->>'type' = 'knowledge';"

echo "rag_test_case (terabox-ai):"
docker exec onecase-postgres psql -U postgres -d onecase -c \
    "SELECT status, count(*) FROM rag_test_case WHERE rag_tag = '$RAG_TAG' GROUP BY status;"

echo "=== DONE ==="
