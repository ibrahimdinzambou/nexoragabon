package com.iptv.saas.service;

import com.iptv.saas.domain.AuditLog;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private final AuditLogRepository logs;
    private final RequestInfoService requestInfo;

    public AuditService(AuditLogRepository logs, RequestInfoService requestInfo) {
        this.logs = logs;
        this.requestInfo = requestInfo;
    }

    @Transactional
    public AuditLog log(UserEntity user, String action, String subjectType, Long subjectId, String metadata) {
        AuditLog log = new AuditLog();
        log.user = user;
        log.action = action;
        log.subjectType = subjectType;
        log.subjectId = subjectId;
        log.ipAddress = requestInfo.clientIp();
        log.userAgent = requestInfo.userAgent();
        log.metadata = metadata;
        return logs.save(log);
    }
}
