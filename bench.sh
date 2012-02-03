#!/bin/bash

#CFG="-Dlog4j.configuration=bench-log4j.xml"
CFG="-Dbench.payloadsize=2048 $CFG"
#CFG="-Dbench.vnodes=48 $CFG"
CFG="-Dbench.numkeys=2500 $CFG"
CFG="-Dbench.transactional=true $CFG"
CFG="-Dbench.dist=true $CFG"
#CFG="-Dbench.nodes=1 $CFG" See below
CFG="-Dbench.readerThreads=250 $CFG"
CFG="-Dbench.writerThreads=50 $CFG"
CFG="-Dbench.warmupMinutes=20 $CFG"
CFG="-Dbench.durationMinutes=25 $CFG"
CFG="-Dbench.extraQuiet=true $CFG"

#To use default Infinispan UDP configuration for JGroups (instead of the benchmark included one, bench-jgroups.xml):
CFG="-Dbench.jgroups_conf=jgroups-udp.xml $CFG"

# Pick which benchmark we want to run:
#BENCH="org.infinispan.benchmark.StartupSpeedTest"
BENCH="org.infinispan.benchmark.Transactional"

JAVA_HOME="/usr/lib/jvm/java-1.6.0-openjdk.x86_64"

#Compile Maven step is run without overriding MAVEN_OPTS:
mvn clean install

MAVEN_OPTS=""
MAVEN_OPTS="$MAVEN_OPTS -Xmx2G -Xms2G -XX:MaxPermSize=128M -XX:+HeapDumpOnOutOfMemoryError -Xss512k -XX:HeapDumpPath=/tmp/java_heap"
MAVEN_OPTS="$MAVEN_OPTS -Djava.net.preferIPv4Stack=true -Djgroups.bind_addr=127.0.0.1"
MAVEN_OPTS="$MAVEN_OPTS -Xbatch -server -XX:+UseCompressedOops"
MAVEN_OPTS="$MAVEN_OPTS -XX:+UseLargePages -XX:LargePageSizeInBytes=2m -XX:+AlwaysPreTouch"

# Optionally select a profiling agent:
AGENT=""
#AGENT="-agentpath:/usr/lib64/oprofile/libjvmti_oprofile.so"
#AGENT="-agentpath:/opt/jprofiler/jprofiler71/bin/linux-x64/libjprofilerti.so=port=8849,nowait"
#AGENT="-agentpath:/opt/yjp-10.0.4/bin/linux-x86-64/libyjpagent.so=disablestacktelemetry,disableexceptiontelemetry,builtinprobes=none,delay=10000"

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

#Versions and number of nodes combinations to test:
#versions="5.0.1.FINAL 5.1.0.CR3 5.1.1.FINAL"
versions="5.1.0.CR3 5.1.1.FINAL"
nodenums="1 2 4 8 16"

cd benchmark
for version in $versions; do
   for nodenum in $nodenums; do
      #perf stat -e LLC-loads -e LLC-load-misses -e LLC-stores -e LLC-store-misses -e LLC-prefetches -e LLC-prefetch-misses mvn -q -o install -DskipTests=true -PClusteredTest $CFG
      echo "Running for version $version on $nodenum nodes:"
      time mvn -q install -DskipTests=true -DinfinispanVersion=$version -Dbench.nodes=$nodenum -DbenchName=$BENCH $CFG -P Benchmark
   done
done
cd ..
echo ""
echo "   Done!"

