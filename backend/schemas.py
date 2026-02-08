from pydantic import BaseModel, field_validator, model_validator
from typing import List, Optional
from datetime import datetime


# ============ USER ============

class UserRegister(BaseModel):
    fcm_token: Optional[str] = None

class UserResponse(BaseModel):
    id: str
    created_at: datetime
    notification_enabled: bool = True
    fcm_token: Optional[str] = None

    class Config:
        from_attributes = True

class FcmTokenUpdate(BaseModel):
    fcm_token: str


# ============ PLAYER ACCOUNTS ============

class PlayerAccountCreate(BaseModel):
    tag: str

    @field_validator("tag")
    @classmethod
    def normalize_tag(cls, v: str) -> str:
        v = v.strip().upper()
        if not v.startswith("#"):
            v = "#" + v
        return v

class PlayerAccountResponse(BaseModel):
    id: str
    tag: str
    name: Optional[str] = None
    current_clan_tag: Optional[str] = None
    current_clan_name: Optional[str] = None
    user_id: str
    last_synced_at: Optional[datetime] = None
    display_name: Optional[str] = None

    class Config:
        from_attributes = True

    @model_validator(mode="after")
    def compute_display_name(self):
        if not self.display_name:
            name = self.name or "Unknown"
            tag = self.tag or ""
            self.display_name = f"{name} ({tag})"
        return self


# ============ TRACKED CLANS ============

class ClanCreate(BaseModel):
    clan_tag: str

    @field_validator("clan_tag")
    @classmethod
    def normalize_clan_tag(cls, v: str) -> str:
        v = v.strip().upper()
        if not v.startswith("#"):
            v = "#" + v
        return v

class TrackedClanResponse(BaseModel):
    id: str
    clan_tag: str
    clan_name: Optional[str] = None
    user_id: str
    created_at: Optional[datetime] = None

    class Config:
        from_attributes = True


# ============ REMINDER CONFIGURATION ============

class ReminderTimeCreate(BaseModel):
    minutes_before_end: int
    label: Optional[str] = None

class ReminderTimeResponse(BaseModel):
    id: str
    minutes_before_end: int
    label: Optional[str] = None
    enabled: bool = True

    class Config:
        from_attributes = True

class ReminderConfigResponse(BaseModel):
    id: str
    event_type: str
    enabled: bool = True
    times: List[ReminderTimeResponse] = []

    class Config:
        from_attributes = True

class ReminderConfigUpdate(BaseModel):
    event_type: str  # 'cw', 'cwl', 'raid'
    enabled: bool = True
    times: List[ReminderTimeCreate] = []

class RemindersUpdateRequest(BaseModel):
    reminders: List[ReminderConfigUpdate]

class RemindersResponse(BaseModel):
    reminders: List[ReminderConfigResponse]

class ReminderToggle(BaseModel):
    enabled: bool


# ============ EVENT SNAPSHOTS / STATUS ============

class EventSnapshotResponse(BaseModel):
    id: str
    account_tag: str
    account_name: Optional[str] = None
    clan_tag: str
    clan_name: Optional[str] = None
    event_type: str
    event_subtype: Optional[str] = None
    state: str
    attacks_used: int = 0
    attacks_max: int = 0
    attacks_remaining: int = 0
    end_time: Optional[datetime] = None
    start_time: Optional[datetime] = None
    time_remaining_seconds: int = 0
    time_remaining_formatted: str = ""
    opponent_name: Optional[str] = None
    opponent_tag: Optional[str] = None
    war_size: Optional[int] = None

    class Config:
        from_attributes = True

class StatusResponse(BaseModel):
    last_polled: Optional[datetime] = None
    events: List[EventSnapshotResponse] = []

class StatusSummaryItem(BaseModel):
    account_display: str
    clan_display: str
    event_label: str
    attacks_remaining: int
    end_time_formatted: str
    end_time_iso: Optional[str] = None

class EventTypeCount(BaseModel):
    count: int = 0
    accounts: int = 0

class StatusSummaryResponse(BaseModel):
    last_polled: Optional[datetime] = None
    total_missing: int = 0
    by_event_type: dict[str, EventTypeCount] = {}
    items: List[StatusSummaryItem] = []
