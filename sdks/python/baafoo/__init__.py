"""Baafoo Thin SDK - Python implementation.

A lightweight SDK for reporting outbound requests to Baafoo Server.
Supports register, heartbeat, poll rules, and report recordings.
"""

from .client import Client, Options
from .models import RecordingEntry, Rule, MatchCondition, ResponseEntry

__version__ = "1.0.0"
__all__ = ["Client", "Options", "RecordingEntry", "Rule", "MatchCondition", "ResponseEntry"]
