#!/usr/bin/env bash
# run_all.sh — 端到端 smoke run（per plan §八 §二十五）。
#
# 阶段：
#   1. prepare dataset
#   2. freeze retrieval (E1 + E2)
#   3. run A/B/C groups on E1 + E2
#   4. compute metrics + error analysis
#   5. generate report
#
# 必填环境变量：
#   AI_GATEWAY_API_KEY    Jev 调用（Vercel AI Gateway）
# 可选环境变量：
#   JINA_API_KEY          缺失则 Jina 走 degrade 模式（fallback retrieval_score DESC）
#   EVAL_DIR               默认 ./evaluation
#
# 用法：
#   export AI_GATEWAY_API_KEY=...
#   ./evaluation/scripts/run_all.sh
set -euo pipefail

cd "$(dirname "$0")/../.."

export EVAL_DIR="${EVAL_DIR:-./evaluation}"

echo "=== run_all.sh: starting at $(date) ==="
echo "EVAL_DIR = $EVAL_DIR"

# 0. compile
mvn -q -DskipTests compile

# 1. prepare dataset
DATASET="$EVAL_DIR/datasets/v1.jsonl"
echo "[1/5] preparing dataset → $DATASET"
mvn -q exec:java \
  -Dexec.mainClass=cn.bugstack.rag.evaluation.dataset.SmokeDatasetBuilderStandalone \
  -Dexec.classpathScope=runtime \
  -Dexec.args="$DATASET"

# 2. freeze retrieval
RETRIEVAL_E1="$EVAL_DIR/retrieval/frozen_candidates_e1.jsonl"
RETRIEVAL_E2="$EVAL_DIR/retrieval/frozen_candidates_e2.jsonl"
echo "[2/5] freezing retrieval → E1 + E2"
mvn -q exec:java \
  -Dexec.mainClass=cn.bugstack.rag.evaluation.pipeline.FrozenCandidateSetGeneratorStandalone \
  -Dexec.classpathScope=runtime \
  -Dexec.args="$DATASET $RETRIEVAL_E1 $RETRIEVAL_E2"

# 3. run experiment
echo "[3/5] running experiment (A/B/C × E1/E2)..."
mvn -q exec:java \
  -Dexec.mainClass=cn.bugstack.rag.evaluation.SmokeMain \
  -Dexec.classpathScope=runtime

# 4. metrics + error analysis 已经在 SmokeMain 内部跑完（输出到 metrics.json）

# 5. report 已经在 SmokeMain 内部生成（output 到 reports/jev-ab-report.md）
echo "[5/5] report generated → $EVAL_DIR/reports/jev-ab-report.md"

echo "=== run_all.sh: done at $(date) ==="