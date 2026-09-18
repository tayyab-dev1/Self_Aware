package com.diversive.agent.execute;

import com.diversive.agent.registry.RegisteredCapability;
import com.diversive.agent.spi.UserContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.util.ReflectionUtils;

/**
 * Runs a capability's handler: the registry's handler method, called on the application's own bean, with the
 * request record and the signed-in user. The plan never names the endpoint; the backend looks it up here
 * (invariant 2).
 */
public final class CapabilityHandlers {

    private final Function<Class<?>, Object> beans;

    /** @param beans finds the application's bean for a handler type, e.g. {@code beanFactory::getBean} */
    public CapabilityHandlers(Function<Class<?>, Object> beans) {
        this.beans = Objects.requireNonNull(beans, "beans");
    }

    /** What the handler returned; null for a {@code void} handler. Its own exceptions come through unwrapped. */
    Object invoke(RegisteredCapability capability, Object request, UserContext user) {
        Method method = capability.handlerMethod();
        Object[] arguments = new Object[method.getParameterCount()];
        if (capability.requestParameter() >= 0) {
            arguments[capability.requestParameter()] = request;
        }
        if (capability.userParameter() >= 0) {
            arguments[capability.userParameter()] = user;
        }
        Object bean = beans.apply(capability.handlerType());
        try {
            ReflectionUtils.makeAccessible(method);
            return method.invoke(bean, arguments);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(capability.id() + ": its handler threw " + e.getCause(), e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(capability.id() + ": its handler cannot be called", e);
        }
    }
}
