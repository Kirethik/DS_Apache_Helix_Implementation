# 🚂 Distributed Train Ticket Booking System

A distributed train ticket booking system built using **Apache Helix** for partition management, leader election, and fault tolerance — with **ZooKeeper** as the coordination backend and a **live web dashboard**.

---

## 📋 Table of Contents

- [Project Overview](#-project-overview)
- [Tech Stack & Versions](#-tech-stack--versions)
- [Prerequisites](#-prerequisites)
- [ZooKeeper Setup (IMPORTANT)](#-zookeeper-setup-important)
- [Building the Project](#-building-the-project)
- [Running the Demo](#-running-the-demo)
- [Project Structure](#-project-structure)
- [Architecture](#-architecture)

---

## 📌 Project Overview

This system demonstrates:
- **Distributed partition management** via Apache Helix
- **Leader election** across multiple participant nodes
- **Fault tolerance** — automatic failover when a node crashes
- **ZooKeeper coordination overhead reduction** via batching
- **Real-time web dashboard** showing cluster state and booking metrics

---

## 🛠 Tech Stack & Versions

| Component | Version |
|-----------|---------|
| **Java** | JDK 11 |
| **Maven** | 3.8.x or higher |
| **Apache Helix** | 1.3.2 |
| **Apache ZooKeeper** | **3.8.4** ✅ |
| **SQLite JDBC** | 3.45.1.0 |
| **SLF4J** | 1.7.36 |
| **Logback** | 1.2.12 |

---

## 📦 Prerequisites

Make sure the following are installed before proceeding:

1. **Java JDK 11** → [Download from Adoptium](https://adoptium.net/temurin/releases/?version=11)
   - Verify: `java -version` should show `11.x.x`

2. **Apache Maven 3.8+** → [Download Maven](https://maven.apache.org/download.cgi)
   - Verify: `mvn -version` should show `3.8.x` or higher

3. **Apache ZooKeeper 3.8.4** → See setup below ↓

---

## 🦓 ZooKeeper Setup (IMPORTANT)

> ⚠️ The project is configured to look for ZooKeeper at `C:\zookeeper`. You MUST follow these steps exactly.

### Step 1 — Download ZooKeeper

Go to the official Apache ZooKeeper downloads page:

👉 **[https://zookeeper.apache.org/releases.html](https://zookeeper.apache.org/releases.html)**

Download the **binary release** (not source):
```
apache-zookeeper-3.8.4-bin.tar.gz
```

> ⚠️ Make sure you download the `-bin` version, NOT the plain `.tar.gz` without `-bin`. The `-bin` version includes the pre-compiled JARs needed to run.

### Step 2 — Extract ZooKeeper

Extract the downloaded archive. You can use **7-Zip** or **WinRAR** on Windows.

After extracting, you will get a folder named:
```
apache-zookeeper-3.8.4-bin
```

### Step 3 — Rename and Place the Folder

> 🔴 **Critical Step**: Rename and move the folder so it matches exactly:

```
C:\zookeeper
```

Your final folder structure should look like this:
```
C:\zookeeper\
├── bin\
│   ├── zkServer.cmd        ← used to start ZooKeeper on Windows
│   ├── zkCli.cmd
│   └── ...
├── conf\
├── lib\
└── ...
```

### Step 4 — Configure ZooKeeper

Inside `C:\zookeeper\conf\`, you will find a file called `zoo_sample.cfg`.

1. **Copy** `zoo_sample.cfg`
2. **Rename the copy** to `zoo.cfg`

The default `zoo.cfg` should work out of the box. It will use:
- Port: `2181`
- Data directory: `C:\zookeeper\data` (create this folder if it doesn't exist)

To create the data folder:
```powershell
mkdir C:\zookeeper\data
```

Then open `zoo.cfg` and make sure this line points to:
```
dataDir=C:/zookeeper/data
```

### Step 5 — Test ZooKeeper Starts

Open a terminal and run:
```cmd
C:\zookeeper\bin\zkServer.cmd
```

You should see output like:
```
Starting ZooKeeper ... STARTED
```

---

## 🔨 Building the Project

Navigate to the project root directory (where `pom.xml` is located):

```powershell
cd d:\MATERIALS\DS\TrainBookingSystem
```

Run the Maven build:
```powershell
mvn clean package -DskipTests
```

This will generate **5 fat JARs** inside the `target\` folder:

| JAR File | Purpose |
|----------|---------|
| `ClusterSetup.jar` | One-time cluster initialization |
| `HelixController.jar` | Helix controller (cluster brain) |
| `ParticipantNode.jar` | Booking nodes (run 3 instances) |
| `BookingClient.jar` | Client that makes booking requests |
| `DashboardServer.jar` | Web dashboard (real-time UI) |

---

## ▶️ Running the Demo

### Option A — Automated (Recommended)

Simply double-click or run from terminal:
```powershell
.\run_demo.bat
```

This will automatically:
1. Start ZooKeeper
2. Run Cluster Setup (one-time)
3. Start the Helix Controller
4. Start 3 Participant Nodes (Node1, Node2, Node3)
5. Run the Booking Client demo

### Option B — Manual (Step by Step)

Open **separate terminal windows** for each step:

**Terminal 1 — Start ZooKeeper:**
```cmd
C:\zookeeper\bin\zkServer.cmd
```

**Terminal 2 — Cluster Setup (run only ONCE):**
```powershell
java -jar target\ClusterSetup.jar localhost:2181
```

**Terminal 3 — Start Helix Controller:**
```powershell
java -jar target\HelixController.jar localhost:2181
```

**Terminals 4, 5, 6 — Start Participant Nodes:**
```powershell
java -jar target\ParticipantNode.jar localhost:2181 Node1
java -jar target\ParticipantNode.jar localhost:2181 Node2
java -jar target\ParticipantNode.jar localhost:2181 Node3
```

**Terminal 7 — Run Booking Client:**
```powershell
java -jar target\BookingClient.jar localhost:2181
```

**Terminal 8 — Start Dashboard (optional):**
```powershell
java -jar target\DashboardServer.jar
```
Then open your browser at: **http://localhost:8080**

---

### 🔁 Failover Demo

To see automatic failover in action:
1. Close the **Node1** terminal window (simulating a crash)
2. Re-run the Booking Client:
```powershell
java -jar target\BookingClient.jar localhost:2181
```
Watch Helix automatically reassign Node1's partitions to the remaining nodes!

---

## 📁 Project Structure

```
TrainBookingSystem/
├── pom.xml                          # Maven build configuration
├── run_demo.bat                     # One-click demo runner
├── README.md
└── src/
    └── main/
        ├── java/com/trainbooking/
        │   ├── setup/
        │   │   └── ClusterSetup.java        # Helix cluster initialization
        │   ├── controller/
        │   │   └── TrainControllerApp.java  # Helix controller
        │   ├── participant/
        │   │   └── ParticipantNode.java     # Booking node participant
        │   ├── client/
        │   │   └── BookingClient.java       # Booking request client
        │   ├── dashboard/
        │   │   └── DashboardServer.java     # Web dashboard server
        │   ├── db/
        │   │   └── DatabaseManager.java     # SQLite persistence
        │   └── model/
        │       └── SeatStatus.java          # Seat state model
        └── resources/
            ├── dashboard.html               # Web dashboard UI
            └── logback.xml                  # Logging configuration
```

---

## 🏗 Architecture

```
┌─────────────────────────────────────────────────────┐
│                   BookingClient                      │
│            (sends booking requests)                  │
└───────────────────────┬─────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────┐
│               ZooKeeper (port 2181)                  │
│         (coordination & state storage)               │
└──────┬────────────────┬────────────────┬────────────┘
       │                │                │
       ▼                ▼                ▼
┌──────────┐    ┌──────────────┐  ┌──────────────┐
│  Helix   │    │ Participant  │  │ Participant  │
│Controller│    │   Node 1    │  │   Node 2    │  ...
│(leader   │    │ (MASTER /   │  │ (SLAVE /    │
│election) │    │  SLAVE)     │  │  MASTER)    │
└──────────┘    └──────────────┘  └──────────────┘
                        │
                        ▼
               ┌────────────────┐
               │  SQLite DB     │
               │ (seat bookings)│
               └────────────────┘
```

---

## 🐛 Common Issues

| Problem | Solution |
|---------|----------|
| `ZooKeeper not found` | Make sure ZooKeeper is at exactly `C:\zookeeper` |
| `zoo.cfg not found` | Copy `zoo_sample.cfg` → `zoo.cfg` inside `C:\zookeeper\conf\` |
| `Port 2181 already in use` | Another ZooKeeper instance is running. Kill it first |
| `JAR not found` | Run `mvn clean package -DskipTests` first |
| `Java version error` | Make sure `java -version` shows JDK **11** |
| `maven not recognized` | Add Maven `bin\` folder to your system PATH |

---

## 👨‍💻 Author

**Kirethik** — [GitHub](https://github.com/Kirethik)
