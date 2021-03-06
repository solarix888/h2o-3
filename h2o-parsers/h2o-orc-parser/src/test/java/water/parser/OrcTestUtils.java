package water.parser;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.ColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.DecimalColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.DoubleColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.LongColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.hadoop.hive.ql.io.orc.OrcFile;
import org.apache.hadoop.hive.ql.io.orc.Reader;
import org.apache.hadoop.hive.ql.io.orc.RecordReader;
import org.apache.hadoop.hive.ql.io.orc.StripeInformation;
import org.apache.hadoop.hive.serde2.io.HiveDecimalWritable;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.joda.time.DateTime;
import org.junit.Ignore;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import water.fvec.Frame;
import water.fvec.Vec;
import water.util.Log;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static water.parser.orc.OrcUtil.isSupportedSchema;

/**
 * ORC testing support methods.
 *
 * Note: Separate ORC-specific logic from tests.
 * This is necessary to avoid classloading of ORC-classes during loading ORC tests.
 */
@Ignore("Support for ORC tests, but no actual tests here")
public class OrcTestUtils {

  static private double EPSILON = 1e-9;
  static private long ERRORMARGIN = 1000L;  // error margin when compare timestamp.
  static final int DAY_TO_MS = 24*3600*1000;
  static final int ADD_OFFSET = 8*3600*1000;
  static final int HOUR_OFFSET = 3600000;  // in ms to offset for leap seconds, years

  static int compareOrcAndH2OFrame(String fileName, File f, Set<String> failedFiles) throws IOException {
    Frame h2oFrame = null;
    try {
      Configuration conf = new Configuration();
      Path p = new Path(f.toString());
      Reader orcFileReader = OrcFile.createReader(p, OrcFile.readerOptions(conf));
      h2oFrame = water.TestUtil.parse_test_file(f.toString());
      return compareH2OFrame(fileName, failedFiles, h2oFrame, orcFileReader);
    } finally {
      if (h2oFrame != null) h2oFrame.delete();
    }
  }
  /**
   * This method will take one H2O frame generated by the Orc parser and the fileName of the Orc file
   * and attempt to compare the content of the Orc file to the H2O frame.  In particular, the following
   * are compared:
   * - column names;
   * - number of columns and rows;
   * - content of each row.
   *
   * If all comparison pass, the test will pass.  Otherwise, the test will fail.
   *
   * @param h2oFrame
   * @param orcReader
   */
  static int compareH2OFrame(String fileName, Set<String> failedFiles, Frame h2oFrame, Reader orcReader) {
    // grab column names, column and row numbers
    StructObjectInspector insp = (StructObjectInspector) orcReader.getObjectInspector();
    List<StructField> allColInfo = (List<StructField>) insp.getAllStructFieldRefs();    // get info of all cols

    // compare number of columns and rows
    int allColNumber = allColInfo.size();    // get and check column number
    boolean[] toInclude = new boolean[allColNumber+1];

    int colNumber = 0 ;
    int index1 = 0;
    for (StructField oneField:allColInfo) {
      String colType = oneField.getFieldObjectInspector().getTypeName();

      if (colType.toLowerCase().contains("decimal"))
        colType = "decimal";

      if (isSupportedSchema(colType)) {
        toInclude[index1 + 1] = true;
        colNumber++;
      }

      index1++;
    }

    assertEquals("Number of columns need to be the same: ", colNumber, h2oFrame.numCols());

    // compare column names
    String[] colNames = new String[colNumber];
    String[] colTypes = new String[colNumber];
    int colIndex = 0;

    for (int index = 0; index < allColNumber; index++) {   // get and check column names
      String typeName = allColInfo.get(index).getFieldObjectInspector().getTypeName();

      if (typeName.toLowerCase().contains("decimal"))
        typeName = "decimal";

      if (isSupportedSchema(typeName)) {
        colNames[colIndex] = allColInfo.get(index).getFieldName();
        colTypes[colIndex] = typeName;
        colIndex++;
      }
    }
    assertArrayEquals("Column names need to be the same: ", colNames, h2oFrame._names);

    // compare one column at a time of the whole row?
    int failed = compareFrameContents(fileName, failedFiles, h2oFrame, orcReader, colTypes, colNames, toInclude);

    Long totalRowNumber = orcReader.getNumberOfRows();    // get and check row number
    assertEquals("Number of rows need to be the same: ", totalRowNumber, (Long) h2oFrame.numRows());
    return failed;
  }


  static int compareFrameContents(String fileName, Set<String> failedFiles, Frame h2oFrame, Reader orcReader,
                                    String[] colTypes, String[] colNames, boolean[] toInclude) {
    List<StripeInformation> stripesInfo = orcReader.getStripes(); // get all stripe info

    int wrongTests = 0;

    if (stripesInfo.size() == 0) {  // Orc file contains no data
      assertEquals("Orc file is empty.  H2O frame row number should be zero: ", 0, h2oFrame.numRows());
    } else {
      Long startRowIndex = 0L;   // row index into H2O frame
      for (StripeInformation oneStripe : stripesInfo) {
        try {
          RecordReader
              perStripe = orcReader.rows(oneStripe.getOffset(), oneStripe.getDataLength(), toInclude, null,
                                         colNames);
          VectorizedRowBatch batch = perStripe.nextBatch(null);  // read orc file stripes in vectorizedRowBatch

          boolean done = false;
          Long rowCounts = 0L;
          Long rowNumber = oneStripe.getNumberOfRows();   // row number of current stripe

          while (!done) {
            long currentBatchRow = batch.count();     // row number of current batch

            ColumnVector[] dataVectors = batch.cols;

            int colIndex = 0;
            for (int cIdx = 0; cIdx < batch.numCols; cIdx++) {   // read one column at a time;
              if (toInclude[cIdx+1]) {
                compare1Cloumn(dataVectors[cIdx], colTypes[colIndex].toLowerCase(), colIndex, currentBatchRow,
                               h2oFrame.vec(colNames[colIndex]), startRowIndex);
                colIndex++;
              }
            }

            rowCounts = rowCounts + currentBatchRow;    // record number of rows of data actually read
            startRowIndex = startRowIndex + currentBatchRow;

            if (rowCounts >= rowNumber)               // read all rows of the stripe already.
              done = true;

            if (!done)  // not done yet, get next batch
              batch = perStripe.nextBatch(batch);
          }
          perStripe.close();
        } catch (Throwable e) {
          failedFiles.add(fileName);
          e.printStackTrace();
          wrongTests += 1;
        }
      }
    }
    return wrongTests;
  }

  static void compare1Cloumn(ColumnVector oneColumn, String columnType, int cIdx, long currentBatchRow,
                              Vec h2oColumn, Long startRowIndex) {

//    if (columnType.contains("bigint"))  // cannot handle big integer right now
//      return;

    if (columnType.contains("binary"))  // binary retrieval problem.  Tomas
      return;

    switch (columnType) {
      case "boolean":
      case "bigint":  // FIXME: not working right now
      case "int":
      case "smallint":
      case "tinyint":
        CompareLongcolumn(oneColumn, oneColumn.isNull, currentBatchRow, h2oColumn, startRowIndex);
        break;
      case "float":
      case "double":
        compareDoublecolumn(oneColumn, oneColumn.isNull, currentBatchRow, h2oColumn, startRowIndex);
        break;
      case "string":  //FIXME: not working right now
      case "varchar":
      case "char":
      case "binary":  //FIXME: only reading it as string right now.
        compareStringcolumn(oneColumn, oneColumn.isNull, currentBatchRow, h2oColumn, startRowIndex, columnType);
        break;
      case "timestamp":
      case "date":
        compareTimecolumn(oneColumn, columnType, oneColumn.isNull, currentBatchRow, h2oColumn, startRowIndex);
        break;
      case "decimal":
        compareDecimalcolumn(oneColumn, oneColumn.isNull, currentBatchRow, h2oColumn, startRowIndex);
        break;
      default:
        Log.warn("String, bigint are not tested.  H2O frame is built for them but cannot be verified.");
    }
  }

  static void compareDecimalcolumn(ColumnVector oneDecimalColumn, boolean[] isNull,
                                    long currentBatchRow, Vec h2oFrame, Long startRowIndex) {
    HiveDecimalWritable[] oneColumn= ((DecimalColumnVector) oneDecimalColumn).vector;
    long frameRowIndex = startRowIndex;

    for (int rowIndex = 0; rowIndex < currentBatchRow; rowIndex++) {
      if (isNull[rowIndex])
        assertEquals("Na is found: ", true, h2oFrame.isNA(frameRowIndex));
      else
        assertEquals("Decimal elements should equal: ", Double.parseDouble(oneColumn[rowIndex].toString()),
                     h2oFrame.at(frameRowIndex), EPSILON);

      frameRowIndex++;
    }
  }

  static void compareTimecolumn(ColumnVector oneTSColumn, String columnType, boolean[] isNull, long currentBatchRow,
                                 Vec h2oFrame, Long startRowIndex) {
    long[] oneColumn = ((LongColumnVector) oneTSColumn).vector;
    long frameRowIndex = startRowIndex;

    for (int rowIndex = 0; rowIndex < currentBatchRow; rowIndex++) {
      if (isNull[rowIndex])
        assertEquals("Na is found: ", true, h2oFrame.isNA(frameRowIndex));
      else {
        if (columnType.contains("timestamp"))
          assertEquals("Numerical elements should equal: ", oneColumn[rowIndex]/1000000, h2oFrame.at8(frameRowIndex),
                       ERRORMARGIN);
        else
          assertEquals("Numerical elements should equal: ", correctTimeStamp(oneColumn[rowIndex]),
                       h2oFrame.at8(frameRowIndex), ERRORMARGIN);
      }

      frameRowIndex++;
    }
  }

  static void compareStringcolumn(ColumnVector oneStringColumn, boolean[] isNull,
                                   long currentBatchRow, Vec h2oFrame, Long startRowIndex, String columnType) {
    byte[][] oneColumn = ((BytesColumnVector) oneStringColumn).vector;
    int[] stringLength = ((BytesColumnVector) oneStringColumn).length;
    int[] stringStart = ((BytesColumnVector) oneStringColumn).start;
    long frameRowIndex = startRowIndex;
    BufferedString tempH2o = new BufferedString();
    BufferedString tempOrc = new BufferedString();

    for (int rowIndex = 0; rowIndex < currentBatchRow; rowIndex++) {
      if (isNull[rowIndex])
        assertEquals("Na is found: ", true, h2oFrame.isNA(frameRowIndex));
      else {
        if (!oneStringColumn.isRepeating || rowIndex == 0)
          tempOrc.set(oneColumn[rowIndex], stringStart[rowIndex], stringLength[rowIndex]);
        h2oFrame.atStr(tempH2o, frameRowIndex);
        assertEquals("isRepeating = " + oneStringColumn.isRepeating + " String/char elements should equal: ", true, tempOrc.equals(tempH2o));
      }

      frameRowIndex++;
    }
  }

  static void compareDoublecolumn(ColumnVector oneDoubleColumn, boolean[] isNull,
                                   long currentBatchRow, Vec h2oFrame, Long startRowIndex) {
    double[] oneColumn= ((DoubleColumnVector) oneDoubleColumn).vector;
    long frameRowIndex = startRowIndex;

    for (int rowIndex = 0; rowIndex < currentBatchRow; rowIndex++) {
      if (isNull[rowIndex])
        assertEquals("Na is found: ", true, h2oFrame.isNA(frameRowIndex));
      else
        assertEquals("Numerical elements should equal: ", oneColumn[rowIndex], h2oFrame.at(frameRowIndex), EPSILON);

      frameRowIndex++;
    }
  }

  static void CompareLongcolumn(ColumnVector oneLongColumn, boolean[] isNull,
                                 long currentBatchRow, Vec h2oFrame, Long startRowIndex) {
    long[] oneColumn= ((LongColumnVector) oneLongColumn).vector;
    long frameRowIndex = startRowIndex;

    for (int rowIndex = 0; rowIndex < currentBatchRow; rowIndex++) {
      if (isNull[rowIndex])
        assertEquals("Na is found: ", true, h2oFrame.isNA(frameRowIndex));
      else {
        if (h2oFrame.isNA(frameRowIndex))
          continue;
        else
          assertEquals("Numerical elements should equal: ", oneColumn[rowIndex], h2oFrame.at8(frameRowIndex));
      }

      frameRowIndex++;
    }
  }

  static long correctTimeStamp(long daysSinceEpoch) {
    long timestamp = (daysSinceEpoch*DAY_TO_MS+ADD_OFFSET);

    DateTime date = new DateTime(timestamp);

    int hour = date.hourOfDay().get();

    if (hour == 0)
      return timestamp;
    else
      return (timestamp-hour*HOUR_OFFSET);
  }
}
