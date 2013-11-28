/**
 * Copyright The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.client;

import junit.framework.Assert;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.client.HTableMultiplexer.HTableMultiplexerStatus;
import org.apache.hadoop.hbase.ipc.HBaseRPCOptions;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TestHTableMultiplexer {
  final Log LOG = LogFactory.getLog(getClass());
  private final static HBaseTestingUtility TEST_UTIL =
      new HBaseTestingUtility();
  private static byte[] FAMILY = Bytes.toBytes("testFamily");
  private static byte[] QUALIFIER = Bytes.toBytes("testQualifier");
  private static byte[] VALUE1 = Bytes.toBytes("testValue1");
  private static byte[] VALUE2 = Bytes.toBytes("testValue2");
  private static int SLAVES = 3;
  private static int PER_REGIONSERVER_QUEUE_SIZE = 100000;

  /**
   * @throws java.lang.Exception
   */
  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    TEST_UTIL.startMiniCluster(SLAVES);
  }

  /**
   * @throws java.lang.Exception
   */
  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    TEST_UTIL.shutdownMiniCluster();
  }

  @Test
  public void testHTableMultiplexer() throws Exception {
    byte[] TABLE = Bytes.toBytes("testHTableMultiplexer");
    final int NUM_REGIONS = 10;
    final int VERSION = 3;
    List<Put> failedPuts = null;
    boolean success = false;
    
    HTableMultiplexer multiplexer = new HTableMultiplexer(
        TEST_UTIL.getConfiguration(), PER_REGIONSERVER_QUEUE_SIZE);
    HTableMultiplexerStatus status = multiplexer.getHTableMultiplexerStatus();

    HTable ht = TEST_UTIL.createTable(TABLE, new byte[][] { FAMILY }, VERSION,
        Bytes.toBytes("aaaaa"), Bytes.toBytes("zzzzz"), NUM_REGIONS);
    byte[][] startRows = ht.getStartKeys();
    byte[][] endRows = ht.getEndKeys();

    // SinglePut case
    for (int i = 0; i < NUM_REGIONS; i++) {
      Put put = new Put(startRows[i]);
      put.add(FAMILY, QUALIFIER, VALUE1);
      success = multiplexer.put(TABLE, put, HBaseRPCOptions.DEFAULT);
      Assert.assertTrue(success);

      // ensure the buffer has been flushed
      verifyAllBufferedPutsHasFlushed(status);

      // verify that the Get returns the correct result
      Get get = new Get(startRows[i]);
      get.addColumn(FAMILY, QUALIFIER);
      Result r = ht.get(get);
      Assert.assertEquals(0,
          Bytes.compareTo(VALUE1, r.getValue(FAMILY, QUALIFIER)));
    }

    // MultiPut case
    List<Put> multiput = new ArrayList<Put>();
    for (int i = 0; i < NUM_REGIONS; i++) {
      Put put = new Put(endRows[i]);
      put.add(FAMILY, QUALIFIER, VALUE2);
      multiput.add(put);
    }
    failedPuts = multiplexer.put(TABLE, multiput, HBaseRPCOptions.DEFAULT);
    Assert.assertTrue(failedPuts == null);

    // ensure the buffer has been flushed
    verifyAllBufferedPutsHasFlushed(status);

    // verify that the Get returns the correct result
    for (int i = 0; i < NUM_REGIONS; i++) {
      Get get = new Get(endRows[i]);
      get.addColumn(FAMILY, QUALIFIER);
      Result r = ht.get(get);
      Assert.assertEquals(0,
          Bytes.compareTo(VALUE2, r.getValue(FAMILY, QUALIFIER)));
    }
  }

  private void verifyAllBufferedPutsHasFlushed(HTableMultiplexerStatus status) {
    int retries = 5;
    int tries = 0;
    do {
      try {
        Thread.sleep(2 * TEST_UTIL.getConfiguration().getLong(
            "hbase.htablemultiplexer.flush.frequency", 100));
        tries++;
      } catch (InterruptedException e) {
      } // ignore
      status.recalculateCounters();
    } while (status.getTotalBufferedCounter() != 0 && tries != retries);

    Assert.assertEquals("There are still some buffered puts left in the queue",
        0, status.getTotalBufferedCounter());
  }


  /**
   * This test is to verify that the buffered and succeeded counters work as
   * expected.
   *
   * @throws Exception
   */
  @Test
  public void testCounters() throws Exception {
    byte[] TABLE = Bytes.toBytes("testCounters");
    Configuration conf = TEST_UTIL.getConfiguration();
    conf.setLong("hbase.htablemultiplexer.flush.frequency.ms", 100);

    HTableMultiplexer multiplexer =
      new HTableMultiplexer(TEST_UTIL.getConfiguration(),
        100);
    HTableMultiplexerStatus status = multiplexer.getHTableMultiplexerStatus();

    HTable ht = TEST_UTIL.createTable(TABLE, new byte[][] { FAMILY });

    int numPuts = 100;
    boolean bufferWasNonEmptyAtSomePoint = false;
    for (int i = 1; i <= numPuts; i++) {
      byte[] row = Bytes.toBytes("Row" + i);
      byte[] qualifier = Bytes.toBytes("Qualifier" + i);
      byte[] value = Bytes.toBytes("Value" + i);
      Put put = new Put(row);
      put.add(FAMILY, qualifier, value);

      multiplexer.put(TABLE, put, HBaseRPCOptions.DEFAULT);
      status.recalculateCounters();
      if (status.getTotalBufferedCounter() > 0) {
        bufferWasNonEmptyAtSomePoint = true;
      }

      try {
        Thread.sleep(5);
      } catch (Exception e) {}
    }

    // Verify that all the puts got flushed.
    verifyAllBufferedPutsHasFlushed(status);

    // Recalculate the counters, and ensure that:
    // 1. The number of buffered puts was non-zero at some point.
    // 2. The number of buffered puts is zero now.
    // 3. The total number of succeeded puts is equal to the number of puts.
    status.recalculateCounters();
    Assert.assertTrue(bufferWasNonEmptyAtSomePoint);
    Assert.assertEquals(0, status.getTotalBufferedCounter());
    Assert.assertEquals(numPuts, status.getTotalSucccededPutCounter());

    // Check if all the puts went through correctly.
    for (int i = 1; i <= numPuts; i++) {
      byte[] row = Bytes.toBytes("Row" + i);
      byte[] qualifier = Bytes.toBytes("Qualifier" + i);
      byte[] value = Bytes.toBytes("Value" + i);

      // verify that the Get returns the correct result
      Get get = new Get(row);
      get.addColumn(FAMILY, qualifier);
      Result r = ht.get(get);
      Assert.assertEquals(0,
        Bytes.compareTo(value, r.getValue(FAMILY, qualifier)));
    }
  }
  /**
   * This test is to verify that different instances of byte-array with same
   * content as the table names will result in the same HTable instance.
   */
  @Test
  public void testCachedOfHTable() throws Exception {
    Configuration conf = TEST_UTIL.getConfiguration();
    conf.setLong("hbase.htablemultiplexer.flush.frequency.ms", 100);
    HTableMultiplexer multiplexer =
        new HTableMultiplexer(TEST_UTIL.getConfiguration(), 100);

    HTableMultiplexerStatus status = multiplexer.getHTableMultiplexerStatus();
    Assert.assertEquals("storedHTableCount", 0, status.getStoredHTableCount());

    byte[] TABLE = Bytes.toBytes("testCounters");

    byte[] row = Bytes.toBytes("Row" + 1);
    byte[] qualifier = Bytes.toBytes("Qualifier" + 1);
    byte[] value = Bytes.toBytes("Value" + 1);
    Put put = new Put(row);
    put.add(FAMILY, qualifier, value);
    // first put
    multiplexer.put(TABLE, put, HBaseRPCOptions.DEFAULT);
    Assert.assertEquals("storedHTableCount", 1, status.getStoredHTableCount());
    // second put
    byte[] TABLE1 = Arrays.copyOf(TABLE, TABLE.length);
    multiplexer.put(TABLE1, put, HBaseRPCOptions.DEFAULT);
    Assert.assertEquals("storedHTableCount", 1, status.getStoredHTableCount());
  }
}

