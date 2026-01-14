package com.yourapp.stavelectroset.feature.news.auth.data.local;

import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface UserDao {

    @Insert
    long insert(UserEntity u);

    @Query("UPDATE users SET lastLogin = :ts WHERE id = :id")
    void markLogin(long id, long ts);

    @Query("SELECT COUNT(*) FROM users WHERE login = :login")
    int existsLogin(String login);

    @Query("SELECT COUNT(*) FROM users WHERE email = :email")
    int existsEmail(String email);

    @Query("SELECT * FROM users WHERE login = :login LIMIT 1")
    UserEntity getByLogin(String login);

    @Query("SELECT * FROM users WHERE email = :email LIMIT 1")
    UserEntity getByEmail(String email);

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    LiveData<UserEntity> observeById(long id);

    @Query("SELECT * FROM users ORDER BY id DESC")
    List<UserEntity> getAllSync();
    @Update
    int update(UserEntity u);

    @Delete
    int delete(UserEntity u);
}
