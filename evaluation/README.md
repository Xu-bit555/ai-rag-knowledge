# Jev Reranker A/B Evaluation Pipeline

> **状态**：Phase 1 全部实现完毕；smoke 测试 22/22 通过；端到端 smoke run 由 `run_all.sh` 触发。
> **核心原则**：零修改 production 代码；A/B/C 共享同一冻结 candidate set；不下结论只列事实。

---

## 实验架构

```
Query (10 条 smoke)
    │
    ▼
[Stage 1] prepare_dataset
    evaluation/datasets/v1.jsonl
    │
    ▼
[Stage 2] freeze_retrieval
    ├─ E1: dedup + MMR(λ=0.5) → frozen_candidates_e1.jsonl
    └─ E2: dedup only          → frozen_candidates_e2.jsonl
    │
    ▼
[Stage 3] run experiment
    ├─ E1.A  JinaRerankAdapter    → baseline_e1_results.jsonl
    ├─ E1.B1 JevRerankAdapter (choice)  → jev_e1_results.jsonl
    ├─ E1.B2 JevRerankAdapter (expected) → jev_e1_results.jsonl
    ├─ E1.B3 JevRerankAdapter (normalized) → jev_e1_results.jsonl
    ├─ E1.C  NoOpRerankAdapter   → no_rerank_e1_results.jsonl
    └─ E2.*  (同上)
    │
    ▼
[Stage 4] compute metrics
    evaluation/results/metrics_*.json + error_analysis.json
    │
    ▼
[Stage 5] generate report
    evaluation/reports/jev-ab-report.md
```

## A/B/C/B1/B2/B3 定义

| 组 | Reranker | 行为 |
|---|---|---|
| A | Jina (`jina-reranker-v1-base-en`) | 完全复现 production `jinaRerankWithScore` |
| B1 | Jev (`typesafe-ai/jev`) | `score = P(directly_answers)` |
| B2 | Jev | `score = 3·P(DA) + 2·P(U) + 1·P(T) + 0·P(I)` |
| B3 | Jev | `score = score_expected / 3` |
| C | 无 rerank | **保持 retrieval 原始 rank**（不重排） |

**B1/B2/B3 共享同一个 Jev response**，不重复调用 API。

## 跑实验

```bash
export AI_GATEWAY_API_KEY="vck_..."   # 必填
# export JINA_API_KEY="..."            # 可选；缺失则 degrade
./evaluation/scripts/run_all.sh
```

输出：
- `evaluation/datasets/v1.jsonl` — 10 条 query + ground truth（4 级 label + provenance）
- `evaluation/retrieval/frozen_candidates_e1.jsonl` / `_e2.jsonl`
- `evaluation/results/baseline_e1_results.jsonl` / `jev_e1_results.jsonl` / `no_rerank_e1_results.jsonl` / `_e2_*.jsonl`
- `evaluation/cache/jev/*.json` — SHA256-keyed Jev response cache
- `evaluation/reports/jev-ab-report.md` — 最终报告

## 单元测试

```bash
mvn test -Dtest='cn.bugstack.rag.evaluation.Eval*Test'
```

22 个测试覆盖：
- `EvalMetricsTest` — Recall/Precision/MRR/nDCG/DropRate/FP/FN 手算对照
- `EvalJevCacheTest` — cache hit/miss/hitRate
- `EvalJevScoringStrategyTest` — B1/B2/B3 公式 + 边界（all-DA / all-irrelevant）
- `EvalErrorAnalysisTest` — 5 类 case 分类
- `EvalMMRRunnerTest` — jaccard 对称性 + MMR 多样性选择
- `EvalAdaptersTest` — NoOp 保持原序 / Jina 缺 API key 抛错
- `EvalSmokeRunnerTest` — dataset + frozen set 生成 + pipeline sanity

## 设计约束（per plan §二十六）

### 不修改的 production 文件

- `RerankService.java` / `RerankServiceImpl.java`
- `VectorStoreRepositoryImpl.java`
- `SearchKnowledgeUseCase.java` / `SearchTestCasesUseCase.java`
- `VectorStoreConfig.java`
- `RerankModelComparisonTest.java`（保留原状）
- `application*.yml` / `pom.xml`

### 关键调用规则

- ❌ 不调 `RerankServiceImpl.rerank()`（内部耦合 retrieval，违反"冻结 candidate"）
- ✅ 直接调 `vectorStoreRepository.similaritySearchWithScoreWithDeduplication`（仅在 `FrozenRetrievalRunner` 中）— **当前 smoke 阶段未启用**（dataset 已含 hand-crafted candidates）
- ✅ Jina HTTP 调用复制 `jinaRerankWithScore` 逻辑
- ✅ Jev HTTP 调用 `JevClient`（新增独立 client）

### Security

- API Key 只从环境变量读
- 日志不含 API Key
- `evaluation/cache/`、`evaluation/results/`、`evaluation/retrieval/` 全部 `.gitignore`

## Smoke 验收标准（per plan §十）

| # | 验证项 | 实现位置 |
|---|---|---|
| 1 | Jev HTTP 调用解析 | `EvalJevScoringStrategyTest`、`JevClient` |
| 2 | Jev cache hit/miss | `EvalJevCacheTest` |
| 3 | API Key 不泄露 | `JevClient` / `JevCache` / `JinaRerankAdapter` 只读 env |
| 4 | Frozen Candidate Set schema | `FrozenCandidatePersister` (三 ID 分离) |
| 5 | E1 vs E2 冻结集不同 | `FrozenCandidateSetGenerator` |
| 6 | A/B/C 三组跑通 | `ExperimentRunner.runSmoke` |
| 7 | Ranking 数量正确 | `NoOpRerankAdapter.rerank` |
| 8 | Ranking 无重复 | `EvalAdaptersTest.rankingHasNoDuplicates` |
| 9 | Ground Truth 映射 | `EvalSmokeRunnerTest.datasetHas10QueriesAndGroundTruth` |
| 10 | C 组保持原序 | `EvalAdaptersTest.noOpRerankPreservesOriginalRank` |
| 11 | B 组三 score 同时存在 | `EvalJevScoringStrategyTest.switchByStrategy` |
| 12 | Metrics 与手算一致 | `EvalMetricsTest` |
| 13 | Retrieval Failure 识别 | `EvalMetricsTest.retrievalFailureDetection` |
| 14 | Error Analysis 5 类 | `EvalErrorAnalysisTest` |
| 15 | Jina Adapter 复现 production | `JinaRerankAdapter`（字段对照 `RerankServiceImpl.jinaRerankWithScore` line 246-288） |
| 16 | Reproducibility 字段齐全 | `ResultsPersister`（9 顶层字段） |
| 17 | .gitignore 生效 | `evaluation/` 全部加入 |

## Phase 2（不在本次范围）

- ❌ E2E RAG Answer Quality（避免 Jev 同时作为 Treatment 和 Judge）
- ❌ 替换生产 `RerankServiceImpl`
- ❌ 三种 Jev 并发策略对比（sequential / concurrent / batch）
- ❌ 其他 reranker（BGE / Cohere / cohere-rerank-v3.5）
- ❌ 部署 / CI 集成