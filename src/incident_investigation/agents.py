"""Three-agent pipeline using Anthropic SDK with tool_use."""
import json
from typing import Any

import anthropic

from .models import CollectedData, CorrelationResult, IncidentInput, IncidentReport
from .tools import TOOL_DEFINITIONS, TOOL_EXECUTORS

MODEL = "claude-opus-4-6"


def _run_agent_loop(
    client: anthropic.Anthropic,
    system: str,
    user_message: str,
    tools: list[dict] | None = None,
) -> str:
    """Generic agentic loop: keeps calling the model until stop_reason == 'end_turn'."""
    messages: list[dict[str, Any]] = [{"role": "user", "content": user_message}]
    create_kwargs: dict[str, Any] = {
        "model": MODEL,
        "max_tokens": 4096,
        "system": system,
        "messages": messages,
    }
    if tools:
        create_kwargs["tools"] = tools

    while True:
        response = client.messages.create(**create_kwargs)

        if response.stop_reason == "end_turn":
            # Extract the final text block
            for block in response.content:
                if block.type == "text":
                    return block.text
            return ""

        # Handle tool_use blocks
        tool_results = []
        for block in response.content:
            if block.type == "tool_use":
                executor = TOOL_EXECUTORS.get(block.name)
                if executor is None:
                    result = {"error": f"Unknown tool: {block.name}"}
                else:
                    try:
                        result = executor(**block.input)
                    except Exception as exc:
                        result = {"error": str(exc)}
                tool_results.append(
                    {
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": json.dumps(result, ensure_ascii=False, default=str),
                    }
                )

        # Append assistant turn + tool results, then continue
        messages.append({"role": "assistant", "content": response.content})
        messages.append({"role": "user", "content": tool_results})
        create_kwargs["messages"] = messages


# ---------------------------------------------------------------------------
# Agent 1: DataCollector
# ---------------------------------------------------------------------------

_COLLECTOR_SYSTEM = """\
You are the DataCollector agent in an incident investigation pipeline.
Your job is to gather ALL relevant data about an ongoing incident using the available tools.

Guidelines:
- Always fetch GitHub releases, issues, AND commits for context.
- Always fetch mock logs and metrics to cover the incident time window.
- After collecting data, return a JSON object (no markdown fences) with keys:
  releases, issues, commits, logs, metrics, summary
  where "summary" is a 2-3 sentence plain-language overview of what you found.
"""


def run_data_collector(client: anthropic.Anthropic, incident: IncidentInput) -> CollectedData:
    """Agent 1: collect raw data from all sources."""
    print("[Agent 1/3] DataCollector: gathering data...")
    prompt = (
        f"Incident description: {incident.description}\n"
        f"Repository: {incident.repo}\n"
        f"Time window: last {incident.window_minutes} minutes\n\n"
        "Please collect all relevant data using the available tools, then return the result as JSON."
    )
    raw = _run_agent_loop(client, _COLLECTOR_SYSTEM, prompt, tools=TOOL_DEFINITIONS)

    # Parse the JSON output from the agent
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        # Fallback: try to extract JSON block if the model wrapped it
        import re
        match = re.search(r"\{.*\}", raw, re.DOTALL)
        data = json.loads(match.group()) if match else {}

    return CollectedData(
        releases=data.get("releases", []),
        issues=data.get("issues", []),
        commits=data.get("commits", []),
        logs=data.get("logs", []),
        metrics=data.get("metrics", []),
        summary=data.get("summary", raw[:500]),
    )


# ---------------------------------------------------------------------------
# Agent 2: CorrelationAnalyzer
# ---------------------------------------------------------------------------

_ANALYZER_SYSTEM = """\
You are the CorrelationAnalyzer agent in an incident investigation pipeline.
You receive raw data (releases, commits, issues, logs, metrics) and identify correlations.

Your analysis should:
1. Build a chronological timeline of notable events.
2. Identify suspicious changes or patterns (e.g., a deploy just before errors spiked).
3. Form a root-cause hypothesis with a confidence level (low/medium/high).

Return a JSON object (no markdown fences) with keys:
  timeline     - list of {time, event, source} dicts, sorted by time
  suspects     - list of strings describing suspicious changes/events
  root_cause_hypothesis - one paragraph
  confidence   - "low" | "medium" | "high"
  summary      - 3-4 sentence plain-language summary
"""


def run_correlation_analyzer(
    client: anthropic.Anthropic,
    incident: IncidentInput,
    collected: CollectedData,
) -> CorrelationResult:
    """Agent 2: correlate data and form a root-cause hypothesis."""
    print("[Agent 2/3] CorrelationAnalyzer: analyzing correlations...")
    prompt = (
        f"Incident: {incident.description}\n\n"
        f"=== Collected Data ===\n"
        f"Releases ({len(collected.releases)}):\n{json.dumps(collected.releases, indent=2, default=str)}\n\n"
        f"Commits ({len(collected.commits)}):\n{json.dumps(collected.commits, indent=2, default=str)}\n\n"
        f"Issues ({len(collected.issues)}):\n{json.dumps(collected.issues, indent=2, default=str)}\n\n"
        f"Metrics sample (first 10):\n{json.dumps(collected.metrics[:10], indent=2, default=str)}\n\n"
        f"Logs sample (last 20):\n{json.dumps(collected.logs[-20:], indent=2, default=str)}\n\n"
        "Analyze the data and return your findings as JSON."
    )
    # Analyzer does not need external tools — the model reasons over the provided data
    raw = _run_agent_loop(client, _ANALYZER_SYSTEM, prompt, tools=None)

    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        import re
        match = re.search(r"\{.*\}", raw, re.DOTALL)
        data = json.loads(match.group()) if match else {}

    return CorrelationResult(
        timeline=data.get("timeline", []),
        suspects=data.get("suspects", []),
        root_cause_hypothesis=data.get("root_cause_hypothesis", ""),
        confidence=data.get("confidence", "low"),
        summary=data.get("summary", raw[:500]),
    )


# ---------------------------------------------------------------------------
# Agent 3: ReportGenerator
# ---------------------------------------------------------------------------

_REPORTER_SYSTEM = """\
You are the ReportGenerator agent in an incident investigation pipeline.
You receive the correlation analysis and produce a professional incident report in Markdown.

The report must include:
- Title (with severity P1–P4)
- Executive Summary (2-3 sentences)
- Timeline of Events (table)
- Root Cause Analysis
- Impact Assessment
- Recommended Remediation Steps (numbered list)
- Prevention Measures

Return a JSON object (no markdown fences) with keys:
  title    - short title string including severity, e.g. "[P2] High error rate on /api/orders"
  severity - "P1" | "P2" | "P3" | "P4"
  markdown - the full Markdown report as a string
"""


def run_report_generator(
    client: anthropic.Anthropic,
    incident: IncidentInput,
    correlation: CorrelationResult,
) -> IncidentReport:
    """Agent 3: generate the final structured incident report."""
    print("[Agent 3/3] ReportGenerator: generating report...")
    prompt = (
        f"Incident: {incident.description}\n\n"
        f"=== Correlation Analysis ===\n"
        f"Timeline:\n{json.dumps(correlation.timeline, indent=2, default=str)}\n\n"
        f"Suspects: {json.dumps(correlation.suspects)}\n\n"
        f"Root Cause Hypothesis:\n{correlation.root_cause_hypothesis}\n\n"
        f"Confidence: {correlation.confidence}\n\n"
        f"Summary:\n{correlation.summary}\n\n"
        "Generate the full incident report as JSON."
    )
    raw = _run_agent_loop(client, _REPORTER_SYSTEM, prompt, tools=None)

    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        import re
        match = re.search(r"\{.*\}", raw, re.DOTALL)
        data = json.loads(match.group()) if match else {}

    return IncidentReport(
        title=data.get("title", "Incident Report"),
        severity=data.get("severity", "P3"),
        markdown=data.get("markdown", raw),
    )
