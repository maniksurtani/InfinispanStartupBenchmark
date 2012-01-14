package org.infinispan.benchmark;

import org.infinispan.Cache;
import org.infinispan.config.Configuration;
import org.infinispan.config.GlobalConfiguration;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.transaction.lookup.DummyTransactionManagerLookup;
import org.infinispan.util.Util;

import javax.transaction.TransactionManager;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
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
   private static final int NUM_KEYS = Integer.getInteger("bench.numkeys", 100);
   private static final boolean USE_TX = Boolean.getBoolean("bench.transactional");
   private static final Random RANDOM = new Random(Long.getLong("bench.randomSeed", 173)); //pick a number, needs to be the same for all benchmarked versions!
   private static final int READER_THREADS = Integer.getInteger("bench.readerThreads", 100);
   private static final int WRITER_THREADS = Integer.getInteger("bench.writerThreads", 70);
   private static final int BENCHMARK_LOOPS = Integer.getInteger("bench.loops", 1000000);

   private static final int NUM_THREADS = READER_THREADS + WRITER_THREADS;
   static final String[] KEYS_W1 = new String[NUM_KEYS];
   static final String[] KEYS_W2 = new String[NUM_KEYS];
   static final String[] KEYS_R = new String[NUM_KEYS*2];

   private static final AtomicLong numWrites = new AtomicLong(0);
   private static final AtomicLong numReads = new AtomicLong(0);
   private static final AtomicBoolean quitWorkers = new AtomicBoolean(false);

   static {
      System.setProperty("jgroups.bind_addr", "127.0.0.1");
      System.setProperty("java.net.preferIPv4Stack", "true");

      for (int i = 0; i < NUM_KEYS; i++) {
         KEYS_W1[i] = "KEY-N1-" + i;
         KEYS_W2[i] = "KEY-N2-" + i;
         KEYS_R[i] = "KEY-N1-" + i;
      }
      for (int i = NUM_KEYS; i < NUM_KEYS*2; i++) {
         KEYS_R[i] = "KEY-N2-" + i;
      }
   }

   EmbeddedCacheManager ecm1;
   EmbeddedCacheManager ecm2;
   Cache<String, String> c1;
   Cache<String, String> c2;

   public static void main(String[] args) throws InterruptedException {
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
               .clustering().mode(org.infinispan.config.Configuration.CacheMode.REPL_SYNC)
               .sync().replTimeout(60000L)
               .stateRetrieval().fetchInMemoryState(false);
      } else {
         cfg.fluent()
               .locking().lockAcquisitionTimeout(60000L).useLockStriping(false)
               .concurrencyLevel(NUM_THREADS * 4)
               .clustering().mode(org.infinispan.config.Configuration.CacheMode.REPL_SYNC)
               .sync().replTimeout(60000L)
               .stateRetrieval().fetchInMemoryState(false);
      }

      ecm1 = new DefaultCacheManager(GlobalConfiguration.getClusteredDefault(), cfg);
      ecm2 = new DefaultCacheManager(GlobalConfiguration.getClusteredDefault(), cfg);

      try {
         c1 = ecm1.getCache();
         c2 = ecm2.getCache();

         while (ecm1.getMembers().size() != 2) Thread.sleep(100);

         // populate cache
         for (String k : KEYS_W1) c1.put(k, generateRandomString(PAYLOAD_SIZE));
         for (String k : KEYS_W2) c1.put(k, generateRandomString(PAYLOAD_SIZE));

         // Now the benchmark
         benchmark();
      } finally {
         ecm1.stop();
         ecm2.stop();
      }
   }

   private void benchmark() throws InterruptedException {
      final CountDownLatch startSignal = new CountDownLatch(1);
      ExecutorService e = Executors.newFixedThreadPool(NUM_THREADS);

      for (int i = 0; i < WRITER_THREADS; i++) {
         // Add a writer
         boolean useC1 = RANDOM.nextBoolean();
         e.submit(new Writer(useC1 ? c1 : c2, startSignal, useC1 ? KEYS_W1 : KEYS_W2));
      }
      for (int i = 0; i < READER_THREADS; i++) {
       //Add a reader
         boolean useC1 = RANDOM.nextBoolean();
         e.submit(new Reader(useC1 ? c1 : c2, startSignal));
      }

      startSignal.countDown();
      e.shutdown();
      //warmup time, leave the workers alone for some 10 seconds:
      Thread.sleep(10000L);
      //now start measuring:
      numReads.set(0);
      numWrites.set(0);
      long start = System.nanoTime();
      e.awaitTermination(12, TimeUnit.HOURS);
      long duration = System.nanoTime() - start;
      long reads = numReads.get();
      long writes = numWrites.get();
      if ( reads+writes == 0) {
         System.out.println("Finished too soon: all work finished before the warmup period was terminated; nothing left to do during the benchmark phase! set an higher number of loops.");
      }
      else {
         System.out.printf("Done %s " + (USE_TX ? "transactional " : "") + "operations in %s using %s%n", reads + writes, Util.prettyPrintTime(duration, TimeUnit.NANOSECONDS), c1.getVersion());
         System.out.printf("  %s reads and %s writes%n", reads, writes);
         System.out.printf("  Reads / second: %s%n", (reads * 1000 * 1000 * 1000) / duration );
         System.out.printf("  Writes/ second: %s%n", (writes * 1000 * 1000 * 1000) / duration );
      }
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
         if (writes % 100000 == 0) System.out.println(writes + " write operations processed");
      }
   }

   private static final class Reader extends Worker {

      private Reader(Cache<String, String> cache, CountDownLatch startSignal) {
         super(cache, startSignal);
      }

      protected final void doWork() {
         cache.get(KEYS_R[RANDOM.nextInt(KEYS_R.length)]);
         long reads = numReads.incrementAndGet();
         if (reads % 1000000 == 0) System.out.println(reads + " read operations processed");
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
