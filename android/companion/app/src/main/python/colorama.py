"""No-op Android compatibility subset for APWorld desktop launcher modules."""


class _Codes:
    def __getattr__(self, _name: str) -> str:
        return ""


Fore = _Codes()
Back = _Codes()
Style = _Codes()


def init(*_args, **_kwargs) -> None:
    pass


def deinit() -> None:
    pass


def reinit() -> None:
    pass


def just_fix_windows_console() -> None:
    pass
