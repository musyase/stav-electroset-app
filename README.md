# Stav Electroset: Android news reader

This Java prototype reads news from the [Stavropol Electric Networks website](https://stavels.ru/). It loads articles from the WordPress REST API, falls back to parsing the news page when the API is unavailable, and stores fetched articles in Room for offline reading.

## What works

- A news list in `RecyclerView` and an article view.
- WordPress REST API loading with an HTML fallback.
- Local Room storage observed by the UI through `LiveData`.
- Pull to refresh with `SwipeRefreshLayout`.
- Image loading with Glide.
- An empty state with a retry action when no news has been cached and the source is unavailable.

```text
WordPress REST API / HTML page
              |
          NewsLoader
              |
         NewsRepository
              |
          Room / NewsDao
              |
     LiveData -> RecyclerView
```

## Run it

Open the project in Android Studio with JDK 17 and Android SDK 35, let Gradle sync, then run the `app` configuration on an emulator or device with Android 7.0 (API 24) or newer.

To build from a terminal, run `./gradlew assembleDebug` on macOS/Linux or `.\gradlew.bat assembleDebug` in PowerShell.

## Code

- Java 17, Android SDK, and Material Components.
- Retrofit and OkHttp for the REST API; Jsoup for the HTML fallback.
- Room, `LiveData`, `RecyclerView`, and Glide.
- Main source directory: `app/src/main/java/dev/musyase/stavelectroset/`.
- Launcher icon source: `app/src/main/res/drawable/ic_launcher_foreground.xml`; run `python tools/build_icons.py` with Pillow installed to regenerate legacy icons.

## Current limits

This is a news reader prototype, not a subscriber app. The source tree contains unfinished profile, registration, and admin screens that are outside the current user flow. Loading depends on the external site's availability and its current API or HTML markup. GitHub Actions builds `assembleDebug`; there are no automated UI tests yet.

The first launch requires the news source to be reachable. If it is not, the app shows a retry action. After a successful load, Room keeps the fetched articles available offline.
