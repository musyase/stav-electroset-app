package com.yourapp.stavelectroset.feature.news.auth.data.local;

import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "users",
        indices = {
                @Index(value = {"login"}, unique = true),
                @Index(value = {"email"}, unique = true)
        }
)
public class UserEntity {
    @PrimaryKey(autoGenerate = true) public long id;

    public String login;
    public String email;
    public String fullName;
    public String passwordHash;
    public String accountNumber;   // номер лицевого счета
    public String phone;           // телефон
    public String meterSerials;    // строка через запятую
    public String subscriberType;  // тип абонента


    public long createdAt;          // millis UTC
    @Nullable public Long lastLogin; // nullable: первый вход ещё не был
}
