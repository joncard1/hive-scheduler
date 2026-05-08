#!/bin/zsh
outputFile=$1
file=$2
distribution=$3
run=$4

cat $file | sed "s/\(.*\)/$distribution\t$run\t\1/" >> $outputFile
