package cn.bugstack.rag.evaluation.dataset;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * SmokeDatasetBuilder 的命令行 wrapper：java -cp ... SmokeDatasetBuilderStandalone <output-path>
 */
public final class SmokeDatasetBuilderStandalone {
    public static void main(String[] args) throws Exception {
        Path out = args.length > 0 ? Paths.get(args[0]) : Paths.get("evaluation/datasets/v1.jsonl");
        SmokeDatasetBuilder.write(out);
        System.out.println("[SmokeDatasetBuilder] → " + out);
    }
}