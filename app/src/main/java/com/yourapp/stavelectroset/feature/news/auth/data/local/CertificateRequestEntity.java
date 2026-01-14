package com.yourapp.stavelectroset.feature.news.auth.data.local;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "certificate_requests")
public class CertificateRequestEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** id пользователя из UserEntity.id */
    public long userId;

    /** когда пользователь запросил справку (millis) */
    public long requestedAt;

    /** выдана ли справка администратором */
    public boolean issued;

    /** дата выдачи, которую введёт админ (произвольная строка) */
    public String issuedDate;
}
