package com.diversive.agent.web;

import com.diversive.agent.spi.UserContext;
import com.diversive.agent.spi.UserContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lets a capability handler take the signed-in {@link UserContext} as a parameter. Execute passes the user in
 * directly; when the same handler is called as a plain HTTP endpoint, this resolves the user from the request,
 * and answers 401 when there is none.
 */
public class UserContextArgumentResolver implements HandlerMethodArgumentResolver {

    private final ObjectProvider<UserContextResolver> userContextResolver;

    public UserContextArgumentResolver(ObjectProvider<UserContextResolver> userContextResolver) {
        this.userContextResolver = userContextResolver;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == UserContext.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container, NativeWebRequest request,
                                  WebDataBinderFactory binderFactory) {
        HttpServletRequest httpRequest = request.getNativeRequest(HttpServletRequest.class);
        UserContextResolver resolver = userContextResolver.getIfAvailable();
        if (httpRequest == null || resolver == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "A valid user credential is required");
        }
        return resolver.resolve(httpRequest)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "A valid user credential is required"));
    }
}
