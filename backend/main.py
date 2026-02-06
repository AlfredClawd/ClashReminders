from fastapi import FastAPI, Depends, HTTPException
from sqlalchemy.orm import Session
from pydantic import BaseModel
from typing import List, Optional
import models
from database import engine, get_db

# Create database tables
models.Base.metadata.create_all(bind=engine)

app = FastAPI(title="ClashReminders API", version="0.1.1")

class UserRegister(BaseModel):
    fcm_token: Optional[str] = None

class UserResponse(BaseModel):
    id: str
    created_at: str

@app.get("/")
async def root():
    return {"message": "Welcome to ClashReminders API", "status": "active"}

@app.post("/api/v1/users/register", response_model=UserResponse)
def register_user(user_data: UserRegister, db: Session = Depends(get_db)):
    new_user = models.User(fcm_token=user_data.fcm_token)
    db.add(new_user)
    db.commit()
    db.refresh(new_user)
    return {
        "id": new_user.id,
        "created_at": new_user.created_at.isoformat()
    }

@app.get("/health")
async def health_check():
    return {"status": "healthy"}
