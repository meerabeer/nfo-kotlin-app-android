# NFO Tracker - Developer Documentation

This document provides a quick reference to the app's architecture and key files.

---

## App Architecture – Login, DeviceHealth, Tracking (Codebase generated)

### Entry / Navigation

| Component | File Path | Notes |
|-----------|-----------|-------|
| **MainActivity** | `app/src/main/java/com/nfo/tracker/MainActivity.kt` | App entry point. Hosts `setContent { MainAppFlow() }` |
| **MainAppFlow()** | `app/src/main/java/com/nfo/tracker/MainActivity.kt` | Root composable. Defines NavHost after permission gate |
| **NavHost** | `app/src/main/java/com/nfo/tracker/MainActivity.kt` | Routes: `"tracking"`, `"device_setup_wizard"`, `"diagnostics"` |
| **First Screen** | `PermissionGateScreen` | Shown first to request location permission before main app |
| **Start Destination** | `"tracking"` → `TrackingScreen()` | Main screen after permissions granted |

**Navigation Flow:**
```
App Launch → PermissionGateScreen → (permission granted) → NavHost("tracking")
                                                              ├── TrackingScreen
                                                              ├── DeviceSetupWizardScreen
                                                              └── DiagnosticsScreen
```

---

### Login / Session State

| Component | File Path | Notes |
|-----------|-----------|-------|
| **ShiftStateHelper** | `app/src/main/java/com/nfo/tracker/work/ShiftStateHelper.kt` | Reads/writes username & display name from SharedPreferences |
| **SharedPreferences file** | `"nfo_tracker_prefs"` | Used across app for user session and shift state |
| **Key: username** | `KEY_USERNAME = "username"` | Stored username (defaults to `"NFO_TEST"` if not set) |
| **Key: display_name** | `KEY_DISPLAY_NAME = "display_name"` | Optional display name |
| **Key: on_shift** | `KEY_ON_SHIFT = "on_shift"` | Boolean flag for shift state |

**Current State (as of this mapping):**
- ❌ NO real login screen exists
- ❌ Username is hardcoded as `"NFO_TEST"` in `TrackingForegroundService`
- ✅ `ShiftStateHelper.getUsername()` and `getDisplayName()` exist and read from prefs
- ✅ `ShiftStateHelper.sendOffShiftHeartbeat()` and `sendLogoutHeartbeat()` exist

---

### Device Health Gate

| Component | File Path | Class/Function |
|-----------|-----------|----------------|
| **DeviceHealthChecker** | `app/src/main/java/com/nfo/tracker/device/DeviceHealthChecker.kt` | `object DeviceHealthChecker` |
| **DeviceHealthStatus** | `app/src/main/java/com/nfo/tracker/device/DeviceHealthChecker.kt` | `data class DeviceHealthStatus` |
| **DeviceHealthScreen** | `app/src/main/java/com/nfo/tracker/ui/DeviceHealthScreen.kt` | `@Composable DeviceHealthScreen()` |
| **DeviceSetupWizardScreen** | `app/src/main/java/com/nfo/tracker/ui/DeviceSetupWizardScreen.kt` | OEM-specific setup instructions |
| **PermissionGateScreen** | `app/src/main/java/com/nfo/tracker/ui/PermissionGateScreen.kt` | Requests location permission at launch |

**Health Checks Performed:**
- `locationPermissionOk` – FINE or COARSE location granted
- `backgroundLocationOk` – Background location (generous, FGS handles it)
- `locationEnabled` – GPS or Network provider enabled
- `batteryOptimizationOk` – Battery optimization (generous, FGS handles it)
- `networkOk` – Internet connectivity

**Critical vs Recommended:**
- **Critical** (blocks shift start): `locationPermissionOk`, `locationEnabled`, `networkOk`
- **Recommended** (shows warning): `backgroundLocationOk`, `batteryOptimizationOk`

**Decision Point:** In `TrackingScreen` → `handleGoOnShiftClicked()`:
```kotlin
if (status.isHealthy) {
    startShift()
} else {
    showDeviceHealthScreen = true
}
```

---

### Foreground Service & Heartbeats

| Component | File Path | Notes |
|-----------|-----------|-------|
| **TrackingForegroundService** | `app/src/main/java/com/nfo/tracker/tracking/TrackingForegroundService.kt` | FGS with `FOREGROUND_SERVICE_TYPE_LOCATION` |
| **HeartbeatEntity** | `app/src/main/java/com/nfo/tracker/data/local/HeartbeatEntity.kt` | Room entity for local heartbeat buffer |
| **HeartbeatDao** | `app/src/main/java/com/nfo/tracker/data/local/HeartbeatDao.kt` | Room DAO for CRUD operations |
| **HeartbeatDatabase** | `app/src/main/java/com/nfo/tracker/data/local/HeartbeatDatabase.kt` | Room database singleton |
| **HeartbeatWorker** | `app/src/main/java/com/nfo/tracker/work/HeartbeatWorker.kt` | WorkManager periodic sync (every 15 min) |
| **HealthWatchdogWorker** | `app/src/main/java/com/nfo/tracker/work/HealthWatchdogWorker.kt` | Monitors heartbeat freshness |
| **HealthWatchdogScheduler** | `app/src/main/java/com/nfo/tracker/work/HealthWatchdogScheduler.kt` | Schedules/cancels watchdog |
| **HeartbeatSyncHelper** | `app/src/main/java/com/nfo/tracker/work/HeartbeatSyncHelper.kt` | (if exists) sync utilities |
| **BootReceiver** | `app/src/main/java/com/nfo/tracker/BootReceiver.kt` | Handles device boot events |

**Service Actions:**
- `ACTION_START` – User-initiated start (from "Go On Shift")
- `ACTION_STOP` – User-initiated stop (from "Go Off Shift")
- `ACTION_START_FROM_WATCHDOG` – (NO-OP on Android 13+)
- `ACTION_START_FROM_BOOT` – (NO-OP on Android 13+)

**Location Updates:**
- Interval: 30 seconds
- Min interval: 15 seconds
- Priority: `PRIORITY_BALANCED_POWER_ACCURACY`

---

### Supabase Client & Configuration

| Component | File Path | Notes |
|-----------|-----------|-------|
| **SupabaseClient** | `app/src/main/java/com/nfo/tracker/data/remote/SupabaseClient.kt` | `object SupabaseClient` |
| **BASE_URL** | `https://rzivbeaqfhamlpsfaqov.supabase.co` | Supabase project URL |
| **API_KEY** | `sb_publishable_...` | Public API key (stored in code) |
| **Table Endpoint** | `/rest/v1/nfo_status?on_conflict=username` | Upsert endpoint |

**Key Function:**
```kotlin
suspend fun syncHeartbeats(heartbeats: List<HeartbeatEntity>): Boolean
```

**Upsert Headers:**
- `Prefer: return=minimal,resolution=merge-duplicates`

**Timezone:** All timestamps converted to `Asia/Riyadh` (UTC+3) before sending.

---

### HeartbeatEntity → nfo_status Mapping

| Room Column | Supabase Column | Type | Notes |
|-------------|-----------------|------|-------|
| `username` | `username` | text | Primary key / upsert key |
| `name` | `name` | text | Display name |
| `onShift` | `on_shift` | bool | Shift status |
| `status` | `status` | text | "on-shift", "off-shift", "device-silent" |
| `activity` | `activity` | text | Current activity type |
| `siteId` | `site_id` | text | Site identifier |
| `workOrderId` | `work_order_id` | text | Work order reference |
| `lat` | `lat` | float8 | Latitude |
| `lng` | `lng` | float8 | Longitude |
| `updatedAt` | `updated_at` | timestamptz | Last update time |
| `loggedIn` | `logged_in` | bool | Login status |
| `lastPing` | `last_ping` | timestamptz | Last ping time |
| `lastActiveSource` | `last_active_source` | text | Source of last activity |
| `lastActiveAt` | `last_active_at` | timestamptz | Time of last activity |
| `homeLocation` | `home_location` | text | Home location reference |

---

### File Structure Summary

```
app/src/main/java/com/nfo/tracker/
├── MainActivity.kt              # Entry point, NavHost, TrackingScreen
├── BootReceiver.kt              # BOOT_COMPLETED handler
├── data/
│   ├── local/
│   │   ├── HeartbeatDao.kt      # Room DAO
│   │   ├── HeartbeatDatabase.kt # Room database
│   │   └── HeartbeatEntity.kt   # Room entity
│   └── remote/
│       └── SupabaseClient.kt    # Supabase REST client
├── device/
│   └── DeviceHealthChecker.kt   # Health checks + DeviceHealthStatus
├── tracking/
│   └── TrackingForegroundService.kt  # Location FGS
├── ui/
│   ├── DeviceHealthScreen.kt    # Health gate UI
│   ├── DeviceSetupWizardScreen.kt    # OEM setup guide
│   ├── DiagnosticsScreen.kt     # Debug/diagnostics UI
│   ├── PermissionGateScreen.kt  # Initial permission request
│   └── theme/                   # Material theme files
└── work/
    ├── HeartbeatWorker.kt       # Periodic sync worker
    ├── HeartbeatSyncHelper.kt   # Sync utilities
    ├── HealthWatchdogScheduler.kt    # Watchdog scheduler
    ├── HealthWatchdogWorker.kt  # Heartbeat freshness monitor
    └── ShiftStateHelper.kt      # Session/username helper
```

---

### Supabase Tables Reference (for future implementation)

| Table | Purpose | Key Columns |
|-------|---------|-------------|
| `nfo_status` | Live NFO tracking status | `username` (PK), `on_shift`, `status`, `lat`, `lng`, etc. |
| `NFOusers` | NFO login credentials | `Username` (PK), `Password`, `name`, `home_location` |
| `MgmtUsers` | Management login | `Username` (PK), `Password` |
| `Site_Coordinates` | Site/warehouse locations | `ID_Serial`, `site_name`, `latitude`, `longitude`, `area`, `site_id` |
| `warehouses` | Warehouse list | `id`, `name`, `region`, `latitude`, `longitude`, `is_active` |

---

### TODO for Full NFO Workflow

- [ ] **Login Screen** – Validate credentials against `NFOusers` table
- [ ] **Store username in prefs** – Replace hardcoded `"NFO_TEST"`
- [ ] **Activity Selection Screen** – Site ID, Activity type, Via Warehouse
- [ ] **Update Activity** – Change activity mid-shift
- [ ] **Close Activity** – End current activity
- [ ] **Logout Flow** – Send logout heartbeat, clear session

---

*Last updated: December 2025*
