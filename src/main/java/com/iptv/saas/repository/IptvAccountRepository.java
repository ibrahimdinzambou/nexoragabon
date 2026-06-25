package com.iptv.saas.repository;

import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.domain.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IptvAccountRepository extends JpaRepository<IptvAccount, Long> {
    List<IptvAccount> findByActiveTrueAndDisabledFalse();

    List<IptvAccount> findByAssignedUserAndActiveTrueAndDisabledFalse(UserEntity assignedUser);

    List<IptvAccount> findByAssignedUser_IdAndActiveTrueAndDisabledFalse(Long assignedUserId);

    long countByActiveTrue();

    long countByDisabledFalseAndActiveTrue();
}
