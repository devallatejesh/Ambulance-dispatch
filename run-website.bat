@echo off
rem Double-click this file (or type  .\run-website.bat  in a terminal) to start the website.
rem It always works from this folder, so you do not need to "cd" anywhere first.
cd /d "%~dp0"
echo Compiling...
if not exist out mkdir out
javac -d out src\*.java || (echo. & echo Compile failed - is the JDK installed? Try:  javac -version & pause & exit /b 1)
echo.
echo Opening http://localhost:8080 in your browser in 3 seconds...
start "" cmd /c "timeout /t 3 >nul & start http://localhost:8080"
java -cp out WebServer
pause
