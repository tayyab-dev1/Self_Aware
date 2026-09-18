package com.diversive.school.dashboard;

import com.diversive.agent.annotation.AgentCapability;
import com.diversive.agent.annotation.AgentEffect;
import com.diversive.agent.annotation.BlastRadius;
import com.diversive.agent.spi.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The main dashboard (UC-01-07, planning contract UC-01-07-PC-1). Its figures are chosen by role, server-side. */
@RestController
public class DashboardController {

    public static final String CAPABILITY = "dashboard.main.read";

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @AgentCapability(
            id = CAPABILITY,
            module = "dashboard",
            readOnly = true,
            blastRadius = BlastRadius.NONE,
            description = """
                    Shows the main dashboard figures for the user's branch, such as how much fee money
                    was collected today or this month and how much is still outstanding, each with the
                    time it was calculated. Which figures appear depends on the user's role.
                    Not for the list of families behind an outstanding figure - use fee.overdue.list.
                    """)
    @AgentEffect(
            replyTemplate = "{branch_name}, as of {calculated_at}: {collected_today} collected today and "
                    + "{collected_this_month} this month; {total_outstanding} still outstanding.",
            facts = {"branch_name", "calculated_at", "collected_today", "collected_this_month", "total_outstanding"})
    @GetMapping("/api/v1/dashboard")
    public DashboardResponse read(UserContext user) {
        return dashboard.read(user);
    }
}
