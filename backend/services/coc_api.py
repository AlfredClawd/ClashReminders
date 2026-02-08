import httpx
from core.config import settings
import urllib.parse
import logging
import asyncio

logger = logging.getLogger(__name__)

class CoCClient:
    def __init__(self, api_key: str):
        self.api_key = api_key
        self.base_url = "https://api.clashofclans.com/v1"
        self.headers = {
            "Authorization": f"Bearer {api_key}",
            "Accept": "application/json"
        }

    async def _get(self, endpoint: str, retries: int = 3):
        for attempt in range(retries):
            async with httpx.AsyncClient(timeout=15.0) as client:
                try:
                    response = await client.get(f"{self.base_url}{endpoint}", headers=self.headers)
                    if response.status_code == 200:
                        return response.json()
                    elif response.status_code == 404:
                        return None
                    elif response.status_code == 429:
                        wait = (attempt + 1) * 2
                        logger.warning(f"Rate limited on {endpoint}, waiting {wait}s (attempt {attempt+1})")
                        await asyncio.sleep(wait)
                        continue
                    elif response.status_code >= 500:
                        wait = (attempt + 1) * 3
                        logger.warning(f"Server error {response.status_code} on {endpoint}, waiting {wait}s")
                        await asyncio.sleep(wait)
                        continue
                    else:
                        logger.error(f"CoC API error {response.status_code} on {endpoint}: {response.text[:200]}")
                        return None
                except httpx.TimeoutException:
                    logger.warning(f"Timeout on {endpoint} (attempt {attempt+1})")
                    if attempt < retries - 1:
                        await asyncio.sleep((attempt + 1) * 2)
                    continue
                except Exception as e:
                    logger.error(f"Request error on {endpoint}: {e}")
                    return None
        logger.error(f"All {retries} retries failed for {endpoint}")
        return None

    async def get_player(self, tag: str):
        """GET /players/{tag}"""
        safe_tag = urllib.parse.quote(tag)
        return await self._get(f"/players/{safe_tag}")

    async def get_clan_info(self, clan_tag: str):
        """GET /clans/{tag} — Basic clan info"""
        safe_tag = urllib.parse.quote(clan_tag)
        return await self._get(f"/clans/{safe_tag}")

    async def get_current_war(self, clan_tag: str):
        """GET /clans/{tag}/currentwar"""
        safe_tag = urllib.parse.quote(clan_tag)
        return await self._get(f"/clans/{safe_tag}/currentwar")

    async def get_cwl_group(self, clan_tag: str):
        """GET /clans/{tag}/currentwar/leaguegroup"""
        safe_tag = urllib.parse.quote(clan_tag)
        return await self._get(f"/clans/{safe_tag}/currentwar/leaguegroup")

    async def get_cwl_war(self, war_tag: str):
        """GET /clanwarleagues/wars/{warTag}"""
        safe_tag = urllib.parse.quote(war_tag)
        return await self._get(f"/clanwarleagues/wars/{safe_tag}")

    async def get_raid_seasons(self, clan_tag: str):
        """GET /clans/{tag}/capitalraidseasons?limit=1"""
        safe_tag = urllib.parse.quote(clan_tag)
        return await self._get(f"/clans/{safe_tag}/capitalraidseasons?limit=1")


# Singleton client
_coc_client = None

def get_coc_client() -> CoCClient:
    global _coc_client
    if _coc_client is None:
        _coc_client = CoCClient(settings.COC_API_KEY)
    return _coc_client

# Function wrappers for convenience
async def get_player(tag: str):
    return await get_coc_client().get_player(tag)

async def get_clan_info(clan_tag: str):
    return await get_coc_client().get_clan_info(clan_tag)

async def get_current_war(clan_tag: str):
    return await get_coc_client().get_current_war(clan_tag)

async def get_cwl_group(clan_tag: str):
    return await get_coc_client().get_cwl_group(clan_tag)

async def get_cwl_war(war_tag: str):
    return await get_coc_client().get_cwl_war(war_tag)

async def get_raid_seasons(clan_tag: str):
    return await get_coc_client().get_raid_seasons(clan_tag)
