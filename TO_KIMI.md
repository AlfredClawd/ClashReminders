# TASK: Init ClashReminders Backend

**Role:** Kimi (Background Heavy-Lifter)
**Context:** New project "ClashReminders". We need the backend foundation.

## Objectives
1.  Create a standard Python project structure in `clash-reminders/backend`.
2.  Initialize a **FastAPI** application (`main.py`).
3.  Create a `requirements.txt` with: `fastapi`, `uvicorn`, `httpx`, `python-dotenv`, `sqlalchemy`.
4.  Create a `core/config.py` handling Environment Variables (API Keys).
5.  Create a simple `services/coc_api.py` with a function `get_player(tag: str)` that hits the Clash of Clans API (use a placeholder or try to read `COC_API_KEY` from env).
6.  Ensure the app runs with `uvicorn main:app --reload`.

## Constraints
*   Use `clash-reminders/backend` as the working directory.
*   Do not worry about the Android app yet.
*   Keep it simple.

## Output
*   Files created.
*   Command to run the server.
