import os
from dotenv import load_dotenv

load_dotenv()

class Settings:
    PROJECT_NAME: str = "ClashReminders"
    COC_API_KEY: str = os.getenv("COC_API_KEY", "")
    CR_API_KEY: str = os.getenv("CR_API_KEY", "")
    DATABASE_URL: str = os.getenv("DATABASE_URL", "sqlite:///./clash_reminders.db")

settings = Settings()
