package com.yourapp.stavelectroset.feature.news.auth.data.local;

import androidx.room.Embedded;

/**
 * Объединённая модель: заявка + ФИО пользователя.
 */
public class CertificateWithUser {

    @Embedded
    public CertificateRequestEntity request;

    /** fullName из таблицы users */
    public String userFullName;
}
