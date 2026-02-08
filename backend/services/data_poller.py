"""
DataPoller Service — Polls CoC API every 60 seconds and updates event_snapshots.
"""
import logging
import asyncio
from datetime import datetime, timezone, timedelta
from sqlalchemy.orm import Session
from services import coc_api
import models

logger = logging.getLogger(__name__)

# ============ TIMESTAMP PARSING ============

def parse_coc_timestamp(ts: str) -> datetime:
    """Parse CoC API timestamp format '20260209T143000.000Z' -> datetime (UTC)"""
    if not ts:
        return None
    try:
        return datetime.strptime(ts, "%Y%m%dT%H%M%S.%fZ").replace(tzinfo=timezone.utc)
    except ValueError:
        try:
            return datetime.strptime(ts, "%Y%m%dT%H%M%SZ").replace(tzinfo=timezone.utc)
        except ValueError:
            logger.error(f"Failed to parse timestamp: {ts}")
            return None


# ============ HELPERS ============

def find_player_in_members(members: list, account_tag: str) -> dict | None:
    """Find a player in a war/raid member list by tag."""
    return next((m for m in members if m.get("tag") == account_tag), None)


def format_duration(seconds: int) -> str:
    """Format seconds into a human-readable duration string."""
    if seconds <= 0:
        return "0m"
    days = seconds // 86400
    hours = (seconds % 86400) // 3600
    minutes = (seconds % 3600) // 60
    parts = []
    if days > 0:
        parts.append(f"{days}d")
    if hours > 0:
        parts.append(f"{hours}h")
    if minutes > 0 or not parts:
        parts.append(f"{minutes}m")
    return " ".join(parts)


# ============ CLAN DATA FETCHING ============

async def fetch_clan_events(clan_tag: str) -> dict:
    """Fetch all event data (CW, CWL, Raid) for a single clan."""
    result = {
        "cw": None,
        "cwl": [],
        "raid": None,
        "clan_name": None,
        "member_tags": set(),  # Clan member tags for raid not-participating detection
    }

    # 1. Clan War (Normal)
    try:
        cw_data = await coc_api.get_current_war(clan_tag)
        if cw_data and cw_data.get("state") in ("preparation", "inWar"):
            # Check attacksPerMember to distinguish CW from CWL
            attacks_per = cw_data.get("attacksPerMember", 2)
            if attacks_per >= 2:
                # Normal CW
                result["cw"] = cw_data
                result["clan_name"] = cw_data.get("clan", {}).get("name")
            # If attacksPerMember == 1, this is CWL battle day — handle in CWL section
    except Exception as e:
        logger.error(f"Error fetching CW for {clan_tag}: {e}")

    # 2. CWL
    try:
        cwl_group = await coc_api.get_cwl_group(clan_tag)
        if cwl_group and "rounds" in cwl_group:
            for round_idx, round_data in enumerate(cwl_group.get("rounds", [])):
                for war_tag in round_data.get("warTags", []):
                    if war_tag == "#0":
                        continue
                    try:
                        war = await coc_api.get_cwl_war(war_tag)
                        if war and war.get("state") == "inWar":
                            # Check if our clan is in this war
                            clan_side = war.get("clan", {}).get("tag")
                            opp_side = war.get("opponent", {}).get("tag")
                            if clan_side == clan_tag or opp_side == clan_tag:
                                war["_round_index"] = round_idx + 1
                                result["cwl"].append(war)
                                if not result["clan_name"]:
                                    if clan_side == clan_tag:
                                        result["clan_name"] = war.get("clan", {}).get("name")
                                    else:
                                        result["clan_name"] = war.get("opponent", {}).get("name")
                    except Exception as e:
                        logger.error(f"Error fetching CWL war {war_tag}: {e}")
    except Exception as e:
        logger.error(f"Error fetching CWL group for {clan_tag}: {e}")

    # 3. Raid Weekend
    try:
        raid_data = await coc_api.get_raid_seasons(clan_tag)
        if raid_data and raid_data.get("items"):
            current_raid = raid_data["items"][0]
            if current_raid.get("state") == "ongoing":
                result["raid"] = current_raid
                if not result["clan_name"]:
                    # Try to get clan name and member list from clan info
                    clan_info = await coc_api.get_clan_info(clan_tag)
                    if clan_info:
                        result["clan_name"] = clan_info.get("name")
                        result["member_tags"] = {m["tag"] for m in clan_info.get("memberList", [])}
    except Exception as e:
        logger.error(f"Error fetching raid for {clan_tag}: {e}")

    # If we still don't have a clan name, fetch it
    if not result["clan_name"]:
        try:
            clan_info = await coc_api.get_clan_info(clan_tag)
            if clan_info:
                result["clan_name"] = clan_info.get("name")
                result["member_tags"] = {m["tag"] for m in clan_info.get("memberList", [])}
        except Exception:
            pass
    
    # Ensure we have member tags for raid not-participating detection
    if not result["member_tags"]:
        try:
            clan_info = await coc_api.get_clan_info(clan_tag)
            if clan_info:
                result["member_tags"] = {m["tag"] for m in clan_info.get("memberList", [])}
                if not result["clan_name"]:
                    result["clan_name"] = clan_info.get("name")
        except Exception:
            pass

    return result


# ============ UPSERT LOGIC ============

def upsert_event_snapshot(
    db: Session,
    user_id: str,
    account_tag: str,
    account_name: str,
    clan_tag: str,
    clan_name: str,
    event_type: str,
    event_subtype: str | None,
    state: str,
    attacks_used: int,
    attacks_max: int,
    end_time: datetime | None,
    start_time: datetime | None,
    opponent_name: str | None,
    opponent_tag: str | None,
    war_size: int | None,
    is_active: bool,
):
    """Insert or update an event snapshot."""
    # Find existing
    query = db.query(models.EventSnapshot).filter(
        models.EventSnapshot.user_id == user_id,
        models.EventSnapshot.account_tag == account_tag,
        models.EventSnapshot.clan_tag == clan_tag,
        models.EventSnapshot.event_type == event_type,
    )
    if event_subtype:
        query = query.filter(models.EventSnapshot.event_subtype == event_subtype)
    else:
        query = query.filter(models.EventSnapshot.event_subtype.is_(None))

    existing = query.first()

    now = datetime.now(timezone.utc)

    if existing:
        existing.account_name = account_name
        existing.clan_name = clan_name
        existing.state = state
        existing.attacks_used = attacks_used
        existing.attacks_max = attacks_max
        existing.end_time = end_time
        existing.start_time = start_time
        existing.opponent_name = opponent_name
        existing.opponent_tag = opponent_tag
        existing.war_size = war_size
        existing.is_active = is_active
        existing.polled_at = now
    else:
        snapshot = models.EventSnapshot(
            user_id=user_id,
            account_tag=account_tag,
            account_name=account_name,
            clan_tag=clan_tag,
            clan_name=clan_name,
            event_type=event_type,
            event_subtype=event_subtype,
            state=state,
            attacks_used=attacks_used,
            attacks_max=attacks_max,
            end_time=end_time,
            start_time=start_time,
            opponent_name=opponent_name,
            opponent_tag=opponent_tag,
            war_size=war_size,
            is_active=is_active,
            polled_at=now,
        )
        db.add(snapshot)


# ============ PROCESS ACCOUNT IN CLAN ============

def process_account_cw(db, user, account, clan_tag, clan_name, war_data):
    """Process a clan war for an account."""
    # Determine which side is our clan
    clan_side_tag = war_data.get("clan", {}).get("tag")
    our_side = "clan" if clan_side_tag == clan_tag else "opponent"
    other_side = "opponent" if our_side == "clan" else "clan"

    members = war_data.get(our_side, {}).get("members", [])
    player = find_player_in_members(members, account.tag)

    if player:
        attacks_used = len(player.get("attacks", []))
        attacks_max = war_data.get("attacksPerMember", 2)
        is_active = (war_data["state"] == "inWar" and attacks_used < attacks_max)

        upsert_event_snapshot(
            db=db,
            user_id=user.id,
            account_tag=account.tag,
            account_name=player.get("name", account.name),
            clan_tag=clan_tag,
            clan_name=clan_name,
            event_type="cw",
            event_subtype=None,
            state=war_data["state"],
            attacks_used=attacks_used,
            attacks_max=attacks_max,
            end_time=parse_coc_timestamp(war_data.get("endTime")),
            start_time=parse_coc_timestamp(war_data.get("startTime")),
            opponent_name=war_data.get(other_side, {}).get("name"),
            opponent_tag=war_data.get(other_side, {}).get("tag"),
            war_size=war_data.get("teamSize"),
            is_active=is_active,
        )


def process_account_cwl(db, user, account, clan_tag, clan_name, cwl_wars):
    """Process CWL wars for an account."""
    for war in cwl_wars:
        clan_side_tag = war.get("clan", {}).get("tag")
        our_side = "clan" if clan_side_tag == clan_tag else "opponent"
        other_side = "opponent" if our_side == "clan" else "clan"

        members = war.get(our_side, {}).get("members", [])
        player = find_player_in_members(members, account.tag)

        if player:
            attacks_used = len(player.get("attacks", []))
            attacks_max = 1  # CWL always 1 attack
            round_idx = war.get("_round_index", 0)
            is_active = (war["state"] == "inWar" and attacks_used < attacks_max)

            upsert_event_snapshot(
                db=db,
                user_id=user.id,
                account_tag=account.tag,
                account_name=player.get("name", account.name),
                clan_tag=clan_tag,
                clan_name=clan_name,
                event_type="cwl",
                event_subtype=f"day_{round_idx}",
                state=war["state"],
                attacks_used=attacks_used,
                attacks_max=attacks_max,
                end_time=parse_coc_timestamp(war.get("endTime")),
                start_time=parse_coc_timestamp(war.get("startTime")),
                opponent_name=war.get(other_side, {}).get("name"),
                opponent_tag=war.get(other_side, {}).get("tag"),
                war_size=war.get("teamSize"),
                is_active=is_active,
            )


def process_account_raid(db, user, account, clan_tag, clan_name, raid_data, clan_member_tags=None):
    """Process raid weekend for an account.
    
    Handles two cases:
    1. Player found in raid members → use actual attack data
    2. Player NOT in raid members but IS in clan → 0 attacks (not yet participating)
       This covers plan §12.3: players in the clan who haven't attacked yet.
    """
    raid_members = raid_data.get("members", [])
    player = find_player_in_members(raid_members, account.tag)

    if player:
        attacks_used = player.get("attacks", 0)
        attack_limit = player.get("attackLimit", 5)
        bonus = player.get("bonusAttackLimit", 1)
        attacks_max = attack_limit + bonus
        is_active = (attacks_used < attacks_max)

        upsert_event_snapshot(
            db=db,
            user_id=user.id,
            account_tag=account.tag,
            account_name=player.get("name", account.name),
            clan_tag=clan_tag,
            clan_name=clan_name,
            event_type="raid",
            event_subtype=None,
            state="ongoing",
            attacks_used=attacks_used,
            attacks_max=attacks_max,
            end_time=parse_coc_timestamp(raid_data.get("endTime")),
            start_time=parse_coc_timestamp(raid_data.get("startTime")),
            opponent_name=None,
            opponent_tag=None,
            war_size=None,
            is_active=is_active,
        )
    else:
        # Player not in raid members yet — check if account is in this clan
        # Use current_clan_tag from player data, or clan_member_tags from clan info
        in_clan = False
        if clan_member_tags and account.tag in clan_member_tags:
            in_clan = True
        elif account.current_clan_tag == clan_tag:
            in_clan = True

        if in_clan:
            # Player is in the clan but hasn't attacked yet → 0/6 attacks
            upsert_event_snapshot(
                db=db,
                user_id=user.id,
                account_tag=account.tag,
                account_name=account.name,
                clan_tag=clan_tag,
                clan_name=clan_name,
                event_type="raid",
                event_subtype=None,
                state="ongoing",
                attacks_used=0,
                attacks_max=6,  # Default: 5 base + 1 bonus
                end_time=parse_coc_timestamp(raid_data.get("endTime")),
                start_time=parse_coc_timestamp(raid_data.get("startTime")),
                opponent_name=None,
                opponent_tag=None,
                war_size=None,
                is_active=True,
            )


# ============ MAIN POLL FUNCTION ============

async def poll_all_users(db: Session):
    """Main polling function — called every 60 seconds by the scheduler."""
    logger.info("Starting poll cycle...")

    try:
        users = db.query(models.User).all()
        if not users:
            logger.info("No users to poll for.")
            return

        # Collect all unique clan tags across all users
        all_tracked_clans = db.query(models.TrackedClan).all()
        unique_clan_tags = set()
        clan_to_users = {}  # clan_tag -> list of user_ids

        for tc in all_tracked_clans:
            unique_clan_tags.add(tc.clan_tag)
            if tc.clan_tag not in clan_to_users:
                clan_to_users[tc.clan_tag] = []
            clan_to_users[tc.clan_tag].append(tc.user_id)

        if not unique_clan_tags:
            logger.info("No clans being tracked.")
            return

        # Fetch data for all unique clans (deduplicated)
        logger.info(f"Fetching data for {len(unique_clan_tags)} unique clans...")
        clan_data_cache = {}
        for clan_tag in unique_clan_tags:
            try:
                clan_data_cache[clan_tag] = await fetch_clan_events(clan_tag)
            except Exception as e:
                logger.error(f"Error fetching clan {clan_tag}: {e}")
                continue

        # Update tracked clan names
        for tc in all_tracked_clans:
            if tc.clan_tag in clan_data_cache:
                name = clan_data_cache[tc.clan_tag].get("clan_name")
                if name:
                    tc.clan_name = name

        # Process each user
        for user in users:
            user_clans = [tc for tc in all_tracked_clans if tc.user_id == user.id]
            user_accounts = db.query(models.PlayerAccount).filter(
                models.PlayerAccount.user_id == user.id
            ).all()

            if not user_accounts or not user_clans:
                continue

            for account in user_accounts:
                for tc in user_clans:
                    clan_tag = tc.clan_tag
                    events = clan_data_cache.get(clan_tag)
                    if not events:
                        continue

                    clan_name = events.get("clan_name", clan_tag)

                    # Clan War
                    if events["cw"]:
                        process_account_cw(db, user, account, clan_tag, clan_name, events["cw"])

                    # CWL
                    if events["cwl"]:
                        process_account_cwl(db, user, account, clan_tag, clan_name, events["cwl"])

                    # Raid
                    if events["raid"]:
                        process_account_raid(db, user, account, clan_tag, clan_name, events["raid"],
                                             clan_member_tags=events.get("member_tags"))

                # Update account's current clan from player API
                try:
                    player_data = await coc_api.get_player(account.tag)
                    if player_data:
                        account.name = player_data.get("name", account.name)
                        account.current_clan_tag = player_data.get("clan", {}).get("tag")
                        account.current_clan_name = player_data.get("clan", {}).get("name")
                        account.last_synced_at = datetime.now(timezone.utc)
                except Exception as e:
                    logger.error(f"Error updating player {account.tag}: {e}")

        db.commit()
        logger.info("Poll cycle completed successfully.")

    except Exception as e:
        logger.error(f"Poll cycle failed: {e}", exc_info=True)
        db.rollback()


async def cleanup_stale_snapshots(db: Session):
    """Mark expired events as inactive and delete old stale snapshots."""
    try:
        now = datetime.now(timezone.utc)

        # Mark expired events as inactive
        expired = db.query(models.EventSnapshot).filter(
            models.EventSnapshot.end_time < now,
            models.EventSnapshot.is_active == True,
        ).all()
        for snap in expired:
            snap.is_active = False

        # Delete snapshots older than 48h that are inactive
        cutoff = now - timedelta(hours=48)
        stale = db.query(models.EventSnapshot).filter(
            models.EventSnapshot.polled_at < cutoff,
            models.EventSnapshot.is_active == False,
        ).all()
        for snap in stale:
            db.delete(snap)

        db.commit()
        logger.info(f"Cleanup: {len(expired)} expired, {len(stale)} deleted.")
    except Exception as e:
        logger.error(f"Cleanup failed: {e}")
        db.rollback()
