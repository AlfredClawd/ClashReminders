import httpx
from core.config import settings
import urllib.parse
import asyncio

class CoCClient:
    def __init__(self, api_key: str):
        self.api_key = api_key
        self.base_url = "https://api.clashofclans.com/v1"
        self.headers = {
            "Authorization": f"Bearer {api_key}",
            "Accept": "application/json"
        }

    async def _get(self, endpoint: str):
        async with httpx.AsyncClient(timeout=10.0) as client:
            try:
                response = await client.get(f"{self.base_url}{endpoint}", headers=self.headers)
                if response.status_code == 200:
                    return response.json()
                elif response.status_code == 404:
                    return None
                else:
                    # Log error status here if needed
                    return None
            except Exception as e:
                # Log exception here
                return None

    async def get_player(self, tag: str):
        safe_tag = urllib.parse.quote(tag)
        return await self._get(f"/players/{safe_tag}")

    async def get_current_war(self, clan_tag: str):
        safe_tag = urllib.parse.quote(clan_tag)
        return await self._get(f"/clans/{safe_tag}/currentwar")

    async def get_cwl_group(self, clan_tag: str):
        safe_tag = urllib.parse.quote(clan_tag)
        return await self._get(f"/clans/{safe_tag}/currentwar/leaguegroup")

    async def get_cwl_war(self, war_tag: str):
        safe_tag = urllib.parse.quote(war_tag)
        return await self._get(f"/clanwarleagues/wars/{safe_tag}")

# Helper for singleton-ish access if needed
_coc_client = None

def get_coc_client():
    global _coc_client
    if _coc_client is None:
        _coc_client = CoCClient(settings.COC_API_KEY)
    return _coc_client

# Legacy function wrappers for compatibility during transition
async def get_player(tag: str, game_type: str = "COC"):
    client = get_coc_client()
    return await client.get_player(tag)

async def get_current_war(clan_tag: str):
    client = get_coc_client()
    return await client.get_current_war(clan_tag)

async def get_cwl_group(clan_tag: str):
    client = get_coc_client()
    return await client.get_cwl_group(clan_tag)

async def get_clan_war_league_war(war_tag: str):
    client = get_coc_client()
    return await client.get_cwl_war(war_tag)
