@echo off
node "%~dp0hook.mjs" %*
exit /b %errorlevel%
