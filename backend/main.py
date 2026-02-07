import logging
from fastapi import FastAPI, Depends, HTTPException, Request
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware
import time
from fastapi.concurrency import run_in_threadpool
from sqlalchemy.orm import Session
from typing import List
import models
import schemas
from database import engine, get_db
from services import coc_api, reminder_service
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

class RateLimitMiddleware(BaseHTTPMiddleware):
    # simple in-memory rate limiting: 1 request per 5 seconds per IP
    def __init__(self, app):
        super().__init__(app)
        self.ip_tracker = {}

    async def dispatch(self, request: Request, call_next):
        client_ip = request.client.host
        current_time = time.time()
        
        if client_ip in self.ip_tracker:
            last_request_time = self.ip_tracker[client_ip]
            if current_time - last_request_time < 5:
                logger.warning(f"Rate limit exceeded for IP: {client_ip}")
                return JSONResponse(
                    status_code=429,
                    content={"detail": "Too many requests. Please wait 5 seconds between requests."}
                )
        
        self.ip_tracker[client_ip] = current_time
        response = await call_next(request)
        return response

app = FastAPI(
    title="ClashReminders API",
    description="API for managing Clash of Clans reminders and tracking war status.",
    version="1.0.0",
    docs_url="/docs",
    redoc_url="/redoc"
)
app.add_middleware(RateLimitMiddleware)

@app.on_event("startup")
async def startup_event():
    if not settings.COC_API_KEY:
        logger.warning("COC_API_KEY is not set in environment variables! CoC features will fail.")
    else:
        logger.info("COC_API_KEY loaded successfully.")

# Global Exception Handler
@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    logger.error(f"Global exception occurred: {exc}", exc_info=True)
    return JSONResponse(
        status_code=500,
        content={"detail": "Internal Server Error"},
    )

@app.get("/", tags=["General"])
async def root():
    """Check if the API is active."""
    return {"message": "Welcome to ClashReminders API", "status": "active"}

@app.get("/health", tags=["General"])
async def health_check():
    """Health check endpoint."""
    return {"status": "healthy"}

# User Management
@app.post("/api/v1/users/register", response_model=schemas.UserResponse, tags=["Users"], summary="Register a new user")
def register_user(user_data: schemas.UserRegister, db: Session = Depends(get_db)):
    """
    Register a new user with an FCM token.
    """
    try:
        new_user = models.User(fcm_token=user_data.fcm_token)
        db.add(new_user)
        db.commit()
        db.refresh(new_user)
        logger.info(f"New user registered: {new_user.id}")
        return new_user
    except Exception as e:
        logger.error(f"Error registering user: {e}")
        db.rollback()
        raise HTTPException(status_code=500, detail="Failed to register user")

# Account Management
@app.post("/api/v1/users/{user_id}/accounts", response_model=schemas.PlayerAccountResponse, tags=["Accounts"], summary="Link a Player Account")
async def add_account(user_id: str, account: schemas.PlayerAccountCreate, db: Session = Depends(get_db)):
    """
    Link a Clash of Clans player account to a user.
    """
    try:
        # Verify user exists (Sync DB call in threadpool)
        user = await run_in_threadpool(lambda: db.query(models.User).filter(models.User.id == user_id).first())
        if not user:
            logger.warning(f"User not found during account linking: {user_id}")
            raise HTTPException(status_code=404, detail="User not found")
        
        # Check if account already exists for user
        existing = await run_in_threadpool(lambda: db.query(models.PlayerAccount).filter(
            models.PlayerAccount.tag == account.tag, 
            models.PlayerAccount.user_id == user_id
        ).first())
        
        if existing:
            raise HTTPException(status_code=400, detail="Account already linked")

        # Verify tag with CoC API (Async)
        try:
            player_data = await coc_api.get_player(account.tag)
        except Exception as e:
            logger.error(f"CoC API error for tag {account.tag}: {e}")
            raise HTTPException(status_code=502, detail="Failed to verify player with CoC API")

        if not player_data:
            raise HTTPException(status_code=404, detail="Player tag not found in Clash of Clans")
        
        # Create account
        def create_acc():
            new_account = models.PlayerAccount(
                tag=player_data["tag"],
                name=player_data["name"],
                clan_tag=player_data.get("clan", {}).get("tag"),
                user_id=user_id,
                game_type="COC"
            )
            db.add(new_account)
            db.commit()
            db.refresh(new_account)
            return new_account

        new_account = await run_in_threadpool(create_acc)
        logger.info(f"Account linked: {new_account.tag} to user {user_id}")
        return new_account

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Unexpected error linking account: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail="Internal server error")

@app.get("/api/v1/users/{user_id}/accounts", response_model=List[schemas.PlayerAccountResponse], tags=["Accounts"], summary="List User Accounts")
def list_accounts(user_id: str, db: Session = Depends(get_db)):
    """
    List all accounts linked to a user.
    """
    accounts = db.query(models.PlayerAccount).filter(models.PlayerAccount.user_id == user_id).all()
    return accounts

@app.delete("/api/v1/users/{user_id}/accounts/{tag}", tags=["Accounts"], summary="Remove a Linked Account")
def delete_account(user_id: str, tag: str, db: Session = Depends(get_db)):
    """
    Remove a linked player account.
    """
    # Decode tag if needed, but FastAPI usually handles it.
    # Usually users pass URL encoded tags.
    account = db.query(models.PlayerAccount).filter(
        models.PlayerAccount.tag == tag, 
        models.PlayerAccount.user_id == user_id
    ).first()
    
    if not account:
        raise HTTPException(status_code=404, detail="Account not found")
    
    db.delete(account)
    db.commit()
    logger.info(f"Account removed: {tag} from user {user_id}")
    return {"message": "Account removed"}

# War Status Check
@app.get("/api/v1/accounts/{tag}/war_status", response_model=schemas.WarCheckResponse, tags=["War Status"], summary="Check War Status")
async def get_war_status(tag: str, db: Session = Depends(get_db)):
    """
    Check the current war status for a given player tag.
    """
    account = await run_in_threadpool(lambda: db.query(models.PlayerAccount).filter(models.PlayerAccount.tag == tag).first())
    
    if not account:
        raise HTTPException(status_code=404, detail="Account not found locally")
    
    try:
        status = await reminder_service.check_war_status(account)
        return status
    except Exception as e:
        logger.error(f"Error checking war status for {tag}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail="Failed to check war status")
