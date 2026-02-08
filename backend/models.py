from sqlalchemy import Column, String, DateTime, ForeignKey, Integer, Boolean, UniqueConstraint
from sqlalchemy.orm import relationship
import uuid
import datetime
from database import Base


class User(Base):
    __tablename__ = "users"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    fcm_token = Column(String, nullable=True)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)
    notification_enabled = Column(Boolean, default=True)
    timezone = Column(String, default="Europe/Berlin")

    accounts = relationship("PlayerAccount", back_populates="user", cascade="all, delete-orphan")
    tracked_clans = relationship("TrackedClan", back_populates="user", cascade="all, delete-orphan")
    reminder_configs = relationship("ReminderConfig", back_populates="user", cascade="all, delete-orphan")
    event_snapshots = relationship("EventSnapshot", back_populates="user", cascade="all, delete-orphan")


class PlayerAccount(Base):
    __tablename__ = "player_accounts"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    tag = Column(String, nullable=False)
    name = Column(String, nullable=True)
    user_id = Column(String, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    current_clan_tag = Column(String, nullable=True)
    current_clan_name = Column(String, nullable=True)
    last_synced_at = Column(DateTime, nullable=True)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)

    __table_args__ = (
        UniqueConstraint("tag", "user_id", name="uq_player_account_tag_user"),
    )

    user = relationship("User", back_populates="accounts")


class TrackedClan(Base):
    __tablename__ = "tracked_clans"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    clan_tag = Column(String, nullable=False)
    clan_name = Column(String, nullable=True)
    user_id = Column(String, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)

    __table_args__ = (
        UniqueConstraint("clan_tag", "user_id", name="uq_tracked_clan_tag_user"),
    )

    user = relationship("User", back_populates="tracked_clans")


class EventSnapshot(Base):
    __tablename__ = "event_snapshots"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id = Column(String, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    account_tag = Column(String, nullable=False)
    account_name = Column(String, nullable=True)
    clan_tag = Column(String, nullable=False)
    clan_name = Column(String, nullable=True)
    event_type = Column(String, nullable=False)       # 'cw', 'cwl', 'raid'
    event_subtype = Column(String, nullable=True)      # CWL: 'day_1', 'day_2', etc.

    # Status data
    state = Column(String, nullable=False)
    attacks_used = Column(Integer, default=0)
    attacks_max = Column(Integer, default=0)

    # Time data
    end_time = Column(DateTime, nullable=True)
    start_time = Column(DateTime, nullable=True)

    # Additional info
    opponent_name = Column(String, nullable=True)
    opponent_tag = Column(String, nullable=True)
    war_size = Column(Integer, nullable=True)

    # Meta
    is_active = Column(Boolean, default=True)
    polled_at = Column(DateTime, default=datetime.datetime.utcnow)

    __table_args__ = (
        UniqueConstraint("user_id", "account_tag", "clan_tag", "event_type", "event_subtype",
                         name="uq_event_snapshot"),
    )

    user = relationship("User", back_populates="event_snapshots")
    notification_logs = relationship("NotificationLog", back_populates="event_snapshot", cascade="all, delete-orphan")

    @property
    def attacks_remaining(self):
        return max(0, self.attacks_max - self.attacks_used)


class ReminderConfig(Base):
    __tablename__ = "reminder_configs"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id = Column(String, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    event_type = Column(String, nullable=False)  # 'cw', 'cwl', 'raid'
    enabled = Column(Boolean, default=True)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)

    __table_args__ = (
        UniqueConstraint("user_id", "event_type", name="uq_reminder_config_user_event"),
    )

    user = relationship("User", back_populates="reminder_configs")
    times = relationship("ReminderTime", back_populates="config", cascade="all, delete-orphan")


class ReminderTime(Base):
    __tablename__ = "reminder_times"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    reminder_config_id = Column(String, ForeignKey("reminder_configs.id", ondelete="CASCADE"), nullable=False)
    minutes_before_end = Column(Integer, nullable=False)
    label = Column(String, nullable=True)
    enabled = Column(Boolean, default=True)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)

    config = relationship("ReminderConfig", back_populates="times")


class NotificationLog(Base):
    __tablename__ = "notification_logs"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id = Column(String, nullable=False)
    event_snapshot_id = Column(String, ForeignKey("event_snapshots.id", ondelete="CASCADE"), nullable=False)
    reminder_time_id = Column(String, nullable=False)
    sent_at = Column(DateTime, default=datetime.datetime.utcnow)
    status = Column(String, default="sent")  # 'sent', 'failed', 'skipped'
    fcm_message_id = Column(String, nullable=True)

    __table_args__ = (
        UniqueConstraint("event_snapshot_id", "reminder_time_id", name="uq_notification_log_snapshot_time"),
    )

    event_snapshot = relationship("EventSnapshot", back_populates="notification_logs")
