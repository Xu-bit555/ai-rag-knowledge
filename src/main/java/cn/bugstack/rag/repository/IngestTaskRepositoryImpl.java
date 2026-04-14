package cn.bugstack.rag.repository;

import cn.bugstack.rag.model.entity.IngestTask;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 摄入任务仓储实现
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class IngestTaskRepositoryImpl implements IIngestTaskRepository {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        log.info("开始初始化 rag_ingest_task 表...");
        try {
            // 创建摄入任务表
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS rag_ingest_task (" +
                    "task_id varchar(64) primary key, " +
                    "rag_tag varchar(128) not null, " +
                    "status varchar(16) not null, " +
                    "file_path varchar(512), " +
                    "error_message text null, " +
                    "created_at timestamp not null default now(), " +
                    "updated_at timestamp not null default now())");
            log.info("CREATE TABLE 执行完成");

            // 创建索引
            try {
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_rag_ingest_task_rag_tag ON rag_ingest_task(rag_tag)");
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_rag_ingest_task_status ON rag_ingest_task(status)");
                log.info("CREATE INDEX 执行完成");
            } catch (Exception e) {
                log.warn("创建索引失败: {}", e.getMessage());
            }

            log.info("rag_ingest_task 表初始化完成");
        } catch (Exception e) {
            log.error("初始化 rag_ingest_task 表失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public int insert(IngestTask task) {
        return jdbcTemplate.update("""
                insert into rag_ingest_task (task_id, rag_tag, status, file_path)
                values (?, ?, ?, ?)
                """, task.getTaskId(), task.getRagTag(),
                task.getStatus().name(), task.getFilePath());
    }

    @Override
    public int updateStatus(String taskId, IngestTask.TaskStatus status, String errorMessage) {
        return jdbcTemplate.update("""
                update rag_ingest_task
                set status = ?, error_message = ?, updated_at = now()
                where task_id = ?
                """, status.name(), errorMessage, taskId);
    }

    @Override
    public IngestTask findById(String taskId) {
        try {
            return jdbcTemplate.queryForObject("""
                    select task_id, rag_tag, status, file_path, error_message, created_at, updated_at
                    from rag_ingest_task
                    where task_id = ?
                    """, (rs, rowNum) -> IngestTask.builder()
                            .taskId(rs.getString("task_id"))
                            .ragTag(rs.getString("rag_tag"))
                            .status(IngestTask.TaskStatus.valueOf(rs.getString("status")))
                            .filePath(rs.getString("file_path"))
                            .errorMessage(rs.getString("error_message"))
                            .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
                            .updatedAt(rs.getTimestamp("updated_at").toLocalDateTime())
                            .build(),
                    taskId);
        } catch (Exception e) {
            log.debug("查询任务失败, taskId: {}, msg: {}", taskId, e.getMessage());
            return null;
        }
    }

}
