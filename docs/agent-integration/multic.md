# Multic Integration Contract

> Status: Contract ready, deployment pending

## What is Multic

Multic is the Agent Hosting Platform. It hosts Web Test Agent instances that:

1. Load SKILL.md (`skills/web-test-agent/SKILL.md`)
2. Register MCP clients to OneCase + Playwright
3. Execute the workflow autonomously

Multic repo: <https://github.com/multica-ai/multica>

## Required MCP Client Configuration

In Multic Agent config:

```yaml
mcp_servers:
  - name: onecase
    type: http
    url: ${ONECASE_MCP_URL}      # e.g. http://10.0.0.1:8090/mcp
  - name: playwright
    type: http
    url: ${PLAYWRIGHT_MCP_URL}   # e.g. http://10.0.0.2:8931/mcp
```

## Required Environment Variables (Agent Runtime)

```bash
# Multic Agent 工作环境需要的 env vars (由部署环境注入)
ONECASE_MCP_URL=http://<onecase-host>:8090/mcp
PLAYWRIGHT_MCP_URL=http://<playwright-host>:8931/mcp

# Agent 自身的 LLM key (Agent LLM 调用,不是 OneCase 的)
AGENT_LLM_API_KEY=${LLM_API_KEY}
```

## Available OneCase MCP Tools (11)

```
onecase_ping
onecase_server_info
onecase_search_knowledge
onecase_search_test_cases
onecase_generate_cases
onecase_validate_case
onecase_adopt_cases
onecase_create_test_run
onecase_record_case_attempt
onecase_diagnose_failure
onecase_get_test_run
```

## Expected Workflow

```
Multic Agent
  ↓ receives user instruction: "在 demo app 登录页测试"
  ↓ loads SKILL.md
  ↓
  1. onecase_generate_cases
  2. onecase_validate_case × N
  3. onecase_adopt_cases → stable caseId[]
  4. onecase_create_test_run → runId
  5. browser_navigate + browser_snapshot + browser_click + browser_type
     (driven by DSL steps from onecase_adopt_cases)
  6. browser_verify_* (assertion)
  7. browser_take_screenshot → POST /api/v1/artifact/upload → artifactId
  8. onecase_record_case_attempt(outcome, evidenceRefs: [artifactId])
  9. onecase_get_test_run → display report
  ↓
  return report to user
```

## Artifact Upload Workflow (REST, NOT MCP)

```bash
curl -X POST $ONECASE_BASE_URL/api/v1/artifact/upload \
  -F "runId=run-xxx" \
  -F "caseId=TC_LOGIN_001" \
  -F "attemptNumber=1" \
  -F "artifactType=SCREENSHOT" \
  -F "file=@/path/to/screenshot.png"
```

Returns:
```json
{"code":"0000","data":{"artifactId":"art-abc123", ...}}
```

Then include in attempt:
```json
{"name": "onecase_record_case_attempt", "arguments": {
  "runId": "run-xxx", "caseId": "TC_LOGIN_001",
  "outcome": "PASSED", "durationMs": 5200,
  "evidenceRefs": ["art-abc123"]
}}
```

## Failure Recovery Workflow

```
Step 3 (click login button) FAILED
  ↓
browser_snapshot (re-fetch page)
  ↓
TargetResolver.resolve(target, snapshot) — TESTID/LABEL/ROLE/TEXT/CSS/XPATH
  ↓
if ref found:
  retry step (attemptNumber + 1)
  record_case_attempt(outcome, recoveryLevel=1)
elif ref NOT found:
  onecase_diagnose_failure(attemptId, errorContext)
  ↓ if Vision Recovery enabled (Phase 7+):
  vision_resolve(screenshot, intent) → coordinate
  ↓ if still fail:
  Human Escalation → case FAILED
```

## Network Requirements

- Multic Agent → OneCase MCP: outbound TCP 8090
- Multic Agent → Playwright MCP: outbound TCP 8931
- Multic Agent → OneCase REST: outbound TCP 8090 (for artifact upload + report GET)
- Playwright MCP → Browser: managed internally by Playwright MCP
- OneCase → PostgreSQL: outbound TCP 5432
- OneCase → Redis: outbound TCP 6379

## NOT YET DEPLOYED

This document is the integration contract. Actual Multic deployment, Agent registration, and end-to-end validation require:

1. Multic server deployed and accessible
2. Web Test Agent instance registered
3. SKILL.md loaded
4. MCP servers registered

**Do not claim "Multic integration verified" until step 4 is confirmed.**

## Mock Mode for Local Testing

For local testing without Multic, see:

```
scripts/phase2.5-e2e.sh   # terminal-only E2E
scripts/phase4-vertical-slice.sh  # full vertical slice demo
```

These scripts manually simulate what Multic Agent would do via curl.