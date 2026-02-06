import httpx
from core.config import settings

async def get_player(tag: str, game_type: str = "COC"):
    """
    Fetch player details from the respective Supercell API.
    """
    base_url = "https://api.clashofclans.com/v1" if game_type == "COC" else "https://api.clashroyale.com/v1"
    api_key = settings.COC_API_KEY if game_type == "COC" else settings.CR_API_KEY
    
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Accept": "application/json"
    }
    
    # URL encode the tag (replace # with %23)
    safe_tag = tag.replace("#", "%23")
    
    async with httpx.AsyncClient() as client:
        response = await client.get(f"{base_url}/players/{safe_tag}", headers=headers)
        if response.status_code == 200:
            return response.json()
        return None
