#!/bin/bash

# Source the .env file to load variables into the current shell session
# Adjust path if .env is not in the same directory as the script
if [ -f ./.env ]; then
    source ./.env
else
    echo "Error: .env file not found. Please create it."
    exit 1
fi

export JAVA_HOME=$JAVA_HOME
export TSI_RATINGS_ENV=$TSI_RATINGS_ENV
export TSI_RATINGS_HOME=$TSI_RATINGS_HOME
export POSTGRES_HOST=$POSTGRES_HOST
export POSTGRES_DB=$POSTGRES_DB
export POSTGRES_USER=$POSTGRES_USER
export POSTGRES_PASSWD=$POSTGRES_PASSWD
export JETTY_HOME=$JETTY_HOME
export JETTY_BASE=$JETTY_BASE
java -jar $JETTY_HOME/start.jar