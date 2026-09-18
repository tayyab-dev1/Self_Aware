package com.diversive.school.platform.agent;

import com.diversive.agent.spi.CapabilityPolicy;
import com.diversive.agent.spi.UserContext;
import org.springframework.stereotype.Component;

/**
 * The POC has one role, and it may use every capability. The plumbing exists anyway, so a real
 * role-to-capability table can replace this class without touching the gateway.
 */
@Component
public class SingleRoleCapabilityPolicy implements CapabilityPolicy {

    static final String POC_ROLE = "accounts_officer";

    @Override
    public boolean permits(UserContext user, String capabilityId) {
        return user.hasRole(POC_ROLE);
    }
}
