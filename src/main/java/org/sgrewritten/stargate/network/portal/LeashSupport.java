package org.sgrewritten.stargate.network.portal;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

/** Supports newer leashable entities without raising the minimum server API version. */
final class LeashSupport {
    private record Methods(Method isLeashed, Method holder, Method setHolder) { }
    private static final ClassValue<Optional<Methods>> METHODS = new ClassValue<>() {
        @Override protected Optional<Methods> computeValue(Class<?> type) {
            try {
                return Optional.of(new Methods(type.getMethod("isLeashed"), type.getMethod("getLeashHolder"),
                        type.getMethod("setLeashHolder", Entity.class)));
            } catch (NoSuchMethodException e) {
                return Optional.empty(); // Old Boat APIs are not leashable.
            }
        }
    };

    private LeashSupport() { }

    static Entity holder(Entity entity) {
        if (entity instanceof LivingEntity living) {
            return living.isLeashed() ? living.getLeashHolder() : null;
        }
        Optional<Methods> methods = METHODS.get(entity.getClass());
        if (methods.isEmpty() || !Boolean.TRUE.equals(invoke(methods.get().isLeashed(), entity))) return null;
        return (Entity) invoke(methods.get().holder(), entity);
    }

    static boolean setHolder(Entity entity, Entity holder) {
        if (entity instanceof LivingEntity living) return living.setLeashHolder(holder);
        return METHODS.get(entity.getClass()).map(methods ->
                Boolean.TRUE.equals(invoke(methods.setHolder(), entity, holder))).orElse(false);
    }

    private static Object invoke(Method method, Entity entity, Object... arguments) {
        try {
            return method.invoke(entity, arguments);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Unable to access entity leash API", e);
        }
    }
}
