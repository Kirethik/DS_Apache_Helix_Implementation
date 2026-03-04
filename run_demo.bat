@echo off
REM ═══════════════════════════════════════════════════════════════════
REM  run_demo.bat — Complete Demo Script for Train Booking System
REM  Runs in 5 terminal windows (or sequentially for quick demo)
REM ═══════════════════════════════════════════════════════════════════

SET ZK_HOME=C:\zookeeper
SET ZK_ADDR=localhost:2181
SET PROJECT_DIR=%~dp0
SET TARGET=%PROJECT_DIR%target

echo ═════════════════════════════════════════════════════════════
echo   Distributed Train Booking System — Demo Runner
echo ═════════════════════════════════════════════════════════════
echo.
echo STEP 1: Starting ZooKeeper...
start "ZooKeeper" cmd /k "%ZK_HOME%\bin\zkServer.cmd"
timeout /t 5 /nobreak >nul
echo   ZooKeeper started.
echo.

echo STEP 2: Running Cluster Setup (one-time)...
java -jar "%TARGET%\ClusterSetup.jar" %ZK_ADDR%
echo.

echo STEP 3: Starting Helix Controller...
start "HelixController" cmd /k "java -jar %TARGET%\HelixController.jar %ZK_ADDR%"
timeout /t 3 /nobreak >nul
echo   Controller started.
echo.

echo STEP 4: Starting 3 Participant Nodes...
start "Node1" cmd /k "java -jar %TARGET%\ParticipantNode.jar %ZK_ADDR% Node1"
timeout /t 2 /nobreak >nul
start "Node2" cmd /k "java -jar %TARGET%\ParticipantNode.jar %ZK_ADDR% Node2"
timeout /t 2 /nobreak >nul
start "Node3" cmd /k "java -jar %TARGET%\ParticipantNode.jar %ZK_ADDR% Node3"
timeout /t 4 /nobreak >nul
echo   All 3 nodes started.
echo.

echo STEP 5: Running Booking Client Demo...
echo ─────────────────────────────────────────────────────────────
java -jar "%TARGET%\BookingClient.jar" %ZK_ADDR%
echo ─────────────────────────────────────────────────────────────
echo.
echo Demo complete! Check the Node windows for state transitions.
echo.
echo FAILOVER DEMO: Close the "Node1" window and re-run:
echo   java -jar %TARGET%\BookingClient.jar %ZK_ADDR%
echo.
pause
