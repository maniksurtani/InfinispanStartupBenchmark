package org.infinispan.benchmark;

import org.infinispan.Cache;
import org.infinispan.Version;
import org.infinispan.config.Configuration;
import org.infinispan.config.Configuration.CacheMode;
import org.infinispan.config.GlobalConfiguration;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.transaction.lookup.DummyTransactionManagerLookup;
import org.infinispan.util.Util;

import javax.transaction.TransactionManager;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.text.NumberFormat;
import java.util.Random;
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
   private static final int NODES = Integer.getInteger("bench.nodes", 4);
   private static final int NUM_KEYS = Integer.getInteger("bench.numkeys", 100);
   private static final boolean USE_TX = Boolean.getBoolean("bench.transactional");
   private static final boolean USE_DISTRIBUTION = Boolean.getBoolean("bench.dist");
   private static final long WARMUP_MILLLIS = Long.getLong("bench.warmupMilliseconds", 20000L);
   private static final Random RANDOM = new Random(Long.getLong("bench.randomSeed", 173)); //pick a number, needs to be the same for all benchmarked versions!
   private static final int READER_THREADS = Integer.getInteger("bench.readerThreads", 100);
   private static final int WRITER_THREADS = Integer.getInteger("bench.writerThreads", 70);
   private static final int BENCHMARK_LOOPS = Integer.getInteger("bench.loops", Integer.MAX_VALUE);

   private static final int NUM_THREADS = READER_THREADS + WRITER_THREADS;
   private static final String[] KEYS_R = new String[NUM_KEYS*2];
   private static final String[][] KEYS_W_PERNODE = new String[NODES][NUM_KEYS];

   private static final AtomicLong numWrites = new AtomicLong(0);
   private static final AtomicLong numReads = new AtomicLong(0);
   private static final AtomicBoolean quitWorkers = new AtomicBoolean(false);

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
      org.infinispan.Version.main(args);
      new Transactional().start();
   }

   public void start() throws InterruptedException {
      // Using deprecated config API to be compatible with Infinispan 5.1 as well as 5.0
      Configuration cfg = new Configuration();

      if (USE_TX) {
         cfg.fluent()
               .locking().lockAcquisitionTimeout(60000L).useLockStriping(false)
               .concurrencyLevel(NUM_THREADS * 4)
               .transaction()
               .transactionManagerLookup(new DummyTransactionManagerLookup())
               .syncCommitPhase(false).syncRollbackPhase(false)
               .clustering().mode(USE_DISTRIBUTION ? CacheMode.DIST_SYNC : CacheMode.REPL_SYNC)
               .sync().replTimeout(60000L)
               .stateRetrieval().fetchInMemoryState(false);
      } else {
         cfg.fluent()
               .locking().lockAcquisitionTimeout(60000L).useLockStriping(false)
               .concurrencyLevel(NUM_THREADS * 4)
               .clustering().mode(USE_DISTRIBUTION ? CacheMode.DIST_SYNC : CacheMode.REPL_SYNC)
               .sync().replTimeout(60000L)
               .stateRetrieval().fetchInMemoryState(false);
      }
      DefaultCacheManager[] cms = new DefaultCacheManager[NODES];
      for (int i=0; i<NODES; i++) {
         cms[i] = new DefaultCacheManager(GlobalConfiguration.getClusteredDefault(), cfg);
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
            if (cacheManager!=null)
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
      //warmup time, leave the workers alone for some time:
      try {
         Thread.sleep(WARMUP_MILLLIS);
      } catch (InterruptedException e1) {
         System.out.println("Interrupted during warmup. Fast quit..");
         quitWorkers.set(true);
         return;
      }
      //now start measuring:
      numReads.set(0);
      numWrites.set(0);
      long start = System.nanoTime();
      try {
         e.awaitTermination(12, TimeUnit.HOURS);
      } catch (InterruptedException e1) {
         System.out.println("Interrupted. Early exit..");
         quitWorkers.set(true);
      }
      long duration = System.nanoTime() - start;
      long reads = numReads.get();
      long writes = numWrites.get();
      System.out.println("");
      if ( reads+writes == 0) {
         System.out.println("Finished too soon: all work finished before the warmup period was terminated; nothing left to do during the benchmark phase! set an higher number of loops or a lower warmup time.");
      }
      else {
         NumberFormat NF = NumberFormat.getInstance();
         System.out.printf("Done %s " + (USE_TX ? "transactional " : "") + "operations in %s using %s%n", NF.format(reads + writes), Util.prettyPrintTime(duration, TimeUnit.NANOSECONDS), Version.printVersion());
         System.out.printf("  %s reads and %s writes%n", NF.format(reads), NF.format(writes));
         System.out.printf("  Reads / second: %s%n", NF.format((reads * 1000 * 1000 * 1000) / duration ));
         System.out.printf("  Writes/ second: %s%n", NF.format((writes * 1000 * 1000 * 1000) / duration ));
      }
      System.out.println("");
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
         int loop = 0;
         try {
            do {
               loop++;
               if (USE_TX) {
                  tm.begin();
                  // Force 2PC
                  tm.getTransaction().enlistResource(new XAResourceAdapter());
               }
               try {
                  doWork();
                  if (USE_TX) tm.commit();
               } catch (Exception e) {
                  if (USE_TX) tm.rollback();
               }
            } while (BENCHMARK_LOOPS != loop && ! quitWorkers.get());
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
         if (writes % 100000 == 0) System.out.println(writes + " write operations performed");
      }
   }

   private static final class Reader extends Worker {

      private Reader(Cache<String, String> cache, CountDownLatch startSignal) {
         super(cache, startSignal);
      }

      protected final void doWork() {
         cache.get(KEYS_R[RANDOM.nextInt(KEYS_R.length)]);
         long reads = numReads.incrementAndGet();
         if (reads % 1000000 == 0) System.out.println(reads + " read operations performed");
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
