package cn.bugstack.rag.service;

/**
 * 需求提炼服务接口
 */
public interface RequirementExtractService {

    String buildExtractPrompt(String rawContent);

    java.util.List<String> parseExtractedScenarios(String rawResult);
}