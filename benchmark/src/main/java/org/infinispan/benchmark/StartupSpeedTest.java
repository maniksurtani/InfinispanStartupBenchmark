package org.infinispan.benchmark;

import org.infinispan.config.Configuration;
import org.infinispan.config.GlobalConfiguration;
import org.infinispan.eviction.EvictionStrategy;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.transaction.lookup.DummyTransactionManagerLookup;
import org.infinispan.util.Util;
import org.infinispan.util.concurrent.IsolationLevel;

import java.text.DecimalFormat;
import java.util.concurrent.TimeUnit;

public class StartupSpeedTest {
   private static final boolean WITH_JMX = Boolean.getBoolean("jmx");
   private static final int LOOP_SIZE=2000;

   public static void main(String[] args) {
      System.setProperty("log4j.configuration", "file:///Users/manik/etc/log4j.xml");
      Configuration c = new Configuration();
      // A simple config... 
      c.setCacheMode(Configuration.CacheMode.LOCAL);
      c.setEvictionMaxEntries(100);
      c.setEvictionStrategy(EvictionStrategy.LIRS);
      c.setIsolationLevel(IsolationLevel.REPEATABLE_READ);
      c.setTransactionManagerLookupClass(DummyTransactionManagerLookup.class.getName());
      c.setExposeJmxStatistics(WITH_JMX);
      GlobalConfiguration gc = GlobalConfiguration.getNonClusteredDefault();
      gc.setExposeGlobalJmxStatistics(WITH_JMX);
      EmbeddedCacheManager ecm = new DefaultCacheManager(gc, c);
      System.out.printf("Using Infinispan %s (JMX enabled? %s) %n", ecm.getCache().getVersion(), WITH_JMX);

      // Warmup loop.
      for (int i=0; i<LOOP_SIZE; i++) {
         ecm.getCache("Cache-" + i);
      }
      ecm.stop();

      ecm = new DefaultCacheManager(c);
      long l = System.nanoTime();
      for (int i=0; i<LOOP_SIZE; i++) {
         ecm.getCache("Cache-" + i);
      }
      long nanos = System.nanoTime() - l;
      
      System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc();
      System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc();
      System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc();
      System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc();
      System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc();

      long mem0 = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
      
      System.out.printf("   Created %s caches in %s and consumed %s of memory.%n%n", LOOP_SIZE, Util.prettyPrintTime(nanos, TimeUnit.NANOSECONDS), format(mem0));
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
