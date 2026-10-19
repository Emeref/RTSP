# YouTube Livestream Automation Plan

## Problem Statement
Currently, the application ends the RTSP/RTMP stream locally, but the YouTube Live Dashboard remains in a "Live" state because the `liveBroadcasts` status is not updated to `complete`. This prevents the user from starting a new stream without manual intervention in the YouTube Studio web interface. Additionally, current authentication is simulated, preventing real stream starts.

## Goal
Automate the transition of the YouTube Live Broadcast status from `live` to `complete` using the YouTube Data API v3, enabling seamless consecutive streaming, and implement a real OAuth 2.0 flow to allow background streaming.

## Requirements
- **Google Cloud Project:** Enabled YouTube Data API v3.
- **OAuth 2.0 Credentials:** Client ID for Android.
- **Refresh Tokens:** Implementation of "Offline Access" to allow silent token renewal during background tasks (AlarmReceiver).

## Implementation Roadmap

### Phase 1: Real Authentication & OAuth 2.0
*Note: Previous implementation was a simulation and must be replaced.*

- [ ] **Task 1.1: Implement Real OAuth 2.0 Flow**
    - Implement `Authorization Code` flow via `CredentialManager` or Google Sign-In.
    - Obtain `Refresh Token` for offline access (crucial for `AlarmReceiver`).
- [ ] **Task 1.2: Backend/Exchange Logic**
    - Implement (or use a serverless function) to exchange `Authorization Code` for `Access Token` and `Refresh Token`.
- [ ] **Task 1.3: Replace Simulated Tokens**
    - Update `AuthManager.kt` to store real tokens from Google instead of hardcoded "simulated" strings.
- [ ] **Task 1.4: Implement Real `TokenRefreshService`**
    - Implement actual HTTP calls to Google's OAuth2 endpoint to use the `refresh_token`.
- [ ] **Task 1.5: Robust error handling in `StreamingService`**
    - Handle scenarios where network is unavailable during token refresh at startup.

### Phase 2: YouTube API Client
- [ ] **Task 2.1: Create `YouTubeApiService`**
    - Wrap REST calls to YouTube Data API v3 (using real `AccessToken`).
- [ ] **Task 2.2: Implement `YouTubeServiceManager`**
    - Implement logic to find active live broadcasts and set their status to `complete`.

### Phase 3: Service Integration
- [ ] **Task 3.1: Inject `YouTubeApiService` into `StreamingService`.**
- [ ] **Task 3.2: Hook into termination logic**
    - Trigger `YouTubeServiceManager.completeBroadcast()` when the stream ends.
- [ ] **Task 3.3: Retry Logic**
    - Implement exponential backoff for API calls to ensure the broadcast is marked as complete even if the network is unstable.

### Phase 4: Verification
- [ ] **Task 4.1: End-to-end Test**
    - Verify: Start stream -> Stop stream -> YouTube Dashboard shows "Completed".
- [ ] **Task 4.2: Background Test**
    - Verify: Trigger stream via `AlarmReceiver` (app in background) -> Check if stream starts successfully using refreshed token.

## Technical Stack
- **API:** YouTube Data API v3
- **Auth:** Google Play Services / Credential Manager (OAuth 2.0)
- **Storage:** EncryptedSharedPreferences (for secure token storage)
