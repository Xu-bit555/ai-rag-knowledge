package cn.bugstack.rag.evaluation.pipeline;

import cn.bugstack.rag.evaluation.dataset.SmokeDatasetBuilder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * FrozenCandidateSetGenerator 的命令行 wrapper：
 *   java -cp ... FrozenCandidateSetGeneratorStandalone <dataset> <e1> <e2>
 */
public final class FrozenCandidateSetGeneratorStandalone {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: FrozenCandidateSetGeneratorStandalone <dataset.jsonl> <e1.jsonl> <e2.jsonl>");
            System.exit(2);
        }
        Path dataset = Paths.get(args[0]);
        Path e1 = Paths.get(args[1]);
        Path e2 = Paths.get(args[2]);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        new FrozenCandidateSetGenerator().generate(
                dataset, e1, e2, /*candidateK*/ 30, "e1_smoke_" + ts, "e2_smoke_" + ts);
        System.out.println("[FrozenCandidateSetGenerator] → " + e1 + ", " + e2);
    }
}