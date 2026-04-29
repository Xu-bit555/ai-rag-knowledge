CREATE EXTENSION IF NOT EXISTS vector;

-- 摄入任务表
create table if not exists rag_ingest_task (
    task_id varchar(64) primary key,
    rag_tag varchar(128) not null,
    status varchar(16) not null,
    file_path varchar(512),
    error_message text null,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now()
);

-- 创建索引
create index if not exists idx_rag_ingest_task_rag_tag on rag_ingest_task(rag_tag);
create index if not exists idx_rag_ingest_task_status on rag_ingest_task(status);