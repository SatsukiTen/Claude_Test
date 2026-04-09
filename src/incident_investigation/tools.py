"""Tool implementations used by the DataCollector agent."""
import json
import os
import random
from datetime import datetime, timedelta, timezone

import requests


# ---------------------------------------------------------------------------
# GitHub API helpers
# Reads GITHUB_TOKEN env var if available for higher rate limits (5000/hr vs 60/hr)
# Falls back to empty list with an error note on API failure
# ---------------------------------------------------------------------------

GITHUB_API = "https://api.github.com"


def _github_headers() -> dict:
    headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return headers


def _github_get(url: str, params: dict) -> list | dict:
    """GET from GitHub API; returns parsed JSON or raises on error."""
    resp = requests.get(url, headers=_github_headers(), params=params, timeout=10)
    if resp.status_code == 403:
        msg = resp.json().get("message", "Forbidden")
        raise RuntimeError(f"GitHub API error (403): {msg}. Set GITHUB_TOKEN env var.")
    resp.raise_for_status()
    return resp.json()


def fetch_github_releases(repo: str, limit: int = 5) -> list[dict]:
    """Return the most recent releases for *repo* (owner/name)."""
    try:
        releases = _github_get(
            f"{GITHUB_API}/repos/{repo}/releases", {"per_page": limit}
        )
        return [
            {
                "tag": r["tag_name"],
                "name": r["name"],
                "published_at": r["published_at"],
                "url": r["html_url"],
                "body": (r.get("body") or "")[:300],
            }
            for r in releases
        ]
    except Exception as exc:
        return [{"error": str(exc), "note": "GitHub releases unavailable"}]


def fetch_github_issues(repo: str, limit: int = 10) -> list[dict]:
    """Return recent open/closed issues for *repo*."""
    try:
        issues = _github_get(
            f"{GITHUB_API}/repos/{repo}/issues",
            {"per_page": limit, "state": "all", "sort": "updated"},
        )
        return [
            {
                "number": i["number"],
                "title": i["title"],
                "state": i["state"],
                "created_at": i["created_at"],
                "updated_at": i["updated_at"],
                "labels": [lb["name"] for lb in i.get("labels", [])],
                "url": i["html_url"],
            }
            for i in issues
            if "pull_request" not in i
        ]
    except Exception as exc:
        return [{"error": str(exc), "note": "GitHub issues unavailable"}]


def fetch_github_commits(repo: str, limit: int = 10) -> list[dict]:
    """Return recent commits for *repo*."""
    try:
        commits = _github_get(
            f"{GITHUB_API}/repos/{repo}/commits", {"per_page": limit}
        )
        return [
            {
                "sha": c["sha"][:7],
                "message": c["commit"]["message"].split("\n")[0],
                "author": c["commit"]["author"]["name"],
                "date": c["commit"]["author"]["date"],
                "url": c["html_url"],
            }
            for c in commits
        ]
    except Exception as exc:
        return [{"error": str(exc), "note": "GitHub commits unavailable"}]


# ---------------------------------------------------------------------------
# Mock log / metrics generators
# ---------------------------------------------------------------------------

_LOG_TEMPLATES = [
    ("INFO", "Request processed in {ms}ms [endpoint={ep}]"),
    ("WARN", "Response time degraded: {ms}ms [endpoint={ep}]"),
    ("ERROR", "Unhandled exception in {ep}: {err}"),
    ("INFO", "Cache hit ratio: {ratio}% [cache=redis]"),
    ("ERROR", "DB connection timeout after {ms}ms"),
    ("WARN", "Memory usage at {pct}% (threshold=80%)"),
    ("INFO", "Health check OK [service={svc}]"),
    ("ERROR", "503 upstream unavailable [service={svc}]"),
]

_ENDPOINTS = ["/api/users", "/api/orders", "/api/products", "/api/auth", "/api/search"]
_SERVICES = ["payment-service", "auth-service", "inventory-service", "notification-service"]
_ERRORS = ["NullPointerException", "TimeoutError", "ConnectionRefusedError", "OutOfMemoryError"]


def generate_mock_logs(window_minutes: int = 60, count: int = 40) -> list[dict]:
    """Generate realistic-looking application log entries."""
    now = datetime.now(timezone.utc)
    logs = []
    # Inject a burst of errors around the midpoint to simulate an incident
    incident_start = now - timedelta(minutes=window_minutes // 2)

    rng = random.Random(42)
    for i in range(count):
        offset = timedelta(minutes=rng.uniform(0, window_minutes))
        ts = now - offset
        level, tmpl = rng.choice(_LOG_TEMPLATES)

        # Increase error rate near incident_start
        near_incident = abs((ts - incident_start).total_seconds()) < 600
        if near_incident and level == "INFO":
            level, tmpl = rng.choice([t for t in _LOG_TEMPLATES if t[0] != "INFO"])

        msg = tmpl.format(
            ms=rng.randint(50, 8000 if near_incident else 500),
            ep=rng.choice(_ENDPOINTS),
            err=rng.choice(_ERRORS),
            ratio=rng.randint(20 if near_incident else 70, 95),
            pct=rng.randint(75 if near_incident else 30, 99),
            svc=rng.choice(_SERVICES),
        )
        logs.append({"timestamp": ts.isoformat(), "level": level, "message": msg})

    return sorted(logs, key=lambda x: x["timestamp"])


def generate_mock_metrics(window_minutes: int = 60, points: int = 30) -> list[dict]:
    """Generate a time-series of key service metrics."""
    now = datetime.now(timezone.utc)
    incident_start = now - timedelta(minutes=window_minutes // 2)
    metrics = []
    rng = random.Random(99)

    for i in range(points):
        ts = now - timedelta(minutes=window_minutes * i / points)
        near_incident = abs((ts - incident_start).total_seconds()) < 600
        metrics.append({
            "timestamp": ts.isoformat(),
            "error_rate_pct": round(rng.uniform(30, 80) if near_incident else rng.uniform(0, 3), 2),
            "p99_latency_ms": rng.randint(3000, 9000) if near_incident else rng.randint(80, 400),
            "request_per_sec": rng.randint(5, 30) if near_incident else rng.randint(80, 200),
            "cpu_pct": rng.randint(70, 99) if near_incident else rng.randint(20, 50),
            "mem_pct": rng.randint(80, 99) if near_incident else rng.randint(30, 60),
        })

    return sorted(metrics, key=lambda x: x["timestamp"])


# ---------------------------------------------------------------------------
# Tool registry (used by agents.py)
# ---------------------------------------------------------------------------

TOOL_DEFINITIONS = [
    {
        "name": "fetch_github_releases",
        "description": (
            "Fetch the most recent releases from a GitHub repository. "
            "Returns tag name, publish date, and release notes."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "repo": {"type": "string", "description": "owner/repo format, e.g. satsukiten/claude_test"},
                "limit": {"type": "integer", "description": "Max number of releases to return (default 5)"},
            },
            "required": ["repo"],
        },
    },
    {
        "name": "fetch_github_issues",
        "description": (
            "Fetch recent issues (open and closed) from a GitHub repository. "
            "Useful for finding existing incident tickets."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "repo": {"type": "string", "description": "owner/repo format"},
                "limit": {"type": "integer", "description": "Max number of issues to return (default 10)"},
            },
            "required": ["repo"],
        },
    },
    {
        "name": "fetch_github_commits",
        "description": (
            "Fetch recent commits from a GitHub repository. "
            "Useful for identifying recent code changes that may have caused a regression."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "repo": {"type": "string", "description": "owner/repo format"},
                "limit": {"type": "integer", "description": "Max number of commits to return (default 10)"},
            },
            "required": ["repo"],
        },
    },
    {
        "name": "generate_mock_logs",
        "description": (
            "Generate realistic application log entries for the incident time window. "
            "Includes INFO, WARN, and ERROR level logs with timestamps."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "window_minutes": {
                    "type": "integer",
                    "description": "How many minutes of logs to generate (default 60)",
                },
                "count": {"type": "integer", "description": "Number of log entries (default 40)"},
            },
        },
    },
    {
        "name": "generate_mock_metrics",
        "description": (
            "Generate time-series metrics (error rate, latency, RPS, CPU, memory) "
            "for the incident time window."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "window_minutes": {
                    "type": "integer",
                    "description": "How many minutes of metrics to generate (default 60)",
                },
                "points": {"type": "integer", "description": "Number of data points (default 30)"},
            },
        },
    },
]

TOOL_EXECUTORS = {
    "fetch_github_releases": lambda **kw: fetch_github_releases(**kw),
    "fetch_github_issues": lambda **kw: fetch_github_issues(**kw),
    "fetch_github_commits": lambda **kw: fetch_github_commits(**kw),
    "generate_mock_logs": lambda **kw: generate_mock_logs(**kw),
    "generate_mock_metrics": lambda **kw: generate_mock_metrics(**kw),
}
