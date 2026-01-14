package com.yourapp.stavelectroset.feature.news.auth.data.local.domain;

import android.text.TextUtils;
import android.util.Patterns;

import  com.yourapp.stavelectroset.feature.news.auth.data.local.UserDao;
import com.yourapp.stavelectroset.feature.news.auth.data.local.UserEntity;

public class AuthRepository {
    private final UserDao dao;
    public AuthRepository(UserDao dao){ this.dao = dao; }

    public static class Result {
        public boolean ok; public String error; public long userId;
        public static Result ok(long id){ Result r=new Result(); r.ok=true; r.userId=id; return r; }
        public static Result err(String e){ Result r=new Result(); r.ok=false; r.error=e; return r; }
    }

    public Result register(String login, String email, String fullName, String pass, String pass2) {
        try {
            if (isEmpty(login) || isEmpty(email) || isEmpty(fullName) || isEmpty(pass) || isEmpty(pass2))
                return Result.err("Заполните все поля");
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches())
                return Result.err("Некорректная почта");
            if (!pass.equals(pass2))
                return Result.err("Пароли не совпадают");
            String weak = checkStrength(pass);
            if (weak != null) return Result.err(weak);
            if (dao.existsLogin(login) > 0) return Result.err("Логин уже занят");
            if (dao.existsEmail(email) > 0) return Result.err("Почта уже используется");

            UserEntity u = new UserEntity();
            u.login = login.trim();
            u.email = email.trim();
            u.fullName = fullName.trim();
            u.passwordHash = PasswordHasher.hash(pass);
            u.createdAt = System.currentTimeMillis();
            long id = dao.insert(u);
            return Result.ok(id);
        } catch (Exception e) {
            return Result.err("Ошибка: " + e.getMessage());
        }
    }

    /** Вход по логину ИЛИ почте + паролю */
    public Result login(String loginOrEmail, String pass) {
        try {
            if (isEmpty(loginOrEmail) || isEmpty(pass)) return Result.err("Введите логин и пароль");
            UserEntity u = dao.getByLogin(loginOrEmail);
            if (u == null) u = dao.getByEmail(loginOrEmail);
            if (u == null) return Result.err("Пользователь не найден");
            if (!PasswordHasher.verify(pass, u.passwordHash)) return Result.err("Неверный пароль");
            dao.markLogin(u.id, System.currentTimeMillis());
            return Result.ok(u.id);
        } catch (Exception e) {
            return Result.err("Ошибка: " + e.getMessage());
        }
    }

    private boolean isEmpty(String s){ return TextUtils.isEmpty(s) || s.trim().isEmpty(); }

    private String checkStrength(String p){
        if (p.length() < 8) return "Пароль слишком короткий (минимум 8 символов)";
        boolean hasU=false,hasL=false,hasD=false,hasS=false;
        for(char c: p.toCharArray()){
            if (Character.isUpperCase(c)) hasU=true;
            else if (Character.isLowerCase(c)) hasL=true;
            else if (Character.isDigit(c)) hasD=true;
            else hasS=true;
        }
        int groups = (hasU?1:0)+(hasL?1:0)+(hasD?1:0)+(hasS?1:0);
        if (groups < 3) return "Добавьте буквы разного регистра, цифры и спецсимволы";
        return null;
    }
}
