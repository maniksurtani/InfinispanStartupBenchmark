#!/bin/bash

#CFG="-Dlog4j.configuration=bench-log4j.xml"
CFG="-Dbench.warmupMilliseconds=240000 $CFG"
CFG="-Dbench.payloadsize=120 $CFG"
CFG="-Dbench.numkeys=1500 $CFG"
CFG="-Dbench.transactional=true $CFG"
CFG="-Dbench.dist=true $CFG"
CFG="-Dbench.nodes=12 $CFG"
CFG="-Dbench.readerThreads=25 $CFG"
CFG="-Dbench.writerThreads=15 $CFG"
CFG="-Dbench.loops=10000000 $CFG"

#To use default Infinispan UDP configuration for JGroups (instead of the benchmark included one, bench-jgroups.xml):
CFG="-Dbench.jgroups_conf=jgroups-udp.xml $CFG"

MAVEN_OPTS=""
MAVEN_OPTS="$MAVEN_OPTS -Xmx2G -Xms2G -XX:MaxPermSize=128M -XX:+HeapDumpOnOutOfMemoryError -Xss512k -XX:HeapDumpPath=/tmp/java_heap"
MAVEN_OPTS="$MAVEN_OPTS -Djava.net.preferIPv4Stack=true -Djgroups.bind_addr=127.0.0.1"
MAVEN_OPTS="$MAVEN_OPTS -Xbatch -server -XX:+UseCompressedOops"
MAVEN_OPTS="$MAVEN_OPTS -XX:+UseLargePages -XX:LargePageSizeInBytes=2m -XX:+AlwaysPreTouch"

AGENT=""
#AGENT="-agentpath:/usr/lib64/oprofile/libjvmti_oprofile.so"
#AGENT="-agentpath:/opt/jprofiler/jprofiler71/bin/linux-x64/libjprofilerti.so=port=8849,nowait"
#AGENT="-agentpath:/opt/yjp-10.0.4/bin/linux-x86-64/libyjpagent.so=disablestacktelemetry,disableexceptiontelemetry,builtinprobes=none,delay=10000"

#MAVEN_OPTS="$MAVEN_OPTS -Dlog4j.configuration=file:~/log4j.xml"
#MAVEN_OPTS="$MAVEN_OPTS -XX:+PrintFlagsFinal"
#MAVEN_OPTS="$MAVEN_OPTS -verbose:gc -Xloggc:~/gc.log "
#MAVEN_OPTS="$MAVEN_OPTS -XX:+UseConcMarkSweepGC"
#MAVEN_OPTS="$MAVEN_OPTS -XX:PrintCMSStatistics=1 -XX:+PrintCMSInitiationStatistics"
#MAVEN_OPTS="$MAVEN_OPTS -XX:+PrintCompilation "

MAVEN_OPTS="$AGENT $MAVEN_OPTS"

echo "---- Starting benchmark ---"
echo ""
echo "  Using test configuration: $CFG"
echo ""
echo "  Using JVM options: $MAVEN_OPTS"
echo ""
echo "  Please standby ... "
echo ""
echo ""

#versions="infinispan-5.1.CR infinispan-5.1.SNAPSHOT"
#versions="infinispan-5.0 infinispan-5.1.CR infinispan-5.1.SNAPSHOT"
versions="infinispan-5.1.SNAPSHOT"
#versions="infinispan-5.0"
#versions="infinispan-5.1.CR"
for inf in $versions; do
  cd $inf
   #perf stat -e LLC-loads -e LLC-load-misses -e LLC-stores -e LLC-store-misses -e LLC-prefetches -e LLC-prefetch-misses mvn -q -o install -DskipTests=true -PClusteredTest $CFG
   mvn -q -o install -DskipTests=true -PClusteredTest $CFG
  cd ..
done
echo ""
echo "   Done!"

