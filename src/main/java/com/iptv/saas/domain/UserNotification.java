package com.iptv.saas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "user_notifications", indexes = {
        @Index(name = "idx_user_notifications_user_created", columnList = "recipient_id, createdAt")
})
public class UserNotification extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    public UserEntity recipient;

    @ManyToOne(fetch = FetchType.LAZY)
    public UserEntity sender;

    @Column(nullable = false, length = 160)
    public String title;

    @Column(nullable = false, length = 4000)
    public String body;

    @Column(length = 80)
    public String source = "ADMIN";

    public boolean emailRequested = true;
    public boolean emailQueued = false;
    public Instant emailQueuedAt;
}
