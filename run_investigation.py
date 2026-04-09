#!/usr/bin/env python3
"""
CLI entry point for the Incident Investigation Pipeline.

Usage:
    python run_investigation.py "High error rate on /api/orders since 14:00"
    python run_investigation.py "DB connection timeouts" --repo owner/repo --window 90
    python run_investigation.py --help

Requires:
    ANTHROPIC_API_KEY environment variable to be set.
"""
import argparse
import sys
from pathlib import Path

# Allow running from the repo root without installing the package
sys.path.insert(0, str(Path(__file__).parent / "src"))

from incident_investigation.models import IncidentInput
from incident_investigation.pipeline import IncidentInvestigationPipeline


def main() -> None:
    parser = argparse.ArgumentParser(
        description="AI-powered incident investigation pipeline",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("description", help="Brief description of the incident")
    parser.add_argument(
        "--repo",
        default="satsukiten/claude_test",
        help="GitHub repository in owner/repo format (default: satsukiten/claude_test)",
    )
    parser.add_argument(
        "--window",
        type=int,
        default=60,
        help="Lookback window in minutes (default: 60)",
    )
    parser.add_argument(
        "--output",
        help="Write the Markdown report to this file (default: print to stdout)",
    )
    args = parser.parse_args()

    incident = IncidentInput(
        description=args.description,
        repo=args.repo,
        window_minutes=args.window,
    )

    pipeline = IncidentInvestigationPipeline()
    report = pipeline.run(incident)

    # Output the report
    separator = "=" * 60
    output_lines = [
        separator,
        f"INCIDENT REPORT: {report.title}",
        separator,
        report.markdown,
        separator,
    ]
    output_text = "\n".join(output_lines)

    if args.output:
        Path(args.output).write_text(output_text, encoding="utf-8")
        print(f"Report saved to: {args.output}")
    else:
        print(output_text)


if __name__ == "__main__":
    main()
