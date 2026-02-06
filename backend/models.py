from sqlalchemy import Column, String, DateTime, Enum, ForeignKey
from sqlalchemy.orm import relationship
import uuid
import datetime
from database import Base

class User(Base):
    __tablename__ = "users"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    fcm_token = Column(String, nullable=True)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)
    
    accounts = relationship("PlayerAccount", back_populates="user")

class PlayerAccount(Base):
    __tablename__ = "player_accounts"

    tag = Column(String, primary_key=True)
    name = Column(String, nullable=True)
    game_type = Column(String, default="COC") # COC or CR
    user_id = Column(String, ForeignKey("users.id"))
    clan_tag = Column(String, nullable=True)

    user = relationship("User", back_populates="accounts")
