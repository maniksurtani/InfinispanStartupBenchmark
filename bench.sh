#!/bin/bash

CFG="-Dlog4j.configuration=bench-log4j.xml"
CFG="-Dbench.payloadsize=10240 $CFG"
CFG="-Dbench.numkeys=500 $CFG"
CFG="-Dbench.transactional=true $CFG"
CFG="-Dbench.writepercent=10 $CFG"
CFG="-Dbench.warmup=100000 $CFG"
CFG="-Dbench.loops=1000000 $CFG"
CFG="-Dbench.threads=50 $CFG"

echo "---- Starting benchmark ---"
echo ""
echo "  Please standby ... "
echo ""
versions="infinispan-5.0 infinispan-5.1.CR infinispan-5.1.SNAPSHOT"
for inf in $versions; do
  cd $inf
  mvn -q -o install -DskipTests=true -PClusteredTest $CFG
  cd ..
done
echo ""
echo "   Done!"

