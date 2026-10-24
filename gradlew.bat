@echo off
where gradle >nul 2>nul
if %ERRORLEVEL% EQU 0 (gradle %*) else (echo Please install Gradle 8.9 or run the GitHub Actions workflow.)
