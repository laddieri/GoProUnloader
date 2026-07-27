# -*- mode: python ; coding: utf-8 -*-
"""
PyInstaller build for GoPro Unloader.

Produces two programs in dist/GoProUnloader/:

    GoProUnloader.exe       the offload window (no console)
    gopro-unloader-cli.exe  the command-line tool
    gopro-diag.exe          the thumbnail diagnostics

Build (on Windows, with the deps installed):

    pip install pyinstaller -r requirements.txt
    pyinstaller GoProUnloader.spec

FFmpeg is not bundled - it is a large, separately-licensed dependency. The
app looks for ffmpeg/ffprobe/ffplay next to the .exe, in an `ffmpeg`
subfolder, and then on PATH, so copying the three binaries into dist works if
you want a fully self-contained folder.
"""

from PyInstaller.utils.hooks import collect_submodules

# bleak loads its OS backend by name, so the Windows one has to be pulled in
# explicitly or the frozen build can't talk to Bluetooth at all.
hidden = (
    collect_submodules("bleak")
    + collect_submodules("bleak_winrt")
    + collect_submodules("winrt")
    # Pillow imports its format plugins lazily by name, so without these the
    # bundled Pillow imports fine but can't actually decode a JPEG.
    + collect_submodules("PIL")
)

# Trim the parts of the stdlib/3rd-party tree nothing here uses.
excludes = [
    "matplotlib", "numpy", "scipy", "pandas", "pytest",
    "PyQt5", "PyQt6", "PySide2", "PySide6", "wx",
    "test", "unittest", "pydoc_data",
]

common = dict(
    pathex=["."],
    binaries=[],
    datas=[],
    hiddenimports=hidden,
    hookspath=[],
    runtime_hooks=[],
    excludes=excludes,
    noarchive=False,
)

gui_a = Analysis(["gopro_gui.py"], **common)
cli_a = Analysis(["gopro_unloader.py"], **common)
diag_a = Analysis(["gopro_diag.py"], **common)

MERGE(
    (gui_a, "gopro_gui", "GoProUnloader"),
    (cli_a, "gopro_unloader", "gopro-unloader-cli"),
    (diag_a, "gopro_diag", "gopro-diag"),
)

gui_pyz = PYZ(gui_a.pure, gui_a.zipped_data)
cli_pyz = PYZ(cli_a.pure, cli_a.zipped_data)
diag_pyz = PYZ(diag_a.pure, diag_a.zipped_data)

gui_exe = EXE(
    gui_pyz,
    gui_a.scripts,
    [],
    exclude_binaries=True,
    name="GoProUnloader",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,          # windowed: no console flashing up behind the UI
    icon=None,
)

cli_exe = EXE(
    cli_pyz,
    cli_a.scripts,
    [],
    exclude_binaries=True,
    name="gopro-unloader-cli",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,           # the CLI needs its terminal
    icon=None,
)

diag_exe = EXE(
    diag_pyz,
    diag_a.scripts,
    [],
    exclude_binaries=True,
    name="gopro-diag",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,
    icon=None,
)

coll = COLLECT(
    gui_exe, gui_a.binaries, gui_a.datas,
    cli_exe, cli_a.binaries, cli_a.datas,
    diag_exe, diag_a.binaries, diag_a.datas,
    strip=False,
    upx=False,
    name="GoProUnloader",
)
