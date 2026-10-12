@echo off
rem Runs GraalVM native-image with the MSVC toolchain and space-free temp dirs.
rem Usage: tools\ni.cmd <native-image args...>
setlocal
if "%GRAALVM_HOME%"=="" set "GRAALVM_HOME=D:\Claude\toolchains\graalvm-25.2.4+7.1"
if "%BINARYEN_HOME%"=="" set "BINARYEN_HOME=D:\Claude\toolchains\binaryen-version_131"
set "TEMP=D:\gradle-tmp\tmp"
set "TMP=D:\gradle-tmp\tmp"
if not exist "%TEMP%" mkdir "%TEMP%"
set "PATH=C:\Program Files (x86)\Microsoft Visual Studio\Installer;%PATH%"
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 (
  echo vcvars64.bat failed 1>&2
  exit /b 1
)
set "PATH=%GRAALVM_HOME%\bin;%BINARYEN_HOME%\bin;%PATH%"
call "%GRAALVM_HOME%\bin\native-image.cmd" %* -J-Djava.io.tmpdir=D:\gradle-tmp\tmp
exit /b %errorlevel%
