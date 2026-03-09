package com.yourapp.stavelectroset.feature.news.auth.data.local.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.AuthRepository;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.ProfileRepository;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ProfileStateGuard {

    public interface AllowedAction {
        void run();
    }

    private final ProfileRepository profileRepository;
    private final AuthRepository authRepository;

    // чтобы не открыть 2 диалога подряд
    private final AtomicBoolean dialogShown = new AtomicBoolean(false);

    public ProfileStateGuard(@NonNull Context context) {
        ServiceLocator sl = ServiceLocator.get(context.getApplicationContext());
        this.profileRepository = sl.profileRepository();
        this.authRepository = sl.authRepository();
    }

    /**
     * Главная точка входа:
     * - если ACTIVE → пускаем
     * - иначе → блокируем и показываем объяснение + CTA
     */
    public void guardOrBlock(@NonNull AppCompatActivity activity,
                             @NonNull AllowedAction onAllowed) {
        // 1) если свежий кеш ACTIVE — мгновенно пускаем (UX быстрый)
        ProfileRepository.CachedState cached = profileRepository.getCached();
        if (cached.isFresh(ProfileRepository.DEFAULT_TTL_MS) && cached.state == ProfileRepository.ProfileState.ACTIVE) {
            onAllowed.run();
            return;
        }

        // 2) иначе делаем refresh (fail-closed при ошибке)
        profileRepository.refresh(result -> activity.runOnUiThread(() -> {
            ProfileRepository.ProfileState st = result.state;

            if (result.ok && st == ProfileRepository.ProfileState.ACTIVE) {
                onAllowed.run();
                return;
            }

            // Если сеть упала и кеш ACTIVE, но не свежий — по твоему правилу НЕ даём создавать
            // Поэтому здесь блокируем всегда, если не ACTIVE.
            showBlockedDialog(activity, st);
        }));
    }

    private void showBlockedDialog(@NonNull AppCompatActivity activity,
                                   @NonNull ProfileRepository.ProfileState st) {
        if (dialogShown.getAndSet(true)) return;

        String title;
        String message;

        if (st == ProfileRepository.ProfileState.PENDING_EMAIL) {
            title = "Подтвердите email";
            message = "Вы изменили email и он ожидает подтверждения.\n\n" +
                    "Пока email не подтверждён, заявки и жалобы недоступны.";
        } else if (st == ProfileRepository.ProfileState.INCOMPLETE) {
            title = "Заполните профиль";
            message = "Не все обязательные поля профиля заполнены.\n\n" +
                    "Заполните профиль в настройках — доступ к заявкам и жалобам откроется автоматически.";
        } else if (st == ProfileRepository.ProfileState.BLOCKED) {
            title = "Доступ ограничен";
            message = "Ваш профиль заблокирован. Обратитесь в поддержку.";
        } else {
            title = "Функция недоступна";
            message = "Не удалось проверить статус профиля.\n\n" +
                    "Проверьте интернет и попробуйте ещё раз.";
        }

        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(true)
                .setOnDismissListener(d -> dialogShown.set(false));

        // CTA 1: в настройки профиля
        b.setPositiveButton("В настройки", (d, which) -> {
            try {
                activity.startActivity(new Intent(activity, ProfileSettingsActivity.class));
            } catch (Exception e) {
                snack(activity, "Не удалось открыть настройки");
            }
        });

        // CTA 2: для PENDING_EMAIL — resend + открыть почту + проверить
        if (st == ProfileRepository.ProfileState.PENDING_EMAIL) {
            b.setNeutralButton("Открыть почту", (d, which) -> openEmailApp(activity));

            b.setNegativeButton("Отправить повторно", (d, which) -> {
                String access = authRepository.getAccessToken();
                String email = access == null ? null : jwtGetString(access, "email");
                // Если у тебя в JWT сейчас старый email, resend для email_change лучше слать на "новый".
                // Но нового email у нас тут нет. Поэтому resend делаем из Settings (там есть newEmail).
                // Здесь даём максимально безопасное поведение:
                if (email == null || email.trim().isEmpty()) {
                    snack(activity, "Откройте настройки и нажмите 'Отправить повторно' там");
                    return;
                }
                profileRepository.resendEmailChange(email.trim(), (ok, err) -> activity.runOnUiThread(() -> {
                    snack(activity, ok ? "Письмо отправлено повторно" : ("Не удалось отправить: " + (err == null ? "" : err)));
                }));
            });
        } else {
            b.setNegativeButton("Закрыть", null);
        }

        b.show();
    }

    private static void openEmailApp(@NonNull AppCompatActivity activity) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_APP_EMAIL);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception ignore) {
            // fallback: mailto
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("mailto:"));
                activity.startActivity(i);
            } catch (Exception e) {
                snack(activity, "Не удалось открыть почту");
            }
        }
    }

    private static void snack(@NonNull AppCompatActivity activity, @NonNull String msg) {
        Snackbar.make(activity.findViewById(android.R.id.content), msg, Snackbar.LENGTH_SHORT).show();
    }

    @Nullable
    private static String jwtGetString(@NonNull String jwt, @NonNull String key) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;

            String payloadB64 = parts[1];
            byte[] decoded = android.util.Base64.decode(payloadB64,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);

            JSONObject obj = new JSONObject(new String(decoded));
            if (!obj.has(key)) return null;
            return obj.optString(key, null);
        } catch (Exception e) {
            return null;
        }
    }
}