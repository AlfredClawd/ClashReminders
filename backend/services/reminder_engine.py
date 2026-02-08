"""
Reminder Engine — Checks event_snapshots against user reminder configs and sends FCM pushes.
"""
import logging
from datetime import datetime, timezone, timedelta
from sqlalchemy.orm import Session
import models
from services import fcm_service
from services.data_poller import format_duration

logger = logging.getLogger(__name__)

EVENT_LABELS = {
    "cw": "Clan War",
    "cwl": "CWL",
    "raid": "Raid Weekend",
}


async def check_reminders(db: Session):
    """
    Check all active event snapshots against user reminder configurations.
    Send push notifications where appropriate.
    """
    logger.info("Starting reminder check cycle...")

    try:
        now = datetime.now(timezone.utc)

        # Get all active snapshots where attacks remain
        active_snapshots = db.query(models.EventSnapshot).filter(
            models.EventSnapshot.is_active == True,
            models.EventSnapshot.state.in_(["inWar", "ongoing"]),
            models.EventSnapshot.end_time.isnot(None),
        ).all()

        # Filter to those with remaining attacks
        active_snapshots = [s for s in active_snapshots if s.attacks_remaining > 0]

        if not active_snapshots:
            logger.info("No active events with remaining attacks.")
            return

        notifications_sent = 0

        for snapshot in active_snapshots:
            user = db.query(models.User).filter(models.User.id == snapshot.user_id).first()
            if not user or not user.notification_enabled or not user.fcm_token:
                continue

            # Find matching reminder config
            config = db.query(models.ReminderConfig).filter(
                models.ReminderConfig.user_id == user.id,
                models.ReminderConfig.event_type == snapshot.event_type,
                models.ReminderConfig.enabled == True,
            ).first()

            if not config:
                continue

            # Get all enabled reminder times
            times = db.query(models.ReminderTime).filter(
                models.ReminderTime.reminder_config_id == config.id,
                models.ReminderTime.enabled == True,
            ).all()

            for rt in times:
                trigger_time = snapshot.end_time - timedelta(minutes=rt.minutes_before_end)

                # Check if it's time to fire (within 90 second window)
                diff_seconds = abs((now - trigger_time).total_seconds())
                if diff_seconds > 90:
                    continue

                # Check for duplicate
                already_sent = db.query(models.NotificationLog).filter(
                    models.NotificationLog.event_snapshot_id == snapshot.id,
                    models.NotificationLog.reminder_time_id == rt.id,
                ).first()

                if already_sent:
                    continue

                # Send notification
                success = await send_reminder_notification(user, snapshot, rt)

                # Log it
                log = models.NotificationLog(
                    user_id=user.id,
                    event_snapshot_id=snapshot.id,
                    reminder_time_id=rt.id,
                    status="sent" if success else "failed",
                )
                db.add(log)
                notifications_sent += 1

        db.commit()
        logger.info(f"Reminder check completed. {notifications_sent} notification(s) processed.")

    except Exception as e:
        logger.error(f"Reminder check failed: {e}", exc_info=True)
        db.rollback()


async def send_reminder_notification(
    user: models.User,
    snapshot: models.EventSnapshot,
    reminder_time: models.ReminderTime,
) -> bool:
    """Build and send a reminder push notification."""
    time_left_seconds = max(0, int((snapshot.end_time - datetime.now(timezone.utc)).total_seconds()))
    time_left = format_duration(time_left_seconds)

    event_label = EVENT_LABELS.get(snapshot.event_type, snapshot.event_type)
    subtype_label = ""
    if snapshot.event_subtype:
        day_num = snapshot.event_subtype.replace("day_", "")
        subtype_label = f" Tag {day_num}"

    title = f"⚔️ {event_label}{subtype_label} — {snapshot.attacks_remaining} Angriff(e) übrig!"

    body_parts = [
        f"👤 {snapshot.account_name or snapshot.account_tag} ({snapshot.account_tag})",
        f"🏰 {snapshot.clan_name or snapshot.clan_tag} ({snapshot.clan_tag})",
    ]
    if snapshot.opponent_name:
        body_parts.append(f"⚔️ vs. {snapshot.opponent_name}")
    body_parts.append(f"⏰ {time_left} verbleibend")
    body = "\n".join(body_parts)

    data = {
        "event_type": snapshot.event_type,
        "account_tag": snapshot.account_tag,
        "clan_tag": snapshot.clan_tag,
        "end_time": snapshot.end_time.isoformat() if snapshot.end_time else "",
    }

    logger.info(f"Sending reminder to user {user.id}: {title}")
    return await fcm_service.send_push(
        token=user.fcm_token,
        title=title,
        body=body,
        data=data,
    )
