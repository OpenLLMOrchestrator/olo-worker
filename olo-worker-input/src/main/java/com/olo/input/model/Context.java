package com.olo.input.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * Tenant, roles, permissions and session context for the workflow.
 */
public final class Context {

    private final String tenantId;
    private final String groupId;
    private final List<String> roles;
    private final List<String> permissions;
    private final String sessionId;

    @JsonCreator
    public Context(
            @JsonProperty("tenantId") String tenantId,
            @JsonProperty("groupId") String groupId,
            @JsonProperty("roles") List<String> roles,
            @JsonProperty("permissions") List<String> permissions,
            @JsonProperty("sessionId") String sessionId) {
        this.tenantId = tenantId;
        this.groupId = groupId;
        this.roles = roles != null ? List.copyOf(roles) : List.of();
        this.permissions = permissions != null ? List.copyOf(permissions) : List.of();
        this.sessionId = sessionId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getGroupId() {
        return groupId;
    }

    public List<String> getRoles() {
        return roles;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public String getSessionId() {
        return sessionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Context context = (Context) o;
        return Objects.equals(tenantId, context.tenantId)
                && Objects.equals(groupId, context.groupId)
                && Objects.equals(roles, context.roles)
                && Objects.equals(permissions, context.permissions)
                && Objects.equals(sessionId, context.sessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, groupId, roles, permissions, sessionId);
    }
}
