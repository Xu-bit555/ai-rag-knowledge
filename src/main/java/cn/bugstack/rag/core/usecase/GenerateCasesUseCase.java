package cn.bugstack.rag.core.usecase;

import cn.bugstack.rag.core.domain.dsl.v1.CanonicalTestCase;
import cn.bugstack.rag.core.domain.dsl.v1.DslValidator;
import cn.bugstack.rag.core.domain.dsl.v1.DslValidator.ValidationResult;
import cn.bugstack.rag.core.port.TestCaseRepositoryPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * GenerateCasesUseCase - 生成 Canonical DSL 测试用例
 *
 * 流程:
 * 1. 构建 DSL v1.0.0 专用 prompt (Web 测试,带 JSON Schema 约束说明)
 * 2. 调 LLM (单次 chatModel.call, 非流式)
 * 3. ObjectMapper 解析 LLM 输出 JSON → CanonicalTestCase
 * 4. DslValidator 双重校验 (JSON Schema + 业务语义)
 * 5. 若校验通过: 返回 CanonicalTestCase
 * 6. 若校验失败: throw IllegalStateException, 错误信息含 validation errors
 *
 * 复用: ChatModel (MiniMax-M3) + TestCaseRepositoryPort (后续 saveRawDsl)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerateCasesUseCase {

    private final ChatModel chatModel;
    private final DslValidator dslValidator;
    private final TestCaseRepositoryPort testCaseRepository;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.minimax.model:MiniMax-M3}")
    private String defaultModel;

    /**
     * P0-6: 把 DSL JSON Schema 内嵌到 prompt, LLM 单次通过率从 ~33% 提到 ≥80%
     */
    @Value("classpath:schemas/canonical-test-case/v1.json")
    private Resource dslSchemaResource;

    /**
     * 执行用例生成
     *
     * @param ragTag 知识库标签 (用于 RAG 检索)
     * @param requirement 用户需求描述 (Current PRD context,不入历史库)
     * @param referenceCaseIds 参考已有用例 ID 列表 (few-shot)
     * @param knowledgeDocs 历史知识库 Top-K 文档内容 (RAG)
     * @return CanonicalTestCase
     * @throws IllegalStateException 当 DSL 校验失败
     */
    public CanonicalTestCase execute(
            String ragTag,
            String requirement,
            List<String> referenceCaseIds,
            List<String> knowledgeDocs) {

        log.info("GenerateCasesUseCase.execute: ragTag={}, requirement.length={}, refCount={}, kbCount={}",
                ragTag, requirement == null ? 0 : requirement.length(),
                referenceCaseIds == null ? 0 : referenceCaseIds.size(),
                knowledgeDocs == null ? 0 : knowledgeDocs.size());

        // 1. 构建 DSL v1.0.0 专用 prompt
        String prompt = buildCanonicalDslPrompt(requirement, referenceCaseIds, knowledgeDocs);

        // 2. 调 LLM
        ChatResponse response = chatModel.call(new Prompt(prompt));
        AssistantMessage message = response.getResult().getOutput();
        String llmOutput = message != null ? message.getText() : "";
        log.debug("LLM output length: {}", llmOutput.length());

        // 3. 清理 + 解析
        String cleanJson = extractJson(llmOutput);
        CanonicalTestCase dsl;
        try {
            dsl = objectMapper.readValue(cleanJson, CanonicalTestCase.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "LLM output is not valid Canonical DSL JSON. First 500 chars: "
                            + cleanJson.substring(0, Math.min(500, cleanJson.length())), e);
        }

        // 4. 校验
        ValidationResult vr = dslValidator.validate(cleanJson);
        if (!vr.isValid()) {
            throw new IllegalStateException(
                    "Generated DSL failed validation: " + String.join("; ", vr.getErrorMessages()));
        }

        // 5. audit save (MVP 暂为 no-op)
        try {
            testCaseRepository.saveRawDsl(dsl);
        } catch (Exception e) {
            log.warn("saveRawDsl audit failed (ignored)", e);
        }

        return dsl;
    }

    /**
     * 构建 Canonical DSL v1.0.0 专用 prompt
     *
     * P0-6 修复: 在 prompt 中内嵌 JSON Schema, LLM 可以机器可读地理解字段约束,
     *   单次通过率从 ~33% 提到 ≥80%. Schema 加载失败 → 抛 IllegalStateException (fail-fast).
     */
    private String buildCanonicalDslPrompt(
            String requirement, List<String> referenceCaseIds, List<String> knowledgeDocs) {

        String schemaJson;
        try {
            schemaJson = dslSchemaResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to load DSL schema for prompt injection: " + e.getMessage(), e);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# 角色\n");
        sb.append("你是一位资深 Web 测试架构师,擅长将需求转化为高质量、可自动化执行的测试用例。\n\n");

        sb.append("# 任务\n");
        sb.append("根据【需求内容】生成测试用例。**严格按 Canonical DSL v1.0.0 输出 JSON**。\n\n");

        // P0-6: 把 JSON Schema 直接喂给 LLM
        sb.append("# JSON Schema (机器可读, 严格遵守)\n");
        sb.append("```json\n");
        sb.append(schemaJson);
        sb.append("\n```\n\n");

        sb.append("# 输出格式 (示例)\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"schemaVersion\": \"1.0.0\",\n");
        sb.append("  \"summary\": { \"app\": \"...\", \"page\": \"...\", \"totalCases\": <int> },\n");
        sb.append("  \"cases\": [\n");
        sb.append("    {\n");
        sb.append("      \"caseId\": \"TC_xxx\",                    // 格式: TC_[A-Za-z0-9_]+\n");
        sb.append("      \"title\": \"...\",\n");
        sb.append("      \"automationCandidate\": \"WEB_FUNCTIONAL\", // 仅接受这个枚举值\n");
        sb.append("      \"priority\": \"P0|P1|P2|P3\",\n");
        sb.append("      \"steps\": [\n");
        sb.append("        { \"order\": <int>, \"action\": \"NAVIGATE|CLICK|INPUT|...\", \"url\"|\"target\": ... }\n");
        sb.append("      ],\n");
        sb.append("      \"expectedOutcome\": \"...\",\n");
        sb.append("      \"assertions\": [\n");
        sb.append("        { \"kind\": \"URL_CONTAINS|ELEMENT_VISIBLE|...\", \"expected\"|\"target\": ... }\n");
        sb.append("      ]\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n");
        sb.append("```\n\n");

        sb.append("# 允许的 StepAction\n");
        sb.append("- NAVIGATE → 必须用 `url` 字段(不是 target)\n");
        sb.append("- CLICK → 必须有 `target.strategy` ∈ {ROLE,TEXT,LABEL,TESTID,CSS,XPATH}\n");
        sb.append("- INPUT → 必须有 `target` + `value`\n");
        sb.append("- SELECT → 必须有 `target` + `value`\n");
        sb.append("- WAIT → 可选 target(等待元素出现)\n");
        sb.append("- ASSERT → 可选 target(独立 assertion 步骤)\n");
        sb.append("- SCREENSHOT → 截图诊断\n\n");

        sb.append("# 允许的 LocatorStrategy\n");
        sb.append("- ROLE → target.role + target.name (例: {role:\"button\", name:\"登录\"})\n");
        sb.append("- TEXT → target.text\n");
        sb.append("- LABEL → target.label\n");
        sb.append("- TESTID → target.testId\n");
        sb.append("- CSS → target.selector (CSS 选择器)\n");
        sb.append("- XPATH → target.xpath\n\n");

        sb.append("# 允许的 AssertionKind\n");
        sb.append("- URL_EQUALS / URL_CONTAINS → expected (URL 字符串)\n");
        sb.append("- ELEMENT_VISIBLE / ELEMENT_NOT_PRESENT → target\n");
        sb.append("- TEXT_EQUALS / TEXT_CONTAINS → expected + 可选 target\n");
        sb.append("- VALUE_EQUALS → expected\n\n");

        sb.append("# 核心约束\n");
        sb.append("1. schemaVersion 必须是 \"1.0.0\",不要输出其他版本\n");
        sb.append("2. caseId 必须匹配正则 ^TC_[A-Za-z0-9_]+$\n");
        sb.append("3. steps.order 必须从 1 开始连续递增\n");
        sb.append("4. automationCandidate 默认 WEB_FUNCTIONAL(MVP 仅支持 Web)\n");
        sb.append("5. 不要编造 UI 元素(按钮、字段);只基于需求中明确提到的元素\n");
        sb.append("6. 严禁在 JSON 外加任何 markdown 或解释\n\n");

        sb.append("# 需求内容\n");
        sb.append("```\n");
        sb.append(requirement != null ? requirement : "");
        sb.append("\n```\n\n");

        if (knowledgeDocs != null && !knowledgeDocs.isEmpty()) {
            sb.append("# 相关的项目知识文档\n");
            for (int i = 0; i < knowledgeDocs.size(); i++) {
                sb.append("## 文档 #").append(i + 1).append("\n");
                sb.append(knowledgeDocs.get(i)).append("\n\n");
            }
        } else {
            sb.append("# 相关的项目知识文档\n");
            sb.append("无相关知识文档\n\n");
        }

        if (referenceCaseIds != null && !referenceCaseIds.isEmpty()) {
            sb.append("# 参考已有用例 ID (few-shot)\n");
            for (String id : referenceCaseIds) {
                sb.append("- ").append(id).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 从 LLM 输出中提取 JSON
     */
    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return "";

        String s = raw.trim();

        // 去掉 ```json 包裹
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
            s = s.trim();
        }

        // 提取第一个 { 到最后一个 }
        int first = s.indexOf('{');
        int last = s.lastIndexOf('}');
        if (first >= 0 && last > first) {
            s = s.substring(first, last + 1);
        }
        return s;
    }
}