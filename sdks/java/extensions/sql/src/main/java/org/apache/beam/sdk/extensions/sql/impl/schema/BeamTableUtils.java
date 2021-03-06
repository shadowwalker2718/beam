/*
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
package org.apache.beam.sdk.extensions.sql.impl.schema;

import static org.apache.beam.sdk.values.Row.toRow;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.Schema.TypeName;
import org.apache.beam.sdk.values.Row;
import org.apache.calcite.util.NlsString;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.joda.time.DateTime;

/**
 * Utility methods for working with {@code BeamTable}.
 *
 * <p>TODO: Does not yet support nested types.
 */
public final class BeamTableUtils {

  /**
   * Decode zero or more CSV records from the given string, according to the specified {@link
   * CSVFormat}, and converts them to {@link Row Rows} with the specified {@link Schema}.
   *
   * <p>A single "line" read from e.g. {@link TextIO} can have zero or more records, depending on
   * whether the line was split on the same characters that delimite CSV records, and whether the
   * {@link CSVFormat} ignores blank lines.
   */
  public static Iterable<Row> csvLines2BeamRows(CSVFormat csvFormat, String line, Schema schema) {
    // Empty lines can result in empty strings after Beam splits the file,
    // which are not empty records to CSVParser unless they have a record terminator.
    if (!line.endsWith(csvFormat.getRecordSeparator())) {
      line += csvFormat.getRecordSeparator();
    }
    try (CSVParser parser = CSVParser.parse(line, csvFormat)) {
      List<Row> rows = new ArrayList<>();
      for (CSVRecord rawRecord : parser.getRecords()) {
        if (rawRecord.size() != schema.getFieldCount()) {
          throw new IllegalArgumentException(
              String.format(
                  "Expect %d fields, but actually %d", schema.getFieldCount(), rawRecord.size()));
        }
        rows.add(
            IntStream.range(0, schema.getFieldCount())
                .mapToObj(idx -> autoCastField(schema.getField(idx), rawRecord.get(idx)))
                .collect(toRow(schema)));
      }
      return rows;
    } catch (IOException e) {
      throw new IllegalArgumentException(
          String.format("Could not parse CSV records from %s with format %s", line, csvFormat), e);
    }
  }

  public static String beamRow2CsvLine(Row row, CSVFormat csvFormat) {
    StringWriter writer = new StringWriter();
    try (CSVPrinter printer = csvFormat.print(writer)) {
      for (int i = 0; i < row.getFieldCount(); i++) {
        printer.print(row.getValue(i).toString());
      }
      printer.println();
    } catch (IOException e) {
      throw new IllegalArgumentException("encodeRecord failed!", e);
    }
    return writer.toString();
  }

  /**
   * Attempt to cast an object to a specified Schema.Field.Type.
   * @throws IllegalArgumentException if the value cannot be cast to that type.
   *
   * @return The casted object in Schema.Field.Type.
   */
  public static Object autoCastField(Schema.Field field, Object rawObj) {
    if (rawObj == null) {
      if (!field.getType().getNullable()) {
        throw new IllegalArgumentException(String.format("Field %s not nullable", field.getName()));
      }
      return null;
    }

    TypeName type = field.getType().getTypeName();
    if (type.isStringType()) {
      if (rawObj instanceof NlsString) {
        return ((NlsString) rawObj).getValue();
      } else {
        return rawObj;
      }
    } else if (type.isDateType()) {
      // Internal representation of DateType in Calcite is convertible to Joda's Datetime.
      return new DateTime(rawObj);
    } else if (type.isNumericType()
        && ((rawObj instanceof String)
            || (rawObj instanceof BigDecimal && type != TypeName.DECIMAL))) {
      String raw = rawObj.toString();
      switch (type) {
        case BYTE:
          return Byte.valueOf(raw);
        case INT16:
          return Short.valueOf(raw);
        case INT32:
          return Integer.valueOf(raw);
        case INT64:
          return Long.valueOf(raw);
        case FLOAT:
          return Float.valueOf(raw);
        case DOUBLE:
          return Double.valueOf(raw);
        default:
          throw new UnsupportedOperationException(
              String.format("Column type %s is not supported yet!", type));
      }
    }
    return rawObj;
  }
}
