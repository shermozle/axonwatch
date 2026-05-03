# AxonWatch — Detection Reporting Protocol

This document describes the protocol used between the AxonWatch Android app and the collection server.

---

## Overview

The app reports Bluetooth detections **in real time** via a persistent WebSocket connection.
A REST API serves as a **fallback** when the socket is unavailable and as the future query interface
for sharing crowd-sourced location data back to clients.

```
App ──WS──▶ Server ──store──▶ DB
             │
             └──REST GET──▶ App (future: "nearby sightings")
```

---

## Authentication

All connections use a **Bearer token** issued by the server:

- WebSocket: `Authorization: Bearer <token>` header at connection time.
- REST: same `Authorization: Bearer <token>` header on every request.

Tokens are stored in the app's encrypted SharedPreferences and entered by the user in Settings.

---

## WebSocket

### Endpoint

```
wss://{host}/ws/detections
```

The connection is long-lived. The app reconnects automatically on failure with exponential back-off
(starting at 3 s, capped at 60 s).

### Message envelope

Every message (both directions) is a JSON object with a `type` field:

```jsonc
{
  "type": "<message_type>",
  "data": { /* payload, type-specific */ },
  "timestamp": "2026-05-03T12:00:00.000Z"   // ISO-8601 UTC, set by sender
}
```

---

### Client → Server messages

#### `detection`

Sent once per **new** match (deduplicated: same MAC is not re-reported within 30 s).

```jsonc
{
  "type": "detection",
  "timestamp": "2026-05-03T12:00:00.123Z",
  "data": {
    "mac":               "00:25:DF:AB:CD:EF",   // full MAC address
    "name":              "Device Name",           // BT display name, may be null
    "rssi":              -72,                     // signal strength in dBm
    "lat":               -33.8688,               // WGS-84, null if no GPS fix
    "lng":               151.2093,
    "location_accuracy": 12.5,                   // metres, null if unavailable
    "reporter_id":       "550e8400-e29b-41d4-a716-446655440000"  // stable device UUID
  }
}
```

#### `ping`

Keepalive sent every 30 s to maintain the connection through NAT/firewalls.

```jsonc
{ "type": "ping", "timestamp": "2026-05-03T12:00:30.000Z" }
```

---

### Server → Client messages

#### `pong`

Reply to a `ping`.

```jsonc
{ "type": "pong", "timestamp": "2026-05-03T12:00:30.010Z" }
```

#### `ack`

Acknowledgement of a received detection.

```jsonc
{
  "type": "ack",
  "timestamp": "2026-05-03T12:00:00.200Z",
  "data": {
    "detection_id": "7f3e9a12-...",   // server-assigned UUID for the stored record
    "mac":          "00:25:DF:AB:CD:EF"
  }
}
```

#### `nearby_detections` *(future)*

Proactively pushed when another reporter spots a matching device near the client's last known location.

```jsonc
{
  "type": "nearby_detections",
  "timestamp": "2026-05-03T12:01:00.000Z",
  "data": {
    "detections": [
      {
        "detection_id": "7f3e9a12-...",
        "mac":          "00:25:DF:AB:CD:EF",
        "lat":          -33.8700,
        "lng":          151.2100,
        "seen_at":      "2026-05-03T12:00:55.000Z",
        "reporter_count": 2
      }
    ]
  }
}
```

---

## REST API

Used as a **write fallback** when WebSocket is unavailable, and as the **read interface** for
future client queries.

Base URL: `https://{host}/api/v1`

All endpoints require `Authorization: Bearer <token>`.

---

### POST `/detections`

Report a single detection. Body is identical to the `data` object in the `detection` WS message.

```
POST /api/v1/detections
Content-Type: application/json

{
  "mac": "00:25:DF:AB:CD:EF",
  "name": "Device Name",
  "rssi": -72,
  "lat": -33.8688,
  "lng": 151.2093,
  "location_accuracy": 12.5,
  "reporter_id": "550e8400-..."
}
```

Response `201 Created`:
```json
{ "detection_id": "7f3e9a12-..." }
```

---

### POST `/detections/batch`

Report multiple detections in one call (used for queued events after reconnection).

```json
{ "detections": [ { /* same as single */ }, ... ] }
```

Response `201 Created`:
```json
{ "created": 5, "failed": 0 }
```

---

### GET `/detections` *(future — server-to-app sharing)*

Query crowd-sourced sightings near a location.

```
GET /api/v1/detections?lat=-33.8688&lng=151.2093&radius=500&since=1746273600
```

| Parameter | Type   | Description                          |
|-----------|--------|--------------------------------------|
| `lat`     | float  | Centre latitude (WGS-84)             |
| `lng`     | float  | Centre longitude                     |
| `radius`  | int    | Search radius in metres (max 5 000)  |
| `since`   | int    | Unix timestamp — only return results after this time |
| `mac`     | string | Optional — filter to a specific MAC  |

Response `200 OK`:
```jsonc
{
  "detections": [
    {
      "detection_id": "7f3e9a12-...",
      "mac":          "00:25:DF:AB:CD:EF",
      "lat":          -33.8700,
      "lng":          151.2100,
      "seen_at":      "2026-05-03T12:00:55.000Z",
      "reporter_count": 3          // number of distinct reporters who saw this event
    }
  ],
  "total": 1
}
```

---

### GET `/devices/{mac}/history` *(future)*

Retrieve the movement history of a specific device across all reporters.

```
GET /api/v1/devices/00:25:DF:AB:CD:EF/history?since=1746187200&until=1746273600
```

Response `200 OK`:
```jsonc
{
  "mac": "00:25:DF:AB:CD:EF",
  "points": [
    { "lat": -33.8688, "lng": 151.2093, "seen_at": "2026-05-03T08:00:00Z", "reporter_count": 1 },
    { "lat": -33.8700, "lng": 151.2100, "seen_at": "2026-05-03T09:15:00Z", "reporter_count": 2 }
  ]
}
```

---

## Privacy & Security notes

- MAC addresses are stored as-is (no hashing) so devices can be tracked across sightings.
  Server access should be restricted to authorised users.
- Reporter UUIDs are randomly generated at first install and never tied to PII in the client.
- All transport must use TLS (`wss://` / `https://`).
- The server should rate-limit POST `/detections` to prevent flooding (suggested: 60 req/min/token).
