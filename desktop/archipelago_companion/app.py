from __future__ import annotations

import os
import sys
import threading
import webbrowser
from pathlib import Path
from time import time

import yaml
from PySide6.QtCore import QPoint, QProcess, QProcessEnvironment, QRect, QSize, Qt, QUrl, Signal
from PySide6.QtGui import QAction, QCloseEvent, QDesktopServices, QFont, QTextCursor
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QComboBox,
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
    QSizePolicy,
    QSplitter,
    QStackedWidget,
    QTableWidget,
    QTableWidgetItem,
    QToolButton,
    QVBoxLayout,
    QWidget,
)

from . import __version__
from .models import AppState, Room
from .player_options import (
    GameSpec,
    OptionSpec,
    default_values,
    game_catalog,
    game_schema,
    player_yaml,
    read_player_yaml,
    safe_player_filename,
)
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
QLineEdit, QPlainTextEdit, QListWidget, QTableWidget, QComboBox {
    background: #121722; border: 1px solid #313b50; border-radius: 7px; padding: 6px;
    selection-background-color: #3267cf;
}
QComboBox QAbstractItemView { background: #151a24; color: #ecf0f6; selection-background-color: #3267cf; }
QToolButton#group {
    background: #202736; border: 1px solid #313b50; border-radius: 7px; padding: 9px;
    font-weight: 600; text-align: left;
}
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


class OptionEditor(QWidget):
    def __init__(self, option: OptionSpec, value=None) -> None:
        super().__init__()
        self.option = option
        value = option.default if value is None else value
        self.structured_override = isinstance(value, dict) and option.kind not in {"dict", "custom"}
        self.setProperty("searchText", f"{option.label} {option.key} {option.description}".casefold())
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 5, 0, 7)
        layout.setSpacing(4)
        if option.kind == "toggle" and not self.structured_override:
            control = QCheckBox(option.label)
            self.control = control
        else:
            title = QLabel(option.label)
            title.setObjectName("section")
            layout.addWidget(title)
            if self.structured_override:
                control = QPlainTextEdit()
                control.setMaximumHeight(120)
                control.setPlaceholderText("Weighted YAML value")
            elif option.kind in {"choice", "text_choice"}:
                control = QComboBox()
                for choice in option.choices:
                    control.addItem(choice.label, choice.value)
                if option.kind == "text_choice":
                    control.setEditable(True)
            elif option.kind in {"list", "set", "dict", "custom"}:
                control = QPlainTextEdit()
                control.setMaximumHeight(120)
                control.setPlaceholderText("One value per line" if option.kind in {"list", "set"} else "YAML value")
            else:
                control = QLineEdit()
                if option.kind == "range":
                    limits = f"{option.minimum} to {option.maximum}"
                    specials = ", ".join(choice.value for choice in option.special_values)
                    control.setPlaceholderText(limits + (f"; also {specials}" if specials else ""))
            self.control = control
        layout.addWidget(self.control)
        if option.description:
            description = QLabel(option.description)
            description.setObjectName("muted")
            description.setWordWrap(True)
            layout.addWidget(description)
        self.set_value(value)

    def set_value(self, value) -> None:
        if isinstance(self.control, QCheckBox):
            self.control.setChecked(bool(value))
        elif isinstance(self.control, QComboBox):
            index = self.control.findData(str(value))
            if index >= 0:
                self.control.setCurrentIndex(index)
            elif self.control.isEditable():
                self.control.setEditText(str(value))
        elif isinstance(self.control, QPlainTextEdit):
            if self.option.kind in {"list", "set"} and isinstance(value, list | tuple | set | frozenset):
                text = "\n".join(str(item) for item in value)
            else:
                text = yaml.safe_dump(value, sort_keys=False, allow_unicode=True).replace("...\n", "").strip()
            self.control.setPlainText(text)
        else:
            self.control.setText(str(value))

    def value(self):
        if isinstance(self.control, QCheckBox):
            return self.control.isChecked()
        if isinstance(self.control, QComboBox):
            index = self.control.currentIndex()
            data = self.control.itemData(index)
            if not self.control.isEditable() or self.control.currentText() == self.control.itemText(index):
                return data
            return self.control.currentText()
        if isinstance(self.control, QPlainTextEdit):
            text = self.control.toPlainText().strip()
            if self.option.kind in {"list", "set"} and not self.structured_override:
                return [item.strip() for item in text.replace(",", "\n").splitlines() if item.strip()]
            if not text:
                return {} if self.option.kind == "dict" else ""
            try:
                return yaml.safe_load(text)
            except yaml.YAMLError as error:
                raise ValueError(f"{self.option.label}: invalid YAML value ({error})") from error
        text = self.control.text()
        if self.option.kind == "range" and text.lstrip("-").isdigit():
            return int(text)
        return text


class CollapsibleOptionGroup(QWidget):
    def __init__(self, title: str, collapsed: bool) -> None:
        super().__init__()
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        self.toggle = QToolButton()
        self.toggle.setObjectName("group")
        self.toggle.setText(title)
        self.toggle.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        self.toggle.setCheckable(True)
        self.toggle.setChecked(not collapsed)
        self.toggle.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextBesideIcon)
        self.body = QWidget()
        self.body_layout = QVBoxLayout(self.body)
        self.body_layout.setContentsMargins(12, 4, 6, 8)
        self.toggle.toggled.connect(self._set_open)
        layout.addWidget(self.toggle)
        layout.addWidget(self.body)
        self._set_open(not collapsed)

    def _set_open(self, opened: bool) -> None:
        self.toggle.setArrowType(Qt.ArrowType.DownArrow if opened else Qt.ArrowType.RightArrow)
        self.body.setVisible(opened)


class PlayerCreatorPage(QWidget):
    catalog_loaded = Signal(object, object)

    def __init__(self, window: "MainWindow") -> None:
        super().__init__()
        self.window = window
        self.schema: GameSpec | None = None
        self.editors: dict[str, OptionEditor] = {}
        self.groups: list[CollapsibleOptionGroup] = []
        self.preserved_values: dict[str, object] = {}
        self.preserved_extras: dict[str, object] = {}
        self.catalog_loading = False
        content, layout = page(
            "Player creator",
            "Create standard Archipelago player YAMLs visually. Games and options come directly from installed "
            "APWorlds.",
        )
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.addWidget(content)

        identity = QWidget()
        identity_form = QFormLayout(identity)
        self.name_edit = QLineEdit("Player1")
        self.name_edit.setMaxLength(16)
        self.game_combo = QComboBox()
        self.game_combo.setEditable(True)
        self.game_combo.setInsertPolicy(QComboBox.InsertPolicy.NoInsert)
        self.game_combo.completer().setCaseSensitivity(Qt.CaseSensitivity.CaseInsensitive)
        self.game_combo.activated.connect(lambda _index: self.load_game(self.game_combo.currentText()))
        identity_form.addRow("Player name", self.name_edit)
        identity_form.addRow("Game", self.game_combo)
        self.search_edit = QLineEdit()
        self.search_edit.setPlaceholderText("Search option names and descriptions")
        self.search_edit.textChanged.connect(self.filter_options)
        identity_form.addRow("Find option", self.search_edit)
        layout.addWidget(card("Player", identity))

        self.notice = QLabel()
        self.notice.setObjectName("muted")
        self.notice.setWordWrap(True)
        self.notice.hide()
        self.options_widget = QWidget()
        self.options_layout = QVBoxLayout(self.options_widget)
        self.options_layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(self.notice)
        layout.addWidget(self.options_widget)
        layout.addWidget(action_bar(
            button("Save and add to seed", self.save_and_add, "primary"),
            button("Preview YAML", self.preview_yaml),
            button("Reset defaults", self.reset_defaults),
            button("Open options page", self.open_options_page),
        ))

        library = QWidget()
        library_layout = QVBoxLayout(library)
        library_layout.setContentsMargins(0, 0, 0, 0)
        self.library_list = QListWidget()
        self.library_list.setMinimumHeight(125)
        self.library_list.doubleClicked.connect(self.load_saved)
        library_layout.addWidget(self.library_list)
        library_layout.addWidget(action_bar(
            button("Load selected", self.load_saved),
            button("Add selected to seed", self.add_saved),
            button("Import YAML…", self.import_yaml),
            button("Delete selected", self.delete_saved, "danger"),
        ))
        layout.addWidget(card("Saved players", library))
        layout.addStretch()
        self.catalog_loaded.connect(self._apply_catalog)
        self.reload_catalog()
        self.refresh_library()

    def reload_catalog(self) -> None:
        if self.catalog_loading:
            return
        self.catalog_loading = True
        self.game_combo.setEnabled(False)
        self.notice.setText("Loading installed APWorld games and options…")
        self.notice.show()
        selected = self.game_combo.currentText()

        def load() -> None:
            try:
                self.catalog_loaded.emit((selected, game_catalog()), None)
            except Exception as error:
                self.catalog_loaded.emit(None, error)

        threading.Thread(target=load, name="desktop-player-options", daemon=True).start()

    def _apply_catalog(self, result, error) -> None:
        self.catalog_loading = False
        self.game_combo.setEnabled(True)
        if error is not None:
            self.notice.setText(f"Could not load installed APWorld options: {error}")
            self.notice.show()
            return
        selected, catalog = result
        self.game_combo.blockSignals(True)
        self.game_combo.clear()
        for game, native, page_url in catalog:
            self.game_combo.addItem(game, (native, page_url))
        self.game_combo.blockSignals(False)
        index = self.game_combo.findText(selected)
        self.game_combo.setCurrentIndex(index if index >= 0 else (0 if self.game_combo.count() else -1))
        if self.game_combo.currentText():
            self.load_game(self.game_combo.currentText())

    def _clear_options(self) -> None:
        while self.options_layout.count():
            item = self.options_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()
        self.editors.clear()
        self.groups.clear()

    def load_game(self, game: str, values: dict | None = None, extras: dict | None = None) -> None:
        if not game:
            return
        try:
            self.schema = game_schema(game)
        except Exception as error:
            self.window.show_error(error)
            return
        self._clear_options()
        self.preserved_values = dict(values or {})
        self.preserved_extras = dict(extras or {})
        if not self.schema.native_options:
            message = f"{game} does not expose an upstream native options form."
            if self.schema.options_page:
                message += " Use its upstream options page to create the player file."
            self.notice.setText(message)
            self.notice.show()
            return
        self.notice.hide()
        for index, group_spec in enumerate(self.schema.groups):
            section = CollapsibleOptionGroup(group_spec.name, group_spec.start_collapsed or index > 0)
            for option in group_spec.options:
                editor = OptionEditor(option, self.preserved_values.pop(option.key, option.default))
                self.editors[option.key] = editor
                section.body_layout.addWidget(editor)
            self.groups.append(section)
            self.options_layout.addWidget(section)
        self.filter_options(self.search_edit.text())

    def filter_options(self, query: str) -> None:
        needle = query.strip().casefold()
        for section in self.groups:
            visible = False
            for index in range(section.body_layout.count()):
                editor = section.body_layout.itemAt(index).widget()
                matches = not needle or needle in str(editor.property("searchText"))
                editor.setVisible(matches)
                visible = visible or matches
            section.setVisible(visible)
            if needle and visible:
                section.toggle.setChecked(True)

    def values(self) -> dict:
        result = dict(self.preserved_values)
        for key, editor in self.editors.items():
            result[key] = editor.value()
        return result

    def yaml_text(self) -> str:
        if not self.schema or not self.schema.native_options:
            raise ValueError("This game does not expose an upstream native options form")
        return player_yaml(self.name_edit.text(), self.schema.game, self.values(), self.preserved_extras)

    def save_and_add(self) -> None:
        try:
            path = self.window.store.save_yaml(safe_player_filename(self.name_edit.text()), self.yaml_text())
            self.window.add_generator_yaml(path)
            self.refresh_library(path)
            self.window.statusBar().showMessage(f"Saved {path.name} and added it to the seed", 5000)
        except Exception as error:
            self.window.show_error(error)

    def preview_yaml(self) -> None:
        try:
            text = self.yaml_text()
        except Exception as error:
            self.window.show_error(error)
            return
        dialog = QDialog(self)
        dialog.setWindowTitle("Player YAML preview")
        dialog.resize(720, 620)
        layout = QVBoxLayout(dialog)
        editor = QPlainTextEdit(text)
        editor.setReadOnly(True)
        layout.addWidget(editor)
        actions = QDialogButtonBox(QDialogButtonBox.StandardButton.Close)
        actions.rejected.connect(dialog.reject)
        layout.addWidget(actions)
        dialog.exec()

    def reset_defaults(self) -> None:
        if self.schema:
            self.load_game(self.schema.game, default_values(self.schema), self.preserved_extras)

    def open_options_page(self) -> None:
        if not self.schema or not self.schema.options_page:
            self.window.statusBar().showMessage("This game uses the built-in visual options form", 4000)
            return
        url = self.schema.options_page
        if not url.startswith(("http://", "https://")):
            url = "https://archipelago.gg/" + url.lstrip("/")
        QDesktopServices.openUrl(QUrl(url))

    def refresh_library(self, selected: Path | None = None) -> None:
        self.library_list.clear()
        for path in self.window.store.list_yamls():
            item = QListWidgetItem(path.name)
            item.setData(Qt.ItemDataRole.UserRole, str(path))
            self.library_list.addItem(item)
            if selected and path == selected:
                self.library_list.setCurrentItem(item)

    def selected_path(self) -> Path | None:
        item = self.library_list.currentItem()
        return Path(item.data(Qt.ItemDataRole.UserRole)) if item else None

    def load_saved(self) -> None:
        path = self.selected_path()
        if not path:
            return
        try:
            name, game, values, extras = read_player_yaml(path)
            index = self.game_combo.findText(game)
            if index < 0:
                raise ValueError(f"The installed worlds do not provide {game}")
            self.game_combo.blockSignals(True)
            self.game_combo.setCurrentIndex(index)
            self.game_combo.blockSignals(False)
            self.name_edit.setText(name)
            self.load_game(game, values, extras)
        except Exception as error:
            self.window.show_error(error)

    def add_saved(self) -> None:
        path = self.selected_path()
        if path:
            self.window.add_generator_yaml(path)

    def import_yaml(self) -> None:
        paths, _ = QFileDialog.getOpenFileNames(self, "Import player YAML", filter="YAML files (*.yaml *.yml)")
        selected = None
        try:
            for source in paths:
                selected = self.window.store.import_yaml(Path(source))
            self.refresh_library(selected)
            if selected:
                self.load_saved()
        except Exception as error:
            self.window.show_error(error)

    def delete_saved(self) -> None:
        path = self.selected_path()
        if not path:
            return
        answer = QMessageBox.question(self, "Delete saved player", f"Delete {path.name} from the Companion library?")
        if answer != QMessageBox.StandardButton.Yes:
            return
        self.window.remove_generator_yaml(path)
        path.unlink(missing_ok=True)
        self.refresh_library()


class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.store = StateStore()
        self.state: AppState = self.store.load()
        self.services = DesktopServices(self.store)
        self.processes: list[QProcess] = []
        self.client_process: QProcess | None = None
        self.generator_yamls: list[Path] = []
        self.page_rows: dict[str, int] = {}
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
        self.player_creator = PlayerCreatorPage(self)
        pages = [
            ("Overview", self._home_page()),
            ("Rooms", self._rooms_page()),
            ("Player creator", self.player_creator),
            ("Generate", self._generator_page()),
            ("APWorlds", self._worlds_page()),
            ("Console", self._console_page()),
            ("Settings", SettingsPage(self)),
        ]
        for row, (title, widget) in enumerate(pages):
            self.page_rows[title] = row
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

    def show_page(self, title: str) -> None:
        self.navigation.setCurrentRow(self.page_rows[title])

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
        quick_layout.addWidget(button("Create player", lambda: self.show_page("Player creator")))
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
        content, layout = page("Generate seed", "Select saved players, then run Archipelago's upstream generator.")
        self.yaml_list = QListWidget()
        self.yaml_list.setMinimumHeight(150)
        yaml_body = QWidget()
        yaml_layout = QVBoxLayout(yaml_body)
        yaml_layout.setContentsMargins(0, 0, 0, 0)
        yaml_layout.addWidget(self.yaml_list)
        yaml_layout.addWidget(action_bar(
            button("Add YAML…", self.add_yaml), button("Remove selected", self.remove_yaml),
        ))
        layout.addWidget(card("Players selected for this seed", yaml_body))
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
            self.add_generator_yaml(imported)
        if hasattr(self, "player_creator"):
            self.player_creator.refresh_library()
        if paths:
            self.show_page("Generate")

    def add_generator_yaml(self, path: Path) -> None:
        path = path.resolve()
        if path not in self.generator_yamls:
            self.generator_yamls.append(path)
            item = QListWidgetItem(path.name)
            item.setToolTip(str(path))
            item.setData(Qt.ItemDataRole.UserRole, str(path))
            self.yaml_list.addItem(item)
        self.show_page("Generate")

    def remove_generator_yaml(self, path: Path) -> None:
        resolved = path.resolve()
        for row in range(len(self.generator_yamls) - 1, -1, -1):
            if self.generator_yamls[row] == resolved:
                self.generator_yamls.pop(row)
                self.yaml_list.takeItem(row)

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
        self.show_page("Console")
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
            self.show_page("Console")
            return
        try:
            command = self.services.text_client_command(room, self.state.settings)
        except Exception as error:
            self.show_error(error)
            return
        self.show_page("Console")
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
