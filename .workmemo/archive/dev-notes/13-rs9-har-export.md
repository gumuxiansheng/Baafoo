# R-S9 — HAR Export

## What was done

Implemented HAR 1.2 export for recording entries.

### 1. HarExporter utility class
Converts `RecordingEntry` list to HAR 1.2 JSON format:
- `log.version`: "1.2"
- `log.creator`: {"name": "Baafoo", "version": "1.0.0"}
- `log.entries[]`: each entry has:
  - `request`: method, url, headers[], postData
  - `response`: status, statusText, headers[], content{mimeType, size, text}
  - `time`: response time in ms
  - `startedDateTime`: ISO 8601 format

### 2. API endpoint
`GET /__baafoo__/api/logs/export/har`
- Query params: `ruleId` (optional filter), `limit` (default 1000, max 10000)
- Returns raw HAR JSON with `Content-Disposition: attachment; filename="baafoo-export.har"`
- Bypasses ApiResponse wrapping via `RawJsonResponse`

### 3. RawJsonResponse class
New response type that allows handlers to return raw JSON content without ApiResponse wrapping.
ManagementApiHandler detects this type and sends the content directly.

## Files modified
- `baafoo-server/src/main/java/com/baafoo/server/api/HarExporter.java` — new HAR exporter
- `baafoo-server/src/main/java/com/baafoo/server/api/RawJsonResponse.java` — new raw JSON response wrapper
- `baafoo-server/src/main/java/com/baafoo/server/api/RecordingApiHandler.java` — added HAR export endpoint
- `baafoo-server/src/main/java/com/baafoo/server/api/ManagementApiHandler.java` — added sendRawJson support
