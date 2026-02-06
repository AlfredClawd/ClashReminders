import httpx
import json

BASE_URL = "http://127.0.0.1:8000"

def test_register():
    print("Testing user registration...")
    try:
        response = httpx.post(f"{BASE_URL}/api/v1/users/register", json={"fcm_token": "test_token_123"})
        if response.status_code == 200:
            print(f"✅ Success: {response.json()}")
        else:
            print(f"❌ Failed: {response.status_code} - {response.text}")
    except Exception as e:
        print(f"❌ Error connecting to server: {e}")

if __name__ == "__main__":
    test_register()
