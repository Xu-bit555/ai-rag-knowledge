# OneCase 2.0 — Test Intelligence Backend + MCP Server

> **AI 驱动的 Web 测试用例生成 + 浏览器执行 + Agentic Recovery 平台**

---

## 1. Project Overview

OneCase 是一个面向 QA / 测试开发工程师的 **Test Intelligence 后端**：

- **RAG 知识检索**：基于历史 PRD / 测试规范 / 领域知识做向量检索
- **Canonical Test Case DSL**：用 JSON Schema + Java DTO + 业务语义三层校验测试用例
- **MCP Server**：通过 Model Context Protocol 暴露 9 个工具给 Agent（Multic 等）
- **Web Test Run 管理**：TestRun → CaseAttempt 多版本模型 + 失败诊断 + Recovery
- **Artifact 管理**：浏览器执行截图通过 REST multipart 上传到 OneCase Local FS + PG metadata
- **Playwright MCP 集成**：通过 JSON-RPC over HTTP 调用浏览器自动化

## 2. Architecture

```
                    ┌──────────────────────┐
                    │       Multic         │  (PENDING)
                    │ Web Test Agent       │
                    │ + Web Test Skill     │
                    └──────────┬───────────┘
                               │ MCP Streamable HTTP
                               ▼
                    ┌──────────────────────┐
                    │       OneCase        │
                    │ Test Intelligence    │
                    │ Backend + MCP Server │
                    └───────┬───────┬──────┘
                            │       │
                       REST │       │ MCP (Playwright client)
                            │       │
                  ┌─────────▼─┐   ┌─▼────────────┐
                  │ Artifact  │   │ Playwright  │
                  │ Storage   │   │ MCP         │
                  │ + PG meta │   │ (external)  │
                  └───────────┘   └─────┬──────┘
                                        │
                                        ▼
                                Headless Browser
                                        │
                                        ▼
                                    Demo Web
                                  :8081/login
```

## 3. Core Concepts

### Canonical Test Case DSL v1.0.0

LLM、Java、DB、Agent 共用的测试用例标准协议。详见 `src/main/resources/schemas/canonical-test-case/v1.json`。

```json
{
  "schemaVersion": "1.0.0",
  "summary": {"app": "...", "page": "...", "totalCases": 1},
  "cases": [{
    "caseId": "TC_LOGIN_001",
    "title": "登录成功",
    "automationCandidate": "WEB_FUNCTIONAL",
    "steps": [
      {"order": 1, "action": "NAVIGATE", "url": "http://localhost:8081/demo/login"},
      {"order": 2, "action": "INPUT", "target": {"strategy":"LABEL","label":"用户名"}, "value": "test"},
      {"order": 3, "action": "INPUT", "target": {"strategy":"LABEL","label":"密码"}, "value": "test123"},
      {"order": 4, "action": "CLICK", "target": {"strategy":"ROLE","role":"button","name":"登录"}}
    ],
    "expectedOutcome": "跳转 /demo/dashboard",
    "assertions": [
      {"kind": "URL_CONTAINS", "expected": "/dashboard"}
    ]
  }]
}
```

### TestRun / CaseAttempt Model

- `rag_test_run`: status = CREATED → RUNNING → COMPLETED
- `rag_case_attempt`: UNIQUE(run_id, case_id, attempt_number) → retry 不覆盖
- 最终 outcome 基于**最新 attempt**（不是 #1）
- 服务端自动计算 attemptNumber（MAX + 1）

### Artifact Metadata

- `rag_artifact_meta`: metadata only
- Binary: Local FS `/onecase-artifacts/{runId}/{caseId}/{attempt}/{type}/{uuid}.{ext}`
- REST 上传 (multipart) → 服务端生成路径(防穿越)
- 支持 GET 回读

## 4. MCP Tools (11)

| Tool | Purpose |
|------|---------|
| `onecase_ping` | 健康检查 |
| `onecase_server_info` | 服务器元信息 |
| `onecase_search_knowledge` | RAG 检索历史 KB |
| `onecase_search_test_cases` | 检索历史 adopted cases |
| `onecase_generate_cases` | PRD → Canonical DSL |
| `onecase_validate_case` | DSL 校验 (server-side) |
| `onecase_adopt_cases` | 持久化 + 分配 stable caseId |
| `onecase_create_test_run` | 建 TestRun |
| `onecase_record_case_attempt` | 写 attempt + 推进 Run 状态机 |
| `onecase_diagnose_failure` | LLM 诊断失败 Attempt |
| `onecase_get_test_run` | 取完整 run 报告 |

## 5. RAG Pipeline

```
Current PRD (不写入向量库)
       ↓
Prompt Context (当前 Run 上下文)
       ↓
Historical KB (pgvector)
       ↓ Top-K
BAAI/bge-m3 Embedding (SiliconFlow)
       ↓
LLM (MiniMax-M3) → Canonical DSL v1.0.0
       ↓
DslValidator (JSON Schema + 业务语义)
       ↓
rag_test_case (结构化存储)
```

## 6. Browser Execution

```
DSL Step
  ↓
browser_snapshot (A11y tree)
  ↓
TargetResolver (TESTID → LABEL → ROLE → TEXT → CSS → XPATH)
  ↓
Playwright MCP callTool(ref)
  ↓
browser_verify_* (real assertion)
  ↓
browser_take_screenshot
  ↓
POST /api/v1/artifact/upload (REST multipart)
  ↓
Local FS + rag_artifact_meta
```

## 7. Failure Diagnosis

```
Attempt FAILED
  ↓
Failure Context {
  caseId, runId, attemptId,
  step, action, expected, actual,
  error, page URL, snapshot, evidenceRefs
}
  ↓
LLM (MiniMax-M3) with prompt
  ↓
FailureDiagnosis {
  category: TARGET_NOT_FOUND | ASSERTION_FAILED | PAGE_STATE_UNEXPECTED
             | INPUT_REJECTED | TIMEOUT | NETWORK_ERROR | APPLICATION_ERROR | UNKNOWN,
  summary, rootCause,
  confidence: HIGH | MEDIUM | LOW,
  suggestedRecovery: "<运行时建议,不动 DSL>"
}
  ↓
rag_failure_diag (持久化)
```

**不修改 TestCase**。Recovery 是 Runtime Capability,不动 DSL 静态内容。

## 8. Recovery (Tiered)

```
Level 0: Playwright native auto-wait
   ↓ fail
Level 1: Semantic (re-snapshot + TargetResolver)
   ↓ fail
Level 2: Vision (Phase 7+, stub)
   ↓ fail
Level 3: Human Escalation → case FAILED
```

每次 retry → 新 attempt (不覆盖)。

## 9. Demo

### Demo Web App (separate module `demo-web/`)

- `GET /demo/login` — 登录页 (username/password)
- `POST /demo/login` — 登录提交
- `GET /demo/dashboard` — 登录后页
- 演示账号: `test / test123`

### Demo PRD (`docs/demo/login-prd.md`)

完整 PRD,供 `onecase_generate_cases` 生成测试用例。

### 预期产出

| Case | 标题 | 预期 outcome |
|------|------|-------------|
| TC_LOGIN_001 | 正确用户名密码登录 | PASSED |
| TC_LOGIN_002 | 错误密码登录 | FAILED (错误提示 visible) |
| TC_LOGIN_003 | 空用户名/密码 | BLOCKED (HTML5 validation) |

## 10. Local Setup

### Prerequisites

- Java 17 (`brew install openjdk@17` 或 SDKMAN)
- Maven 3.9+
- Docker (for local PG + Redis)
- Node.js 18+ (for Playwright MCP)

### Start

```bash
# 1. Start backend services
docker compose up -d postgres redis

# 2. Build + Start OneCase (loads V2/V3 SQL migrations automatically)
mvn package -DskipTests
set -a && source .env && set +a
java -jar target/ai-rag-knowledge-2.0.jar

# 3. (separate terminal) Start Demo Web
mvn -f demo-web/pom.xml package -DskipTests
java -jar demo-web/target/demo-web-1.0.0.jar

# 4. (separate terminal) Start Playwright MCP
npx @playwright/mcp@latest --port 8931 --headless --caps=testing,devtools
```

### Test

```bash
# OneCase MCP health
curl -X POST http://localhost:8090/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# Demo Web
curl -I http://localhost:8081/demo/login

# Playwright MCP
curl -X POST http://localhost:8931/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## 11. Environment Variables

详见 `.env.example`。所有敏感信息(API Key、密码)必须通过 env 注入,不允许硬编码。

```bash
DB_URL=...
DB_USER=...
DB_PASSWORD=...
REDIS_URL=...
MINIMAX_BASE_URL=...
MINIMAX_API_KEY=...
MINIMAX_MODEL=...
SILICONFLOW_BASE_URL=...
SILICONFLOW_API_KEY=...
JINA_API_KEY=...
ARTIFACT_STORAGE_DIR=...
```

## 12. Docker Setup

```bash
docker compose up -d
# 启动: postgres + redis + onecase + demo-web + playwright-mcp
```

## 13. MCP Configuration for Multic

详见 `docs/agent-integration/multic.md`。注意:

- Multic 尚未部署
- 契约已完整,运行时验证 pending
- 提供 `mcp-config.example.json` 模板

## 14. Current Limitations (PENDING)

| 限制 | 状态 |
|------|------|
| Cloud E2E 验证 | **PENDING** (缺云端 PG/Redis/Playwright) |
| Multic Runtime 集成 | **PENDING** (Multic 未部署) |
| Phase 7 Vision Recovery | Stub (接口预留,实现未接) |
| Self-healing Case 更新 | 明确不做 (违反架构原则) |
| 外部 CUA (OpenAI / 豆包) | 不在 MVP 范围 |

## 15. Test Results

```
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
- DslValidatorTest:                    10/10
- RecordCaseAttemptUseCaseTest:        5/5
- ArtifactServiceTest:                  6/6
```

更多测试(MCP / Playwright client / Recovery / TargetResolver)在 Phase 2.5+ 持续补充。

## 16. Build

```bash
mvn compile -DskipTests       # compile
mvn test                      # 21/21 tests pass
mvn package -DskipTests       # build JAR
mvn -f demo-web/pom.xml package -DskipTests  # demo-web JAR
```

## 17. Project Layout

```
onecase/
├── src/main/java/cn/bugstack/rag/
│   ├── core/
│   │   ├── domain/           ← DSL + execution POJOs + enums
│   │   ├── port/             ← Repository + Service ports
│   │   └── usecase/          ← Business logic
│   ├── adapter/
│   │   ├── mcp/              ← MCP tool annotations + config
│   │   ├── persistence/      ← JdbcTemplate repos
│   │   ├── playwright/       ← Playwright MCP client + TargetResolver
│   │   └── web/              ← REST controllers (multipart + report)
│   └── config/               ← Spring AI + ChatModel configs
├── src/main/resources/
│   ├── db/migration/         ← V1 V2 V3 SQL DDL
│   └── schemas/canonical-test-case/v1.json
├── demo-web/                  ← Spring Boot demo (login + dashboard)
├── skills/web-test-agent/    ← Agent SKILL.md
├── docs/
│   ├── demo/login-prd.md
│   └── agent-integration/
│       ├── multic.md
│       └── mcp-config.example.json
├── scripts/                   ← E2E shell scripts (phase2.5, phase4)
├── Dockerfile
├── docker-compose.yml
├── .env.example
└── README.md
```

## 18. Status Summary

```
Phase 1 (Spring AI 1.1.8 migration)       : COMPLETE
Phase 1.5 (Canonical DSL)                 : COMPLETE
Phase 2 (9 MCP Tools)                     : COMPLETE
Phase 2.5 (Playwright MCP client + code)  : COMPLETE (local unit tests)
Phase 3 (Browser Runtime + TargetResolve): COMPLETE (code only)
Phase 4 (Vertical Slice logic)            : COMPLETE (code + CLI scripts)
Phase 5 (Failure Diagnosis integration)  : COMPLETE (code)
Phase 6 (Semantic Recovery Level 1)      : COMPLETE (code)
Phase 7 (Vision Recovery stub)          : INTERFACE ONLY
Phase 8 (REST Report API + ExecutionResult DTO) : COMPLETE
Phase 9 (Skill + Docker + CLI + README)  : COMPLETE
Cloud E2E validation                     : PENDING (no PG/Redis/Browser locally)
Multic Runtime integration              : PENDING (not deployed)
```

---

# OneCase Final Implementation Report

> **CODE IMPLEMENTATION = COMPLETE**
> **CLOUD E2E = PENDING**
> **MULTIC RUNTIME INTEGRATION = PENDING**

## 已完成 Phase

| Phase | 状态 |
|-------|------|
| Phase 1: Spring AI 1.1.8 + Spring Boot 3.5.16 迁移 | ✅ |
| Phase 1.5: Canonical DSL v1.0.0 + DslValidator | ✅ |
| Phase 2: 9 个核心 MCP Tools + 2 个基础 | ✅ |
| Phase 2.5: Playwright MCP Client + ArtifactService (代码) | ✅ |
| Phase 3: Browser Runtime + TargetResolver + ExecutionContext | ✅ |
| Phase 4: Vertical Slice (Generate → Adopt → Run → Execute → Record) | ✅ |
| Phase 5: Failure Diagnosis (LLM 集成) | ✅ |
| Phase 6: Semantic Recovery Level 1 | ✅ |
| Phase 7: Vision Recovery 接口 | ⚠️ stub |
| Phase 8: REST Report API + ExecutionResult DTO | ✅ |
| Phase 9: Skill + Docker + CLI + README | ✅ |

## 最终代码结构

- `src/main/java/cn/bugstack/rag/`：134 个 Java 文件
  - `core/domain/`：DSL v1 + execution (TestRun/Case/Attempt/Diagnosis/Artifact)
  - `core/port/`：Repository ports (TestCase/TestRun/Attempt/Diagnosis/Artifact/Playwright/Vision)
  - `core/usecase/`：10+ 个 UseCase
  - `adapter/mcp/`：11 个 @Tool 实现
  - `adapter/persistence/`：JdbcTemplate repos
  - `adapter/playwright/`：MCP client + TargetResolver + 编排
  - `adapter/web/`：REST (Artifact + Report)
- `demo-web/`：Spring Boot 独立模块
- `skills/web-test-agent/SKILL.md`：Agent Skill
- `docs/demo/login-prd.md` + `docs/agent-integration/`：文档
- `scripts/`：E2E shell 脚本
- `Dockerfile` + `demo-web/Dockerfile` + `docker-compose.yml`

## MCP Tools (11)

onecase_ping, onecase_server_info, onecase_search_knowledge, onecase_search_test_cases, onecase_generate_cases, onecase_validate_case, onecase_adopt_cases, onecase_create_test_run, onecase_record_case_attempt, onecase_diagnose_failure, onecase_get_test_run

## Browser Runtime

PlaywrightMcpClient (JSON-RPC over HTTP) → TargetResolver (TESTID/LABEL/ROLE/TEXT/CSS/XPATH) → BrowserExecutionService (DSL → browser actions) → StepResult + Artifact

## Artifact

`POST /api/v1/artifact/upload` (multipart) → 服务端生成 UUID 路径 → Local FS + rag_artifact_meta。防路径穿越,50MB 上限,白名单 Content-Type。

## TestRun / Attempt

- 状态机: CREATED → RUNNING → COMPLETED
- `UNIQUE(run_id, case_id, attempt_number)` 防覆盖
- 服务端计算 attemptNumber (MAX + 1)
- 最终 outcome 基于最新 attempt

## Failure Diagnosis

`onecase_diagnose_failure` Tool + `DiagnoseFailureUseCase` + `rag_failure_diag` 表。8 种闭集 category,3 种 confidence,**不修改 TestCase**。

## Recovery

SemanticRecoveryService Level 1: re-snapshot + TargetResolver 重解析 + retry (max 3) → 新 attempt。VisionRecoveryPort Level 2 接口预留。

## Skill

`skills/web-test-agent/SKILL.md` 完整描述 12 步工作流 + 组件边界 + Hard Rules + Recovery Policy。

## Demo

- Demo Web App: 登录页 + Dashboard(test/test123)
- Demo PRD: `docs/demo/login-prd.md` (供 generate_cases)
- 预期产出: 3 个 case (PASS / FAIL / BLOCKED)

## Docker

- `Dockerfile`：OneCase
- `demo-web/Dockerfile`：Demo Web
- `docker-compose.yml`：postgres + redis + onecase + demo-web + playwright-mcp
- `.env.example`：环境变量模板

## Tests

```
mvn test → 21/21 pass
- DslValidatorTest: 10
- RecordCaseAttemptUseCaseTest: 5
- ArtifactServiceTest: 6
```

## Build

```
mvn compile -DskipTests → BUILD SUCCESS (134 source files)
mvn package -DskipTests → ai-rag-knowledge-2.0.jar (122 MB)
mvn -f demo-web/pom.xml package → demo-web-1.0.0.jar (22 MB)
```

## 尚未验证的部分

- Cloud E2E (需要云端 PG + Redis + Playwright MCP + 浏览器)
- Multic Runtime 集成(需要部署 Multic 并加载 Skill)
- 真实 PRD → Test Case 的 LLM 生成 (需要 MiniMax key)
- Playwright MCP 截图实际接入 (需要本地 Playwright Chromium 安装)

## 下一步唯一剩余任务

1. **部署到云服务器**(本地已无 DB/Redis/Browser 资源)
2. 配置真实 env vars (MiniMax / SiliconFlow / Jina)
3. 执行 `scripts/phase2.5-e2e.sh` 和 `scripts/phase4-vertical-slice.sh` 完成运行时验证
4. 部署 Multic 加载 `skills/web-test-agent/SKILL.md` 完成 Agent 集成验证

---

**CODE IMPLEMENTATION = COMPLETE**
**CLOUD E2E = PENDING**
**MULTIC RUNTIME INTEGRATION = PENDING**