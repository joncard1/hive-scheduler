#!/bin/bash

set -ex

JAVA_HOME='/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home'
parent="experiments/delays"
for experiment in $(ls "$parent" | grep 'delay-'); do
    $JAVA_HOME/bin/java -Xmx5g '-classpath' './target/scala-3.7.4/hive-scheduler-assembly-0.1.0-SNAPSHOT.jar' '-Duser.dir=/Users/jonathancard/Documents/vscode/hive-scheduler' 'eusocialcooperation.scheduler.Demo' "$parent/$experiment" --runs=5 --parent="$parent"
    sleep 3
done