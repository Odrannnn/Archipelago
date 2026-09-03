from __future__ import annotations

import os
import sys
import webbrowser
from pathlib import Path
from time import time

from PySide6.QtCore import QPoint, QProcess, QProcessEnvironment, QRect, QSize, Qt, QUrl
from PySide6.QtGui import QAction, QCloseEvent, QDesktopServices, QFont, QTextCursor
from PySide6.QtWidgets import (
    QApplication,
    QDialog,
    QDialogButtonBox,
    QFileDialog,
    QFormLayout,
    QFrame,
    QHBoxLayout,
    QLabel,
    QLayout,
    QLayoutItem,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QMainWindow,
    QMessageBox,
    QPlainTextEdit,
    QPushButton,
    QScrollArea,
    QSplitter,
    QStackedWidget,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from . import __version__
from .models import AppState, Room
from .services import Command, DesktopServices
from .storage import StateStore


APP_STYLE = """
QWidget { font-size: 14px; }
QMainWindow, QDialog, QWidget#page, QScrollArea { background: #10131a; }
QWidget { color: #ecf0f6; }
QMenuBar, QMenu, QStatusBar { background: #151a24; color: #dce3ee; }
QFrame#card { background: #191e29; border: 1px solid #293142; border-radius: 12px; }
QLabel#muted { color: #9ca8ba; }
QLabel#heading { font-size: 25px; font-weight: 650; }
QLabel#section { font-size: 17px; font-weight: 600; }
QPushButton { background: #283247; border: 1px solid #3b4862; border-radius: 7px; padding: 8px 13px; }
QPushButton:hover { background: #33415c; }
QPushButton#primary { background: #3267cf; border-color: #5683dc; }
QPushButton#danger { background: #572a32; border-color: #82404c; }
QLineEdit, QPlainTextEdit, QListWidget, QTableWidget { background: #121722; border: 1px solid #313b50; border-radius: 7px; padding: 6px; selection-background-color: #3267cf; }
QListWidget#navigation { background: #151a24; border: 0; border-right: 1px solid #293142; border-radius: 0; padding: 10px; }
QListWidget#navigation::item { padding: 11px; margin: 2px; border-radius: 7px; }
QListWidget#navigation::item:selected { background: #2b3850; }
QHeaderView::section { background: #202736; color: #dce3ee; padding: 7px; border: 0; }
QScrollBar:vertical { background: transparent; width: 11px; }
QScrollBar::handle:vertical { background: #3b465b; border-radius: 5px; min-height: 25px; }
"""


class FlowLayout(QLayout):
    """A small wrapping layout for action bars at narrow window widths."""

    def __init__(self, parent: QWidget | None = None, spacing: int = 7) -> None:
        super().__init__(parent)
        self._items: list[QLayoutItem] = []
        self.setContentsMargins(0, 0, 0, 0)
        self.setSpacing(spacing)

    def addItem(self, item: QLayoutItem) -> None:
        self._items.append(item)

    def count(self) -> int:
        return len(self._items)

    def itemAt(self, index: int) -> QLayoutItem | None:
        return self._items[index] if 0 <= index < len(self._items) else None

    def takeAt(self, index: int) -> QLayoutItem | None:
        return self._items.pop(index) if 0 <= index < len(self._items) else None

    def expandingDirections(self) -> Qt.Orientations:
        return Qt.Orientations()

    def hasHeightForWidth(self) -> bool:
        return True

    def heightForWidth(self, width: int) -> int:
        return self._arrange(QRect(0, 0, width, 0), test_only=True)

    def setGeometry(self, rectangle: QRect) -> None:
        super().setGeometry(rectangle)
        self._arrange(rectangle, test_only=False)

    def sizeHint(self) -> QSize:
        return self.minimumSize()

    def minimumSize(self) -> QSize:
        size = QSize()
        for item in self._items:
            size = size.expandedTo(item.minimumSize())
        margins = self.contentsMargins()
        return size + QSize(margins.left() + margins.right(), margins.top() + margins.bottom())

    def _arrange(self, rectangle: QRect, test_only: bool) -> int:
        margins = self.contentsMargins()
        available = rectangle.adjusted(margins.left(), margins.top(), -margins.right(), -margins.bottom())
        x, y, row_height = available.x(), available.y(), 0
        for item in self._items:
            hint = item.sizeHint()
            next_x = x + hint.width() + self.spacing()
            if next_x - self.spacing() > available.right() + 1 and row_height > 0:
                x = available.x()
                y += row_height + self.spacing()
                next_x = x + hint.width() + self.spacing()
                row_height = 0
            if not test_only:
                item.setGeometry(QRect(QPoint(x, y), hint))
            x = next_x
            row_height = max(row_height, hint.height())
        return y + row_height - rectangle.y() + margins.bottom()


def action_bar(*widgets: QWidget) -> QWidget:
    result = QWidget()
    layout = FlowLayout(result)
    for widget in widgets:
        layout.addWidget(widget)
    return result


def button(text: str, callback, name: str = "") -> QPushButton:
    result = QPushButton(text)
    if name:
        result.setObjectName(name)
    result.clicked.connect(callback)
    return result


def card(title: str, body: QWidget) -> QFrame:
    result = QFrame()
    result.setObjectName("card")
    layout = QVBoxLayout(result)
    layout.setContentsMargins(18, 16, 18, 16)
    heading = QLabel(title)
    heading.setObjectName("section")
    layout.addWidget(heading)
    layout.addWidget(body)
    return result


def page(title: str, subtitle: str) -> tuple[QWidget, QVBoxLayout]:
    content = QWidget()
    content.setObjectName("page")
    body = QVBoxLayout(content)
    body.setContentsMargins(24, 22, 24, 24)
    body.setSpacing(14)
    heading = QLabel(title)
    heading.setObjectName("heading")
    body.addWidget(heading)
    muted = QLabel(subtitle)
    muted.setObjectName("muted")
    muted.setWordWrap(True)
    body.addWidget(muted)
    return content, body


class RoomDialog(QDialog):
    def __init__(self, parent: QWidget, room: Room | None = None) -> None:
        super().__init__(parent)
        self.setWindowTitle("Room details")
        self.setMinimumWidth(470)
        source = room or Room()
        self.name = QLineEdit(source.name)
        self.game = QLineEdit(source.game)
        self.server = QLineEdit(source.server)
        self.slot = QLineEdit(source.slot)
        self.password = QLineEdit(source.password)
        self.password.setEchoMode(QLineEdit.EchoMode.Password)
        self.patch = QLineEdit(source.patch_path)
        choose = button("Browse…", self._choose_patch)
        patch_row = QWidget()
        patch_layout = QHBoxLayout(patch_row)
        patch_layout.setContentsMargins(0, 0, 0, 0)
        patch_layout.addWidget(self.patch, 1)
        patch_layout.addWidget(choose)
        form = QFormLayout(self)
        form.addRow("Name", self.name)
        form.addRow("Game", self.game)
        form.addRow("Server", self.server)
        form.addRow("Player / slot", self.slot)
        form.addRow("Password", self.password)
        form.addRow("Player patch", patch_row)
        actions = QDialogButtonBox(QDialogButtonBox.StandardButton.Save | QDialogButtonBox.StandardButton.Cancel)
        actions.accepted.connect(self.accept)
        actions.rejected.connect(self.reject)
        form.addRow(actions)

    def _choose_patch(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Choose player patch")
        if path:
            self.patch.setText(path)

    def apply(self, room: Room) -> None:
        room.name = self.name.text().strip() or "Room"
        room.game = self.game.text().strip()
        room.server = self.server.text().strip()
        room.slot = self.slot.text().strip()
        room.password = self.password.text()
        room.patch_path = self.patch.text().strip()
        room.updated_at = time()


class SettingsPage(QWidget):
    def __init__(self, window: "MainWindow") -> None:
        super().__init__()
        self.window = window
        content, layout = page("Settings", "Configure desktop applications and runtime behavior.")
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.addWidget(content)
        form_widget = QWidget()
        form = QFormLayout(form_widget)
        self.python = self._path_row(form, "Python", "python_executable", "Executables (*.exe);;All files (*)")
        self.retroarch = self._path_row(form, "RetroArch", "retroarch_executable")
        self.dolphin = self._path_row(form, "Dolphin", "dolphin_executable")
        self.poptracker = self._path_row(form, "PopTracker", "poptracker_executable")
        layout.addWidget(card("Applications", form_widget))
        layout.addWidget(button("Save settings", self.save, "primary"), 0, Qt.AlignmentFlag.AlignLeft)
        layout.addStretch()

    def _path_row(self, form: QFormLayout, label: str, attribute: str, file_filter: str = "All files (*)") -> QLineEdit:
        edit = QLineEdit(getattr(self.window.state.settings, attribute))
        row = QWidget()
        layout = QHBoxLayout(row)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(edit, 1)
        def choose() -> None:
            path, _ = QFileDialog.getOpenFileName(self, f"Choose {label}", filter=file_filter)
            if path:
                edit.setText(path)
        layout.addWidget(button("Browse…", choose))
        form.addRow(label, row)
        return edit

    def save(self) -> None:
        settings = self.window.state.settings
        settings.python_executable = self.python.text().strip()
        settings.retroarch_executable = self.retroarch.text().strip()
        settings.dolphin_executable = self.dolphin.text().strip()
        settings.poptracker_executable = self.poptracker.text().strip()
        self.window.save_state()
        self.window.statusBar().showMessage("Settings saved", 3000)


class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.store = StateStore()
        self.state: AppState = self.store.load()
        self.services = DesktopServices(self.store)
        self.processes: list[QProcess] = []
        self.client_process: QProcess | None = None
        self.generator_yamls: list[Path] = []
        self.setWindowTitle(f"Archipelago Companion {__version__}")
        self.resize(1180, 760)
        self.setMinimumSize(720, 520)
        self._build_menu()
        self._build_shell()
        self.refresh_rooms()
        self.refresh_home()

    def _build_menu(self) -> None:
        file_menu = self.menuBar().addMenu("File")
        backup = QAction("Create backup…", self)
        backup.triggered.connect(self.create_backup)
        file_menu.addAction(backup)
        restore = QAction("Restore backup…", self)
        restore.triggered.connect(self.restore_backup)
        file_menu.addAction(restore)
        file_menu.addSeparator()
        quit_action = QAction("Quit", self)
        quit_action.triggered.connect(self.close)
        file_menu.addAction(quit_action)
        help_menu = self.menuBar().addMenu("Help")
        release = QAction("Check for updates", self)
        release.triggered.connect(self.check_updates)
        help_menu.addAction(release)
        data_action = QAction("Open data folder", self)
        data_action.triggered.connect(lambda: QDesktopServices.openUrl(QUrl.fromLocalFile(str(self.store.root))))
        help_menu.addAction(data_action)

    def _build_shell(self) -> None:
        splitter = QSplitter()
        self.navigation = QListWidget()
        self.navigation.setObjectName("navigation")
        self.navigation.setMinimumWidth(165)
        self.navigation.setMaximumWidth(230)
        self.stack = QStackedWidget()
        pages = [
            ("Overview", self._home_page()),
            ("Rooms", self._rooms_page()),
            ("Generate", self._generator_page()),
            ("APWorlds", self._worlds_page()),
            ("Console", self._console_page()),
            ("Settings", SettingsPage(self)),
        ]
        for title, widget in pages:
            self.navigation.addItem(QListWidgetItem(title))
            scroll = QScrollArea()
            scroll.setWidgetResizable(True)
            scroll.setFrameShape(QFrame.Shape.NoFrame)
            scroll.setWidget(widget)
            self.stack.addWidget(scroll)
        self.navigation.currentRowChanged.connect(self.stack.setCurrentIndex)
        self.navigation.setCurrentRow(0)
        splitter.addWidget(self.navigation)
        splitter.addWidget(self.stack)
        splitter.setStretchFactor(1, 1)
        self.setCentralWidget(splitter)
        self.statusBar().showMessage(f"Data: {self.store.root}")

    def _home_page(self) -> QWidget:
        content, layout = page("Archipelago Companion", "Generate, organize, patch, launch, and monitor multiworld sessions.")
        self.home_room_title = QLabel("No active room")
        self.home_room_title.setObjectName("section")
        self.home_room_details = QLabel("Add a room to begin.")
        self.home_room_details.setObjectName("muted")
        self.home_room_details.setWordWrap(True)
        room_body = QWidget()
        room_layout = QVBoxLayout(room_body)
        room_layout.setContentsMargins(0, 0, 0, 0)
        room_layout.addWidget(self.home_room_title)
        room_layout.addWidget(self.home_room_details)
        room_layout.addWidget(action_bar(
            button("Start game client", self.connect_active, "primary"),
            button("Connect console", self.connect_console),
            button("Open player patch", self.patch_active),
            button("Open PopTracker", self.open_poptracker),
            button("Open RetroArch", lambda: self.open_configured_app("RetroArch", "retroarch_executable")),
            button("Open Dolphin", lambda: self.open_configured_app("Dolphin", "dolphin_executable")),
        ))
        layout.addWidget(card("Active room", room_body))
        quick = QWidget()
        quick_layout = FlowLayout(quick)
        quick_layout.addWidget(button("Add room", self.add_room))
        quick_layout.addWidget(button("Add player YAML", self.add_yaml))
        quick_layout.addWidget(button("Install APWorld", self.install_world))
        layout.addWidget(card("Quick actions", quick))
        layout.addStretch()
        return content

    def _rooms_page(self) -> QWidget:
        content, layout = page("Rooms", "Rooms are kept locally and sorted by most recent use.")
        self.room_table = QTableWidget(0, 4)
        self.room_table.setHorizontalHeaderLabels(("Name", "Game", "Server", "Player"))
        self.room_table.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
        self.room_table.setSelectionMode(QTableWidget.SelectionMode.SingleSelection)
        self.room_table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        self.room_table.horizontalHeader().setStretchLastSection(True)
        self.room_table.doubleClicked.connect(self.edit_room)
        layout.addWidget(self.room_table, 1)
        layout.addWidget(action_bar(
            button("Add", self.add_room, "primary"), button("Edit", self.edit_room),
            button("Make active", self.activate_room), button("Remove", self.remove_room, "danger"),
        ))
        return content

    def _generator_page(self) -> QWidget:
        content, layout = page("Generate seed", "Runs the repository's upstream generator in a separate Python process.")
        self.yaml_list = QListWidget()
        self.yaml_list.setMinimumHeight(150)
        yaml_body = QWidget()
        yaml_layout = QVBoxLayout(yaml_body)
        yaml_layout.setContentsMargins(0, 0, 0, 0)
        yaml_layout.addWidget(self.yaml_list)
        yaml_layout.addWidget(action_bar(
            button("Add YAML…", self.add_yaml), button("Remove selected", self.remove_yaml),
        ))
        layout.addWidget(card("Players", yaml_body))
        options = QWidget()
        options_form = QFormLayout(options)
        self.seed_edit = QLineEdit()
        self.seed_edit.setPlaceholderText("Random")
        options_form.addRow("Seed number", self.seed_edit)
        self.generate_button = button("Generate", self.generate, "primary")
        options_form.addRow("", self.generate_button)
        layout.addWidget(card("Generation", options))
        layout.addStretch()
        return content

    def _worlds_page(self) -> QWidget:
        content, layout = page("APWorlds", "Install custom worlds through Archipelago's standard desktop installer.")
        self.world_list = QListWidget()
        layout.addWidget(self.world_list, 1)
        layout.addWidget(action_bar(
            button("Install .apworld…", self.install_world, "primary"),
            button("Refresh", self.refresh_worlds),
        ))
        self.refresh_worlds()
        return content

    def _console_page(self) -> QWidget:
        content, layout = page("Client console", "Output and commands for the client started by this window.")
        self.console = QPlainTextEdit()
        self.console.setReadOnly(True)
        font = QFont("monospace")
        font.setStyleHint(QFont.StyleHint.Monospace)
        self.console.setFont(font)
        layout.addWidget(self.console, 1)
        layout.addWidget(action_bar(
            button("Connect active room", self.connect_console, "primary"),
            button("Disconnect", self.disconnect_console),
        ))
        row = QHBoxLayout()
        self.command_edit = QLineEdit()
        self.command_edit.setPlaceholderText("Command or chat message")
        self.command_edit.returnPressed.connect(self.send_console)
        row.addWidget(self.command_edit, 1)
        row.addWidget(button("Send", self.send_console, "primary"))
        row.addWidget(button("Clear", self.console.clear))
        layout.addLayout(row)
        return content

    def selected_room(self) -> Room | None:
        row = self.room_table.currentRow()
        if row < 0:
            return None
        room_id = self.room_table.item(row, 0).data(Qt.ItemDataRole.UserRole)
        return next((room for room in self.state.rooms if room.id == room_id), None)

    def add_room(self) -> None:
        dialog = RoomDialog(self)
        if dialog.exec() != QDialog.DialogCode.Accepted:
            return
        room = Room()
        dialog.apply(room)
        self.state.rooms.insert(0, room)
        self.state.active_room_id = room.id
        self.save_state()
        self.refresh_rooms()
        self.refresh_home()

    def edit_room(self) -> None:
        room = self.selected_room() or self.state.active_room
        if not room:
            return
        dialog = RoomDialog(self, room)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            dialog.apply(room)
            self.save_state()
            self.refresh_rooms()
            self.refresh_home()

    def activate_room(self) -> None:
        room = self.selected_room()
        if room:
            room.updated_at = time()
            self.state.active_room_id = room.id
            self.save_state()
            self.refresh_rooms()
            self.refresh_home()

    def remove_room(self) -> None:
        room = self.selected_room()
        if not room or QMessageBox.question(self, "Remove room", f"Remove {room.name} from this device?") != QMessageBox.StandardButton.Yes:
            return
        self.state.rooms = [candidate for candidate in self.state.rooms if candidate.id != room.id]
        if self.state.active_room_id == room.id:
            self.state.active_room_id = self.state.rooms[0].id if self.state.rooms else ""
        self.save_state()
        self.refresh_rooms()
        self.refresh_home()

    def refresh_rooms(self) -> None:
        self.state.rooms.sort(key=lambda room: (-room.updated_at, room.id))
        self.room_table.setRowCount(len(self.state.rooms))
        for row, room in enumerate(self.state.rooms):
            values = (room.name, room.game, room.server, room.slot)
            for column, value in enumerate(values):
                item = QTableWidgetItem(value)
                if column == 0:
                    item.setData(Qt.ItemDataRole.UserRole, room.id)
                    if room.id == self.state.active_room_id:
                        item.setText("● " + value)
                self.room_table.setItem(row, column, item)
        self.room_table.resizeColumnsToContents()

    def refresh_home(self) -> None:
        room = self.state.active_room
        if room:
            self.home_room_title.setText(room.name)
            self.home_room_details.setText(f"{room.game or 'Unspecified game'} · {room.slot or 'No player'} · {room.server or 'No server'}")
        else:
            self.home_room_title.setText("No active room")
            self.home_room_details.setText("Add a room to begin.")

    def add_yaml(self) -> None:
        paths, _ = QFileDialog.getOpenFileNames(self, "Add player YAML", filter="YAML files (*.yaml *.yml)")
        for path in paths:
            imported = self.store.import_yaml(Path(path))
            if imported not in self.generator_yamls:
                self.generator_yamls.append(imported)
                self.yaml_list.addItem(str(imported))
        if paths:
            self.navigation.setCurrentRow(2)

    def remove_yaml(self) -> None:
        row = self.yaml_list.currentRow()
        if row >= 0:
            self.generator_yamls.pop(row)
            self.yaml_list.takeItem(row)

    def generate(self) -> None:
        try:
            command = self.services.generation_command(self.generator_yamls, self.seed_edit.text(), self.state.settings)
        except Exception as error:
            self.show_error(error)
            return
        self.generate_button.setEnabled(False)
        self.navigation.setCurrentRow(4)
        self.run_process(command, "Generator", on_finished=lambda _: self.generate_button.setEnabled(True))

    def install_world(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Install APWorld", filter="Archipelago worlds (*.apworld)")
        if not path:
            return
        try:
            imported = self.store.import_world(Path(path))
            command = self.services.install_world_command(imported, self.state.settings)
        except Exception as error:
            self.show_error(error)
            return
        self.run_process(command, "APWorld installer")
        self.refresh_worlds()

    def refresh_worlds(self) -> None:
        if not hasattr(self, "world_list"):
            return
        self.world_list.clear()
        for source in sorted(self.store.world_dir.glob("*.apworld"), key=lambda path: path.stat().st_mtime, reverse=True):
            self.world_list.addItem(source.name)

    def connect_active(self) -> None:
        room = self.state.active_room
        if not room:
            self.show_error(ValueError("Select a room first"))
            return
        try:
            command = self.services.client_command(room, self.state.settings)
        except Exception as error:
            self.show_error(error)
            return
        self.run_process(command, f"{room.game or 'Archipelago'} client launcher")

    def connect_console(self) -> None:
        room = self.state.active_room
        if not room:
            self.show_error(ValueError("Select a room first"))
            return
        if self.client_process and self.client_process.state() != QProcess.ProcessState.NotRunning:
            self.navigation.setCurrentRow(4)
            return
        try:
            command = self.services.text_client_command(room, self.state.settings)
        except Exception as error:
            self.show_error(error)
            return
        self.navigation.setCurrentRow(4)
        self.client_process = self.run_process(command, "Text client")

    def disconnect_console(self) -> None:
        if self.client_process and self.client_process.state() != QProcess.ProcessState.NotRunning:
            self.client_process.write(b"/exit\n")

    def patch_active(self) -> None:
        room = self.state.active_room
        if not room or not room.patch_path:
            self.show_error(ValueError("Choose a player patch in the active room first"))
            return
        try:
            command = self.services.patch_command(Path(room.patch_path), self.state.settings)
        except Exception as error:
            self.show_error(error)
            return
        self.run_process(command, "Patch launcher")

    def open_poptracker(self) -> None:
        room = self.state.active_room
        if not room:
            self.show_error(ValueError("Select a room first"))
            return
        try:
            command = self.services.poptracker_command(room, self.state.settings)
        except Exception as error:
            self.show_error(error)
            return
        self.run_process(command, "PopTracker")

    def open_configured_app(self, label: str, setting: str) -> None:
        try:
            command = self.services.executable_command(getattr(self.state.settings, setting))
        except Exception as error:
            self.show_error(error)
            return
        self.run_process(command, label)

    def run_process(self, command: Command, label: str, on_finished=None) -> QProcess:
        process = QProcess(self)
        process.setProcessChannelMode(QProcess.ProcessChannelMode.MergedChannels)
        process.setWorkingDirectory(command.working_directory)
        environment = QProcessEnvironment.systemEnvironment()
        environment.insert("PYTHONUNBUFFERED", "1")
        process.setProcessEnvironment(environment)
        process.readyReadStandardOutput.connect(lambda: self._read_process(process, label))
        process.errorOccurred.connect(lambda error: self.console.appendPlainText(f"[{label}] process error: {error.name}"))
        def finished(code: int, _status) -> None:
            self._read_process(process, label)
            self.console.appendPlainText(f"[{label}] exited with code {code}")
            if on_finished:
                on_finished(code)
            if process in self.processes:
                self.processes.remove(process)
            if self.client_process is process:
                self.client_process = None
        process.finished.connect(finished)
        self.processes.append(process)
        self.console.appendPlainText(f"[{label}] starting…")
        process.start(command.program, command.arguments)
        return process

    def _read_process(self, process: QProcess, label: str) -> None:
        data = bytes(process.readAllStandardOutput()).decode(errors="replace")
        if data:
            self.console.moveCursor(QTextCursor.MoveOperation.End)
            self.console.insertPlainText(data)
            self.console.ensureCursorVisible()

    def send_console(self) -> None:
        command = self.command_edit.text()
        if not command:
            return
        process = self.client_process
        if not process or process.state() != QProcess.ProcessState.Running:
            self.show_error(RuntimeError("No client started by the companion is currently running"))
            return
        process.write((command + "\n").encode())
        self.console.appendPlainText(f"> {command}")
        self.command_edit.clear()

    def save_state(self) -> None:
        self.store.save(self.state)

    def create_backup(self) -> None:
        path, _ = QFileDialog.getSaveFileName(self, "Create backup", "Archipelago-Companion-Backup.zip", "ZIP archives (*.zip)")
        if path:
            try:
                self.save_state()
                self.store.create_backup(Path(path))
                self.statusBar().showMessage("Backup created", 4000)
            except Exception as error:
                self.show_error(error)

    def restore_backup(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Restore backup", filter="ZIP archives (*.zip)")
        if not path:
            return
        if QMessageBox.question(self, "Restore backup", "Replace current desktop companion data with this backup?") != QMessageBox.StandardButton.Yes:
            return
        try:
            self.store.restore_backup(Path(path))
            self.state = self.store.load()
            self.refresh_rooms()
            self.refresh_home()
            self.statusBar().showMessage("Backup restored", 4000)
        except Exception as error:
            self.show_error(error)

    def check_updates(self) -> None:
        try:
            tag, url = self.services.latest_release()
        except Exception as error:
            self.show_error(error)
            return
        if QMessageBox.question(self, "Latest release", f"Latest published release: {tag}\n\nOpen it in your browser?") == QMessageBox.StandardButton.Yes:
            webbrowser.open(url)

    def show_error(self, error: Exception) -> None:
        QMessageBox.critical(self, "Archipelago Companion", str(error))

    def closeEvent(self, event: QCloseEvent) -> None:
        running = [process for process in self.processes if process.state() != QProcess.ProcessState.NotRunning]
        if running and QMessageBox.question(self, "Quit", "Stop running Companion processes and quit?") != QMessageBox.StandardButton.Yes:
            event.ignore()
            return
        for process in running:
            process.terminate()
        self.save_state()
        event.accept()


def main() -> int:
    os.environ.setdefault("QT_ENABLE_HIGHDPI_SCALING", "1")
    application = QApplication(sys.argv)
    application.setApplicationName("Archipelago Companion")
    application.setOrganizationName("Odrannnn")
    application.setStyle("Fusion")
    application.setStyleSheet(APP_STYLE)
    window = MainWindow()
    window.show()
    return application.exec()


if __name__ == "__main__":
    raise SystemExit(main())
