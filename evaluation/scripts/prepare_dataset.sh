#!/usr/bin/env bash
# prepare_dataset.sh
# 生成 evaluation/datasets/v1.jsonl（per plan §三 / §四）。
#
# 数据来自：
#   • RerankModelComparisonTest 现有 5 条手工 case（query 文本）
#   • 用户手工构造的 5 条新 query（覆盖 mixed_zh_en / no_answer / long_context 等）
#   • 全部基于 OneCase 真实知识库片段（Redis Cluster、Spring Boot、Apple、客服 SLA、密码规则）
#   • 4 级 label（0=irrelevant / 1=tangential / 2=useful / 3=directly_answers）由人工定义
#
# 用法：
#   ./evaluation/scripts/prepare_dataset.sh
set -euo pipefail

cd "$(dirname "$0")/../.."

EVAL_DIR="${EVAL_DIR:-./evaluation}"
DATASET="$EVAL_DIR/datasets/v1.jsonl"

echo "[prepare_dataset] generating $DATASET ..."
mkdir -p "$(dirname "$DATASET")"

JAVA_OPTS="${JAVA_OPTS:-}"
mvn -q -DskipTests compile
mvn -q exec:java \
  -Dexec.mainClass=cn.bugstack.rag.evaluation.dataset.SmokeDatasetBuilderStandalone \
  -Dexec.classpathScope=runtime \
  -Dexec.args="$DATASET"

echo "[prepare_dataset] done → $DATASET"
echo "[prepare_dataset] lines: $(wc -l < "$DATASET")"