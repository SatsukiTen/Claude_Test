"""Incident Investigation Pipeline: orchestrates the three agents in sequence."""
import anthropic

from .agents import run_correlation_analyzer, run_data_collector, run_report_generator
from .models import CollectedData, CorrelationResult, IncidentInput, IncidentReport


class IncidentInvestigationPipeline:
    """
    Simple linear pipeline:
        DataCollector → CorrelationAnalyzer → ReportGenerator

    Each agent's output becomes the next agent's input.
    """

    def __init__(self, api_key: str | None = None) -> None:
        # api_key=None lets the SDK pick up ANTHROPIC_API_KEY from the environment
        self.client = anthropic.Anthropic(api_key=api_key)

    def run(self, incident: IncidentInput) -> IncidentReport:
        """Execute the full pipeline and return the final incident report."""
        print(f"\n{'='*60}")
        print(f"Incident Investigation Pipeline")
        print(f"{'='*60}")
        print(f"Incident : {incident.description}")
        print(f"Repo     : {incident.repo}")
        print(f"Window   : {incident.window_minutes} minutes")
        print(f"{'='*60}\n")

        # Stage 1
        collected: CollectedData = run_data_collector(self.client, incident)
        print(f"  -> Collected: {len(collected.releases)} releases, "
              f"{len(collected.commits)} commits, "
              f"{len(collected.issues)} issues, "
              f"{len(collected.logs)} logs, "
              f"{len(collected.metrics)} metric points\n")

        # Stage 2
        correlation: CorrelationResult = run_correlation_analyzer(self.client, incident, collected)
        print(f"  -> Correlation: {len(correlation.suspects)} suspects, "
              f"confidence={correlation.confidence}\n")

        # Stage 3
        report: IncidentReport = run_report_generator(self.client, incident, correlation)
        print(f"  -> Report: {report.title}\n")

        return report
