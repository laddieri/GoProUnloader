@echo off
REM Build GoPro Unloader into dist\GoProUnloader\ .
REM Run from the repository folder on Windows with Python 3.11+ installed.

setlocal

echo.
echo === Installing build dependencies ===
python -m pip install --upgrade pip || goto :failed
python -m pip install -r requirements.txt || goto :failed
python -m pip install pyinstaller || goto :failed

echo.
echo === Building ===
python -m PyInstaller GoProUnloader.spec --noconfirm || goto :failed

echo.
echo === Done ===
echo Built into: dist\GoProUnloader\
echo   GoProUnloader.exe        the offload window
echo   gopro-unloader-cli.exe   the command-line tool
echo   gopro-diag.exe           thumbnail diagnostics
echo.
echo FFmpeg is not bundled. Either keep it on PATH, or copy ffmpeg.exe,
echo ffprobe.exe and ffplay.exe into dist\GoProUnloader\ for a folder you
echo can move to another machine as-is.
echo.
pause
exit /b 0

:failed
echo.
echo Build failed. See the messages above.
pause
exit /b 1
