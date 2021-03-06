/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kiji.mapreduce.lib.bulkimport;

import java.io.IOException;
import java.text.ParseException;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.io.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kiji.annotations.ApiAudience;
import org.kiji.hadoop.configurator.HadoopConf;
import org.kiji.mapreduce.KijiTableContext;
import org.kiji.mapreduce.lib.util.CSVParser;
import org.kiji.schema.EntityId;
import org.kiji.schema.KijiColumnName;

/**
 * Bulk importer that handles comma separated files.  TSVs are also supported by setting the
 * <code>kiji.import.text.field.separator</code> configuration item specified by
 * {@link #CONF_FIELD_DELIMITER}.
 *
 * For import files that do not contain a header row, a default can be specified by setting the
 * <code>kiji.import.text.column.header_row</code> configuration item specified by
 * {@link #CONF_INPUT_HEADER_ROW}.
 *
 * {@inheritDoc}
 */
@ApiAudience.Public
public final class CSVBulkImporter extends DescribedInputTextBulkImporter {
  private static final Logger LOG =
      LoggerFactory.getLogger(CSVBulkImporter.class);

  /** Configuration variable for a header row containing delimited string of names of fields. */
  public static final String CONF_INPUT_HEADER_ROW = "kiji.import.text.column.header_row";

  /** Configuration variable that specifies the cell value separator in the text input files. */
  public static final String CONF_FIELD_DELIMITER = "kiji.import.text.field.separator";

  private static final String CSV_DELIMITER = ",";
  private static final String TSV_DELIMITER = "\t";

  /** The string that separates the columns of data in the input file. */
  @HadoopConf(key=CONF_FIELD_DELIMITER)
  private String mColumnDelimiter = CSV_DELIMITER;

  /** Internal map of field names to field positions in the parsed line. */
  private Map<String, Integer> mFieldMap = null;

  /** {@inheritDoc} */
  @Override
  public void setupImporter(KijiTableContext context) throws IOException {
    // Validate that the passed in delimiter is one of the supported options.
    List<String> validDelimiters = Lists.newArrayList(CSV_DELIMITER, TSV_DELIMITER);
    if (!validDelimiters.contains(mColumnDelimiter)) {
      throw new IOException(
          String.format("Invalid delimiter '%s' specified.  Valid options are: '%s'",
          mColumnDelimiter, StringUtils.join(validDelimiters, "','")));
    }

    // If the header row is specified in the configuration, use that.
    if (getConf().get(CONF_INPUT_HEADER_ROW) != null) {
      List<String> fields = null;
      String confInputHeaderRow = getConf().get(CONF_INPUT_HEADER_ROW);
      try {
        fields = split(confInputHeaderRow);
      } catch (ParseException pe) {
        LOG.error("Unable to parse header row: {} with exception {}",
            confInputHeaderRow, pe.getMessage());
        throw new IOException("Unable to parse header row: " + confInputHeaderRow);
      }
      initializeHeader(fields);
    }
  }

  /**
   * Initializes the field to column position mapping for this file.
   * @param headerFields the header fields for this delimited file.
   */
  private void initializeHeader(List<String> headerFields) {
    LOG.info("Initializing field map with fields: " + StringUtils.join(headerFields, ","));
    Map<String, Integer> fieldMap = Maps.newHashMap();
    for (int index=0; index < headerFields.size(); index++) {
      fieldMap.put(headerFields.get(index), index);
    }
    mFieldMap = ImmutableMap.copyOf(fieldMap);
  }

  /**
   * Wrapper around CSV or TSV parsers based on the configuration of this job builder.
   * @return a list of fields split by the mColumnDelimiter.
   * @param line the line to split
   * @throws ParseException if the parser encounters an error while parsing
   */
  private List<String> split(String line) throws ParseException {
    if (CSV_DELIMITER.equals(mColumnDelimiter)) {
      return CSVParser.parseCSV(line);
    } else if (TSV_DELIMITER.equals(mColumnDelimiter)) {
      return CSVParser.parseTSV(line);
    }
    throw new ParseException("Unrecognized delimiter: " + mColumnDelimiter, 0);
  }

  /**
   * Generates the entity id for this imported line.
   * Called within the produce() method.
   * This implementation returns the first entry in <code>entries</code> as the EntityId.
   * Override this method to specify a different EntityId during the produce() method.
   *
   * @param fields One line of input text split on the column delimiter.
   * @param context The context used by the produce() method.
   * @return The EntityId for the data that gets imported by this line.
   */
  protected EntityId getEntityId(List<String> fields, KijiTableContext context) {
    //TODO(KIJIMRLIB-3) Extend this to support composite row key ids
    String rowkey = fields.get(mFieldMap.get(getEntityIdSource()));
    return context.getEntityId(rowkey);
  }

  /** {@inheritDoc} */
  @Override
  public void produce(Text value, KijiTableContext context) throws IOException {
    // This is the header line since fieldList isn't populated
    if (mFieldMap == null) {
      List<String> fields = null;
      try {
        fields = split(value.toString());
      } catch (ParseException pe) {
        LOG.error("Unable to parse header row: {} with exception {}",
            value.toString(), pe.getMessage());
        throw new IOException("Unable to parse header row: " + value.toString());
      }
      initializeHeader(fields);
      // Don't actually import this line
      return;
    }

    List<String> fields = null;
    try {
      fields = split(value.toString());
    } catch (ParseException pe) {
      LOG.error("Unable to parse line: {} with exception {}",
          value.toString(), pe.getMessage());
      //TODO(KIJIMRLIB-4) Emit this to a rejected output so that import can be reattempted
      return;
    }
    for (KijiColumnName kijiColumnName : getDestinationColumns()) {
      final EntityId eid = getEntityId(fields, context);
      String source = getSource(kijiColumnName);

      if (mFieldMap.get(source) < fields.size()) {
        String fieldValue = fields.get(mFieldMap.get(source));
        context.put(eid, kijiColumnName.getFamily(), kijiColumnName.getQualifier(), fieldValue);
      } else {
        LOG.warn("Detected trailing empty field: " + source);
      }
    }

  }
}
