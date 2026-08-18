"""Upstream command-processing surface used by Archipelago clients.

The Android companion does not host an Archipelago server.  Keep this module
limited to the unchanged parser/decorator layer which upstream clients import
without requiring server state.
"""

import functools
import inspect
import shlex
import typing


_Return = typing.TypeVar("_Return")


def mark_raw(function: typing.Callable[[typing.Any], _Return]) -> typing.Callable[[typing.Any], _Return]:
    """Match the command decorator exported by the desktop MultiServer."""
    function.raw_text = True
    return function


class CommandMeta(type):
    def __new__(cls, name, bases, attrs):
        commands = attrs["commands"] = {}
        for base in bases:
            commands.update(base.commands)
        commands.update({command_name[5:]: method for command_name, method in attrs.items() if
                         command_name.startswith("_cmd_")})
        for command_name, method in commands.items():
            if inspect.iscoroutinefunction(method):
                def _wrapper(self, *args, _method=method, **kwargs):
                    from Utils import async_start
                    return async_start(_method(self, *args, **kwargs))
                functools.update_wrapper(_wrapper, method)
                commands[command_name] = _wrapper
        return super(CommandMeta, cls).__new__(cls, name, bases, attrs)


class CommandProcessor(metaclass=CommandMeta):
    """Desktop Archipelago command parser, without server-hosting state."""

    commands: typing.Dict[str, typing.Callable]
    client = None
    marker = "/"

    def output(self, text: str):
        print(text)

    def __call__(self, raw: str) -> typing.Optional[bool]:
        if not raw:
            return None
        try:
            try:
                command = shlex.split(raw, comments=False)
            except ValueError:
                command = raw.split()
            basecommand = command[0]
            if basecommand[0] == self.marker:
                method = self.commands.get(basecommand[1:].lower(), None)
                if not method:
                    self._error_unknown_command(basecommand[1:])
                elif getattr(method, "raw_text", False):
                    arg = raw.split(maxsplit=1)
                    if len(arg) > 1:
                        return method(self, arg[1])
                    return method(self)
                else:
                    return method(self, *command[1:])
            else:
                self.default(raw)
        except Exception as error:
            self._error_parsing_command(error)
        return None

    def get_help_text(self) -> str:
        result = ""
        for command, method in self.commands.items():
            spec = inspect.signature(method).parameters
            argtext = ""
            for argname, parameter in spec.items():
                if argname == "self":
                    continue
                if isinstance(parameter.default, str):
                    if not parameter.default:
                        argname = f"[{argname}]"
                    else:
                        argname += "=" + parameter.default
                argtext += argname + " "
            method_doc = inspect.getdoc(method) or "(missing help text)"
            doctext = "\n    ".join(method_doc.split("\n"))
            result += f"{self.marker}{command} {argtext}\n    {doctext}\n"
        return result

    def _cmd_help(self):
        """Returns the help listing"""
        self.output(self.get_help_text())

    def _cmd_license(self):
        """Returns the licensing information"""
        from Utils import local_path
        license_text = getattr(CommandProcessor, "license", None)
        if not license_text:
            with open(local_path("LICENSE"), encoding="utf-8") as license_file:
                license_text = license_file.read()
                CommandProcessor.license = license_text
        self.output(license_text)

    def default(self, raw: str):
        self.output("Echo: " + raw)

    def _error_unknown_command(self, raw: str):
        self.output(f"Could not find command {raw}. Known commands: {', '.join(self.commands)}")

    def _error_parsing_command(self, exception: Exception):
        import traceback
        self.output(traceback.format_exc())
