# ClashReminders — Vollständiger Implementationsplan

> **Datum:** 09.02.2026
> **Referenz-Features:** `missinghits` + `listeningevents` aus LostManager
> **Stack:** Python/FastAPI Backend, Kotlin/Jetpack Compose Android App, SQLite DB

---

## Inhaltsverzeichnis

1. [Gesamtüberblick & Funktionsabgrenzung](#1-gesamtüberblick--funktionsabgrenzung)
2. [Datenmodell (Backend)](#2-datenmodell-backend)
3. [Backend — Polling-Engine & Datenerfassung](#3-backend--polling-engine--datenerfassung)
4. [Backend — Reminder-System (ListeningEvents-Analog)](#4-backend--reminder-system-listeningevents-analog)
5. [Backend — API-Endpunkte](#5-backend--api-endpunkte)
6. [Android — Account- & Clan-Management](#6-android--account---clan-management)
7. [Android — Reminder-Konfiguration](#7-android--reminder-konfiguration)
8. [Android — MissingHits-Widget](#8-android--missinghits-widget)
9. [Android — Hauptscreen-Überarbeitung](#9-android--hauptscreen-überarbeitung)
10. [Push-Notifications (FCM)](#10-push-notifications-fcm)
11. [Phasenplan & Abhängigkeiten](#11-phasenplan--abhängigkeiten)
12. [Technische Details & Edge Cases](#12-technische-details--edge-cases)

---

## 1. Gesamtüberblick & Funktionsabgrenzung

### Was gebaut wird

| Feature | Vorbild (LostManager) | Anpassung für ClashReminders |
|---|---|---|
| **Account-Management** | `/link`, `players`-Tabelle | User verknüpft eigene CoC-Tags via App |
| **Clan-Management** | `clans`/`sideclans`-Tabellen | User gibt Clans an, in denen nach seinen Accounts gecheckt wird |
| **MissingHits-Anzeige** | `/missinghits` Command | Hauptscreen + Home-Widget, minütlich gepollt |
| **Reminder-Konfiguration** | `/listeningevent add` | Pro Event-Typ (CW/CWL/Raid) eine Liste von Reminder-Zeitpunkten |
| **Push-Benachrichtigungen** | Discord-Nachrichten | FCM Push Notifications |

### Kernprinzipien

- **Accounts sind der Dreh- und Angelpunkt**: Der User registriert seine Spieler-Tags. Clans werden *nur* als Scope angegeben — wenn ein Account in keinem der angegebenen Clans ist, wird er ignoriert (kein Fehler).
- **Zentrale Datenerfassung**: Ein minütlicher Background-Poller auf dem Backend sammelt **alle** War/CWL/Raid-Daten einheitlich in einer `event_snapshots`-Tabelle. Daraus speisen sich:
  - Die MissingHits-Anzeige (App + Widget)
  - Die Reminder-Logik (wann wird gepusht)
- **Anzeige immer als `Name (Tag)`**: z.B. `Lost Warrior (#P00P)` bzw. `LOST (#2YYCQ8VP2)`.

---

## 2. Datenmodell (Backend)

### 2.1 Bestehende Tabellen (angepasst)

#### `users` (erweitert)
```sql
CREATE TABLE users (
    id              TEXT PRIMARY KEY DEFAULT (uuid4()),
    fcm_token       TEXT,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    notification_enabled BOOLEAN DEFAULT TRUE,
    timezone        TEXT DEFAULT 'Europe/Berlin'
);
```

#### `player_accounts` (erweitert)
```sql
CREATE TABLE player_accounts (
    tag             TEXT NOT NULL,
    name            TEXT,                -- gecached, periodisch aktualisiert
    user_id         TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    current_clan_tag TEXT,               -- gecached vom letzten Poll
    current_clan_name TEXT,              -- gecached vom letzten Poll
    last_synced_at  DATETIME,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tag, user_id)           -- gleicher Tag kann bei verschiedenen Usern sein
);
```
> **Änderung:** PK ist jetzt `(tag, user_id)` statt nur `tag`, da mehrere User denselben Account tracken könnten. `game_type` entfällt (nur CoC).

### 2.2 Neue Tabellen

#### `tracked_clans`
```sql
CREATE TABLE tracked_clans (
    clan_tag        TEXT NOT NULL,
    clan_name       TEXT,                -- gecached, periodisch aktualisiert
    user_id         TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (clan_tag, user_id)
);
```
> Clans, die der User angegeben hat. Beim Poll wird **nur** in diesen Clans nach den Accounts des Users geschaut.

#### `event_snapshots` ⭐ (Zentrale Datentabelle)
```sql
CREATE TABLE event_snapshots (
    id              TEXT PRIMARY KEY DEFAULT (uuid4()),
    user_id         TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    account_tag     TEXT NOT NULL,
    account_name    TEXT,
    clan_tag        TEXT NOT NULL,
    clan_name       TEXT,
    event_type      TEXT NOT NULL,       -- 'cw', 'cwl', 'raid'
    event_subtype   TEXT,                -- CWL: 'day_1', 'day_2', ..., 'day_7'
    
    -- Status-Daten
    state           TEXT NOT NULL,       -- 'preparation', 'inWar', 'warEnded', 'ongoing', 'ended', 'notInWar'
    attacks_used    INTEGER DEFAULT 0,
    attacks_max     INTEGER DEFAULT 0,
    attacks_remaining INTEGER GENERATED ALWAYS AS (attacks_max - attacks_used) STORED,
    
    -- Zeitdaten
    end_time        DATETIME,            -- Wann endet das Event (UTC)
    start_time      DATETIME,            -- Wann hat das Event begonnen (UTC)
    
    -- Zusätzliche Infos
    opponent_name   TEXT,                -- CW/CWL: Gegner-Clan
    opponent_tag    TEXT,
    war_size        INTEGER,             -- CW/CWL: Teamgröße
    
    -- Meta
    is_active       BOOLEAN DEFAULT TRUE,  -- Nur aktive Events werden angezeigt
    polled_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE(user_id, account_tag, clan_tag, event_type, event_subtype)
);
```
> **Zentrale Idee:** Jeder minütliche Poll aktualisiert (UPSERT) diese Tabelle. Ein Eintrag pro Account×Clan×EventType×Subtype. `is_active = FALSE` wenn das Event vorbei ist. Frontend und Reminder-Engine lesen beide von hier.

#### `reminder_configs`
```sql
CREATE TABLE reminder_configs (
    id              TEXT PRIMARY KEY DEFAULT (uuid4()),
    user_id         TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_type      TEXT NOT NULL,       -- 'cw', 'cwl', 'raid'
    enabled         BOOLEAN DEFAULT TRUE,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP
);
```

#### `reminder_times` (Die "Liste" von Reminder-Zeitpunkten)
```sql
CREATE TABLE reminder_times (
    id              TEXT PRIMARY KEY DEFAULT (uuid4()),
    reminder_config_id TEXT NOT NULL REFERENCES reminder_configs(id) ON DELETE CASCADE,
    minutes_before_end INTEGER NOT NULL,  -- z.B. 60 = 1h vorher, 240 = 4h vorher
    label           TEXT,                 -- z.B. "1 Stunde vorher", optional
    enabled         BOOLEAN DEFAULT TRUE,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP
);
```
> **Analog zu ListeningEvents:** Jeder Eintrag in `reminder_times` ist ein separater Trigger-Zeitpunkt. Der User kann pro Event-Typ beliebig viele Zeitpunkte konfigurieren. Beispiel:
> - CW-Config: `[60min, 240min, 30min]` → 3 separate Push-Zeitpunkte
> - Raid-Config: `[120min, 480min]` → 2 separate Push-Zeitpunkte
> - CWL-Config: `[60min]` → 1 Push-Zeitpunkt

#### `notification_log` (Duplikat-Schutz)
```sql
CREATE TABLE notification_log (
    id              TEXT PRIMARY KEY DEFAULT (uuid4()),
    user_id         TEXT NOT NULL,
    event_snapshot_id TEXT NOT NULL,
    reminder_time_id TEXT NOT NULL,
    sent_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
    status          TEXT DEFAULT 'sent',  -- 'sent', 'failed', 'skipped'
    fcm_message_id  TEXT,
    
    UNIQUE(event_snapshot_id, reminder_time_id)  -- Verhindert doppelte Benachrichtigungen
);
```

### 2.3 ER-Diagramm

```
users
  │
  ├──1:N──> player_accounts (tag, user_id)
  │
  ├──1:N──> tracked_clans (clan_tag, user_id)
  │
  ├──1:N──> reminder_configs
  │              │
  │              └──1:N──> reminder_times (minutes_before_end)
  │
  └──1:N──> event_snapshots ←── Befüllt durch Poller
                 │
                 └──1:N──> notification_log ←── Befüllt durch Reminder-Engine
```

---

## 3. Backend — Polling-Engine & Datenerfassung

### 3.1 Architektur

```
┌──────────────────────────────────────────────────┐
│                  APScheduler                       │
│  ┌────────────┐    ┌─────────────────────────┐    │
│  │ Poll Job   │    │ Reminder Check Job       │    │
│  │ (60 sek)   │    │ (60 sek, versetzt 30s)  │    │
│  └─────┬──────┘    └──────────┬──────────────┘    │
│        │                      │                    │
│        ▼                      ▼                    │
│  ┌─────────────┐    ┌─────────────────────────┐   │
│  │ DataPoller  │    │ ReminderEngine           │   │
│  │ Service     │    │ Service                  │   │
│  └─────┬──────┘    └──────────┬──────────────┘    │
│        │                      │                    │
│        ▼                      ▼                    │
│  event_snapshots DB     notification_log DB        │
│        │                      │                    │
│        │                      ▼                    │
│        │               FCM Push Service            │
│        │                                           │
│        ▼                                           │
│  GET /api/v1/.../status  (App + Widget lesen)      │
└──────────────────────────────────────────────────┘
```

### 3.2 DataPoller Service — `services/data_poller.py` (NEU)

**Ablauf des minütlichen Poll-Jobs:**

```python
async def poll_all_users():
    """Hauptmethode — wird jede 60 Sekunden aufgerufen."""
    
    users = db.query(User).all()
    
    for user in users:
        accounts = get_accounts_for_user(user.id)
        clans = get_tracked_clans_for_user(user.id)
        
        # Sammle alle einzigartigen Clan-Tags
        clan_tags = {c.clan_tag for c in clans}
        
        # Cache: Clan-Daten nur einmal pro Clan holen
        clan_data_cache = {}
        
        for clan_tag in clan_tags:
            clan_data_cache[clan_tag] = await fetch_clan_events(clan_tag)
        
        # Für jeden Account prüfen, in welchem Clan er ist
        for account in accounts:
            for clan_tag, events in clan_data_cache.items():
                await process_account_in_clan(user, account, clan_tag, events)
```

**`fetch_clan_events(clan_tag)` — Holt alle Event-Daten eines Clans:**

```python
async def fetch_clan_events(clan_tag: str) -> dict:
    """Holt CW, CWL und Raid-Daten für einen Clan."""
    
    result = {
        "cw": None,
        "cwl": None,      # Liste von Tages-Wars
        "raid": None,
        "clan_name": None
    }
    
    # 1. Clan War (Normal)
    cw_data = await coc_api.get_current_war(clan_tag)
    if cw_data and cw_data.get("state") in ("preparation", "inWar"):
        result["cw"] = cw_data
        result["clan_name"] = cw_data.get("clan", {}).get("name")
    
    # 2. CWL — Nur wenn kein normaler CW aktiv (CW und CWL schließen sich aus)
    if not result["cw"] or cw_data.get("state") == "notInWar":
        cwl_group = await coc_api.get_cwl_group(clan_tag)
        if cwl_group and "rounds" in cwl_group:
            cwl_wars = []
            for round_idx, round_data in enumerate(cwl_group["rounds"]):
                for war_tag in round_data.get("warTags", []):
                    if war_tag == "#0":
                        continue
                    war = await coc_api.get_cwl_war(war_tag)
                    if war and war.get("state") == "inWar":
                        # Prüfe ob unser Clan beteiligt ist
                        if (war.get("clan", {}).get("tag") == clan_tag or 
                            war.get("opponent", {}).get("tag") == clan_tag):
                            war["_round_index"] = round_idx + 1
                            cwl_wars.append(war)
            result["cwl"] = cwl_wars if cwl_wars else None
    
    # 3. Raid Weekend
    raid_data = await coc_api.get_raid_seasons(clan_tag)  # Neuer API-Call nötig
    if raid_data and raid_data.get("items"):
        current_raid = raid_data["items"][0]
        if current_raid.get("state") == "ongoing":
            result["raid"] = current_raid
    
    return result
```

**`process_account_in_clan()` — Prüft einen Account gegen Clan-Events:**

```python
async def process_account_in_clan(user, account, clan_tag, events):
    """Prüft ob ein Account in CW/CWL/Raid des Clans ist und aktualisiert Snapshots."""
    
    clan_name = events.get("clan_name", clan_tag)
    
    # --- CLAN WAR ---
    if events["cw"]:
        war = events["cw"]
        # Bestimme welche Seite unser Clan ist
        our_side = "clan" if war["clan"]["tag"] == clan_tag else "opponent"
        members = war.get(our_side, {}).get("members", [])
        player = find_player(members, account.tag)
        
        if player:
            attacks_used = len(player.get("attacks", []))
            attacks_max = war.get("attacksPerMember", 2)
            
            upsert_event_snapshot(
                user_id=user.id,
                account_tag=account.tag,
                account_name=player.get("name", account.name),
                clan_tag=clan_tag,
                clan_name=clan_name,
                event_type="cw",
                event_subtype=None,
                state=war["state"],
                attacks_used=attacks_used,
                attacks_max=attacks_max,
                end_time=parse_coc_timestamp(war["endTime"]),
                start_time=parse_coc_timestamp(war["startTime"]),
                opponent_name=war.get("opponent" if our_side == "clan" else "clan", {}).get("name"),
                opponent_tag=war.get("opponent" if our_side == "clan" else "clan", {}).get("tag"),
                war_size=war.get("teamSize"),
                is_active=(war["state"] == "inWar" and attacks_used < attacks_max)
            )
    
    # --- CWL ---
    if events["cwl"]:
        for war in events["cwl"]:
            our_side = "clan" if war["clan"]["tag"] == clan_tag else "opponent"
            members = war.get(our_side, {}).get("members", [])
            player = find_player(members, account.tag)
            
            if player:
                attacks_used = len(player.get("attacks", []))
                attacks_max = 1  # CWL = immer 1 Angriff
                round_idx = war.get("_round_index", 0)
                
                upsert_event_snapshot(
                    user_id=user.id,
                    account_tag=account.tag,
                    account_name=player.get("name", account.name),
                    clan_tag=clan_tag,
                    clan_name=clan_name,
                    event_type="cwl",
                    event_subtype=f"day_{round_idx}",
                    state=war["state"],
                    attacks_used=attacks_used,
                    attacks_max=attacks_max,
                    end_time=parse_coc_timestamp(war["endTime"]),
                    start_time=parse_coc_timestamp(war["startTime"]),
                    opponent_name=war.get("opponent" if our_side == "clan" else "clan", {}).get("name"),
                    opponent_tag=war.get("opponent" if our_side == "clan" else "clan", {}).get("tag"),
                    war_size=war.get("teamSize"),
                    is_active=(war["state"] == "inWar" and attacks_used < attacks_max)
                )
    
    # --- RAID WEEKEND ---
    if events["raid"]:
        raid = events["raid"]
        raid_members = raid.get("members", [])
        player = next((m for m in raid_members if m["tag"] == account.tag), None)
        
        if player:
            attacks_used = player.get("attacks", 0)
            attacks_max = player.get("attackLimit", 5) + player.get("bonusAttackLimit", 1)
            
            upsert_event_snapshot(
                user_id=user.id,
                account_tag=account.tag,
                account_name=player.get("name", account.name),
                clan_tag=clan_tag,
                clan_name=clan_name,
                event_type="raid",
                event_subtype=None,
                state=raid["state"],
                attacks_used=attacks_used,
                attacks_max=attacks_max,
                end_time=parse_coc_timestamp(raid["endTime"]),
                start_time=parse_coc_timestamp(raid["startTime"]),
                opponent_name=None,
                opponent_tag=None,
                war_size=None,
                is_active=(attacks_used < attacks_max)
            )
        else:
            # Account ist im Clan aber nimmt nicht an Raid teil
            upsert_event_snapshot(
                user_id=user.id,
                account_tag=account.tag,
                account_name=account.name,
                clan_tag=clan_tag,
                clan_name=clan_name,
                event_type="raid",
                event_subtype=None,
                state="not_participating",
                attacks_used=0,
                attacks_max=0,
                end_time=parse_coc_timestamp(raid["endTime"]),
                start_time=parse_coc_timestamp(raid["startTime"]),
                opponent_name=None,
                opponent_tag=None,
                war_size=None,
                is_active=False
            )
```

### 3.3 Cleanup-Logik

```python
async def cleanup_stale_snapshots():
    """Wird nach jedem Poll-Zyklus aufgerufen. Markiert abgelaufene Events als inaktiv."""
    
    now = datetime.utcnow()
    
    # Alle Snapshots deren end_time abgelaufen ist → is_active = False
    db.execute(
        update(EventSnapshot)
        .where(EventSnapshot.end_time < now, EventSnapshot.is_active == True)
        .values(is_active=False)
    )
    
    # Snapshots die älter als 48h sind und inaktiv → löschen
    cutoff = now - timedelta(hours=48)
    db.execute(
        delete(EventSnapshot)
        .where(EventSnapshot.polled_at < cutoff, EventSnapshot.is_active == False)
    )
```

### 3.4 CoC API Erweiterungen — `services/coc_api.py`

Neue Methoden die hinzugefügt werden müssen:

```python
async def get_raid_seasons(self, clan_tag: str):
    """GET /clans/{tag}/capitalraidseasons?limit=1"""
    safe_tag = urllib.parse.quote(clan_tag)
    return await self._get(f"/clans/{safe_tag}/capitalraidseasons?limit=1")

async def get_clan_info(self, clan_tag: str):
    """GET /clans/{tag} — Basis-Clan-Infos (Name, Badge, etc.)"""
    safe_tag = urllib.parse.quote(clan_tag)
    return await self._get(f"/clans/{safe_tag}")
```

### 3.5 Rate-Limiting & Optimierung

- **Globaler Clan-Cache pro Poll-Zyklus:** Wenn mehrere User denselben Clan tracken, wird die CoC API nur 1x pro Clan aufgerufen, nicht pro User.
- **Batch-Verarbeitung:** Alle einzigartigen Clan-Tags über alle User hinweg sammeln → parallel fetchen → Ergebnisse pro User verteilen.
- **CoC API Rate-Limit:** Max ~30-40 Requests/sec. Bei z.B. 20 Clans: 20 (CW) + 20 (CWL Group) + ~40 (CWL Wars) + 20 (Raid) = ~100 Requests → über 60 Sekunden verteilt kein Problem.
- **Exponentielles Backoff** bei 429/5xx-Responses (bereits im `CoCClient._get()` vorhanden, muss erweitert werden).

---

## 4. Backend — Reminder-System (ListeningEvents-Analog)

### 4.1 ReminderEngine Service — `services/reminder_engine.py` (NEU)

**Ablauf des Reminder-Check-Jobs (alle 60 Sek, 30 Sek versetzt zum Poller):**

```python
async def check_reminders():
    """Prüft alle aktiven Event-Snapshots gegen User-Reminder-Konfigurationen."""
    
    now = datetime.utcnow()
    
    # Alle aktiven Snapshots wo Angriffe fehlen
    active_snapshots = db.query(EventSnapshot).filter(
        EventSnapshot.is_active == True,
        EventSnapshot.state.in_(["inWar", "ongoing"]),
        EventSnapshot.attacks_remaining > 0
    ).all()
    
    for snapshot in active_snapshots:
        user = get_user(snapshot.user_id)
        if not user or not user.notification_enabled or not user.fcm_token:
            continue
        
        # Passende Reminder-Configs für diesen Event-Typ
        configs = db.query(ReminderConfig).filter(
            ReminderConfig.user_id == user.id,
            ReminderConfig.event_type == snapshot.event_type,
            ReminderConfig.enabled == True
        ).all()
        
        for config in configs:
            times = db.query(ReminderTime).filter(
                ReminderTime.reminder_config_id == config.id,
                ReminderTime.enabled == True
            ).all()
            
            for rt in times:
                trigger_time = snapshot.end_time - timedelta(minutes=rt.minutes_before_end)
                
                # Ist es jetzt Zeit zu feuern? (±90 Sek Toleranz)
                if abs((now - trigger_time).total_seconds()) <= 90:
                    # Duplicate-Check
                    already_sent = db.query(NotificationLog).filter(
                        NotificationLog.event_snapshot_id == snapshot.id,
                        NotificationLog.reminder_time_id == rt.id
                    ).first()
                    
                    if not already_sent:
                        await send_reminder(user, snapshot, rt)
```

### 4.2 Notification-Nachricht

```python
async def send_reminder(user, snapshot, reminder_time):
    """Sendet eine FCM Push-Notification."""
    
    time_left = format_duration(snapshot.end_time - datetime.utcnow())
    
    # Titel
    event_labels = {"cw": "Clan War", "cwl": "CWL", "raid": "Raid Weekend"}
    title = f"⚔️ {event_labels[snapshot.event_type]} — {snapshot.attacks_remaining} Angriff(e) übrig!"
    
    # Body
    body_parts = [
        f"👤 {snapshot.account_name} ({snapshot.account_tag})",
        f"🏰 {snapshot.clan_name} ({snapshot.clan_tag})",
    ]
    if snapshot.opponent_name:
        body_parts.append(f"⚔️ vs. {snapshot.opponent_name}")
    body_parts.append(f"⏰ {time_left} verbleibend")
    body = "\n".join(body_parts)
    
    # FCM senden
    success = await fcm_service.send_push(
        token=user.fcm_token,
        title=title,
        body=body,
        data={
            "event_type": snapshot.event_type,
            "account_tag": snapshot.account_tag,
            "clan_tag": snapshot.clan_tag,
            "end_time": snapshot.end_time.isoformat()
        }
    )
    
    # Log
    log = NotificationLog(
        user_id=user.id,
        event_snapshot_id=snapshot.id,
        reminder_time_id=reminder_time.id,
        status="sent" if success else "failed"
    )
    db.add(log)
    db.commit()
```

### 4.3 FCM Service — `services/fcm_service.py` (NEU)

```python
import firebase_admin
from firebase_admin import credentials, messaging

class FCMService:
    def __init__(self):
        cred = credentials.Certificate("firebase-service-account.json")
        firebase_admin.initialize_app(cred)
    
    async def send_push(self, token: str, title: str, body: str, data: dict = None) -> bool:
        message = messaging.Message(
            notification=messaging.Notification(title=title, body=body),
            data=data or {},
            token=token,
            android=messaging.AndroidConfig(
                priority="high",
                notification=messaging.AndroidNotification(
                    channel_id="clash_reminders",
                    icon="ic_notification"
                )
            )
        )
        try:
            response = messaging.send(message)
            return True
        except Exception as e:
            logger.error(f"FCM send failed: {e}")
            return False
```

---

## 5. Backend — API-Endpunkte

### 5.1 Bestehende Endpunkte (bleiben)

| Methode | Pfad | Zweck |
|---|---|---|
| POST | `/api/v1/users/register` | User registrieren |
| PUT | `/api/v1/users/{user_id}/fcm` | FCM-Token aktualisieren |
| GET | `/api/v1/users/{user_id}/accounts` | Accounts auflisten |
| POST | `/api/v1/users/{user_id}/accounts` | Account hinzufügen |
| DELETE | `/api/v1/users/{user_id}/accounts/{tag}` | Account entfernen |

### 5.2 Neue Endpunkte — Clan-Management

| Methode | Pfad | Zweck |
|---|---|---|
| GET | `/api/v1/users/{user_id}/clans` | Getrackte Clans auflisten |
| POST | `/api/v1/users/{user_id}/clans` | Clan hinzufügen |
| DELETE | `/api/v1/users/{user_id}/clans/{clan_tag}` | Clan entfernen |

**POST `/api/v1/users/{user_id}/clans`:**
```json
// Request
{ "clan_tag": "#2YYCQ8VP2" }

// Response (200)
{
    "clan_tag": "#2YYCQ8VP2",
    "clan_name": "LOST",
    "user_id": "abc-123"
}
```
> Validierung: Clan-Tag wird gegen die CoC API geprüft. Clan-Name wird gecached.

### 5.3 Neue Endpunkte — Reminder-Konfiguration

| Methode | Pfad | Zweck |
|---|---|---|
| GET | `/api/v1/users/{user_id}/reminders` | Alle Reminder-Konfigurationen |
| PUT | `/api/v1/users/{user_id}/reminders` | Komplette Reminder-Config setzen (Bulk) |
| POST | `/api/v1/users/{user_id}/reminders/{event_type}/times` | Einzelnen Zeitpunkt hinzufügen |
| DELETE | `/api/v1/users/{user_id}/reminders/{event_type}/times/{time_id}` | Zeitpunkt entfernen |
| PATCH | `/api/v1/users/{user_id}/reminders/{event_type}` | Event-Typ aktivieren/deaktivieren |

**GET `/api/v1/users/{user_id}/reminders` — Response:**
```json
{
    "reminders": [
        {
            "id": "config-uuid-1",
            "event_type": "cw",
            "enabled": true,
            "times": [
                { "id": "time-uuid-1", "minutes_before_end": 60, "label": "1h vorher", "enabled": true },
                { "id": "time-uuid-2", "minutes_before_end": 240, "label": "4h vorher", "enabled": true },
                { "id": "time-uuid-3", "minutes_before_end": 30, "label": "30min vorher", "enabled": true }
            ]
        },
        {
            "id": "config-uuid-2",
            "event_type": "cwl",
            "enabled": true,
            "times": [
                { "id": "time-uuid-4", "minutes_before_end": 60, "label": "1h vorher", "enabled": true }
            ]
        },
        {
            "id": "config-uuid-3",
            "event_type": "raid",
            "enabled": false,
            "times": []
        }
    ]
}
```

**PUT `/api/v1/users/{user_id}/reminders` — Bulk-Update:**
```json
{
    "reminders": [
        {
            "event_type": "cw",
            "enabled": true,
            "times": [
                { "minutes_before_end": 60 },
                { "minutes_before_end": 240 },
                { "minutes_before_end": 30 }
            ]
        },
        {
            "event_type": "cwl",
            "enabled": true,
            "times": [
                { "minutes_before_end": 60 }
            ]
        },
        {
            "event_type": "raid",
            "enabled": true,
            "times": [
                { "minutes_before_end": 120 },
                { "minutes_before_end": 480 }
            ]
        }
    ]
}
```
> Dieser Endpunkt ersetzt die komplette Reminder-Config. Einfacher für die App als einzelne CRUD-Operationen.

### 5.4 Neue Endpunkte — Event-Status (MissingHits)

| Methode | Pfad | Zweck |
|---|---|---|
| GET | `/api/v1/users/{user_id}/status` | Alle aktiven Event-Snapshots (MissingHits-Daten) |
| GET | `/api/v1/users/{user_id}/status/summary` | Kompakte Zusammenfassung fürs Widget |

**GET `/api/v1/users/{user_id}/status` — Response:**
```json
{
    "last_polled": "2026-02-09T14:30:00Z",
    "events": [
        {
            "id": "snap-uuid-1",
            "account_tag": "#P00P",
            "account_name": "Lost Warrior",
            "clan_tag": "#2YYCQ8VP2",
            "clan_name": "LOST",
            "event_type": "cw",
            "event_subtype": null,
            "state": "inWar",
            "attacks_used": 1,
            "attacks_max": 2,
            "attacks_remaining": 1,
            "end_time": "2026-02-09T18:30:00Z",
            "time_remaining_seconds": 14400,
            "time_remaining_formatted": "4h 0m",
            "opponent_name": "Enemy Clan",
            "opponent_tag": "#9XYZ",
            "war_size": 30
        },
        {
            "id": "snap-uuid-2",
            "account_tag": "#P00P",
            "account_name": "Lost Warrior",
            "clan_tag": "#2YYCQ8VP2",
            "clan_name": "LOST",
            "event_type": "raid",
            "event_subtype": null,
            "state": "ongoing",
            "attacks_used": 3,
            "attacks_max": 6,
            "attacks_remaining": 3,
            "end_time": "2026-02-10T07:00:00Z",
            "time_remaining_seconds": 59400,
            "time_remaining_formatted": "16h 30m",
            "opponent_name": null,
            "opponent_tag": null,
            "war_size": null
        }
    ]
}
```

**GET `/api/v1/users/{user_id}/status/summary` — Widget-optimiert:**
```json
{
    "last_polled": "2026-02-09T14:30:00Z",
    "total_missing": 4,
    "by_event_type": {
        "cw": { "count": 1, "accounts": 1 },
        "cwl": { "count": 0, "accounts": 0 },
        "raid": { "count": 3, "accounts": 1 }
    },
    "items": [
        {
            "account_display": "Lost Warrior (#P00P)",
            "clan_display": "LOST (#2YYCQ8VP2)",
            "event_label": "Clan War",
            "attacks_remaining": 1,
            "end_time_formatted": "4h 0m",
            "end_time_iso": "2026-02-09T18:30:00Z"
        }
    ]
}
```

---

## 6. Android — Account- & Clan-Management

### 6.1 Neue Screens

#### `AccountManagementScreen.kt`

**Layout:**
```
┌─────────────────────────────────────────┐
│  ← Meine Accounts                       │
├─────────────────────────────────────────┤
│                                          │
│  ┌─────────────────────────────────────┐ │
│  │ 👤 Lost Warrior (#P00P)            │ │
│  │    🏰 Aktuell in: LOST (#2YYC...) │ │
│  │                            [🗑️]    │ │
│  └─────────────────────────────────────┘ │
│                                          │
│  ┌─────────────────────────────────────┐ │
│  │ 👤 Mini Account (#ABC123)          │ │
│  │    🏰 Aktuell in: LOST 2 (#XYZ)  │ │
│  │                            [🗑️]    │ │
│  └─────────────────────────────────────┘ │
│                                          │
│  ┌ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐ │
│  │  + Account hinzufügen              │ │
│  │  [_____#TAG eingeben_____] [ADD]   │ │
│  └ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┘ │
│                                          │
└─────────────────────────────────────────┘
```

**Funktionalität:**
- Liste aller verknüpften Accounts mit `Name (Tag)` Anzeige
- Aktueller Clan wird angezeigt (gecached vom letzten Poll)
- Swipe-to-Delete oder Trash-Icon zum Entfernen
- Eingabefeld unten zum Hinzufügen neuer Tags
- Validierung: Tag wird ans Backend gesendet → API-Check → Bestätigung mit Name

#### `ClanManagementScreen.kt`

**Layout:**
```
┌─────────────────────────────────────────┐
│  ← Meine Clans                          │
├─────────────────────────────────────────┤
│                                          │
│  ℹ️ Diese Clans werden auf deine         │
│  Accounts geprüft.                       │
│                                          │
│  ┌─────────────────────────────────────┐ │
│  │ 🏰 LOST (#2YYCQ8VP2)              │ │
│  │                            [🗑️]    │ │
│  └─────────────────────────────────────┘ │
│                                          │
│  ┌─────────────────────────────────────┐ │
│  │ 🏰 LOST 2 (#XYZ123)               │ │
│  │                            [🗑️]    │ │
│  └─────────────────────────────────────┘ │
│                                          │
│  ┌ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐ │
│  │  + Clan hinzufügen                 │ │
│  │  [_____#CLANTAG eingeben___] [ADD] │ │
│  └ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┘ │
│                                          │
└─────────────────────────────────────────┘
```

**Funktionalität:**
- Liste mit `ClanName (ClanTag)` Anzeige
- Info-Banner: "Clans dienen nur als Scope — wenn dein Account nicht im Clan ist, wird er dort übersprungen"
- Hinzufügen/Entfernen wie bei Accounts
- Validierung gegen CoC API

### 6.2 API-Service Erweiterung

```kotlin
// ClashApiService.kt — Neue Endpoints
interface ClashApiService {
    // ... bestehende ...
    
    // Clans
    @GET("api/v1/users/{userId}/clans")
    suspend fun getClans(@Path("userId") userId: String): List<TrackedClanResponse>
    
    @POST("api/v1/users/{userId}/clans")
    suspend fun addClan(@Path("userId") userId: String, @Body clan: ClanCreate): TrackedClanResponse
    
    @DELETE("api/v1/users/{userId}/clans/{clanTag}")
    suspend fun removeClan(@Path("userId") userId: String, @Path("clanTag") clanTag: String)
}
```

### 6.3 Neue Models

```kotlin
// Models.kt — Ergänzungen
data class TrackedClanResponse(
    val clan_tag: String,
    val clan_name: String?,
    val user_id: String
)

data class ClanCreate(
    val clan_tag: String
)
```

---

## 7. Android — Reminder-Konfiguration

### 7.1 `ReminderSettingsScreen.kt`

**Layout:**
```
┌─────────────────────────────────────────┐
│  ← Erinnerungen                         │
├─────────────────────────────────────────┤
│                                          │
│  ┌─────────────────────────────────────┐ │
│  │  ⚔️ Clan War                [🔘ON] │ │
│  │  ─────────────────────────────────  │ │
│  │  • 30 Minuten vorher       [🗑️]   │ │
│  │  • 1 Stunde vorher         [🗑️]   │ │
│  │  • 4 Stunden vorher        [🗑️]   │ │
│  │                                     │ │
│  │  [+ Zeitpunkt hinzufügen]           │ │
│  └─────────────────────────────────────┘ │
│                                          │
│  ┌─────────────────────────────────────┐ │
│  │  🏆 CWL                    [🔘ON] │ │
│  │  ─────────────────────────────────  │ │
│  │  • 1 Stunde vorher         [🗑️]   │ │
│  │                                     │ │
│  │  [+ Zeitpunkt hinzufügen]           │ │
│  └─────────────────────────────────────┘ │
│                                          │
│  ┌─────────────────────────────────────┐ │
│  │  🏚️ Raid Weekend           [🔘OFF]│ │
│  │  ─────────────────────────────────  │ │
│  │  (deaktiviert)                      │ │
│  │                                     │ │
│  │  [+ Zeitpunkt hinzufügen]           │ │
│  └─────────────────────────────────────┘ │
│                                          │
│  ┌─────────────────────────────────────┐ │
│  │        [💾 Speichern]               │ │
│  └─────────────────────────────────────┘ │
│                                          │
└─────────────────────────────────────────┘
```

**"+ Zeitpunkt hinzufügen" — Dialog:**
```
┌─────────────────────────────────┐
│  Erinnerung hinzufügen          │
│                                  │
│  Wie lange vor Event-Ende?       │
│                                  │
│  Vorschläge:                     │
│  [15m] [30m] [1h] [2h] [4h] [8h]│
│                                  │
│  Oder manuell:                   │
│  [___] Stunden [___] Minuten     │
│                                  │
│         [Abbrechen] [Hinzufügen] │
└─────────────────────────────────┘
```

### 7.2 Datenfluss

1. App startet → `GET /reminders` → zeigt aktuelle Config
2. User fügt Zeitpunkte hinzu/entfernt sie lokal
3. User drückt "Speichern" → `PUT /reminders` → Backend ersetzt Config komplett
4. Backend-ReminderEngine nutzt sofort die neuen Werte

### 7.3 Default-Konfiguration

Bei User-Registrierung werden automatisch Default-Reminders erstellt:

```python
default_reminders = [
    {"event_type": "cw",  "enabled": True, "times": [60, 240]},     # 1h + 4h
    {"event_type": "cwl", "enabled": True, "times": [60]},           # 1h
    {"event_type": "raid", "enabled": True, "times": [120, 480]},    # 2h + 8h
]
```

---

## 8. Android — MissingHits-Widget

### 8.1 Widget-Typ & Specs

| Eigenschaft | Wert |
|---|---|
| Typ | `AppWidgetProvider` mit `RemoteViews` |
| Min. Größe | 4×2 (Cells) |
| Max. Größe | 4×4 (resizable) |
| Update-Intervall | ~60 Sekunden (via `WorkManager`, nicht `updatePeriodMillis`) |
| Klick-Aktion | Öffnet App → HomeScreen |

### 8.2 Widget-Layout

```
┌──────────────────────────────────────────┐
│  ⚔️ ClashReminders          🔄 14:30    │
├──────────────────────────────────────────┤
│                                           │
│  Lost Warrior (#P00P) — LOST             │
│  ├ ⚔️ CW: 1 Angriff fehlt  (4h 0m)     │
│  └ 🏚️ Raid: 3 fehlen       (16h 30m)    │
│                                           │
│  Mini (#ABC) — LOST 2                    │
│  └ 🏆 CWL Tag 3: 1 fehlt   (2h 15m)    │
│                                           │
│  ─────────────────────────────────────── │
│  Gesamt: 5 fehlende Angriffe             │
└──────────────────────────────────────────┘
```

Wenn alles erledigt:
```
┌──────────────────────────────────────────┐
│  ⚔️ ClashReminders          🔄 14:30    │
├──────────────────────────────────────────┤
│                                           │
│  ✅ Keine fehlenden Angriffe!             │
│                                           │
└──────────────────────────────────────────┘
```

### 8.3 Technische Umsetzung

#### Dateien

| Datei | Zweck |
|---|---|
| `widget/MissingHitsWidgetProvider.kt` | `AppWidgetProvider` — empfängt Updates, baut `RemoteViews` |
| `widget/MissingHitsWidgetService.kt` | `RemoteViewsService` für `ListView` im Widget (scrollbare Liste) |
| `widget/WidgetUpdateWorker.kt` | `CoroutineWorker` — ruft API auf, triggert Widget-Refresh |
| `res/xml/missing_hits_widget_info.xml` | Widget-Metadaten (Größe, Preview, resize) |
| `res/layout/widget_missing_hits.xml` | Haupt-Layout (Header + ListView + Footer) |
| `res/layout/widget_missing_hits_item.xml` | Einzelnes Item in der ScrollListe |

#### Update-Mechanismus

```kotlin
// WidgetUpdateWorker.kt
class WidgetUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        val userId = PreferenceManager.getUserId(applicationContext) ?: return Result.failure()
        
        return try {
            // API aufrufen
            val response = RetrofitClient.apiService.getStatusSummary(userId)
            
            // Daten in SharedPreferences cachen (Widget kann nur daraus lesen)
            WidgetDataStore.saveStatus(applicationContext, response)
            
            // Widget-Update triggern
            val widgetManager = AppWidgetManager.getInstance(applicationContext)
            val widgetComponent = ComponentName(applicationContext, MissingHitsWidgetProvider::class.java)
            val widgetIds = widgetManager.getAppWidgetIds(widgetComponent)
            widgetManager.notifyAppWidgetViewDataChanged(widgetIds, R.id.widget_list_view)
            
            // Nächsten Worker einplanen (60 Sek Intervall)
            scheduleNextUpdate(applicationContext)
            
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
```

#### WorkManager-Setup (in `Application.onCreate()`)

```kotlin
fun scheduleWidgetUpdates(context: Context) {
    val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
        15, TimeUnit.MINUTES  // Minimum für PeriodicWork
    ).build()
    
    // Zusätzlich: OneTimeWorkRequest alle 60 Sek via chain
    // (PeriodicWork min. 15 Min, daher OneTime-Chain für 1-Min-Updates)
    enqueueNextUpdate(context)
}

fun enqueueNextUpdate(context: Context) {
    val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
        .setInitialDelay(60, TimeUnit.SECONDS)
        .build()
    WorkManager.getInstance(context)
        .enqueueUniqueWork("widget_update", ExistingWorkPolicy.REPLACE, request)
}
```

### 8.4 AndroidManifest-Einträge

```xml
<receiver android:name=".widget.MissingHitsWidgetProvider"
    android:exported="true">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data
        android:name="android.appwidget.provider"
        android:resource="@xml/missing_hits_widget_info" />
</receiver>

<service android:name=".widget.MissingHitsWidgetService"
    android:permission="android.permission.BIND_REMOTEVIEWS" />
```

---

## 9. Android — Hauptscreen-Überarbeitung

### 9.1 Navigation (Jetpack Navigation Compose)

```
NavHost
├── "onboarding"     → OnboardingScreen
├── "home"           → HomeScreen (MissingHits Live-Ansicht)
├── "accounts"       → AccountManagementScreen
├── "clans"          → ClanManagementScreen
└── "reminders"      → ReminderSettingsScreen
```

### 9.2 HomeScreen — Redesign (MissingHits-Ansicht)

```
┌─────────────────────────────────────────┐
│  ⚔️ ClashReminders                      │
│  Letztes Update: 14:30:45         [⚙️]  │
├─────────────────────────────────────────┤
│                                          │
│  ┌─────────────────────────────────────┐ │
│  │ Lost Warrior (#P00P)                │ │
│  │ 🏰 LOST (#2YYCQ8VP2)              │ │
│  │                                     │ │
│  │ ⚔️ Clan War vs. Enemy Clan         │ │
│  │    1/2 Angriffe │ ⏰ 4h 0m         │ │
│  │    ░░░░░░░░░░░░░░█████ 50%         │ │
│  │                                     │ │
│  │ 🏚️ Raid Weekend                    │ │
│  │    3/6 Angriffe │ ⏰ 16h 30m       │ │
│  │    ░░░░░░░░░░█████████ 50%         │ │
│  └─────────────────────────────────────┘ │
│                                          │
│  ┌─────────────────────────────────────┐ │
│  │ Mini (#ABC123)                      │ │
│  │ 🏰 LOST 2 (#XYZ)                   │ │
│  │                                     │ │
│  │ 🏆 CWL Tag 3 vs. Other Clan        │ │
│  │    0/1 Angriffe │ ⏰ 2h 15m        │ │
│  │    ██████████████████████ 0%        │ │
│  └─────────────────────────────────────┘ │
│                                          │
│  ┌─────────────────────────────────────┐ │
│  │ ✅ Alt Account (#DEF456)            │ │
│  │ Keine fehlenden Angriffe            │ │
│  └─────────────────────────────────────┘ │
│                                          │
├─────────────────────────────────────────┤
│  [👤 Accounts]  [🏰 Clans]  [🔔 Reminder]│
└─────────────────────────────────────────┘
```

**Features:**
- **Auto-Refresh**: Alle 60 Sekunden automatischer API-Call
- **Pull-to-Refresh**: Manuell sofort aktualisieren
- **Countdown-Timer**: `time_remaining` wird client-seitig jede Sekunde heruntergezählt (nicht nur bei API-Refresh)
- **Farbkodierung**: 
  - Rot: < 1h verbleibend
  - Orange: < 4h verbleibend
  - Grün: > 4h oder keine fehlenden Angriffe
- **Progress-Bar**: Visuell für attacks_used / attacks_max
- **Bottom Navigation Bar**: Schnellzugriff auf Accounts, Clans, Reminder-Settings

### 9.3 Auto-Refresh Logik (ViewModel)

```kotlin
class MainViewModel : ViewModel() {
    
    private val _missingHits = MutableStateFlow<List<EventSnapshotUi>>(emptyList())
    val missingHits: StateFlow<List<EventSnapshotUi>> = _missingHits
    
    private var refreshJob: Job? = null
    
    fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (isActive) {
                fetchMissingHits()
                delay(60_000) // 60 Sekunden
            }
        }
    }
    
    fun stopAutoRefresh() {
        refreshJob?.cancel()
    }
    
    private suspend fun fetchMissingHits() {
        try {
            val response = repository.getStatus(userId)
            _missingHits.value = response.events.map { it.toUiModel() }
            _lastUpdated.value = Clock.System.now()
        } catch (e: Exception) {
            _error.value = e.message
        }
    }
}
```

---

## 10. Push-Notifications (FCM)

### 10.1 Android-Seite

#### `services/ClashFirebaseMessagingService.kt` (NEU)

```kotlin
class ClashFirebaseMessagingService : FirebaseMessagingService() {
    
    override fun onNewToken(token: String) {
        // Token ans Backend senden
        CoroutineScope(Dispatchers.IO).launch {
            val userId = PreferenceManager.getUserId(this@ClashFirebaseMessagingService)
            if (userId != null) {
                RetrofitClient.apiService.updateFcmToken(userId, FcmTokenUpdate(token))
            }
        }
    }
    
    override fun onMessageReceived(message: RemoteMessage) {
        // Notification-Channel erstellen falls nötig
        createNotificationChannel()
        
        val notification = NotificationCompat.Builder(this, "clash_reminders")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(message.notification?.title)
            .setContentText(message.notification?.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.notification?.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(createPendingIntent())
            .build()
        
        NotificationManagerCompat.from(this).notify(
            message.data["account_tag"].hashCode(), // Unique ID pro Account
            notification
        )
    }
}
```

### 10.2 Backend-Seite

- `firebase-admin` Python-Paket hinzufügen zu `requirements.txt`
- Firebase Service Account JSON im Backend ablegen
- `FCMService` wie in Abschnitt 4.3 beschrieben

### 10.3 Notification-Channels

| Channel ID | Name | Importance |
|---|---|---|
| `clash_cw` | Clan War Erinnerungen | HIGH |
| `clash_cwl` | CWL Erinnerungen | HIGH |
| `clash_raid` | Raid Erinnerungen | DEFAULT |

---

## 11. Phasenplan & Abhängigkeiten

### Phase 1: Datenmodell & Polling-Engine (Backend) 🔴 Keine Abhängigkeiten

**Aufwand:** ~2-3 Tage

| # | Task | Dateien |
|---|---|---|
| 1.1 | DB-Modelle erweitern/erstellen (`tracked_clans`, `event_snapshots`, `reminder_configs`, `reminder_times`, `notification_log`) | `models.py` |
| 1.2 | Pydantic-Schemas für neue Endpunkte | `schemas.py` |
| 1.3 | CoC API Erweiterung (Raid-Seasons, Clan-Info) | `services/coc_api.py` |
| 1.4 | DataPoller Service implementieren | `services/data_poller.py` (NEU) |
| 1.5 | APScheduler einrichten (1-Min-Poll, Cleanup) | `main.py`, `requirements.txt` |
| 1.6 | Testen: Poller manuell triggern, DB-Einträge prüfen | `test_poller.py` |

### Phase 2: Backend-APIs (Account/Clan/Reminder/Status) 🔴 Abhängig von Phase 1

**Aufwand:** ~2 Tage

| # | Task | Dateien |
|---|---|---|
| 2.1 | Clan-Management Endpoints (CRUD) | `main.py`, `schemas.py` |
| 2.2 | Reminder-Config Endpoints (GET, PUT, POST, DELETE, PATCH) | `main.py`, `schemas.py` |
| 2.3 | Status-Endpoints (`/status`, `/status/summary`) | `main.py`, `schemas.py` |
| 2.4 | Default-Reminders bei User-Registrierung erstellen | `main.py` |
| 2.5 | Bestehende Endpoints anpassen (`player_accounts` PK-Änderung) | `models.py`, `main.py` |
| 2.6 | API-Tests | `test_api.py` |

### Phase 3: Reminder-Engine & FCM (Backend) 🔴 Abhängig von Phase 1 + 2

**Aufwand:** ~2 Tage

| # | Task | Dateien |
|---|---|---|
| 3.1 | FCM Service implementieren | `services/fcm_service.py` (NEU) |
| 3.2 | ReminderEngine Service implementieren | `services/reminder_engine.py` (NEU) |
| 3.3 | Scheduler: Reminder-Check alle 60 Sek | `main.py` |
| 3.4 | Notification-Logging & Duplikat-Schutz | `services/reminder_engine.py` |
| 3.5 | Firebase-Projekt einrichten, Service-Account-Key | Firebase Console |
| 3.6 | Testen: Push an echtes Gerät | Manuell |

### Phase 4: Android — Navigation & Account/Clan-Management 🔴 Abhängig von Phase 2

**Aufwand:** ~2-3 Tage

| # | Task | Dateien |
|---|---|---|
| 4.1 | Jetpack Navigation einrichten (NavHost, Routes) | `MainActivity.kt` |
| 4.2 | AccountManagementScreen | `ui/screens/AccountManagementScreen.kt` (NEU) |
| 4.3 | ClanManagementScreen | `ui/screens/ClanManagementScreen.kt` (NEU) |
| 4.4 | API-Service erweitern (Clan-Endpoints) | `api/ClashApiService.kt` |
| 4.5 | Models erweitern | `model/Models.kt` |
| 4.6 | Repository erweitern | `data/UserRepository.kt` |
| 4.7 | Onboarding-Flow anpassen (nach Registrierung → Accounts + Clans) | `OnboardingScreen.kt` |

### Phase 5: Android — HomeScreen Redesign (MissingHits) 🔴 Abhängig von Phase 2 + 4

**Aufwand:** ~2-3 Tage

| # | Task | Dateien |
|---|---|---|
| 5.1 | HomeScreen komplett neu (MissingHits-Ansicht) | `ui/screens/HomeScreen.kt` |
| 5.2 | Auto-Refresh (60s) + Pull-to-Refresh | `ui/MainViewModel.kt` |
| 5.3 | Client-seitiger Countdown-Timer (1s Tick) | `ui/MainViewModel.kt` |
| 5.4 | `Name (Tag)` Anzeige überall | Alle Screens |
| 5.5 | Farbkodierung & Progress-Bars | `ui/screens/HomeScreen.kt` |
| 5.6 | Bottom Navigation Bar | `MainActivity.kt` |

### Phase 6: Android — Reminder-Settings Screen 🔴 Abhängig von Phase 2 + 4

**Aufwand:** ~1-2 Tage

| # | Task | Dateien |
|---|---|---|
| 6.1 | ReminderSettingsScreen mit Listen-Konfiguration | `ui/screens/ReminderSettingsScreen.kt` (NEU) |
| 6.2 | "Zeitpunkt hinzufügen"-Dialog (Quick-Picks + Manuell) | `ui/screens/ReminderSettingsScreen.kt` |
| 6.3 | Bulk-Save an Backend | `data/UserRepository.kt` |
| 6.4 | ReminderViewModel | `ui/ReminderViewModel.kt` (NEU) |

### Phase 7: Android — Widget 🔴 Abhängig von Phase 2 + 5

**Aufwand:** ~2-3 Tage

| # | Task | Dateien |
|---|---|---|
| 7.1 | Widget-Layouts (XML) | `res/layout/widget_*.xml`, `res/xml/widget_info.xml` |
| 7.2 | MissingHitsWidgetProvider | `widget/MissingHitsWidgetProvider.kt` (NEU) |
| 7.3 | MissingHitsWidgetService (ListView) | `widget/MissingHitsWidgetService.kt` (NEU) |
| 7.4 | WidgetUpdateWorker (WorkManager 1-Min-Polling) | `widget/WidgetUpdateWorker.kt` (NEU) |
| 7.5 | WidgetDataStore (SharedPreferences Cache) | `widget/WidgetDataStore.kt` (NEU) |
| 7.6 | AndroidManifest Einträge | `AndroidManifest.xml` |
| 7.7 | Klick → App öffnen | `MissingHitsWidgetProvider.kt` |

### Phase 8: FCM Integration Android + Polish 🔴 Abhängig von Phase 3 + 4

**Aufwand:** ~1-2 Tage

| # | Task | Dateien |
|---|---|---|
| 8.1 | FirebaseMessagingService | `services/ClashFirebaseMessagingService.kt` (NEU) |
| 8.2 | Token-Refresh → Backend Update | `services/ClashFirebaseMessagingService.kt` |
| 8.3 | Notification-Channels erstellen | `ClashReminderApp.kt` (Application-Klasse) |
| 8.4 | google-services.json einbinden | `app/` |
| 8.5 | POST_NOTIFICATIONS Permission (Android 13+) | `AndroidManifest.xml`, `MainActivity.kt` |

### Phase 9: Testing & Deployment 🔴 Abhängig von allen

**Aufwand:** ~1-2 Tage

| # | Task |
|---|---|
| 9.1 | Backend: Integrationstests (Poll → DB → Reminder → FCM) |
| 9.2 | Android: UI-Tests (Navigation, Eingaben, Widget) |
| 9.3 | End-to-End: Account anlegen → Clan hinzufügen → Reminder setzen → Push empfangen |
| 9.4 | Docker-Setup aktualisieren (APScheduler, Firebase Credentials) |
| 9.5 | APK bauen & verteilen |

---

## 12. Technische Details & Edge Cases

### 12.1 CoC API Timestamp Parsing

```python
def parse_coc_timestamp(ts: str) -> datetime:
    """Parst '20260209T143000.000Z' → datetime(2026, 2, 9, 14, 30, 0, tzinfo=UTC)"""
    return datetime.strptime(ts, "%Y%m%dT%H%M%S.%fZ").replace(tzinfo=timezone.utc)
```

### 12.2 CWL-Erkennung: CW vs. CWL

- `/currentwar` kann sowohl normalen CW als auch den aktuellen CWL-Kampftag zurückgeben
- Zuverlässige Unterscheidung: `attacksPerMember == 1` → CWL, `attacksPerMember == 2` → CW
- Fallback: Wenn `teamSize` in {15, 30} UND `attacksPerMember == 1` → definitiv CWL
- Zusätzlich: Wenn `/currentwar/leaguegroup` einen aktiven CWL-Gruppenstand zurückgibt → CWL-Saison

### 12.3 Raid-API Besonderheit

- Endpoint: `GET /clans/{tag}/capitalraidseasons?limit=1`
- Gibt die aktuellste Raid-Saison zurück
- `state: "ongoing"` → Aktiv, `state: "ended"` → Vorbei
- `members` Array enthält nur Spieler die bereits was angegriffen haben
- Spieler die im Clan sind aber noch nicht angegriffen haben → **"not_participating"** Status
  - Um zu prüfen ob ein Account im Clan ist: `/clans/{tag}` → `memberList` → Tag-Check
  - Oder: Der Account ist in `tracked_clans` + Clan-Mitgliederliste → Raid-Teilnahme wird erwartet

### 12.4 Multi-User Same-Clan Optimierung

Wenn User A und User B beide Clan #XYZ tracken:
- Der Poller sammelt zuerst **alle einzigartigen Clan-Tags** über alle User
- Ein API-Call pro Clan (nicht pro User×Clan)
- Die Ergebnisse werden dann pro User×Account zugeordnet

```python
# Optimierter Poll
all_clan_tags = db.query(distinct(TrackedClan.clan_tag)).all()
clan_data = {tag: await fetch_clan_events(tag) for tag in all_clan_tags}

for user in all_users:
    user_clans = get_tracked_clans(user.id)
    user_accounts = get_accounts(user.id)
    for account in user_accounts:
        for clan in user_clans:
            if clan.clan_tag in clan_data:
                await process_account_in_clan(user, account, clan.clan_tag, clan_data[clan.clan_tag])
```

### 12.5 Widget 1-Minute Polling Limitation

- Android `updatePeriodMillis` Minimum = 30 Minuten
- Lösung: `WorkManager` mit `OneTimeWorkRequest` + `setInitialDelay(60, SECONDS)` im Chain-Pattern
- Der Worker ruft nach Abschluss sofort den nächsten Worker ein (self-rescheduling)
- **Batterie-Optimierung beachten:** Doze-Mode kann Workers verzögern
- Empfehlung: Zusätzlich `AlarmManager.setExactAndAllowWhileIdle()` als Fallback

### 12.6 Offline-Verhalten

- Widget zeigt gecachete Daten aus `SharedPreferences` + "Zuletzt aktualisiert: ..." Timestamp
- App zeigt gecachete Daten + Fehler-Banner wenn Backend nicht erreichbar
- Room-Datenbank als lokaler Cache (Optional, Phase 2): Verhindert leere Screens beim App-Start

### 12.7 Account nicht im Clan

Wenn ein getrackter Account in keinem der getrackten Clans gefunden wird:
- **Kein Fehler**, kein Eintrag in `event_snapshots`
- Der Account wird einfach übersprungen
- Der `current_clan_tag` in `player_accounts` wird trotzdem via `/players/{tag}` aktualisiert (periodischer Sync)
- Anzeige in der App: "Keine aktiven Events" für diesen Account

### 12.8 Neue Dependencies

**Backend (`requirements.txt`):**
```
fastapi
uvicorn
httpx
python-dotenv
sqlalchemy
apscheduler        # NEU — Scheduler für Polling + Reminders
firebase-admin     # NEU — FCM Push Notifications
```

**Android (`build.gradle.kts`):**
```kotlin
// Bestehend (bereits deklariert, jetzt tatsächlich nutzen)
implementation("com.google.firebase:firebase-messaging")
implementation("androidx.navigation:navigation-compose:2.7.7")

// NEU
implementation("androidx.work:work-runtime-ktx:2.9.0")           // WorkManager für Widget
implementation("androidx.glance:glance-appwidget:1.0.0")         // Optional: Glance statt RemoteViews
implementation("com.google.accompanist:accompanist-swiperefresh:0.34.0") // Pull-to-Refresh
```

---

## Zusammenfassung

| Bereich | Was | Wie viele Dateien | Aufwand |
|---|---|---|---|
| Backend DB | 4 neue Tabellen, 1 erweitert | `models.py` | ~0.5 Tage |
| Backend Poller | Minütliches API-Polling, UPSERT in DB | 1 neue Datei | ~1.5 Tage |
| Backend APIs | 8 neue Endpoints | `main.py`, `schemas.py` | ~1.5 Tage |
| Backend Reminder | Engine + FCM Service | 2 neue Dateien | ~1.5 Tage |
| Android Screens | 3 neue Screens + HomeScreen Redesign | 4-5 neue/geänderte Dateien | ~4 Tage |
| Android Widget | Provider + Service + Worker + Layouts | 5-6 neue Dateien | ~2.5 Tage |
| Android FCM | Messaging Service + Channels | 2 neue Dateien | ~1 Tag |
| Testing | E2E + Integration | Tests | ~1.5 Tage |
| **Gesamt** | | **~20-25 neue/geänderte Dateien** | **~14-18 Tage** |
