package com.diversive.agent.error;

import com.diversive.agent.execute.ExecuteController;
import com.diversive.agent.preflight.PreflightController;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A body that is not JSON, or has a field the contract does not define, is {@code INVALID_PLAN}. Only for
 * gateway endpoints that take a body. The body is never echoed back.
 */
@RestControllerAdvice(assignableTypes = {PreflightController.class, ExecuteController.class})
public class AgentRequestBodyExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public AgentErrorResponse unreadable(HttpMessageNotReadableException exception) {
        String detail = "it is not valid JSON";
        if (exception.getCause() instanceof UnrecognizedPropertyException unknown) {
            detail = "it has a field the contract does not define: '" + unknown.getPropertyName() + "'" + at(unknown);
        } else if (exception.getCause() instanceof JsonMappingException mapping) {
            detail = "a field has the wrong shape" + at(mapping);
        }
        return new AgentErrorResponse(AgentErrorCodes.INVALID_PLAN, "The request body cannot be read: " + detail);
    }

    private static String at(JsonMappingException exception) {
        String path = exception.getPath().stream()
                .map(reference -> reference.getFieldName() != null ? reference.getFieldName() : "[" + reference.getIndex() + "]")
                .collect(Collectors.joining("."))
                .replace(".[", "[");
        return path.isEmpty() ? "" : " (at " + path + ")";
    }
}
