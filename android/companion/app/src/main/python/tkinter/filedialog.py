"""Headless file-dialog compatibility used only by desktop fallback paths."""


def askopenfilename(*_args, **_kwargs) -> str:
    return ""
