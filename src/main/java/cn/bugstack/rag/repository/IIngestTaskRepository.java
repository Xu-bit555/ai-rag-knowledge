package cn.bugstack.rag.repository;

import cn.bugstack.rag.model.entity.IngestTask;

/**
 * 摄入任务仓储接口
 */
public interface IIngestTaskRepository {

    /**
     * 插入任务
     */
    int insert(IngestTask task);

    /**
     * 更新任务状态
     */
    int updateStatus(String taskId, IngestTask.TaskStatus status, String errorMessage);

    /**
     * 根据ID查询任务
     */
    IngestTask findById(String taskId);

}
