@echo off
setlocal
rem Regenerates every binary fixture from fixtures\content, then verifies each one
rem by reading it back with the same libraries the application uses.
rem
rem The jars are copied into target\fixture-libs so the classpath can be the
rem wildcard "target\fixture-libs\*". Passing the full classpath inline does not
rem work here: it is over 10,000 characters and cmd truncates it silently.

cd /d "%~dp0..\.."

set "WORK=target\fixture-classes"
if not exist "%WORK%" mkdir "%WORK%"

echo [1/3] Resolving dependencies...
call "%CD%\mvnw.cmd" -q -o dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/fixture-libs
if errorlevel 1 (
  echo Offline resolve failed, retrying online...
  call "%CD%\mvnw.cmd" -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/fixture-libs || goto :fail
)

echo [2/3] Compiling generator...
javac -nowarn -encoding UTF-8 -d "%WORK%" -cp "target\fixture-libs\*" ^
  fixtures\tools\GenerateFixtures.java ^
  fixtures\tools\VerifyFixtures.java ^
  src\main\java\com\apliman\cvevaluator\redaction\PiiRedactor.java || goto :fail

echo [3/3] Generating and verifying...
java -Dfile.encoding=UTF-8 -cp "%WORK%;target\fixture-libs\*" GenerateFixtures fixtures || goto :fail
java -Dfile.encoding=UTF-8 -cp "%WORK%;target\fixture-libs\*" VerifyFixtures fixtures || goto :fail

echo.
echo Done.
exit /b 0

:fail
echo.
echo FAILED. If javac reports "release version" trouble, point JAVA_HOME at a JDK 21.
exit /b 1
