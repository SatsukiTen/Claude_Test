"""Data models for the incident investigation pipeline."""
from dataclasses import dataclass, field
from typing import Any


@dataclass
class IncidentInput:
    """User-provided incident description."""
    description: str
    repo: str = "satsukiten/claude_test"
    window_minutes: int = 60  # time window to look back


@dataclass
class CollectedData:
    """Raw data gathered by the DataCollector agent."""
    releases: list[dict[str, Any]] = field(default_factory=list)
    issues: list[dict[str, Any]] = field(default_factory=list)
    commits: list[dict[str, Any]] = field(default_factory=list)
    logs: list[dict[str, Any]] = field(default_factory=list)
    metrics: list[dict[str, Any]] = field(default_factory=list)
    summary: str = ""


@dataclass
class CorrelationResult:
    """Analysis output from the CorrelationAnalyzer agent."""
    timeline: list[dict[str, Any]] = field(default_factory=list)
    suspects: list[str] = field(default_factory=list)
    root_cause_hypothesis: str = ""
    confidence: str = "low"  # low / medium / high
    summary: str = ""


@dataclass
class IncidentReport:
    """Final report produced by the ReportGenerator agent."""
    title: str = ""
    severity: str = ""   # P1 / P2 / P3 / P4
    markdown: str = ""
