package cn.bugstack.rag.evaluation.pipeline;

import cn.bugstack.rag.model.dto.DocumentWithScoreDTO;
import cn.bugstack.rag.repository.IVectorStoreRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 冻结 retrieval candidate set（per plan §五 / §二）。
 *
 * E1: Vector Retrieval → Deduplication → MMR(λ=0.5) → Frozen Top-K
 * E2: Vector Retrieval → Deduplication → Frozen Top-K
 *
 * 关键约束：
 * - retrieval 只跑一次
 * - dedup 输出复用 production 接口 similaritySearchWithScoreWithDeduplication
 * - MMR 算法与 RerankServiceImpl.applyMMRWithScores（line 178-241）一致
 *   （用 char-Jaccard similarity，selectCount = K 而非 topN）
 *
 * 注意：production MMR 默认 selectCount=topN=5。为冻结 30 个候选，
 * 这里**临时** selectCount=K（不动 production 代码）。
 */
public final class FrozenRetrievalRunner {

    public static final double MMR_LAMBDA = 0.5;

    private final IVectorStoreRepository vectorStoreRepository;

    public FrozenRetrievalRunner(IVectorStoreRepository vectorStoreRepository) {
        if (vectorStoreRepository == null)
            throw new IllegalArgumentException("vectorStoreRepository must not be null");
        this.vectorStoreRepository = vectorStoreRepository;
    }

    /**
     * E1: dedup + MMR
     */
    public List<RetrievalCandidate> freezeE1(String query, String ragTag, int topK) {
        if (query == null || query.isBlank())
            throw new IllegalArgumentException("query must not be blank");
        if (topK < 1) throw new IllegalArgumentException("topK must be >= 1");

        List<DocumentWithScoreDTO> retrieved = vectorStoreRepository
                .similaritySearchWithScoreWithDeduplication(List.of(query), ragTag, topK);
        List<RetrievalCandidate> candidates = toCandidates(retrieved);
        return applyMMR(candidates, MMR_LAMBDA, Math.min(topK, candidates.size()));
    }

    /**
     * E2: dedup only（passthrough）
     */
    public List<RetrievalCandidate> freezeE2(String query, String ragTag, int topK) {
        if (query == null || query.isBlank())
            throw new IllegalArgumentException("query must not be blank");
        if (topK < 1) throw new IllegalArgumentException("topK must be >= 1");

        List<DocumentWithScoreDTO> retrieved = vectorStoreRepository
                .similaritySearchWithScoreWithDeduplication(List.of(query), ragTag, topK);
        return toCandidates(retrieved);
    }

    /**
     * MMR 多样性选择（per plan §二 E1）。
     *
     * 算法对照 RerankServiceImpl.applyMMRWithScores（line 178-241）：
     * - 第一个选 score 最高的
     * - 后续选 MMR 最大的：λ · score − (1−λ) · max_sim_to_selected
     * - similarity = char-Jaccard（无 metadata 时回退到 plain text）
     *
     * 输入 candidates 已按 retrieval score DESC 排好（来自 dedup）。
     * 输出 candidates 保持 ragRank 表示"retrieval 原始 rank"。
     * 选中的顺序用 newRank 表示（写在 RankedItem 里），此处只保证内容。
     */
    public static List<RetrievalCandidate> applyMMR(List<RetrievalCandidate> input, double lambda, int selectCount) {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        if (selectCount < 1) throw new IllegalArgumentException("selectCount must be >= 1");
        if (input.size() <= selectCount) {
            return new ArrayList<>(input);
        }

        // char-Jaccard similarity（与 production 一致：取字母+数字字符 lowercase 后 Jaccard）
        List<RetrievalCandidate> selected = new ArrayList<>();
        List<RetrievalCandidate> remaining = new ArrayList<>(input);

        // 第一个：score 最高的（已在 input 中按 score DESC）
        RetrievalCandidate first = remaining.remove(0);
        selected.add(first);

        // 后续：MMR 最大
        while (selected.size() < selectCount && !remaining.isEmpty()) {
            double bestMmr = Double.NEGATIVE_INFINITY;
            int bestIdx = -1;
            for (int i = 0; i < remaining.size(); i++) {
                RetrievalCandidate cand = remaining.get(i);
                double maxSim = 0.0;
                for (RetrievalCandidate s : selected) {
                    double sim = jaccardSimilarity(cand.text(), s.text());
                    if (sim > maxSim) maxSim = sim;
                }
                double mmr = lambda * cand.retrievalScore() - (1.0 - lambda) * maxSim;
                if (mmr > bestMmr) {
                    bestMmr = mmr;
                    bestIdx = i;
                }
            }
            if (bestIdx < 0) break;
            selected.add(remaining.remove(bestIdx));
        }

        // 选中的 candidates 仍保留 ragRank（retrieval 原 rank）用于跨 group 关联
        return selected;
    }

    private static List<RetrievalCandidate> toCandidates(List<DocumentWithScoreDTO> docs) {
        List<RetrievalCandidate> out = new ArrayList<>(docs.size());
        // dedup 输出已按 score DESC 排序，这里 ragRank = 1..N
        for (int i = 0; i < docs.size(); i++) {
            DocumentWithScoreDTO d = docs.get(i);
            out.add(RetrievalCandidate.of(d.getContent(), d.getScore(), i + 1));
        }
        return out;
    }

    /**
     * char-Jaccard similarity：与 RerankServiceImpl.calculateSimilarity（line 293-351）
     * 对 plain text 的回退路径一致（取字母+数字字符 lowercase 后 Jaccard）。
     */
    public static double jaccardSimilarity(String text1, String text2) {
        if (text1 == null || text2 == null) return 0.0;
        if (text1.equals(text2)) return 1.0;
        Set<Character> s1 = new HashSet<>();
        Set<Character> s2 = new HashSet<>();
        for (char c : text1.toCharArray()) {
            if (Character.isLetterOrDigit(c)) s1.add(Character.toLowerCase(c));
        }
        for (char c : text2.toCharArray()) {
            if (Character.isLetterOrDigit(c)) s2.add(Character.toLowerCase(c));
        }
        if (s1.isEmpty() && s2.isEmpty()) return 0.0;
        Set<Character> inter = new HashSet<>(s1);
        inter.retainAll(s2);
        Set<Character> union = new HashSet<>(s1);
        union.addAll(s2);
        return (double) inter.size() / union.size();
    }
}