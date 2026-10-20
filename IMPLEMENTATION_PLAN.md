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

### Phase 1: Real Authentication & OAuth 2.0 (Completed with Mock)
*Note: The UI/Credential layer using `CredentialManager` is implemented. The backend exchange is mocked for testing.*

- [x] **Task 1.1: Complete Real OAuth 2.0 Flow**
    - [x] Implement `Authorization Code` flow via `CredentialManager`.
    - [ ] Obtain `Refresh Token` via backend exchange.
- [x] **Task 1.2: Backend/Exchange Logic (Mocked)**
    - [x] Implemented `TokenExchangeService` and `MockTokenExchangeService`.
- [x] **Task 1.3: Replace Simulated Tokens**
    - [x] Implement logic to store tokens in `TokenManager`.
    - [x] Use `TokenExchangeService` to exchange `serverAuthCode` for tokens.
- [x] **Task 1.4: Implement Real `TokenRefreshService`**
    - [x] Defined interface for `TokenRefreshService`.
- [ ] **Task 1.5: Robust error handling in `StreamingService`**
    - [ ] Improve error handling for token refresh during `onStartCommand`.

### Phase 2: YouTube API Client (Mostly Complete)
- [x] **Task 2.1: Create `YouTubeApiService`**
    - [x] Defined API for YouTube Data API v3.
- [x] **Task 2.2: Implement `YouTubeServiceManager`**
    - [x] Implemented `findActiveBroadcastId` and `completeBroadcast`.

### Phase 3: Service Integration (Mostly Complete)
- [x] **Task 3.1: Inject `YouTubeApiService` into `StreamingService`**
- [x] **Task 3.2: Hook into termination logic**
    - [x] `StreamingService` triggers `completeBroadcast` in `endSession`.
- [x] **Task 3.3: Retry Logic**
    - [x] Implemented exponential backoff in `YouTubeServiceManager.completeBroadcast`.

### Phase 4: Verification
- [ ] **Task 4.1: End-to-end Test**
    - [ ] Verify: Start stream -> Stop stream -> YouTube Dashboard shows "Completed".
- [ ] **Task 4.2: Background Test**
    - [ ] Verify: Trigger stream via `AlarmReceiver` (app in background) -> Check if stream starts successfully using refreshed token.

## Technical Stack
- **API:** YouTube Data API v3
- **Auth:** Google Play Services / Credential Manager (OAuth 2.0)
- **Storage:** EncryptedSharedPreferences (for secure token storage)
