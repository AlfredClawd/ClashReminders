from pydantic import BaseModel
from typing import List, Optional
from datetime import datetime

class UserRegister(BaseModel):
    fcm_token: Optional[str] = None

class UserResponse(BaseModel):
    id: str
    created_at: datetime
    
    class Config:
        from_attributes = True

class PlayerAccountCreate(BaseModel):
    tag: str

class PlayerAccountResponse(BaseModel):
    tag: str
    name: Optional[str]
    clan_tag: Optional[str]
    game_type: str
    user_id: str

    class Config:
        from_attributes = True

class WarCheckResponse(BaseModel):
    tag: str
    in_war: bool
    state: str # preparation, inWar, warEnded, none
    attacks_left: int
    time_remaining_seconds: int
    opponent_name: Optional[str]
