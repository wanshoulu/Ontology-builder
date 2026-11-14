package com.ontologybuilder.instantiation.repository;

import com.ontologybuilder.instantiation.model.InstantiationTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface InstantiationTaskRepository extends JpaRepository<InstantiationTask, String> {

    List<InstantiationTask> findByTenantId(String tenantId);

    List<InstantiationTask> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<InstantiationTask> findByOntologyId(String ontologyId);

    List<InstantiationTask> findByTenantIdAndStatus(String tenantId, InstantiationTask.TaskStatus status);

    List<InstantiationTask> findByOntologyIdAndStatus(String ontologyId, InstantiationTask.TaskStatus status);

    List<InstantiationTask> findByOntologyIdAndStatusOrderByVersionDesc(String ontologyId, InstantiationTask.TaskStatus status);

    long countByOntologyIdAndStatus(String ontologyId, InstantiationTask.TaskStatus status);

    List<InstantiationTask> findByJenaModelName(String jenaModelName);

    List<InstantiationTask> findAllByOrderByCreatedAtDesc();

    /** 查询已启用调度且到期需要执行的任务 */
    List<InstantiationTask> findByScheduleEnabledTrueAndNextScheduledRunAtBefore(LocalDateTime now);

    @Query("SELECT COUNT(DISTINCT t.tenantId) FROM InstantiationTask t")
    long countDistinctTenantIds();
}
