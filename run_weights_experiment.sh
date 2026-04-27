#!/bin/bash

set -ex

export JAVA_HOME='/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home'
for experiment in $(ls "experiments/weights"); do
    $JAVA_HOME/bin/java -Xmx4g '-classpath' './target/scala-3.7.4/hive-scheduler-assembly-0.1.0-SNAPSHOT.jar' '-Duser.dir=/Users/jonathancard/Documents/vscode/hive-scheduler' 'eusocialcooperation.scheduler.Demo' "experiments/weights/$experiment" --headless=true &
    sleep 300
done