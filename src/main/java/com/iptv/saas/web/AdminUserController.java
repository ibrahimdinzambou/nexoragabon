package com.iptv.saas.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.IptvAccountRepository;
import com.iptv.saas.repository.UserRepository;
import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.service.TokenService;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {
    private final UserRepository users;
    private final ObjectMapper mapper;
    private final TokenService tokens;
    private final IptvAccountRepository iptvAccounts;

    public AdminUserController(UserRepository users, ObjectMapper mapper, TokenService tokens) {
        this(users, mapper, tokens, null);
    }

    @Autowired
    public AdminUserController(
            UserRepository users,
            ObjectMapper mapper,
            TokenService tokens,
            IptvAccountRepository iptvAccounts
    ) {
        this.users = users;
        this.mapper = mapper;
        this.tokens = tokens;
        this.iptvAccounts = iptvAccounts;
    }

    @GetMapping
    public Object users() {
        return Responses.ok(this.users.findAll().stream()
                .filter(user -> !isDeleted(user))
                .map(this::userWithIptv)
                .toList());
    }

    private Map<String, Object> userWithIptv(UserEntity user) {
        Map<String, Object> payload = ApiMappers.user(user);
        List<IptvAccount> assigned = iptvAccounts == null || user.id == null
                ? List.of()
                : iptvAccounts.findByAssignedUser_IdAndActiveTrueAndDisabledFalse(user.id);
        payload.put("iptvActive", !assigned.isEmpty());
        payload.put("iptvAssignedCount", assigned.size());
        payload.put("iptvAccountName", assigned.isEmpty() ? null : assigned.get(0).name);
        payload.put("iptvAccountHealth", assigned.isEmpty() ? null : assigned.get(0).lastHealthStatus);
        return payload;
    }

    @PatchMapping("/{id}/toggle")
    public Object toggle(@PathVariable Long id, @RequestBody(required = false) ToggleRequest request) {
        var user = users.findById(id).orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
        user.active = request == null || request.active() == null ? !user.active : request.active();
        return Responses.ok(ApiMappers.user(users.save(user)));
    }

    @PatchMapping("/{id}/role")
    public Object role(@PathVariable Long id, @RequestBody RoleRequest request) {
        var user = users.findById(id).orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
        user.role = request.role();
        return Responses.ok(ApiMappers.user(users.save(user)));
    }

    @PostMapping("/{id}/categories")
    public Object categories(@PathVariable Long id, @RequestBody CategoriesRequest request) throws Exception {
        var user = users.findById(id).orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
        user.allowedCategories = mapper.writeValueAsString(request.categories());
        return Responses.ok(ApiMappers.user(users.save(user)));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Object delete(@PathVariable Long id) {
        UserEntity actor = SecurityUtils.currentUser();
        if (actor.role != Enums.UserRole.SUPER_ADMIN && actor.role != Enums.UserRole.ADMIN) {
            throw ApiException.forbidden("Permission insuffisante");
        }
        if (Objects.equals(actor.id, id)) {
            throw ApiException.forbidden("Impossible de supprimer votre propre compte");
        }

        UserEntity user = users.findById(id).orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
        if (isDeleted(user)) {
            return Responses.ok(Map.of("id", id, "deleted", true));
        }
        if (user.role == Enums.UserRole.SUPER_ADMIN && activeSuperAdmins() <= 1) {
            throw ApiException.forbidden("Impossible de supprimer le dernier super-admin actif");
        }

        tokens.revokeAll(user);
        user.active = false;
        user.email = deletedEmail(user);
        user.name = "Utilisateur supprime";
        user.emailVerified = false;
        user.twoFactorEnabled = false;
        user.emailOtp = null;
        user.emailOtpExpiresAt = null;
        user.resetOtp = null;
        user.resetOtpExpiresAt = null;
        user.twoFactorCode = null;
        user.twoFactorCodeExpiresAt = null;
        user.currentOrganization = null;
        user.allowedCategories = "[]";
        users.save(user);
        return Responses.ok(Map.of("id", id, "deleted", true));
    }

    private long activeSuperAdmins() {
        return users.findAll().stream()
                .filter(user -> user.role == Enums.UserRole.SUPER_ADMIN)
                .filter(user -> user.active && !isDeleted(user))
                .count();
    }

    private boolean isDeleted(UserEntity user) {
        return user != null
                && user.email != null
                && user.email.startsWith("deleted-user-")
                && user.email.endsWith("@deleted.local");
    }

    private String deletedEmail(UserEntity user) {
        return "deleted-user-" + user.id + "@deleted.local";
    }

    public record ToggleRequest(Boolean active) {
    }

    public record RoleRequest(Enums.UserRole role) {
    }

    public record CategoriesRequest(List<String> categories) {
    }
}
