# TSI Ratings App
An objective diagnostic framework, designed to give MSMEs and their ecosystem partners a clear, data-driven perspective on where they stand and where they need to go. It helps transform operational trust into verifiable digital collateral.

Note: This solution is currently in Public Preview.

## Getting Started

[Launch Note](https://techadvisory.substack.com/p/unlocking-msme-credit-operational)

[Introduction](https://www.youtube.com/watch?v=4dRcO0ySz2U)

[Functional Demo](https://www.youtube.com/watch?v=4aAh8btPv2E&t=6s)

[Code Walkthrough](https://www.youtube.com/watch?v=tphCEvtJtCk)

[Sample Report](https://github.com/tsi-coop/tsi-ratings/blob/main/reports/TSI_Rating_Report_TSI_Coop.pdf)

[Pilot Portal](https://tsicoop.org/ratings/)

## Prerequisites

Before you begin, ensure you have the following software installed on your development machine or server:

* **Java Development Kit (JDK) 17 or higher**: Required to build and run the Java application.
    * **Installation Steps:**
        * **Linux (Ubuntu/Debian):**
            ```bash
            sudo apt update
            sudo apt install openjdk-17-jdk
            ```
        * **Windows:** Download the JDK 17 installer from Oracle (requires account) or Adoptium (Eclipse Temurin, recommended open-source distribution) and follow the installation wizard. Ensure `JAVA_HOME` environment variable is set and `%JAVA_HOME%\bin` is in your system's `Path`.
    * **Verification:**
        ```bash
        java -version
        javac -version
        ```

* **Apache Maven 3.6.0 or higher**: Project build automation tool.
    * **Installation Steps:**
        * **Linux (Ubuntu/Debian):**
            ```bash
            sudo apt install maven
            ```
        * **Windows:** Download the Maven binary zip from the Apache Maven website, extract it, and add the `bin` directory to your system's `Path` environment variable.
    * **Verification:**
        ```bash
        mvn -v
        ```

* **Docker Desktop (or Docker Engine + Docker Compose)**: Essential for containerizing and running the application and database locally.
    * **Installation Steps:**
        * **Windows:** Download and install Docker Desktop from the [official Docker website](https://www.docker.com/products/docker-desktop/).
        * **Linux:** Follow the official Docker Engine installation guide for your specific distribution (e.g., [Docker Docs](https://docs.docker.com/engine/install/)). Install Docker Compose separately if using Docker Engine.
    * **Configuration & Verification (Windows Specific):**
        * Ensure **WSL 2** is enabled and configured. Open PowerShell as Administrator and run `wsl --install` or `wsl --update`.
        * Verify **virtualization (Intel VT-x / AMD-V)** is enabled in your computer's BIOS/UEFI settings.
        * Start Docker Desktop and wait for the whale icon in the system tray to turn solid.
    * **Verification:**
        ```bash
        docker --version
        docker compose version # Or docker-compose --version for older installations
        ```

* **Git**: For cloning the repository.
    * **Installation Steps:**
        * **Linux (Ubuntu/Debian):**
            ```bash
            sudo apt install git
            ```
        * **Windows:** Download the Git for Windows installer from [git-scm.com](https://git-scm.com/download/win) and follow the installation wizard.
    * **Verification:**
        ```bash
        git --version
        ```
* **NodeJS**: The JavaScript runtime environment for frontend app.
    * **Installation Steps:**
        * Navigate to the Node.js [Downloads](https://nodejs.org/en/download) page.
        * Select the installer for your operating system (Windows, macOS, or Linux). Choose the LTS (Long-Term Support) version, as it is the most stable and recommended for most users.
        * Run the installer and follow the on-screen prompts. The installer will automatically set up both Node.js and npm.

    * **Verification:**
      ```bash
      node -v
      ```
* **BSV Blockchain**: For anchoring the token.
  * Get an overview of [BSV Blockchain](https://bsvblockchain.org/)
  * Install Metanet Client:** [Download and install Metanet Desktop](https://getmetanet.com/)
  * Explore [WhatsonChain](https://whatsonchain.com/)
      
## Installation Steps

These steps describe how to install and run the TSI Ratings solution directly on a Linux/Windows server without using Docker.

1.   **Clone the Repository:**
     ```bash
     git clone https://github.com/tsi-coop/tsi-ratings.git
     cd tsi-ratings
     ```

2.  **PostgreSQL Database Setup:**
    * Log in as the PostgreSQL superuser (e.g., `postgres` user on Linux).
    ```bash
    sudo -i -u postgres psql
    ```
    * Create the database and user:
    ```sql
    CREATE DATABASE <<your-db-name-here>>;
    CREATE USER <<your-db-user-here>> WITH ENCRYPTED PASSWORD '<<your_db_password_here>>';
    GRANT ALL PRIVILEGES ON DATABASE <<your-db-name-here>> TO <<your-db-user-here>>;
    ```
    * Exit the postgres user: `exit`
    * **Initialize Schema:** Execute the `db/init.sql` script to create the necessary tables.
    ```bash
    psql -U <<your-db-user-here>> -d <<your-db-name-here>> -h localhost -f /path/to/tsi-ratings/db/init.sql
    ```
3.  **Build WAR:**
    ```bash
    cd /path/to/tsi-ratings
    mvn clean package
    ```
    This will generate `target/tsi-ratings.war`.

4.  **Deploy Solution (linux):**
    ```bash
    cd /path/to/tsi-ratings/server
    cp .example .env
    ```
    Now, **edit the newly created `.env` file** and fill in the placeholder values.

    ```bash
    ./set-base.sh #Sets the jetty base directory
    ./serve.sh # Copies the target/tsi-ratings.war to %JETTY_BASE%/webapps/ROOT.war. Starts the server in 8080
    ```
5. **Deploy Solution (windows):**
   ```bash
   cd /path/to/tsi-ratings/server
   copy .example .env
   ```
   Now, **edit the newly created `.env` file** and fill in the placeholder values.

   ```bash
   set-base.bat #Sets the jetty base directory
   serve.bat # Copies the target/tsi_ratings.war to %JETTY_BASE%/webapps/ROOT.wat. Starts the server in 8080
   ```

6. **Deploy Web3 Module (windows):**
   ```bash
   cd /path/to/tsi-ratings/web3
   copy .example .env
   ```
   Now, **edit the newly created `.env` file** and fill in the placeholder values.

   ```bash
   npm start

## References

[Digital Maturity Assessment - How does it help?](https://techadvisory.substack.com/p/why-measuring-digital-maturity-matters)

[Capability maturity assessment - how does it help?](https://techadvisory.substack.com/p/capability-maturity-assessment-how)

[TSI Digital Maturity Assessment](https://techadvisory.substack.com/p/understanding-your-digital-standing)

[TSI Capability Maturity Assessment](https://techadvisory.substack.com/p/your-path-to-high-quality-delivery)

