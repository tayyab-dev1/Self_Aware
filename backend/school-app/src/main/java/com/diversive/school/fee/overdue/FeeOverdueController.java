package com.diversive.school.fee.overdue;

import com.diversive.agent.annotation.AgentCapability;
import com.diversive.agent.annotation.AgentEffect;
import com.diversive.agent.annotation.AgentParam;
import com.diversive.agent.annotation.BlastRadius;
import com.diversive.agent.spi.UserContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The overdue list (UC-04-06, planning contract UC-04-06-PC-1). */
@RestController
public class FeeOverdueController {

    public static final String CAPABILITY = "fee.overdue.list";

    private final FeeOverdueService overdue;

    public FeeOverdueController(FeeOverdueService overdue) {
        this.overdue = overdue;
    }

    @AgentCapability(
            id = CAPABILITY,
            module = "fee",
            readOnly = true,
            blastRadius = BlastRadius.NONE,
            description = """
                    Shows who has not paid their fees and how much each family still owes, with how
                    many days each debt is overdue and the total outstanding, for the whole school, one
                    class, one section or one student. It only lists; it contacts nobody.
                    Not for chasing the families on the list - use fee.reminder.send.
                    """,
            disambiguateFrom = {"fee.reminder.send"})
    @AgentParam(name = "scope", meaning = "What the list covers: the whole school, one class, one section or one student")
    @AgentParam(name = "class_id", meaning = "The class, when the list covers a whole class, e.g. Class 5",
            resolver = "class", label = "class_name")
    @AgentParam(name = "section_id", meaning = "The section, when the list covers one section, e.g. Class 5 Blue",
            resolver = "section", label = "section_name")
    @AgentParam(name = "student_id", meaning = "The student, when the list covers one student",
            resolver = "student", label = "student_name")
    @AgentParam(name = "age_band", meaning = "Only debts overdue for this long, when the user narrows by age")
    @AgentParam(name = "minimum_amount", meaning = "Hide debts below this amount, only when the user states one")
    @AgentEffect(
            replyTemplate = "Overdue fees for {scope_name}: {students}, {total_outstanding} outstanding.",
            facts = {"scope_name", "students", "total_outstanding"})
    @GetMapping("/api/v1/fee-overdue")
    public FeeOverdueResponse list(@Valid FeeOverdueQuery query, UserContext user) {
        return overdue.list(query, user);
    }
}
