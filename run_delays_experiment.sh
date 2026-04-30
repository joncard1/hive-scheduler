#!/bin/bash

set -ex

export JAVA_HOME='/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home'
for experiment in $(ls "experiments/delays"); do
    $JAVA_HOME/bin/java -Xmx4g '-classpath' './target/scala-3.7.4/hive-scheduler-assembly-0.1.0-SNAPSHOT.jar' '-Duser.dir=/Users/jonathancard/Documents/vscode/hive-scheduler' 'eusocialcooperation.scheduler.Demo' "experiments/delays/$experiment" --runs=5 --headless=true
    sleep 3
done