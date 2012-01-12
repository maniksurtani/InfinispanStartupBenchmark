package org.infinispan.benchmark;

import com.arjuna.ats.arjuna.common.arjPropertyManager;
import org.infinispan.Cache;
import org.infinispan.config.Configuration;
import org.infinispan.config.GlobalConfiguration;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.transaction.lookup.JBossStandaloneJTAManagerLookup;
import org.infinispan.util.Util;

import javax.transaction.TransactionManager;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class Transactional {
   // ******* CONSTANTS *******
   final static int PAYLOAD_SIZE = 1024; // 10k
   final static int NUM_KEYS = 100;
   final static boolean USE_TX = true;
   static final List<String> KEYS_W1;
   static final List<String> KEYS_W2;
   static final List<String> KEYS_R;
   static final Random RANDOM = new Random();
   static final int WRITE_PERCENTAGE = 10;
   static final int WARMUP_LOOPS = 10000;
   static final int BENCHMARK_LOOPS = 100000;
   static final int NUM_THREADS = 50;

   private AtomicLong numWrites = new AtomicLong(0);
   private AtomicLong numReads = new AtomicLong(0);

   static {
      arjPropertyManager.getCoordinatorEnvironmentBean().setCommunicationStore(com.arjuna.ats.internal.arjuna.objectstore.VolatileStore.class.getName());
      arjPropertyManager.getObjectStoreEnvironmentBean().setObjectStoreType(com.arjuna.ats.internal.arjuna.objectstore.VolatileStore.class.getName());

      System.setProperty("jgroups.bind_addr", "127.0.0.1");
      System.setProperty("java.net.preferIPv4Stack", "true");

      KEYS_W1 = new ArrayList<String>(NUM_KEYS);
      KEYS_W2 = new ArrayList<String>(NUM_KEYS);
      KEYS_R = new ArrayList<String>(NUM_KEYS * 2);
      
      for (int i = 0; i < NUM_KEYS; i++) {
         KEYS_W1.add("KEY-N1-" + i);
         KEYS_W2.add("KEY-N2-" + i);
         KEYS_R.add("KEY-N1-" + i);
         KEYS_R.add("KEY-N2-" + i);

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
               .locking().lockAcquisitionTimeout(60000L)
               .transaction()
               .transactionManagerLookup(new JBossStandaloneJTAManagerLookup())
               .clustering().mode(org.infinispan.config.Configuration.CacheMode.REPL_SYNC)
               .stateRetrieval().fetchInMemoryState(false);
      } else {
         cfg.fluent()
               .locking().lockAcquisitionTimeout(60000L)
               .clustering().mode(org.infinispan.config.Configuration.CacheMode.REPL_SYNC)
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

         warmup();

         // Now the benchmark
         benchmark();
      } finally {
         ecm1.stop();
         ecm2.stop();
      }
   }

   private enum Mode {READ, WRITE}

   private void benchmark() throws InterruptedException {
      final CountDownLatch startSignal = new CountDownLatch(1);
      ExecutorService e = Executors.newFixedThreadPool(NUM_THREADS);

      List<Mode> work = new ArrayList<Mode>(BENCHMARK_LOOPS);
      int writeCount = (int) (((double) WRITE_PERCENTAGE/100) * BENCHMARK_LOOPS);
      for (int i=0; i<writeCount; i++) work.add(Mode.WRITE);
      for (int i=0; i<BENCHMARK_LOOPS - writeCount; i++) work.add(Mode.READ);

      for (int i = 0; i < work.size(); i++) {
         if (work.remove(RANDOM.nextInt(work.size() - 1)) == Mode.READ)
            e.submit(new Reader(RANDOM.nextBoolean() ? c1 : c2, i, startSignal, false));
         else {
            boolean useC1 = RANDOM.nextBoolean();
            e.submit(new Writer(useC1 ? c1 : c2, i, startSignal, false, useC1 ? KEYS_W1 : KEYS_W2));
         }
      }
      long start = System.nanoTime();
      startSignal.countDown();
      e.shutdown();
      e.awaitTermination(10, TimeUnit.MINUTES);
      long duration = System.nanoTime() - start;
      System.out.printf("Done %s " + (USE_TX ? "transactional " : "") + "operations in %s using %s%n", BENCHMARK_LOOPS, Util.prettyPrintTime(duration, TimeUnit.NANOSECONDS), c1.getVersion());
      System.out.printf("  %s reads and %s writes%n", numReads.get(), numWrites.get());
   }

   private void warmup() throws InterruptedException {
      System.out.printf("Starting %s warmup loops using %s threads%n", WARMUP_LOOPS, NUM_THREADS);
      ExecutorService e = Executors.newFixedThreadPool(NUM_THREADS);
      final CountDownLatch startSignal = new CountDownLatch(1);

      // Warmup
      for (int i = 0; i < WARMUP_LOOPS / 4; i++) {
         e.submit(new Writer(c1, i, startSignal, true, KEYS_W1));
         e.submit(new Reader(c1, i, startSignal, true));
         e.submit(new Writer(c2, i, startSignal, true, KEYS_W2));
         e.submit(new Reader(c2, i, startSignal, true));
      }

      startSignal.countDown();
      e.shutdown();
      if (!e.awaitTermination(10, TimeUnit.MINUTES)) System.out.println("STALE WARMUP TASKS!!");
      System.out.println("Warmup complete.");
   }

   public static String generateRandomString(int size) {
      // each char is 2 bytes
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < size / 2; i++) sb.append((char) (64 + RANDOM.nextInt(26)));
      return sb.toString();
   }

   private abstract class Worker implements Callable<Void> {
      final int idx;
      final CountDownLatch startSignal;
      final Cache<String, String> cache;
      final TransactionManager tm;
      final boolean warmup;

      private Worker(Cache<String, String> cache, int idx, CountDownLatch startSignal, boolean warmup) {
         this.idx = idx;
         this.startSignal = startSignal;
         this.cache = cache;
         this.tm = cache.getAdvancedCache().getTransactionManager();
         this.warmup = warmup;
      }

      @Override
      public Void call() throws Exception {
         if (!warmup && idx % 1000 == 0) System.out.println(idx + " calls processed");
         startSignal.await();
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
         return null;
      }

      protected abstract void doWork();
   }

   private class Writer extends Worker {
      private final String payload = generateRandomString(PAYLOAD_SIZE);
      private final List<String> keys;
      private Writer(Cache<String, String> cache, int idx, CountDownLatch startSignal, boolean warmup, List<String> keys) {
         super(cache, idx, startSignal, warmup);
         this.keys = keys;
         if (!warmup) numWrites.getAndIncrement();
      }

      protected void doWork() {
         cache.put(keys.get(RANDOM.nextInt(keys.size())), payload);
      }
   }

   private class Reader extends Worker {

      private Reader(Cache<String, String> cache, int idx, CountDownLatch startSignal, boolean warmup) {
         super(cache, idx, startSignal, warmup);
         if (!warmup) numReads.getAndIncrement();
      }

      protected void doWork() {
         cache.get(KEYS_R.get(RANDOM.nextInt(KEYS_R.size())));
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