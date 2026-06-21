package com.iptv.saas.repository;

import com.iptv.saas.domain.AuditLog;
import com.iptv.saas.domain.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findTop100ByOrderByCreatedAtDesc();

    List<AuditLog> findByCreatedAtBetweenOrderByCreatedAtDesc(Instant start, Instant end);

    List<AuditLog> findTop50ByCreatedAtAfterOrderByCreatedAtDesc(Instant start);

    List<AuditLog> findTop50ByUserOrderByCreatedAtDesc(UserEntity user);
}
