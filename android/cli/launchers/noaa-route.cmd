@echo off
rem noaa-route: NOAA Route's planner on the command line (Windows).
rem Needs Java 17 or later. NOAA_ROUTE_JAVA_OPTS replaces the default -Xmx1g (e.g. -Xmx2g for long trips).
where java >nul 2>nul
if errorlevel 1 (
  echo noaa-route needs Java 17 or later, from https://adoptium.net 1>&2
  exit /b 2
)
if "%NOAA_ROUTE_JAVA_OPTS%"=="" (set "NOAA_ROUTE_OPTS=-Xmx1g") else (set "NOAA_ROUTE_OPTS=%NOAA_ROUTE_JAVA_OPTS%")
java %NOAA_ROUTE_OPTS% -jar "%~dp0noaa-route.jar" %*
exit /b %errorlevel%
