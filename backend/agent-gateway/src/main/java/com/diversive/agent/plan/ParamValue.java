package com.diversive.agent.plan;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * One parameter of a plan step, in exactly one of three forms:
 *
 * <ul>
 *   <li><b>A value</b>, used as given: {@code {"value": "whatsapp"}}. For parameters without a resolver.</li>
 *   <li><b>A name to look up</b>: {@code {"raw": "class 5 blue"}}. For parameters with a resolver. After
 *       {@code AMBIGUOUS_ENTITY}, the user's choice is sent back with the same words:
 *       {@code {"raw": "class 5", "chosen_id": "2"}}. The choice must be one of the candidates those
 *       words match, so an id cannot be slipped in.</li>
 *   <li><b>A value from an earlier step</b>: {@code {"from_step": 1, "field": "receipt_number"}}, where
 *       the field is a fact the earlier step's capability publishes. Unknown until that step runs, so
 *       this step is confirmed with its pending template.</li>
 * </ul>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParamValue(
        Object value,
        String raw,
        String chosenId,
        Integer fromStep,
        String field) {

    /** Which of the three forms this is, or {@link Form#MALFORMED} when it is not exactly one. */
    public Form form() {
        boolean isValue = value != null;
        boolean isName = raw != null || chosenId != null;
        boolean isEarlierStep = fromStep != null || field != null;
        int forms = (isValue ? 1 : 0) + (isName ? 1 : 0) + (isEarlierStep ? 1 : 0);
        if (forms != 1) {
            return Form.MALFORMED;
        }
        if (isValue) {
            return Form.VALUE;
        }
        if (isName) {
            return raw == null || raw.isBlank() || (chosenId != null && chosenId.isBlank()) ? Form.MALFORMED : Form.NAME;
        }
        return fromStep == null || field == null || field.isBlank() ? Form.MALFORMED : Form.EARLIER_STEP;
    }

    public enum Form {
        VALUE, NAME, EARLIER_STEP, MALFORMED
    }
}
