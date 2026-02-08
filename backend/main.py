import logging
import asyncio
from contextlib import asynccontextmanager
from datetime import datetime, timezone

from fastapi import FastAPI, Depends, HTTPException, Request
from fastapi.responses import JSONResponse
from fastapi.concurrency import run_in_threadpool
from sqlalchemy.orm import Session
from typing import List

import models
import schemas
from database import engine, get_db, SessionLocal
from services import coc_api, fcm_service
from services.data_poller import poll_all_users, cleanup_stale_snapshots, format_duration
from services.reminder_engine import check_reminders
from core.config import settings

# Configure Logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger(__name__)

# Create database tables
try:
    models.Base.metadata.create_all(bind=engine)
    logger.info("Database tables created successfully.")
except Exception as e:
    logger.error(f"Error creating database tables: {e}")


# ============ SCHEDULER ============

_scheduler_task = None

async def scheduler_loop():
    """Background scheduler loop — polls data and checks reminders."""
    poll_interval = settings.POLL_INTERVAL_SECONDS
    logger.info(f"Scheduler started: polling every {poll_interval}s")

    while True:
        try:
            db = SessionLocal()
            try:
                # Poll data
                await poll_all_users(db)
                # Cleanup stale snapshots
                await cleanup_stale_snapshots(db)
            finally:
                db.close()

            # Wait half interval, then check reminders
            await asyncio.sleep(poll_interval // 2)

            db = SessionLocal()
            try:
                await check_reminders(db)
            finally:
                db.close()

            # Wait remaining half
            await asyncio.sleep(poll_interval - (poll_interval // 2))

        except asyncio.CancelledError:
            logger.info("Scheduler loop cancelled.")
            break
        except Exception as e:
            logger.error(f"Scheduler error: {e}", exc_info=True)
            await asyncio.sleep(poll_interval)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan — start/stop background tasks."""
    global _scheduler_task

    # Startup
    if not settings.COC_API_KEY:
        logger.warning("COC_API_KEY is not set! CoC features will fail.")
    else:
        logger.info("COC_API_KEY loaded successfully.")

    # Initialize Firebase
    fcm_service.init_firebase()

    # Start scheduler
    _scheduler_task = asyncio.create_task(scheduler_loop())
    logger.info("Background scheduler started.")

    yield

    # Shutdown
    if _scheduler_task:
        _scheduler_task.cancel()
        try:
            await _scheduler_task
        except asyncio.CancelledError:
            pass
    logger.info("Background scheduler stopped.")


# ============ APP ============

app = FastAPI(
    title="ClashReminders API",
    description="API for managing Clash of Clans reminders and tracking war/CWL/raid status.",
    version="2.0.0",
    docs_url="/docs",
    redoc_url="/redoc",
    lifespan=lifespan,
)

# Global Exception Handler
@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    logger.error(f"Global exception occurred: {exc}", exc_info=True)
    return JSONResponse(status_code=500, content={"detail": "Internal Server Error"})


# ============ GENERAL ============

@app.get("/", tags=["General"])
async def root():
    return {"message": "Welcome to ClashReminders API", "status": "active", "version": "2.0.0"}

@app.get("/health", tags=["General"])
async def health_check():
    return {"status": "healthy"}


# ============ HELPERS ============

def get_user_or_404(db: Session, user_id: str) -> models.User:
    user = db.query(models.User).filter(models.User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    return user


def create_default_reminders(db: Session, user_id: str):
    """Create default reminder configs for a new user."""
    defaults = [
        {"event_type": "cw", "times": [60, 240]},
        {"event_type": "cwl", "times": [60]},
        {"event_type": "raid", "times": [120, 480]},
    ]
    for d in defaults:
        config = models.ReminderConfig(user_id=user_id, event_type=d["event_type"], enabled=True)
        db.add(config)
        db.flush()
        for mins in d["times"]:
            label = format_minutes_label(mins)
            time_entry = models.ReminderTime(
                reminder_config_id=config.id,
                minutes_before_end=mins,
                label=label,
                enabled=True,
            )
            db.add(time_entry)


def format_minutes_label(minutes: int) -> str:
    if minutes >= 1440:
        d = minutes // 1440
        return f"{d}d"
    if minutes >= 60:
        h = minutes // 60
        m = minutes % 60
        if m:
            return f"{h}h {m}m"
        return f"{h}h"
    return f"{minutes}m"


# ============ USER MANAGEMENT ============

@app.post("/api/v1/users/register", response_model=schemas.UserResponse, tags=["Users"])
def register_user(user_data: schemas.UserRegister, db: Session = Depends(get_db)):
    """Register a new user with optional FCM token."""
    try:
        new_user = models.User(fcm_token=user_data.fcm_token)
        db.add(new_user)
        db.flush()

        # Create default reminder configs
        create_default_reminders(db, new_user.id)

        db.commit()
        db.refresh(new_user)
        logger.info(f"New user registered: {new_user.id}")
        return new_user
    except Exception as e:
        logger.error(f"Error registering user: {e}")
        db.rollback()
        raise HTTPException(status_code=500, detail="Failed to register user")


@app.put("/api/v1/users/{user_id}/fcm", tags=["Users"])
def update_fcm_token(user_id: str, data: schemas.FcmTokenUpdate, db: Session = Depends(get_db)):
    """Update FCM token for a user."""
    user = get_user_or_404(db, user_id)
    user.fcm_token = data.fcm_token
    db.commit()
    return {"message": "FCM token updated"}


# ============ ACCOUNT MANAGEMENT ============

@app.post("/api/v1/users/{user_id}/accounts", response_model=schemas.PlayerAccountResponse, tags=["Accounts"])
async def add_account(user_id: str, account: schemas.PlayerAccountCreate, db: Session = Depends(get_db)):
    """Link a CoC player account to a user."""
    user = await run_in_threadpool(lambda: get_user_or_404(db, user_id))

    # Check duplicate
    existing = await run_in_threadpool(lambda: db.query(models.PlayerAccount).filter(
        models.PlayerAccount.tag == account.tag,
        models.PlayerAccount.user_id == user_id,
    ).first())
    if existing:
        raise HTTPException(status_code=400, detail="Account already linked")

    # Verify with CoC API
    player_data = await coc_api.get_player(account.tag)
    if not player_data:
        raise HTTPException(status_code=404, detail="Player tag not found in Clash of Clans")

    def create():
        new_acc = models.PlayerAccount(
            tag=player_data["tag"],
            name=player_data.get("name"),
            user_id=user_id,
            current_clan_tag=player_data.get("clan", {}).get("tag"),
            current_clan_name=player_data.get("clan", {}).get("name"),
            last_synced_at=datetime.now(timezone.utc),
        )
        db.add(new_acc)
        db.commit()
        db.refresh(new_acc)
        return new_acc

    new_account = await run_in_threadpool(create)
    logger.info(f"Account linked: {new_account.tag} to user {user_id}")
    return new_account


@app.get("/api/v1/users/{user_id}/accounts", response_model=List[schemas.PlayerAccountResponse], tags=["Accounts"])
def list_accounts(user_id: str, db: Session = Depends(get_db)):
    """List all accounts linked to a user."""
    get_user_or_404(db, user_id)
    return db.query(models.PlayerAccount).filter(models.PlayerAccount.user_id == user_id).all()


@app.delete("/api/v1/users/{user_id}/accounts/{tag}", tags=["Accounts"])
def delete_account(user_id: str, tag: str, db: Session = Depends(get_db)):
    """Remove a linked player account."""
    account = db.query(models.PlayerAccount).filter(
        models.PlayerAccount.tag == tag,
        models.PlayerAccount.user_id == user_id,
    ).first()
    if not account:
        raise HTTPException(status_code=404, detail="Account not found")

    # Also clean up related snapshots
    db.query(models.EventSnapshot).filter(
        models.EventSnapshot.user_id == user_id,
        models.EventSnapshot.account_tag == tag,
    ).delete()

    db.delete(account)
    db.commit()
    logger.info(f"Account removed: {tag} from user {user_id}")
    return {"message": "Account removed"}


# ============ CLAN MANAGEMENT ============

@app.get("/api/v1/users/{user_id}/clans", response_model=List[schemas.TrackedClanResponse], tags=["Clans"])
def list_clans(user_id: str, db: Session = Depends(get_db)):
    """List all tracked clans for a user."""
    get_user_or_404(db, user_id)
    return db.query(models.TrackedClan).filter(models.TrackedClan.user_id == user_id).all()


@app.post("/api/v1/users/{user_id}/clans", response_model=schemas.TrackedClanResponse, tags=["Clans"])
async def add_clan(user_id: str, data: schemas.ClanCreate, db: Session = Depends(get_db)):
    """Add a clan to track for a user."""
    await run_in_threadpool(lambda: get_user_or_404(db, user_id))

    # Check duplicate
    existing = await run_in_threadpool(lambda: db.query(models.TrackedClan).filter(
        models.TrackedClan.clan_tag == data.clan_tag,
        models.TrackedClan.user_id == user_id,
    ).first())
    if existing:
        raise HTTPException(status_code=400, detail="Clan already tracked")

    # Verify with CoC API
    clan_data = await coc_api.get_clan_info(data.clan_tag)
    if not clan_data:
        raise HTTPException(status_code=404, detail="Clan tag not found in Clash of Clans")

    def create():
        new_clan = models.TrackedClan(
            clan_tag=clan_data["tag"],
            clan_name=clan_data.get("name"),
            user_id=user_id,
        )
        db.add(new_clan)
        db.commit()
        db.refresh(new_clan)
        return new_clan

    result = await run_in_threadpool(create)
    logger.info(f"Clan tracked: {result.clan_tag} for user {user_id}")
    return result


@app.delete("/api/v1/users/{user_id}/clans/{clan_tag}", tags=["Clans"])
def delete_clan(user_id: str, clan_tag: str, db: Session = Depends(get_db)):
    """Remove a tracked clan."""
    clan = db.query(models.TrackedClan).filter(
        models.TrackedClan.clan_tag == clan_tag,
        models.TrackedClan.user_id == user_id,
    ).first()
    if not clan:
        raise HTTPException(status_code=404, detail="Tracked clan not found")

    # Clean up related snapshots
    db.query(models.EventSnapshot).filter(
        models.EventSnapshot.user_id == user_id,
        models.EventSnapshot.clan_tag == clan_tag,
    ).delete()

    db.delete(clan)
    db.commit()
    logger.info(f"Clan untracked: {clan_tag} from user {user_id}")
    return {"message": "Clan removed"}


# ============ REMINDER CONFIGURATION ============

@app.get("/api/v1/users/{user_id}/reminders", response_model=schemas.RemindersResponse, tags=["Reminders"])
def get_reminders(user_id: str, db: Session = Depends(get_db)):
    """Get all reminder configurations for a user."""
    get_user_or_404(db, user_id)
    configs = db.query(models.ReminderConfig).filter(
        models.ReminderConfig.user_id == user_id
    ).all()
    return schemas.RemindersResponse(reminders=configs)


@app.put("/api/v1/users/{user_id}/reminders", response_model=schemas.RemindersResponse, tags=["Reminders"])
def update_reminders(user_id: str, data: schemas.RemindersUpdateRequest, db: Session = Depends(get_db)):
    """Replace all reminder configurations for a user (bulk update)."""
    get_user_or_404(db, user_id)

    # Delete existing configs (cascade deletes times)
    db.query(models.ReminderConfig).filter(
        models.ReminderConfig.user_id == user_id
    ).delete()
    db.flush()

    # Create new configs
    for rc in data.reminders:
        if rc.event_type not in ("cw", "cwl", "raid"):
            raise HTTPException(status_code=400, detail=f"Invalid event_type: {rc.event_type}")

        config = models.ReminderConfig(
            user_id=user_id,
            event_type=rc.event_type,
            enabled=rc.enabled,
        )
        db.add(config)
        db.flush()

        for t in rc.times:
            time_entry = models.ReminderTime(
                reminder_config_id=config.id,
                minutes_before_end=t.minutes_before_end,
                label=t.label or format_minutes_label(t.minutes_before_end),
                enabled=True,
            )
            db.add(time_entry)

    db.commit()

    # Return updated
    configs = db.query(models.ReminderConfig).filter(
        models.ReminderConfig.user_id == user_id
    ).all()
    return schemas.RemindersResponse(reminders=configs)


@app.patch("/api/v1/users/{user_id}/reminders/{event_type}", tags=["Reminders"])
def toggle_reminder(user_id: str, event_type: str, data: schemas.ReminderToggle, db: Session = Depends(get_db)):
    """Enable/disable reminders for an event type."""
    get_user_or_404(db, user_id)
    config = db.query(models.ReminderConfig).filter(
        models.ReminderConfig.user_id == user_id,
        models.ReminderConfig.event_type == event_type,
    ).first()
    if not config:
        raise HTTPException(status_code=404, detail="Reminder config not found")

    config.enabled = data.enabled
    db.commit()
    return {"message": f"Reminder for {event_type} {'enabled' if data.enabled else 'disabled'}"}


@app.post("/api/v1/users/{user_id}/reminders/{event_type}/times", tags=["Reminders"])
def add_reminder_time(
    user_id: str, event_type: str, data: schemas.ReminderTimeCreate, db: Session = Depends(get_db)
):
    """Add a single reminder time to an event type config."""
    get_user_or_404(db, user_id)
    config = db.query(models.ReminderConfig).filter(
        models.ReminderConfig.user_id == user_id,
        models.ReminderConfig.event_type == event_type,
    ).first()

    if not config:
        # Auto-create config
        config = models.ReminderConfig(user_id=user_id, event_type=event_type, enabled=True)
        db.add(config)
        db.flush()

    time_entry = models.ReminderTime(
        reminder_config_id=config.id,
        minutes_before_end=data.minutes_before_end,
        label=data.label or format_minutes_label(data.minutes_before_end),
        enabled=True,
    )
    db.add(time_entry)
    db.commit()
    db.refresh(time_entry)

    return schemas.ReminderTimeResponse.model_validate(time_entry)


@app.delete("/api/v1/users/{user_id}/reminders/{event_type}/times/{time_id}", tags=["Reminders"])
def delete_reminder_time(user_id: str, event_type: str, time_id: str, db: Session = Depends(get_db)):
    """Remove a single reminder time."""
    get_user_or_404(db, user_id)
    time_entry = db.query(models.ReminderTime).filter(models.ReminderTime.id == time_id).first()
    if not time_entry:
        raise HTTPException(status_code=404, detail="Reminder time not found")

    db.delete(time_entry)
    db.commit()
    return {"message": "Reminder time removed"}


# ============ EVENT STATUS (MISSING HITS) ============

@app.get("/api/v1/users/{user_id}/status", response_model=schemas.StatusResponse, tags=["Status"])
def get_status(user_id: str, db: Session = Depends(get_db)):
    """Get all active event snapshots for a user (MissingHits data)."""
    get_user_or_404(db, user_id)

    snapshots = db.query(models.EventSnapshot).filter(
        models.EventSnapshot.user_id == user_id,
        models.EventSnapshot.is_active == True,
    ).order_by(models.EventSnapshot.end_time.asc()).all()

    now = datetime.now(timezone.utc)
    events = []
    last_polled = None

    for snap in snapshots:
        if snap.polled_at:
            if last_polled is None or snap.polled_at > last_polled:
                last_polled = snap.polled_at

        remaining_secs = 0
        remaining_formatted = ""
        if snap.end_time:
            remaining_secs = max(0, int((snap.end_time - now).total_seconds()))
            remaining_formatted = format_duration(remaining_secs)

        events.append(schemas.EventSnapshotResponse(
            id=snap.id,
            account_tag=snap.account_tag,
            account_name=snap.account_name,
            clan_tag=snap.clan_tag,
            clan_name=snap.clan_name,
            event_type=snap.event_type,
            event_subtype=snap.event_subtype,
            state=snap.state,
            attacks_used=snap.attacks_used,
            attacks_max=snap.attacks_max,
            attacks_remaining=snap.attacks_remaining,
            end_time=snap.end_time,
            start_time=snap.start_time,
            time_remaining_seconds=remaining_secs,
            time_remaining_formatted=remaining_formatted,
            opponent_name=snap.opponent_name,
            opponent_tag=snap.opponent_tag,
            war_size=snap.war_size,
        ))

    return schemas.StatusResponse(last_polled=last_polled, events=events)


@app.get("/api/v1/users/{user_id}/status/summary", response_model=schemas.StatusSummaryResponse, tags=["Status"])
def get_status_summary(user_id: str, db: Session = Depends(get_db)):
    """Get compact status summary for widget display."""
    get_user_or_404(db, user_id)

    snapshots = db.query(models.EventSnapshot).filter(
        models.EventSnapshot.user_id == user_id,
        models.EventSnapshot.is_active == True,
    ).order_by(models.EventSnapshot.end_time.asc()).all()

    now = datetime.now(timezone.utc)
    items = []
    by_type = {}
    total_missing = 0
    last_polled = None

    event_labels = {"cw": "Clan War", "cwl": "CWL", "raid": "Raid Weekend"}

    for snap in snapshots:
        if snap.polled_at:
            if last_polled is None or snap.polled_at > last_polled:
                last_polled = snap.polled_at

        remaining = snap.attacks_remaining
        if remaining <= 0:
            continue

        total_missing += remaining

        et = snap.event_type
        if et not in by_type:
            by_type[et] = {"count": 0, "account_tags": set()}
        by_type[et]["count"] += remaining
        by_type[et]["account_tags"].add(snap.account_tag)

        remaining_secs = 0
        remaining_formatted = ""
        if snap.end_time:
            remaining_secs = max(0, int((snap.end_time - now).total_seconds()))
            remaining_formatted = format_duration(remaining_secs)

        label = event_labels.get(et, et)
        if snap.event_subtype:
            day = snap.event_subtype.replace("day_", "")
            label = f"{label} Tag {day}"

        items.append(schemas.StatusSummaryItem(
            account_display=f"{snap.account_name or 'Unknown'} ({snap.account_tag})",
            clan_display=f"{snap.clan_name or 'Unknown'} ({snap.clan_tag})",
            event_label=label,
            attacks_remaining=remaining,
            end_time_formatted=remaining_formatted,
            end_time_iso=snap.end_time.isoformat() if snap.end_time else None,
        ))

    by_event_type = {}
    for et, data in by_type.items():
        by_event_type[et] = schemas.EventTypeCount(count=data["count"], accounts=len(data["account_tags"]))

    return schemas.StatusSummaryResponse(
        last_polled=last_polled,
        total_missing=total_missing,
        by_event_type=by_event_type,
        items=items,
    )
