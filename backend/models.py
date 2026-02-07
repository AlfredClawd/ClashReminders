from sqlalchemy import Column, String, DateTime, Enum, ForeignKey, Integer, Boolean, JSON
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
    notification_hours_before = Column(Integer, default=2)

    accounts = relationship("PlayerAccount", back_populates="user")


class PlayerAccount(Base):
    __tablename__ = "player_accounts"

    tag = Column(String, primary_key=True)
    name = Column(String, nullable=True)
    game_type = Column(String, default="COC")
    user_id = Column(String, ForeignKey("users.id"))
    clan_tag = Column(String, nullable=True)
    last_check = Column(DateTime, nullable=True)

    user = relationship("User", back_populates="accounts")
    status_checks = relationship(
        "WarStatusLog",
        back_populates="account",
        order_by="WarStatusLog.checked_at.desc()"
    )
    notification_logs = relationship(
        "NotificationLog",
        back_populates="account",
        order_by="NotificationLog.sent_at.desc()"
    )


class WarStatusLog(Base):
    __tablename__ = "war_status_logs"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    account_tag = Column(String, ForeignKey("player_accounts.tag"))
    state = Column(String)
    active_war = Column(Boolean, default=False)
    attacks_left = Column(Integer, default=0)
    end_time = Column(String, nullable=True)
    opponent = Column(String, nullable=True)
    checked_at = Column(DateTime, default=datetime.datetime.utcnow)
    raw_data = Column(JSON, nullable=True)

    account = relationship("PlayerAccount", back_populates="status_checks")


class NotificationLog(Base):
    __tablename__ = "notification_logs"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    account_tag = Column(String, ForeignKey("player_accounts.tag"))
    notification_type = Column(String)
    status = Column(String)
    fcm_response = Column(String, nullable=True)
    sent_at = Column(DateTime, default=datetime.datetime.utcnow)

    account = relationship("PlayerAccount", back_populates="notification_logs")
