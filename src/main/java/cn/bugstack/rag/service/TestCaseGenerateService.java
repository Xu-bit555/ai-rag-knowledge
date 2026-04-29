package cn.bugstack.rag.service;

import java.util.List;

/**
 * 测试用例生成服务接口
 */
public interface TestCaseGenerateService {

    String buildGeneratePrompt(String requirement, List<String> referenceCases);

    String buildGeneratePrompt(String requirement, List<String> referenceCases, List<String> knowledgeDocs);
}