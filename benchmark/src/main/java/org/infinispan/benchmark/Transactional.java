package org.infinispan.benchmark;

import org.infinispan.Cache;
import org.infinispan.Version;
import org.infinispan.config.Configuration;
import org.infinispan.config.Configuration.CacheMode;
import org.infinispan.config.FluentConfiguration.ClusteringConfig;
import org.infinispan.config.GlobalConfiguration;
import org.infinispan.eviction.EvictionStrategy;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.infinispan.transaction.lookup.DummyTransactionManagerLookup;
import org.infinispan.util.Util;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import javax.transaction.TransactionManager;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.text.NumberFormat;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class Transactional {

   // ******* CONSTANTS *******
   private static final int PAYLOAD_SIZE = Integer.getInteger("bench.payloadsize", 10240);
   private static final int NODES = Integer.getInteger("bench.nodes", 8);
   private static final int NUM_KEYS = Integer.getInteger("bench.numkeys", 500);
   private static final boolean USE_TX = Boolean.getBoolean("bench.transactional");
   private static final boolean USE_DISTRIBUTION = Boolean.getBoolean("bench.dist");
   private static final boolean L1_ENABLED = Boolean.getBoolean("bench.l1Enabled");
   private static final boolean EXTRA_QUIET = Boolean.getBoolean("bench.extraQuiet");
   private static final int NUM_VNODES = Integer.getInteger("bench.vnodes", 48);
   private static final int WARMUP_MINUTES = Integer.getInteger("bench.warmupMinutes", 20);
   private static final int TESTTIME_MINUTES = Integer.getInteger("bench.durationMinutes", 40);
   private static final Random RANDOM = new Random(Long.getLong("bench.randomSeed", 173)); //pick a number, needs to be the same for all benchmarked versions!
   private static final int READER_THREADS = Integer.getInteger("bench.readerThreads", 100);
   private static final int WRITER_THREADS = Integer.getInteger("bench.writerThreads", 70);
   private static final String JGROUPS_CONF = System.getProperty("bench.jgroups_conf", "bench-jgroups.xml");

   private static final int NUM_THREADS = READER_THREADS + WRITER_THREADS;
   private static final String[] KEYS_R = new String[NUM_KEYS*2];
   private static final String[][] KEYS_W_PERNODE = new String[NODES][NUM_KEYS];

   private static final AtomicLong numWrites = new AtomicLong(0);
   private static final AtomicLong numReads = new AtomicLong(0);
   private static final AtomicBoolean quitWorkers = new AtomicBoolean(false);
   private final CountDownLatch endSignal = new CountDownLatch(1);

   private static final Log log = LogFactory.getLog(Transactional.class);
   private static final boolean trace = log.isTraceEnabled();
   
   private static final Timer timer = new Timer( "TestProgressMonitor", true );
   private static final NumberFormat NF = NumberFormat.getInstance();//Not threadsafe - one thread only using it
   
   private static void printConfiguration() {
      System.out.println("Payload size:\t" + PAYLOAD_SIZE);
      System.out.println("Number of nodes:\t" + NODES);
      System.out.println("Using transactions:\t" + USE_TX);
      System.out.println("Using distribution:\t" + USE_DISTRIBUTION);
      System.out.println("Number of Virtual nodes:\t" + NUM_VNODES);
      System.out.println("Number of Writing threads:\t" + WRITER_THREADS);
      System.out.println("Number of Reading threads:\t" + READER_THREADS);
   }

   static {
      System.setProperty("jgroups.bind_addr", "127.0.0.1");
      System.setProperty("java.net.preferIPv4Stack", "true");

      for (int i = 0; i < NUM_KEYS; i++) {
         final String root = "KEY-N"+i+"-NODE";
         for (int node = 0; node < NODES; node++) {
            KEYS_W_PERNODE[node][i] = root + node;
         }
         KEYS_R[i] = "KEY-N1-" + i;
      }
      for (int i = NUM_KEYS; i < NUM_KEYS*2; i++) {
         KEYS_R[i] = "KEY-N2-" + i;
      }
   }

   public static void main(String[] args) throws InterruptedException {
      //print out current Infinispan version:
      if (!EXTRA_QUIET) {
         org.infinispan.Version.main(args);
         printConfiguration();
      }
      new Transactional().start();
   }

   public void start() throws InterruptedException {
      // Using deprecated config API to be compatible with Infinispan 5.1 as well as 5.0
      GlobalConfiguration gc = new GlobalConfiguration();
      gc.setTransportClass(JGroupsTransport.class.getName());
      gc.getTransportProperties().setProperty("configurationFile", JGROUPS_CONF);
      
      Configuration cfg = new Configuration();

      if (USE_TX) {
         ClusteringConfig mode = cfg.fluent()
               .locking().lockAcquisitionTimeout(60000L).useLockStriping(false)
               .concurrencyLevel(NUM_THREADS * 4)
               .eviction().strategy(EvictionStrategy.NONE)
               .transaction()
               .transactionManagerLookup(new DummyTransactionManagerLookup())
               .syncCommitPhase(false).syncRollbackPhase(false)
               .clustering();
         applyClusteringOptions(mode);
      } else {
         ClusteringConfig mode =cfg.fluent()
               .locking().lockAcquisitionTimeout(60000L).useLockStriping(false)
               .concurrencyLevel(NUM_THREADS * 4)
               .eviction().strategy(EvictionStrategy.NONE)
               .clustering().mode(USE_DISTRIBUTION ? CacheMode.DIST_SYNC : CacheMode.REPL_SYNC);
         applyClusteringOptions(mode);
      }
      DefaultCacheManager[] cms = new DefaultCacheManager[NODES];
      for (int i=0; i<NODES; i++) {
         cms[i] = new DefaultCacheManager(gc, cfg);
      }

      try {
         Cache[] caches = new Cache[NODES];
         for (int i=0; i<NODES; i++) {
            caches[i] = cms[i].getCache();
         }

         while (cms[0].getMembers().size() != NODES) Thread.sleep(100);

         // populate cache
         for (int node=0; node<NODES; node++) {
            final Cache cache = caches[node];
            for (int i = 0; i < NUM_KEYS; i++) {
               cache.put(KEYS_W_PERNODE[node][i], generateRandomString(PAYLOAD_SIZE));
            }
         }

         // Now the benchmark
         benchmark(caches);
      } finally {
         for (int i=0; i<NODES; i++) {
            DefaultCacheManager cacheManager = cms[i];
            if (cacheManager != null)
               cacheManager.stop();
         }
      }
   }

   private void benchmark(Cache[] caches) {
      final CountDownLatch startSignal = new CountDownLatch(1);
      ExecutorService e = Executors.newFixedThreadPool(NUM_THREADS);

      for (int i = 0; i < WRITER_THREADS; i++) {
         // Add a writer
         int nodeIndex = RANDOM.nextInt(NODES);
         e.submit(new Writer(caches[nodeIndex], startSignal, KEYS_W_PERNODE[nodeIndex]));
      }
      for (int i = 0; i < READER_THREADS; i++) {
         //Add a reader
         int nodeIndex = RANDOM.nextInt(NODES);
         e.submit(new Reader(caches[nodeIndex], startSignal));
      }

      startSignal.countDown();
      e.shutdown();
      System.out.println("STARTING");

      timer.schedule( new ProgressTask(), 10000, 10000 );
      try {
         endSignal.await();
      } catch (InterruptedException e1) {
         //main thread quitting, no need to reset interruption
      }
      quitWorkers.set(true);
   }

   private void printStats(long duration, long reads, long writes) {
      System.out.printf(Version.VERSION + ": done %s " + (USE_TX ? "transactional " : "") + "operations in %s", NF.format(reads + writes), Util.prettyPrintTime(duration, TimeUnit.NANOSECONDS));
      System.out.printf("  %s reads and %s writes%n", NF.format(reads), NF.format(writes));
      System.out.printf("  Reads / second: %s%n", NF.format((reads * 1000 * 1000 * 1000) / duration ));
      System.out.printf("  Writes/ second: %s%n", NF.format((writes * 1000 * 1000 * 1000) / duration ));
   }

   public static String generateRandomString(int size) {
      // each char is 2 bytes
      StringBuilder sb = new StringBuilder(size);
      for (int i = 0; i < size / 2; i++) sb.append((char) (64 + RANDOM.nextInt(26)));
      return sb.toString();
   }

   private static abstract class Worker implements Callable<Void> {
      final CountDownLatch startSignal;
      final Cache<String, String> cache;
      final TransactionManager tm;

      private Worker(Cache<String, String> cache, CountDownLatch startSignal) {
         this.startSignal = startSignal;
         this.cache = cache;
         this.tm = cache.getAdvancedCache().getTransactionManager();
      }

      @Override
      public final Void call() throws Exception {
         startSignal.await();
         try {
            do {
               if (USE_TX) {
                  tm.begin();
                  // Force 2PC
                  tm.getTransaction().enlistResource(new XAResourceAdapter());
               }
               try {
                  doWork();
                  if (USE_TX) tm.commit();
               } catch (Exception e) {
                  try {
                     if (USE_TX) tm.rollback();
                     log.error(e);
                  }
                  finally {
                     boolean newErrorToReport = quitWorkers.compareAndSet(false, true);
                     if (newErrorToReport) System.out.println("Error - terminating");
                  }
               }
            } while (! quitWorkers.get());
         }
         catch (Exception e) {
            log.error(e);
         }
         finally {
            //when one worked finishes, the others should quite as well to keep the measured situation constant.
            quitWorkers.set(true);
         }
         return null;
      }

      protected abstract void doWork();
   }

   private static final class Writer extends Worker {
      private final String payload = generateRandomString(PAYLOAD_SIZE);
      private final String[] keys;
      private Writer(Cache<String, String> cache, CountDownLatch startSignal, String[] keys) {
         super(cache, startSignal);
         this.keys = keys;
      }

      protected final void doWork() {
         cache.put(keys[RANDOM.nextInt(keys.length)], payload);
         long writes = numWrites.incrementAndGet();
         if (trace) {
            log.trace(writes + " write operations performed");
         }
      }
   }

   private static final class Reader extends Worker {

      private Reader(Cache<String, String> cache, CountDownLatch startSignal) {
         super(cache, startSignal);
      }

      protected final void doWork() {
         cache.get(KEYS_R[RANDOM.nextInt(KEYS_R.length)]);
         long reads = numReads.incrementAndGet();
         if (trace) {
            log.trace(reads + " read operations performed");
         }
      }
   }

   private static void applyClusteringOptions(ClusteringConfig mode) {
      mode.mode(USE_DISTRIBUTION ? CacheMode.DIST_SYNC : CacheMode.REPL_SYNC);
      if (! L1_ENABLED && USE_DISTRIBUTION)
         mode.l1().disable();
      if (USE_DISTRIBUTION) {
         mode.hash().numVirtualNodes(NUM_VNODES);
      }
      mode.sync().replTimeout(60000L)
         .stateRetrieval().fetchInMemoryState(false);
   }

   private class ProgressTask extends TimerTask {

      private boolean warmup = true;
      private long startTime = System.nanoTime(); // starts with an approximation - it's warmup anyway
      private int loop = 0;
      private long lastSeenReads = 0;
      private long lastSeenWrites = 0;

      public void run() {
         loop++;
         final long duration = System.nanoTime() - startTime;
         final long reads = numReads.get();
         final long writes = numWrites.get();
         if ( (lastSeenReads!=0 && lastSeenReads==reads) || (lastSeenWrites!=0 && lastSeenWrites==writes) ) {
            System.out.println("No progress made! aborting");
            endSignal.countDown();
         }
         else {
            lastSeenReads = reads;
            lastSeenWrites = writes;
         }
         if (!EXTRA_QUIET)
            printStats(duration, reads, writes);
         if (warmup && (loop / 6) >= WARMUP_MINUTES) {
            System.out.println("WARMUP FINISHED - RESETTING STATS");
            startTime = System.nanoTime();
            numReads.set(0);
            numWrites.set(0);
            warmup = false;
            loop=0;
         }
         else if (!warmup && (loop / 6) >= TESTTIME_MINUTES) {
            System.out.println("TEST FINISHED");
            endSignal.countDown();
            printConfiguration();
            printStats(duration, reads, writes);
         }
      }

   }

}

class XAResourceAdapter implements XAResource {
   private static final Xid[] XIDS = new Xid[0];

   public void commit(Xid xid, boolean b) throws XAException {
      // no-op
   }

   public void end(Xid xid, int i) throws XAException {
      // no-op
   }

   public void forget(Xid xid) throws XAException {
      // no-op
   }

   public int getTransactionTimeout() throws XAException {
      return 0;
   }

   public boolean isSameRM(XAResource xaResource) throws XAException {
      return false;
   }

   public int prepare(Xid xid) throws XAException {
      return XA_OK;
   }

   public Xid[] recover(int i) throws XAException {
      return XIDS;
   }

   public void rollback(Xid xid) throws XAException {
      // no-op
   }

   public boolean setTransactionTimeout(int i) throws XAException {
      return false;
   }

   public void start(Xid xid, int i) throws XAException {
      // no-op
   }
}
