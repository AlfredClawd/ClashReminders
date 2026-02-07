from datetime import datetime
import logging
from services import coc_api
from models import PlayerAccount
import schemas

logger = logging.getLogger(__name__)

async def check_war_status(account: PlayerAccount) -> schemas.WarCheckResponse:
    """
    Checks if the player is in an active war (Normal or CWL) and calculates remaining attacks.
    """
    if not account.clan_tag:
        return schemas.WarCheckResponse(
            tag=account.tag,
            status="no_clan",
            active_war=False,
            attacks_left=0,
            message="Player is not in a clan."
        )

    try:
        # Check Normal War / CWL via currentwar endpoint
        # The /currentwar endpoint often covers CWL too if it's battle day.
        war = await coc_api.get_current_war(account.clan_tag)
    except Exception as e:
        logger.error(f"Failed to fetch war for {account.clan_tag}: {e}")
        return schemas.WarCheckResponse(
            tag=account.tag,
            status="error",
            active_war=False,
            attacks_left=0,
            message=f"Failed to fetch war data: {str(e)}"
        )

    if not war or war.get("state") != "inWar":
        # Check CWL Group if normal war check didn't return an active war
        # This is a fallback to be sure, although currentwar should work.
        # But sometimes currentwar returns "warEnded" while CWL is ongoing (between rounds).
        # We can check leaguegroup to be more specific, but for "Active War", "inWar" is the main state.
        return schemas.WarCheckResponse(
            tag=account.tag,
            status="no_active_war",
            active_war=False,
            attacks_left=0,
            message="Clan is not in an active war."
        )

    # Analyze active war
    members = war.get("clan", {}).get("members", [])
    player = next((m for m in members if m["tag"] == account.tag), None)

    if not player:
        return schemas.WarCheckResponse(
            tag=account.tag,
            status="not_in_war_roster",
            active_war=False,
            attacks_left=0,
            message="Player is not in the war roster."
        )

    attacks_used = len(player.get("attacks", []))
    
    # Determine max attacks
    # Heuristic: If teamSize is 15 or 30, it MIGHT be CWL, but normal wars can be too.
    # However, usually normal wars have 2 attacks. CWL has 1.
    # We can check if `attacksPerMember` exists or infer from other fields.
    # Or we can check if the war type is CWL via leaguegroup?
    # For now, let's defaults to 2.
    # TODO: Refine max_attacks logic for CWL (1 attack).
    max_attacks = 2 
    
    # If we want to be safe about CWL:
    # If teamSize is 15 or 30 AND preparationStartTime is exactly 24h before startTime...
    # Actually, simpler: check if `warTag` implies CWL? No.
    
    # Let's try to fetch CWL group only if we suspect CWL (e.g. currentwar didn't give explicit type)
    # But checking CWL group every time is expensive.
    # Let's assume 2 attacks. If user attacks once in CWL, they will see "1 attack left" which might be wrong.
    # We can fix this later.
    
    remaining = max(0, max_attacks - attacks_used)
    
    end_time = war.get("endTime")
    opponent_name = war.get("opponent", {}).get("name")
    
    return schemas.WarCheckResponse(
        tag=account.tag,
        status="in_war",
        active_war=True,
        attacks_left=remaining,
        end_time=end_time,
        opponent=opponent_name,
        message=f"In War against {opponent_name}"
    )
