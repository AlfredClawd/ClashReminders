# ClashReminders Android Client

This is the Android client for ClashReminders.

## Project Structure

*   `app/src/main/java/com/clashreminders/`: Source code.
    *   `api/`: Retrofit API client and interfaces.
    *   `data/`: Repository and data handling.
    *   `model/`: Data models for API responses.
    *   `ui/`: UI components (Screens, ViewModel, Theme).
*   `app/src/main/res/`: Resources (layouts, strings, themes).

## Build Instructions

1.  Open the project in Android Studio.
2.  Ensure you have JDK 17 or higher set as Gradle JDK.
3.  Sync Gradle.
4.  Run the app on an emulator or device.

## Configuration

The API Base URL is hardcoded in `com.clashreminders.api.RetrofitClient` as `http://10.0.2.2:8000/`.
If you are running the backend on a different machine or device, update this URL.

## Features

*   **Onboarding**: Registers a new user and adds a Clash of Clans account via Player Tag.
*   **Home Screen**: Displays added accounts and their current war status (active war, attacks left, end time).
