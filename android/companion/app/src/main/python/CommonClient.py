"""Headless CommonClient surface used while importing desktop APWorld clients."""

import argparse
import logging

from android_client_runtime import AndroidClientCommandProcessor, AndroidClientContext


logger = logging.getLogger("Client")
gui_enabled = False
ClientCommandProcessor = AndroidClientCommandProcessor
CommonContext = AndroidClientContext


def get_base_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--connect")
    parser.add_argument("--password")
    return parser


async def server_loop(_ctx) -> None:
    """Desktop clients define entry points against this symbol; Android owns networking."""
