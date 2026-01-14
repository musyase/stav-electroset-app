package com.yourapp.stavelectroset.feature.news.auth.data.local;


import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface CertificateRequestDao {

    @Insert
    long insert(CertificateRequestEntity r);

    @Update
    int update(CertificateRequestEntity r);

    // Все заявки для админ-экрана
    @Query("SELECT * FROM certificate_requests ORDER BY requestedAt DESC")
    List<CertificateRequestEntity> getAllSync();

    // Заявка конкретного пользователя (чтобы не создавать дубликаты)
    @Query("SELECT * FROM certificate_requests WHERE userId = :uid LIMIT 1")
    CertificateRequestEntity getByUserSync(long uid);
    @Query("SELECT certificate_requests.*, users.fullName AS userFullName " +
            "FROM certificate_requests " +
            "LEFT JOIN users ON users.id = certificate_requests.userId " +
            "ORDER BY certificate_requests.requestedAt DESC")
    List<CertificateWithUser> getAllWithUser();
}
