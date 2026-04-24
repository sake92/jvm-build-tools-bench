TOOL_COLORS = {
    "maven": "#1f77b4",
    "mill": "#666666",
    "deder": "#2ca02c",
    "bleep": "#ff6b35",
    "sbt": "#01191F",
}


def get_tool_color(tool: str) -> str:
    try:
        return TOOL_COLORS[tool]
    except KeyError as exc:
        raise ValueError(f"Unknown build tool '{tool}' has no configured chart color") from exc
