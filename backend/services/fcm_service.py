"""
FCM Push Notification Service.
"""
import logging
import os

logger = logging.getLogger(__name__)

_firebase_initialized = False


def init_firebase():
    """Initialize Firebase Admin SDK. Call once at startup."""
    global _firebase_initialized
    if _firebase_initialized:
        return True

    try:
        import firebase_admin
        from firebase_admin import credentials
        from core.config import settings

        cred_path = settings.FIREBASE_CREDENTIALS_PATH
        if os.path.exists(cred_path):
            cred = credentials.Certificate(cred_path)
            firebase_admin.initialize_app(cred)
            _firebase_initialized = True
            logger.info("Firebase Admin SDK initialized successfully.")
            return True
        else:
            logger.warning(f"Firebase credentials file not found at {cred_path}. Push notifications disabled.")
            return False
    except Exception as e:
        logger.warning(f"Failed to initialize Firebase: {e}. Push notifications disabled.")
        return False


async def send_push(token: str, title: str, body: str, data: dict = None) -> bool:
    """
    Send a push notification via FCM.
    Returns True on success, False on failure.
    """
    if not _firebase_initialized:
        logger.debug("Firebase not initialized, skipping push notification.")
        return False

    try:
        from firebase_admin import messaging

        message = messaging.Message(
            notification=messaging.Notification(
                title=title,
                body=body,
            ),
            data={k: str(v) for k, v in (data or {}).items()},
            token=token,
            android=messaging.AndroidConfig(
                priority="high",
                notification=messaging.AndroidNotification(
                    channel_id="clash_reminders",
                    icon="ic_notification",
                ),
            ),
        )

        response = messaging.send(message)
        logger.info(f"FCM push sent successfully: {response}")
        return True

    except Exception as e:
        logger.error(f"FCM push failed: {e}")
        return False
