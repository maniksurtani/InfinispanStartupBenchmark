package org.infinispan.benchmark;

import org.infinispan.transaction.tm.DummyXid;
import org.infinispan.util.Util;

import java.text.NumberFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * // TODO: Document this
 *
 * @author Manik Surtani
 * @since 5.1
 */
public class XidMicroBench {
   public static void main(String[] args) {
      UUID uuid = UUID.randomUUID();
      int loops = 1000000;
      // warmup
      for (int i=0;i<100000; i++) {
//         DummyXid xid = new DummyXid(uuid);
      }

      Set<DummyXid> set = new HashSet<DummyXid>(loops);

      System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc();
      System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc();
      System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc();
      System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc();
      System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc();


      long nanos = System.nanoTime();
      for (int i=0;i<loops; i++) {
//         set.add(new DummyXid(uuid));
      }
      long time = System.nanoTime() - nanos;

      System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc();
      System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc();
      System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc();
      System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc();
      System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc(); System.gc();

      long mem0 = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

      System.out.printf("Created %s DummyXids in %s and consumed %s memory%n%n", NumberFormat.getInstance().format(loops), Util.prettyPrintTime(time, TimeUnit.NANOSECONDS), StartupSpeedTest.format(mem0));
   }
}
