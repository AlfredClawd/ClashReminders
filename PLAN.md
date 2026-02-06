# Implementation Plan

## Phase 1: Foundation (Current)
1.  **Project Setup**: Initialize Git, Folder structure (`backend/`, `android/`).
2.  **Backend Skeleton**: Basic FastAPI app running locally.
3.  **CoC API Client**: Implement a wrapper to fetch Player and War data.
4.  **Database**: Setup SQLite + User/Account models.

## Phase 2: Core Logic
1.  **Polling Engine**: Implement the loop to check wars.
2.  **Notification Logic**: Determine *when* to alert.
3.  **API Implementation**: Endpoints for the App to register tags.

## Phase 3: Android App Prototype
1.  **UI Shell**: Material 3 Scaffold.
2.  **Onboarding**: "Enter your Player Tag".
3.  **Backend Integration**: Send Tag + FCM Token to Backend.
4.  **Push Receiver**: Handle incoming FCM messages.

## Phase 4: Polish & Deploy
1.  Configurable settings in App.
2.  Deploy Backend to Server (Docker).
3.  Build APK.

---

# Next Steps (Immediate)
Assign Task to Kimi: **Phase 1 Implementation**
