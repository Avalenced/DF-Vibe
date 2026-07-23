@echo off
rem ---------------------------------------------------------------------------
rem  DF Item Editor — double-click to launch the item/GUI editor.
rem  Uses pythonw (no console window) if available, else python.
rem  Pass a .py line to open it on launch:  "DF Item Editor.bat" plots\foo\bar.py
rem ---------------------------------------------------------------------------
where pythonw >nul 2>nul && (
  start "" pythonw "%~dp0codec\df_editor.py" %*
) || (
  start "" python "%~dp0codec\df_editor.py" %*
)
