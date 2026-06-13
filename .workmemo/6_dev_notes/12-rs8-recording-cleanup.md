# R-S8 — Recording Data Auto-Cleanup

## What was done

Implemented automatic recording cleanup based on retention time and storage size limits.

### 1. ServerConfig additions
- `recordingRetentionDays` (already existed, default 7) — number of days to retain recordings
- `recordingMaxSizeMb` (new, default 500) — maximum total recording storage in MB

### 2. StorageService interface additions
- `deleteRecordingsOlderThan(int retentionDays)` — delete recordings older than N days, returns count
- `getRecordingCount()` — get total recording count
- `getRecordingTotalSizeBytes()` — get estimated total recording size in bytes

### 3. RecordingCleanupTask
Scheduled task that runs every hour:
- **Time-based cleanup**: Deletes recordings older than `recordingRetentionDays`
- **Size-based cleanup**: If total size exceeds `recordingMaxSizeMb`, deletes oldest recordings until under limit
- Logs all cleanup actions at INFO level
- Uses a daemon thread (`baafoo-recording-cleanup`) so it doesn't block JVM shutdown

### 4. RecordingMapper additions
- `deleteRecordingsOlderThan(cutoffTime)` — SQL DELETE for old recordings
- `countAllRecordings()` — SQL COUNT for total recordings
- `listOldestRecordings(limit)` — SQL SELECT for oldest recordings (for size-based cleanup)

### 5. BaafooServer integration
- RecordingCleanupTask is started after storage initialization
- RecordingCleanupTask is stopped during server shutdown

## Files modified
- `baafoo-core/src/main/java/com/baafoo/core/config/ServerConfig.java` — added `recordingMaxSizeMb`
- `baafoo-server/src/main/java/com/baafoo/server/storage/StorageService.java` — added cleanup interface methods
- `baafoo-server/src/main/java/com/baafoo/server/storage/JdbcStorageService.java` — implemented cleanup methods
- `baafoo-server/src/main/java/com/baafoo/server/storage/FileStorage.java` — implemented cleanup methods
- `baafoo-server/src/main/java/com/baafoo/server/storage/RecordingCleanupTask.java` — new cleanup task
- `baafoo-server/src/main/java/com/baafoo/server/storage/mapper/RecordingMapper.java` — added mapper methods
- `baafoo-server/src/main/resources/mapper/RecordingMapper.xml` — added SQL statements
- `baafoo-server/src/main/java/com/baafoo/server/bootstrap/BaafooServer.java` — integrated cleanup task
