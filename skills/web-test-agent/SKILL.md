# Web Test Agent Skill

> Owner: OneCase + Playwright MCP
> Status: implemented / integration contract ready / runtime validation pending

## Purpose

驱动浏览器执行真实测试用例并产出证据。Multic 等 Agent 宿主加载本 Skill 后,通过 MCP 调用 OneCase + Playwright 完成 PRD → 用例 → 执行 → 结果的端到端流程。

## Workflow

```
Understand (read PRD)
  → Retrieve (onecase_search_knowledge)
  → Generate (onecase_generate_cases)
  → Validate (onecase_validate_case)
  → Adopt (onecase_adopt_cases)
  → Create TestRun (onecase_create_test_run)
  → Execute (browser via Playwright MCP)
  → Observe (onecase_record_case_attempt + screenshot)
  → Diagnose if failed (onecase_diagnose_failure)
  → Recover (Semantic Level 1 via TargetResolver)
  → Verify (re-run failed step)
  → Report (onecase_get_test_run)
```

## Component Boundaries

### OneCase MCP — Test Intelligence

- `onecase_search_knowledge`: RAG over PRD/specs
- `onecase_search_test_cases`: lookup existing cases
- `onecase_generate_cases`: PRD → Canonical DSL
- `onecase_validate_case`: server-side JSON Schema + semantic validation
- `onecase_adopt_cases`: persist cases, return stable caseId
- `onecase_create_test_run`: build TestRun with adopted caseIds
- `onecase_record_case_attempt`: append attempt, auto advance TestRun status
- `onecase_diagnose_failure`: LLM analysis of failed attempt
- `onecase_get_test_run`: full report including attempts/diagnosis

### Playwright MCP — Browser Executor

- `browser_navigate(url)`
- `browser_snapshot()` — A11y tree
- `browser_click(ref)` / `browser_type(ref, value)`
- `browser_verify_element_visible(ref)`
- `browser_verify_text_visible(text)`
- `browser_verify_value(ref, value)`
- `browser_take_screenshot(filename)`

### Agent — Orchestration

- Plan: parse DSL steps into ordered browser actions
- Tool selection: OneCase for test data, Playwright for browser
- Recovery: re-snapshot, re-resolve target, retry (max 3)
- Reporting: pull TestRun JSON, summarize

## Hard Rules

1. Final PASS must come from `browser_verify_*` (real assertion)
2. LLM cannot judge "looks correct" as PASS
3. Recovery must retry with re-snapshot, never with stale ref
4. Attempt #1 FAILED + Attempt #2 PASSED are both persisted (no overwrite)
5. `automationCandidate` filter: skip non-WEB_FUNCTIONAL cases
6. Every browser step → screenshot → Artifact → evidenceRefs

## Recovery Policy (Tiered)

| Level | Trigger | Mechanism |
|-------|---------|-----------|
| 0 | Default | Playwright native auto-wait |
| 1 | Level 0 fail | browser_snapshot + TargetResolver re-match (TESTID → LABEL → ROLE → TEXT → CSS → XPATH) |
| 2 | Level 1 fail | screenshot + Vision API (Phase 7+, stub) |
| 3 | Level 2 fail | Human Escalation, mark case FAILED |

Max recovery attempts: 3 (configurable via `onecase.recovery.max-attempts`).

## Output Contract

Every TestRun report contains:

```json
{
  "runId": "run-xxx",
  "status": "COMPLETED",
  "summary": {"total":3,"passed":2,"failed":1,"blocked":0},
  "cases": [
    {
      "caseId": "TC_LOGIN_001",
      "latestAttempt": {"attemptNumber": 1, "outcome": "PASSED"},
      "attemptHistory": [
        {"attemptNumber": 1, "outcome": "PASSED", "durationMs": 5200}
      ],
      "evidenceRefs": ["art-xxx"]
    }
  ]
}
```

## Multic Deployment Status

**Pending.** Multic is not currently deployed. Integration contract is documented in `docs/agent-integration/multic.md` but runtime validation requires Multic instance.

## NOT in MVP

- LLM-generated Pass (only real assertion counts)
- Mobile testing
- API testing
- External CUA (OpenAI Computer Use / 豆包)
- Self-healing Test Case update
- Multi-user / OAuth
- Kafka / K8s / distributed scheduler
- Complex observability platform