package cn.bugstack.rag.core.domain.execution;

public enum DiagnosisCategory {
    TARGET_NOT_FOUND,
    ASSERTION_FAILED,
    PAGE_STATE_UNEXPECTED,
    INPUT_REJECTED,
    TIMEOUT,
    NETWORK_ERROR,
    APPLICATION_ERROR,
    UNKNOWN
}