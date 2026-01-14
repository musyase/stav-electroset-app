package com.yourapp.stavelectroset.feature.news.auth.data.local.domain;

import android.content.Context;
import android.content.SharedPreferences;

public final class SessionManager {
    private SessionManager() {}

    private static final String PREFS = "auth_session";
    private static final String KEY_UID = "current_user_id";
    private static final String KEY_ADMIN = "is_admin";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // вход обычного пользователя
    public static void login(Context c, long userId) {
        sp(c).edit()
                .putLong(KEY_UID, userId)
                .putBoolean(KEY_ADMIN, false)
                .apply();
    }

    // вход админа (если используешь)
    public static void loginAdmin(Context c) {
        sp(c).edit()
                .putLong(KEY_UID, 0L)
                .putBoolean(KEY_ADMIN, true)
                .apply();
    }

    // выход
    public static void logout(Context c) {
        sp(c).edit()
                .remove(KEY_UID)
                .remove(KEY_ADMIN)
                .apply();
    }

    public static long currentUserId(Context c) {
        return sp(c).getLong(KEY_UID, -1L);
    }

    public static boolean isLoggedIn(Context c) {
        return sp(c).contains(KEY_UID);
    }

    public static boolean isAdmin(Context c) {
        return sp(c).getBoolean(KEY_ADMIN, false);
    }
}
