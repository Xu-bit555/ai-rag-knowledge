package cn.bugstack.rag.core.usecase;

import cn.bugstack.rag.core.domain.execution.CaseAttempt;
import cn.bugstack.rag.core.domain.execution.DiagnosisCategory;
import cn.bugstack.rag.core.domain.execution.DiagnosisConfidence;
import cn.bugstack.rag.core.domain.execution.FailureDiagnosis;
import cn.bugstack.rag.core.port.CaseAttemptRepositoryPort;
import cn.bugstack.rag.core.port.FailureDiagnosisRepositoryPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * DiagnoseFailureUseCase - 调用 LLM 诊断失败原因
 *
 * 输出:
 * - category (8 种枚举之一)
 * - summary
 * - rootCause
 * - confidence (HIGH/MEDIUM/LOW)
 * - suggestedRecovery (运行时建议,不动 DSL)
 *
 * 仅诊断,不修改 TestCase
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnoseFailureUseCase {

    private final CaseAttemptRepositoryPort caseAttemptRepository;
    private final FailureDiagnosisRepositoryPort failureDiagnosisRepository;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public FailureDiagnosis execute(String runId, String caseId, long attemptId,
                                   String failureContextJson) {

        CaseAttempt attempt = caseAttemptRepository.findByAttemptId(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Attempt not found: " + attemptId));

        String prompt = buildPrompt(caseId, attempt, failureContextJson);

        ChatResponse response;
        try {
            response = chatModel.call(new Prompt(prompt));
        } catch (Exception e) {
            log.error("LLM call failed for diagnosis: run={}, case={}, attempt={}",
                    runId, caseId, attemptId, e);
            // Fallback: 写 UNKNOWN 诊断,不阻塞 Agent
            return failureDiagnosisRepository.insert(
                    runId, caseId, attemptId,
                    DiagnosisCategory.UNKNOWN,
                    "LLM diagnosis failed: " + e.getMessage(),
                    e.getMessage(),
                    DiagnosisConfidence.LOW,
                    "Manual investigation required",
                    "{}");
        }

        AssistantMessage msg = response.getResult().getOutput();
        String raw = msg != null ? msg.getText() : "";
        log.debug("LLM diagnosis response length: {}", raw.length());

        ParsedDiagnosis parsed = parseDiagnosis(raw);
        return failureDiagnosisRepository.insert(
                runId, caseId, attemptId,
                parsed.category,
                parsed.summary,
                parsed.rootCause,
                parsed.confidence,
                parsed.suggestedRecovery,
                raw);
    }

    private String buildPrompt(String caseId, CaseAttempt attempt, String failureContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 任务\n你是 Web 测试自动化失败诊断专家。根据以下失败上下文,返回 JSON 诊断结果。\n\n");

        sb.append("# Case\n").append(caseId).append("\n\n");
        sb.append("# Attempt #").append(attempt.getAttemptNumber()).append("\n");
        sb.append("outcome: ").append(attempt.getOutcome()).append("\n");
        sb.append("errorSummary: ").append(attempt.getErrorSummary() != null ? attempt.getErrorSummary() : "(none)").append("\n");
        sb.append("executionData: ").append(attempt.getExecutionData() != null ? attempt.getExecutionData() : "(none)").append("\n\n");

        sb.append("# 失败上下文\n").append(failureContext != null ? failureContext : "(none)").append("\n\n");

        sb.append("# 输出格式 (严格 JSON)\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"category\": \"TARGET_NOT_FOUND | ASSERTION_FAILED | PAGE_STATE_UNEXPECTED | INPUT_REJECTED | TIMEOUT | NETWORK_ERROR | APPLICATION_ERROR | UNKNOWN\",\n");
        sb.append("  \"summary\": \"<一句话描述>\",\n");
        sb.append("  \"rootCause\": \"<详细根因分析>\",\n");
        sb.append("  \"confidence\": \"HIGH | MEDIUM | LOW\",\n");
        sb.append("  \"suggestedRecovery\": \"<运行时建议,例如:用 snapshot 找新 locator,不要修改 Case>\"\n");
        sb.append("}\n");
        sb.append("```\n");
        return sb.toString();
    }

    private ParsedDiagnosis parseDiagnosis(String raw) {
        ParsedDiagnosis p = new ParsedDiagnosis();
        p.category = DiagnosisCategory.UNKNOWN;
        p.confidence = DiagnosisConfidence.LOW;
        p.summary = "Diagnosis parse failed";
        p.rootCause = raw.length() > 500 ? raw.substring(0, 500) : raw;
        p.suggestedRecovery = "Manual investigation required";

        try {
            String clean = raw;
            int first = clean.indexOf('{');
            int last = clean.lastIndexOf('}');
            if (first >= 0 && last > first) {
                clean = clean.substring(first, last + 1);
            }
            JsonNode node = objectMapper.readTree(clean);

            String cat = textOrNull(node, "category");
            if (cat != null) {
                try { p.category = DiagnosisCategory.valueOf(cat); } catch (Exception ignore) {}
            }
            p.summary = textOrNull(node, "summary");
            p.rootCause = textOrNull(node, "rootCause");
            p.suggestedRecovery = textOrNull(node, "suggestedRecovery");

            String conf = textOrNull(node, "confidence");
            if (conf != null) {
                try { p.confidence = DiagnosisConfidence.valueOf(conf); } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            log.warn("Failed to parse LLM diagnosis JSON, fallback to UNKNOWN", e);
        }
        return p;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static class ParsedDiagnosis {
        DiagnosisCategory category;
        String summary;
        String rootCause;
        DiagnosisConfidence confidence;
        String suggestedRecovery;
    }
}