package com.socialapp.common.security;

import java.util.List;

/**
 * Holds the identity of the caller for the current request thread.
 * Populated by {@link HeaderAuthFilter} from the X-User-Id / X-User-Roles
 * headers that api-gateway sets after validating the JWT.
 */
public final class CurrentUserContext {

    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> ROLES = new ThreadLocal<>();

    private CurrentUserContext() {
    }

    static void set(String userId, List<String> roles) {
        USER_ID.set(userId);
        ROLES.set(roles);
    }

    static void clear() {
        USER_ID.remove();
        ROLES.remove();
    }

    public static String getUserId() {
        return USER_ID.get();
    }

    public static List<String> getRoles() {
        return ROLES.get();
    }

    public static boolean isAuthenticated() {
        return USER_ID.get() != null;
    }
}
