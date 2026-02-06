# ClashReminders Architecture

## Overview
ClashReminders is a system designed to notify Clash of Clans players about pending attacks in Clan Wars (CW), Clan War Leagues (CWL), and Raid Weekends. It mimics the functionality of "ListeningEvents" from Lost Manager but focuses on personalized push notifications via a native Android App.

## Technology Stack

### Backend
*   **Language:** Python 3.11+
*   **Framework:** FastAPI (High performance, easy async support for API calls)
*   **Database:** SQLite (v1, migration to PostgreSQL later if needed)
*   **ORM:** SQLAlchemy or Tortoise ORM
*   **External APIs:**
    *   Clash of Clans Official API (Data source)
    *   Clash Royale Official API (Placeholder/Future source)
    *   Firebase Cloud Messaging (FCM) (Push notifications)
*   **Scheduler:** APScheduler (For polling APIs)

### Mobile App
*   **OS:** Android
*   **Language:** Kotlin
*   **UI Framework:** Jetpack Compose (Material 3)
*   **Networking:** Retrofit / Ktor Client
*   **Local Data:** DataStore (Preferences) / Room (Cache)

## Data Structure (Schema)

### 1. User (Device)
*   `id` (UUID)
*   `fcm_token` (String, for Push Notifications)
*   `created_at` (Timestamp)
*   `settings` (JSON - e.g., reminder offsets: "1h before war end", "4h before war end")

### 2. PlayerAccount
*   `tag` (String, PK - e.g., #P00P)
*   `name` (String, cached)
*   `game_type` (Enum: "COC", "CR" - *Placeholder*)
*   `user_id` (FK -> User.id)
*   `clan_tag` (String, nullable, cached current clan)

### 3. TrackedClan (Optional/Derived)
*   If we want to track whole clans instead of just players.
*   `tag` (String, PK)
*   `name` (String)
*   `game_type` (Enum: "COC", "CR" - *Placeholder*)

## API Interfaces (Backend <-> App)

### Base URL: `/api/v1`

#### User Management
*   `POST /users/register`
    *   Body: `{"fcm_token": "..."}`
    *   Response: `{"user_id": "..."}` (Save locally)
*   `PUT /users/{user_id}/fcm`
    *   Body: `{"fcm_token": "..."}` (Update token on refresh)

#### Account Management
*   `POST /users/{user_id}/accounts`
    *   Body: `{"player_tag": "#...", "game_type": "COC"}`
    *   Logic: Validates tag with respective game API, saves to DB.
*   `GET /users/{user_id}/accounts`
    *   Response: List of tracked accounts (Tag, Name, Clan, GameType).
*   `DELETE /users/{user_id}/accounts/{player_tag}`

#### Settings
*   `GET /users/{user_id}/settings`
*   `PUT /users/{user_id}/settings`
    *   Body: `{"notify_coc_cw": true, "notify_coc_cwl": true, "notify_coc_raid": true, "notify_cr_war": false, "reminder_hours": [1, 4]}`

## Background Service Logic (The "Brain")

1.  **Polling Cycle (e.g., every 10 minutes):**
    *   Iterate through all unique `PlayerAccount` tags.
    *   Fetch Player details -> Check `clan_tag`.
    *   Fetch Clan Current War / CWL Round / Raid Weekend data.
2.  **Notification Logic:**
    *   **CW/CWL:**
        *   Is war state `inWar`?
        *   Does player have remaining attacks?
        *   Is time remaining <= configured reminder time (e.g., 1h)?
        *   *Check:* Have we already sent this specific notification? (Need a Redis cache or DB table for `SentNotifications`).
        *   *Action:* Send FCM Push.
    *   **Raid:**
        *   Is it weekend?
        *   Attacks < 6?
        *   Time check.

## Security
*   Basic API Key for App <-> Backend communication (to prevent unauthorized spam).
*   User ID validation (UUID).

## Future Expansion
*   Clash Royale (CR) full integration.
*   Discord Webhook integration.
*   Multi-account summary view in App.
