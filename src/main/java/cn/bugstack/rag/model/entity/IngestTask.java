package cn.bugstack.rag.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 摄入任务模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 知识库标签
     */
    private String ragTag;

    /**
     * 状态：PENDING-待处理 PROCESSING-处理中 COMPLETED-已完成 FAILED-失败
     */
    private TaskStatus status;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 文件路径
     */
    private String filePath;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 任务状态枚举
     */
    public enum TaskStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    /**
     * 创建待处理任务
     */
    public static IngestTask createPending(String taskId, String ragTag, String filePath) {
        return IngestTask.builder()
                .taskId(taskId)
                .ragTag(ragTag)
                .status(TaskStatus.PENDING)
                .filePath(filePath)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 标记为处理中
     */
    public void markProcessing() {
        this.status = TaskStatus.PROCESSING;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 标记为完成
     */
    public void markCompleted() {
        this.status = TaskStatus.COMPLETED;
        this.updatedAt = LocalDateTime.now();
        this.errorMessage = null;
    }

    /**
     * 标记为失败
     */
    public void markFailed(String errorMessage) {
        this.status = TaskStatus.FAILED;
        this.updatedAt = LocalDateTime.now();
        this.errorMessage = errorMessage != null && errorMessage.length() > 2000
                ? errorMessage.substring(0, 2000)
                : errorMessage;
    }

}
