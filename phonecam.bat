@echo off
chcp 65001 >nul
echo ========================================
echo PHONECAM - Camara Virtual
echo ========================================
echo.
where python >nul 2>&1
if errorlevel 1 (
    echo Python no esta instalado. Descargalo desde https://python.org/downloads
    pause
    exit /b 1
)
python -m pip install -r "%~dp0desktop\requirements.txt"
if errorlevel 1 (
    echo No se pudieron instalar las dependencias.
    pause
    exit /b 1
)
echo.
echo Iniciando PhoneCam...
python "%~dp0desktop\gui_app.py"
pause
