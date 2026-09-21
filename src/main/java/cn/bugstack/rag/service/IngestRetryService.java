package cn.bugstack.rag.service;

import cn.bugstack.rag.config.RedisStreamConfigProperties;
import cn.bugstack.rag.model.entity.IngestTask;
import cn.bugstack.rag.model.response.Response;
import cn.bugstack.rag.repository.IIngestTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.springframework.stereotype.Service;

/**
 * P0-9: 摄入任务重试服务 - 从 DLQ 回放到主 stream
 *
 * 用法:
 *   POST /api/v1/knowledge/ingest/retry?taskId=xxx
 *   POST /api/v1/knowledge/ingest/retry-all
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestRetryService {

    private final RedissonClient redissonClient;
    private final RedisStreamConfigProperties streamConfig;
    private final IIngestTaskRepository ingestTaskRepository;

    /**
     * 重试单个失败任务
     */
    public Response<String> retry(String taskId) {
        IngestTask task = ingestTaskRepository.findById(taskId);
        if (task == null) {
            return Response.error("404", "Task not found: " + taskId);
        }
        if (task.getStatus() != IngestTask.TaskStatus.FAILED) {
            return Response.error("400", "Task is not in FAILED status: " + task.getStatus());
        }

        // 重新发布到主 stream (重置 retryCount=0)
        RStream<String, String> mainStream = redissonClient.getStream(streamConfig.getStreamKey());
        mainStream.add(StreamAddArgs.<String, String>entries(
                "taskId", taskId,
                "ragTag", task.getRagTag() != null ? task.getRagTag() : "",
                "retryCount", "0"));

        // 状态改回 PENDING 让 consumer 重新消费
        ingestTaskRepository.updateStatus(taskId, IngestTask.TaskStatus.PENDING, null);

        log.info("Task retried from DLQ: taskId={}", taskId);
        return Response.ok("Task " + taskId + " re-queued to main stream");
    }

    /**
     * 批量重试 DLQ 中的所有任务 (单次最多 100 条)
     */
    public Response<Integer> retryAll() {
        RStream<String, String> dlq = redissonClient.getStream(streamConfig.getDlqStreamKey());
        int count = 0;
        for (var entry : dlq.entryRange(0, 100)) {
            String taskId = entry.getValue().get("taskId");
            if (taskId != null) {
                Response<String> r = retry(taskId);
                if ("200".equals(r.getCode())) {
                    count++;
                    dlq.remove(entry.getId());
                }
            }
        }
        log.info("Batch retried {} tasks from DLQ", count);
        return Response.ok(count);
    }
}
