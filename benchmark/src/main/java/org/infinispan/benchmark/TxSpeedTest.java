package org.infinispan.benchmark;

import org.infinispan.AdvancedCache;
import org.infinispan.config.Configuration;
import org.infinispan.config.GlobalConfiguration;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.transaction.lookup.DummyTransactionManagerLookup;
import org.infinispan.transaction.lookup.JBossStandaloneJTAManagerLookup;
import org.infinispan.util.Util;
import org.infinispan.util.concurrent.IsolationLevel;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * // TODO: Document this
 *
 * @author Manik Surtani
 * @since 5.1
 */
public class TxSpeedTest {
   private static final int LOOP_SIZE=1000;
   final static int payloadSize = 10240; // 10k
   private static final List<String> keys;
   private static final Random r = new Random();
   static {
      keys = new ArrayList<String>(1000);
      for (int i=0; i<1000; i++) keys.add("KEY-" + i);
   }


   public static void main(String[] args) {
      System.setProperty("log4j.configuration", "file:///Users/manik/etc/log4j.xml");
      System.setProperty("jgroups.bind_addr", "127.0.0.1");
      System.setProperty("java.net.preferIPv4Stack", "true");
      Configuration c = new Configuration();
      // A simple config...
      c.setCacheMode(Configuration.CacheMode.REPL_SYNC);
      c.setIsolationLevel(IsolationLevel.REPEATABLE_READ);
      c.setTransactionManagerLookupClass(JBossStandaloneJTAManagerLookup.class.getName());
      c.setTransactionManagerLookupClass(DummyTransactionManagerLookup.class.getName());
      GlobalConfiguration gc = GlobalConfiguration.getClusteredDefault();
      EmbeddedCacheManager ecm1 = new DefaultCacheManager(gc, c);
      EmbeddedCacheManager ecm2 = new DefaultCacheManager(gc.clone(), c.clone());
      System.out.printf("Using Infinispan %s %n", ecm1.getCache().getVersion());

      ecm1.getCache();
      ecm2.getCache();
      AdvancedCache<Object, Object> cache = ecm1.getCache().getAdvancedCache();
      // Warmup loop.
      System.out.println("Warming up JIT");
      for (int i=0; i<100; i++) {
         for (String key: keys) {
            try {
               cache.getTransactionManager().begin();
               cache.getTransactionManager().getTransaction().enlistResource(new XAResourceAdapter());
               cache.put(key, Transactional.generateRandomString(payloadSize));
               cache.getTransactionManager().commit();
            } catch (Exception e) {
               e.printStackTrace();
            }
         }
      }

      System.out.println("Resetting cache");
      cache.clear();
      System.out.println("Starting microbenchmark");
      long l = System.nanoTime();
      for (int i=0; i<LOOP_SIZE; i++) {
         for (String key: keys) {
            try {
               cache.getTransactionManager().begin();
               cache.getTransactionManager().getTransaction().enlistResource(new XAResourceAdapter());
               cache.put(key, Transactional.generateRandomString(payloadSize));
               cache.getTransactionManager().commit();
            } catch (Exception e) {
               e.printStackTrace();
            }
         }
      }
      long nanos = System.nanoTime() - l;

      System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc();
      System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc();
      System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc();
      System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc();
      System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc();

      long mem0 = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

      System.out.printf("   Did %s PUTs in %s and consumed %s of memory.%n%n", LOOP_SIZE * keys.size(), Util.prettyPrintTime(nanos, TimeUnit.NANOSECONDS), format(mem0));
   }

   private static final String format(long bytes) {
      double val = bytes;
      int mag = 0;
      while (val > 1024) {
         val = val / 1024;
         mag ++;
      }

      DecimalFormat twoDForm = new DecimalFormat("#.##");
      String formatted = twoDForm.format(val);
      switch (mag) {
         case 0:
            return formatted + " bytes";
         case 1:
            return formatted + " kb";
         case 2:
            return formatted + " Mb";
         case 3:
            return formatted + " Gb";
         default:
            return "WTF?";
      }
   }

}
