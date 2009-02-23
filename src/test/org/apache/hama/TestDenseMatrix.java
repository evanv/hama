/**
 * Copyright 2007 The Apache Software Foundation
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
package org.apache.hama;

import java.io.IOException;
import java.util.Iterator;

import junit.extensions.TestSetup;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.io.Writable;
import org.apache.hama.io.DoubleEntry;
import org.apache.hama.util.RandomVariable;
import org.apache.log4j.Logger;

/**
 * Matrix test
 */
public class TestDenseMatrix extends TestCase {
  static final Logger LOG = Logger.getLogger(TestDenseMatrix.class);
  private static int SIZE = 10;
  private static Matrix m1;
  private static Matrix m2;
  private final static String aliase1 = "matrix_aliase_A";
  private final static String aliase2 = "matrix_aliase_B";
  private static HamaConfiguration conf;
  private static HBaseAdmin admin;
  private static HamaAdmin hamaAdmin;

  public static Test suite() {
    TestSetup setup = new TestSetup(new TestSuite(TestDenseMatrix.class)) {
      protected void setUp() throws Exception {
        HCluster hCluster = new HCluster();
        hCluster.setUp();

        conf = hCluster.getConf();
        admin = new HBaseAdmin(conf);
        hamaAdmin = new HamaAdminImpl(conf, admin);

        m1 = DenseMatrix.random(hCluster.getConf(), SIZE, SIZE);
        m2 = DenseMatrix.random(hCluster.getConf(), SIZE, SIZE);
      }

      protected void tearDown() {
        try {
          closeTest();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    };
    return setup;
  }

  public static void closeTest() throws IOException {
    m1.close();
    m2.close();
  }

  public void testEntryAdd() throws IOException {
    double origin = m1.get(1, 1);
    m1.add(1, 1, 0.5);

    assertEquals(m1.get(1, 1), origin + 0.5);
  }

  public void testGet() throws IOException {
    boolean ex = false;
    try {
      m1.get(SIZE + 1, SIZE + 1);
    } catch (ArrayIndexOutOfBoundsException e) {
      ex = true;
    }
    assertTrue(ex);
    assertTrue(m1.get(0, 0) > 0);
  }
  
  /**
   * Column vector test.
   * 
   * @param rand
   * @throws IOException
   */
  public void testGetColumn() throws IOException {
    Vector v = m1.getColumn(0);
    Iterator<Writable> it = v.iterator();
    int x = 0;
    while (it.hasNext()) {
      assertEquals(m1.get(x, 0), ((DoubleEntry) it.next()).getValue());
      x++;
    }
  }

  public void testGetSetAttribute() throws IOException {
    m1.setRowLabel(0, "row1");
    assertEquals(m1.getRowLabel(0), "row1");
    assertEquals(m1.getRowLabel(1), null);

    m1.setColumnLabel(0, "column1");
    assertEquals(m1.getColumnLabel(0), "column1");
    assertEquals(m1.getColumnLabel(1), null);
  }

  public void testSubMatrix() throws IOException {
    SubMatrix a = m1.subMatrix(2, 4, 2, 5); // A : 3 * 4
    for (int i = 0; i < a.getRows(); i++) {
      for (int j = 0; j < a.getColumns(); j++) {
        assertEquals(a.get(i, j), m1.get(i + 2, j + 2));
      }
    }

    SubMatrix b = m2.subMatrix(0, 3, 0, 2); // B : 4 * 3
    SubMatrix c = a.mult(b);

    double[][] C = new double[3][3]; // A * B
    for (int i = 0; i < 3; i++) {
      for (int j = 0; j < 3; j++) {
        for (int k = 0; k < 4; k++) {
          C[i][j] += m1.get(i + 2, k + 2) * m2.get(k, j);
        }
      }
    }

    for (int i = 0; i < 3; i++) {
      for (int j = 0; j < 3; j++) {
        assertEquals(C[i][j], c.get(i, j));
      }
    }
  }

  /**
   * Test matrices addition
   * 
   * @throws IOException
   */
  public void testMatrixAdd() throws IOException {
    Matrix result = m1.add(m2);

    assertEquals(result.getRows(), SIZE);
    assertEquals(result.getColumns(), SIZE);

    for (int i = 0; i < SIZE; i++) {
      for (int j = 0; j < SIZE; j++) {
        assertEquals(result.get(i, j), m1.get(i, j) + m2.get(i, j));
      }
    }
  }

  /**
   * Test matrices multiplication
   * 
   * @throws IOException
   */
  public void testMatrixMult() throws IOException {
    Matrix result = m1.mult(m2);

    assertEquals(result.getRows(), SIZE);
    assertEquals(result.getColumns(), SIZE);

    verifyMultResult(m1, m2, result);
  }

  public void testSetMatrix() throws IOException {
    Matrix a = new DenseMatrix(conf);
    a.set(m1);

    for (int i = 0; i < 5; i++) {
      int x = RandomVariable.randInt(0, 10);
      int y = RandomVariable.randInt(0, 10);
      assertEquals(a.get(x, y), m1.get(x, y));
    }
  }

  public void testSetAlphaMatrix() throws IOException {
    Matrix a = new DenseMatrix(conf);
    a.set(0.5, m1);
    
    for (int i = 0; i < 5; i++) {
      int x = RandomVariable.randInt(0, 10);
      int y = RandomVariable.randInt(0, 10);
      assertEquals(a.get(x, y), (m1.get(x, y) * 0.5));
    }
  }
  
  public void testAddAlphaMatrix() throws IOException {
    double value = m1.get(0, 0) + (m2.get(0, 0) * 0.1);
    Matrix result = m1.add(0.1, m2);
    assertEquals(value, result.get(0, 0));
  }
  
  public void testSetRow() throws IOException {
    Vector v = new DenseVector();
    double[] entries = new double[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 0 };

    for (int i = 0; i < SIZE; i++) {
      v.set(i, entries[i]);
    }

    m1.setRow(SIZE + 1, v);
    Iterator<Writable> it = m1.getRow(SIZE + 1).iterator();

    int i = 0;
    while (it.hasNext()) {
      assertEquals(entries[i], ((DoubleEntry) it.next()).getValue());
      i++;
    }
    
    assertEquals(m1.getRows(), SIZE + 1);
  }

  public void testSetColumn() throws IOException {
    Vector v = new DenseVector();
    double[] entries = new double[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 0 };

    for (int i = 0; i < SIZE; i++) {
      v.set(i, entries[i]);
    }

    m1.setColumn(SIZE + 1, v);
    Iterator<Writable> it = m1.getColumn(SIZE + 1).iterator();

    int i = 0;
    while (it.hasNext()) {
      assertEquals(entries[i], ((DoubleEntry) it.next()).getValue());
      i++;
    }
    
    assertEquals(m1.getColumns(), SIZE + 1);
  }

  public void testEnsureForAddition() {
    try {
      m1.add(m2);
      fail("Matrix-Addition should be failed while rows and columns aren't same.");
    } catch (IOException e) {
      LOG.info(e.toString());
    }
  }
  

  public void testEnsureForMultiplication() {
    try {
      m1.mult(m2);
      fail("Matrix-Mult should be failed while A.columns!=B.rows.");
    } catch (IOException e) {
      LOG.info(e.toString());
    }
  }
  
  public void testLoadSave() throws IOException {
    String path1 = m1.getPath();
    // save m1 to aliase1
    m1.save(aliase1);
    // load matrix m1 using aliase1
    DenseMatrix loadTest = new DenseMatrix(conf, aliase1, false);

    for (int i = 0; i < loadTest.getRows(); i++) {
      for (int j = 0; j < loadTest.getColumns(); j++) {
        assertEquals(m1.get(i, j), loadTest.get(i, j));
      }
    }

    assertEquals(path1, loadTest.getPath());
    // close loadTest, it just disconnect to the table but didn't delete it.
    loadTest.close();

    // try to close m1 & load matrix m1 using aliase1 again.
    m1.close();
    DenseMatrix loadTest2 = new DenseMatrix(conf, aliase1, false);
    assertEquals(path1, loadTest2.getPath());
    // remove aliase1
    // because loadTest2 connect the aliase1, so we just remove aliase entry
    // but didn't delete the table.
    hamaAdmin.delete(aliase1);
    assertEquals(true, admin.tableExists(path1));
    // close loadTest2, because it is the last one who reference table 'path1'
    // it will do the gc!
    loadTest2.close();
    assertEquals(false, admin.tableExists(path1));

    // if we try to load non-existed matrix using aliase name, it should fail.
    DenseMatrix loadTest3 = null;
    try {
      loadTest3 = new DenseMatrix(conf, aliase1, false);
      fail("Try to load a non-existed matrix should fail!");
    } catch (IOException e) {

    } finally {
      if (loadTest3 != null)
        loadTest3.close();
    }
  }

  public void testForceCreate() throws IOException {
    String path2 = m2.getPath();
    // save m2 to aliase2
    m2.save(aliase2);
    // load matrix m2 using aliase2
    DenseMatrix loadTest = new DenseMatrix(conf, aliase2, false);

    for (int i = 0; i < loadTest.getRows(); i++) {
      for (int j = 0; j < loadTest.getColumns(); j++) {
        assertEquals(m2.get(i, j), loadTest.get(i, j));
      }
    }

    assertEquals(path2, loadTest.getPath());

    // force to create matrix loadTest2 using aliasename 'aliase2'
    DenseMatrix loadTest2 = new DenseMatrix(conf, aliase2, true);
    String loadPath2 = loadTest2.getPath();
    assertFalse(path2.equals(loadPath2));
    assertEquals(loadPath2, hamaAdmin.getPath(aliase2));
    assertFalse(path2.equals(hamaAdmin.getPath(aliase2)));

    // try to close m2 & loadTest, it table will be deleted finally
    m2.close();
    assertEquals(true, admin.tableExists(path2));
    loadTest.close();
    assertEquals(false, admin.tableExists(path2));

    // remove 'aliase2' & close loadTest2
    loadTest2.close();
    assertEquals(true, admin.tableExists(loadPath2));
    hamaAdmin.delete(aliase2);
    assertEquals(false, admin.tableExists(loadPath2));
  }

  /**
   * Verifying multiplication result
   * 
   * @param m1
   * @param m2
   * @param result
   * @throws IOException
   */
  private void verifyMultResult(Matrix m1, Matrix m2, Matrix result)
      throws IOException {
    double[][] c = new double[SIZE][SIZE];

    for (int i = 0; i < SIZE; i++) {
      for (int j = 0; j < SIZE; j++) {
        for (int k = 0; k < SIZE; k++) {
          c[i][k] += m1.get(i, j) * m2.get(j, k);
        }
      }
    }

    for (int i = 0; i < SIZE; i++) {
      for (int j = 0; j < SIZE; j++) {
        double gap = (c[i][j] - result.get(i, j));
        assertTrue(gap < 0.000001 || gap < -0.000001);
      }
    }
  }
}
