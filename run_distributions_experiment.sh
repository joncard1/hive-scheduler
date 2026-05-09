#!/bin/bash

#set -ex

JAVA_HOME='/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home'
parent="experiments/distribution"
numRuns=10
for experiment in $(ls "${parent}" | sed -n '/config/!p'); do
    $JAVA_HOME/bin/java -Xmx5g '-classpath' './target/scala-3.7.4/hive-scheduler-assembly-0.1.0-SNAPSHOT.jar' '-Duser.dir=$(realpath .)' 'eusocialcooperation.scheduler.Demo' "${parent}/$experiment" --runs=$numRuns --parent="$parent/"
    sleep 5
done
pointsDataHeaders=(distribution run seqnum x y z)
prospectsHeaders=(distribution run seqnum x y)
metadataHeaders=(distribution run seqnum type timestamp name phase parent)
queueLengthsHeaders=(distribution run timestamp length)

for file in pointsData prospects metadata queueLengths; do
    headers="${file}Headers"
    header=$(hdrVar="${headers}[*]"; IFS=$(printf "\t") ; echo "${!hdrVar}")
    outputFile="${parent}/${file}Agg.csv"
    printf "$header\n" > "$outputFile"
    find . -regex ".*/$file.csv" | sed -e '/.*/p' -e "s/\.\/${parent/\//\/}\/\(.*\)\/run_\([0-9]\)*\/$file.csv/\1\n\2/" | xargs -n 3 ./concat_data.sh "$outputFile"
done