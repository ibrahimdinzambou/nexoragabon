package com.iptv.saas.repository;

import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.domain.UserNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {
    List<UserNotification> findTop50ByRecipientOrderByCreatedAtDesc(UserEntity recipient);
}
