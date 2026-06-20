package com.iptv.saas.repository;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.Organization;
import com.iptv.saas.domain.Plan;
import com.iptv.saas.domain.Subscription;
import com.iptv.saas.domain.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
    Optional<Subscription> findFirstByOrganizationOrderByCreatedAtDesc(Organization organization);

    List<Subscription> findByOrganization(Organization organization);

    boolean existsByOrganizationAndPlanAndTrialEndsAtIsNotNull(Organization organization, Plan plan);

    @Query("""
            select count(subscription)
            from Subscription subscription
            where subscription.organization.owner = :owner
              and subscription.plan = :plan
              and subscription.trialEndsAt is not null
            """)
    long countTrialsForOwnerAndPlan(@Param("owner") UserEntity owner, @Param("plan") Plan plan);

    long countByStatus(Enums.SubscriptionStatus status);

    List<Subscription> findByStatusIn(Collection<Enums.SubscriptionStatus> statuses);
}
