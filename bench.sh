#!/bin/bash

echo "---- Starting benchmark ---"
echo ""
echo "  Please standby ... "
echo ""
versions="infinispan-4.2 infinispan-5.0 infinispan-5.1.CR infinispan-5.1.SNAPSHOT"
for inf in $versions; do
  cd $inf
  mvn -q -o install -DskipTests=true -Djmx=false
  mvn -q -o install -DskipTests=true -Djmx=true
  cd ..
done
echo ""
echo "   Done!"

