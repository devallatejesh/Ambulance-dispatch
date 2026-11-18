@echo off
rem Double-click this file (or type  .\run-console.bat  in a terminal) to start the console menu program.
cd /d "%~dp0"
echo Compiling...
if not exist out mkdir out
javac -d out src\*.java || (echo. & echo Compile failed - is the JDK installed? Try:  javac -version & pause & exit /b 1)
echo.
java -cp out Main
pause
