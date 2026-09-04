# Google Sign-In setup

The Android app uses package name `com.example.app`. Before testing login, create
an **OAuth 2.0 Client ID** of type **Android** in the Google Cloud project that
owns this application (or add the same values under Firebase Authentication).

For the current debug build, register:

- Package name: `com.example.app`
- SHA-1: `63:13:00:F2:2F:3B:CC:C0:DD:52:DA:DB:98:18:0D:0B:BE:C9:41:22`

If a backend verifies the Google ID token, also create an OAuth client of type
**Web application**, then copy its client ID to `local.properties`:

```properties
GOOGLE_WEB_CLIENT_ID=your-web-client-id.apps.googleusercontent.com
```

Rebuild and reinstall the app after changing `local.properties`. Release builds
need their own release-signing SHA-1 registered in the same Android OAuth client.

`ApiException` status `10` means the Android OAuth client/package/SHA-1
combination is not registered or does not match the installed APK.

## YouTube account permission

In the same Google Cloud project, enable **YouTube Data API v3** and configure
the OAuth consent screen. The app asks for the read-only scope
`https://www.googleapis.com/auth/youtube.readonly`, so it can later retrieve
private YouTube account data after the user grants consent. Add your Google
account as a test user while the consent screen is in testing mode.
