TOOL_COLORS = {
    "maven": "#1f77b4",
    "mill": "#ff7f0e",
    "deder": "#2ca02c",
    "sbt": "#d62728",
}


def get_tool_color(tool: str) -> str:
    try:
        return TOOL_COLORS[tool]
    except KeyError as exc:
        raise ValueError(f"Unknown build tool '{tool}' has no configured chart color") from exc

