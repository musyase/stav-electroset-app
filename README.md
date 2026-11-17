# Stav Electroset — Android news reader

Небольшое Android-приложение для чтения новостей сайта [Ставропольских электрических сетей](https://stavels.ru/). Проект показывает работу с сетевым источником, локальным кэшем Room и Android UI на Java.

## Что работает

- Главный экран со списком новостей (`RecyclerView`) и открытием статьи.
- Загрузка публикаций через WordPress REST API; если API недоступен, загрузчик пробует разобрать страницу новостей.
- Локальное хранение новостей в Room; интерфейс наблюдает за таблицей через `LiveData`.
- Обновление списка жестом вниз (`SwipeRefreshLayout`).
- Отображение изображений через Glide.

```text
WordPress REST API / HTML-страница
              ↓
          NewsLoader
              ↓
         NewsRepository
              ↓
          Room / NewsDao
              ↓
     LiveData → RecyclerView
```

## Запуск

1. Откройте проект в Android Studio с JDK 17 и Android SDK 35.
2. Дождитесь синхронизации Gradle.
3. Запустите конфигурацию `app` на эмуляторе или устройстве с Android 7.0 (API 24) или новее.

Из командной строки: `./gradlew assembleDebug` на macOS/Linux или `.\gradlew.bat assembleDebug` в PowerShell.

## Стек и устройство проекта

- Java 17, Android SDK, Material Components.
- Retrofit и OkHttp для REST API, Jsoup для HTML fallback.
- Room, `LiveData`, `RecyclerView`, Glide.
- Основной код: `app/src/main/java/com/yourapp/stavelectroset/`.

## Текущее состояние

Это прототип новостного раздела, а не готовое приложение для абонентов. В дереве исходников есть заготовки профиля, регистрации и администрирования, но они не подключены к текущему пользовательскому сценарию. Работа зависит от доступности внешнего сайта и его текущей разметки/API. Сборка `assembleDebug` проверяется в GitHub Actions; автоматических UI-тестов пока нет.
