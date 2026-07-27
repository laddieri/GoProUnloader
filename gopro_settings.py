#!/usr/bin/env python3
"""
Persisted UI preferences
-------------------------
Remembers the choices made in the offload window between runs, in a small
JSON file under the user's config directory:

    Windows : %APPDATA%\\GoProUnloader\\settings.json
    else    : ~/.config/gopro-unloader/settings.json

Reads never raise: a missing, unreadable, or corrupt file falls back to
defaults, because losing preferences should never stop the app from starting.
"""

import json
import logging
import os
import sys
from pathlib import Path

log = logging.getLogger("gopro")

APP_NAME = "GoProUnloader"

DEFAULTS: dict = {
    "output_dir"      : "F:/gopro",
    "transcode"       : True,
    "keep_originals"  : False,
    "delete_from_cam" : True,
    "skip_existing"   : True,
    "auto_join_wifi"  : False,
}


def settings_dir() -> Path:
    """Per-user config directory, following the platform convention."""
    if sys.platform == "win32":
        base = os.environ.get("APPDATA")
        if base:
            return Path(base) / APP_NAME
        return Path.home() / "AppData" / "Roaming" / APP_NAME
    base = os.environ.get("XDG_CONFIG_HOME")
    root = Path(base) if base else Path.home() / ".config"
    return root / "gopro-unloader"


def settings_path() -> Path:
    return settings_dir() / "settings.json"


def load() -> dict:
    """
    Current preferences, defaults filled in for anything missing.

    Values whose type doesn't match the default are discarded - an
    edited-by-hand file shouldn't be able to put the UI in a broken state.
    """
    values = dict(DEFAULTS)
    path = settings_path()
    try:
        if not path.exists():
            return values
        stored = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(stored, dict):
            log.warning("Ignoring %s: expected a JSON object.", path)
            return values
    except Exception as e:
        log.warning("Could not read settings (%s); using defaults.", e)
        return values

    for key, default in DEFAULTS.items():
        if key not in stored:
            continue
        value = stored[key]
        if isinstance(default, bool):
            if isinstance(value, bool):
                values[key] = value
        elif isinstance(value, type(default)):
            values[key] = value
    return values


def save(values: dict) -> bool:
    """
    Write preferences out, keeping only recognised keys.

    Merges over what is already stored, so saving a subset updates just those
    keys instead of dropping the rest. Writes to a temporary file and replaces
    the real one, so an interrupted write can't leave a half-written settings
    file behind. Returns False (and warns) if it couldn't be saved - never
    raises.
    """
    keep = load()
    keep.update({k: values[k] for k in DEFAULTS if k in values})
    path = settings_path()
    tmp = path.with_suffix(".json.tmp")
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp.write_text(json.dumps(keep, indent=2) + "\n", encoding="utf-8")
        tmp.replace(path)
        return True
    except Exception as e:
        log.warning("Could not save settings to %s: %s", path, e)
        try:
            tmp.unlink(missing_ok=True)
        except OSError:
            pass
        return False
