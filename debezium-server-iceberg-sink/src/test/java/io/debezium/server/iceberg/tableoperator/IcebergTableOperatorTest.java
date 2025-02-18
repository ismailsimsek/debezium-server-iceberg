/*
 *
 *  * Copyright memiiso Authors.
 *  *
 *  * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 *
 */

package io.debezium.server.iceberg.tableoperator;

import io.debezium.DebeziumException;
import io.debezium.server.iceberg.IcebergUtil;
import io.debezium.server.iceberg.RecordConverter;
import io.debezium.server.iceberg.testresources.BaseSparkTest;
import io.debezium.server.iceberg.testresources.IcebergChangeEventBuilder;
import io.debezium.server.iceberg.testresources.S3Minio;
import io.debezium.server.iceberg.testresources.SourcePostgresqlDB;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.hadoop.HadoopCatalog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.apache.iceberg.TableProperties.DEFAULT_FILE_FORMAT;
import static org.apache.iceberg.TableProperties.DEFAULT_FILE_FORMAT_DEFAULT;
import static org.junit.jupiter.api.Assertions.assertThrows;


/**
 *
 * @author Ismail Simsek
 */
@QuarkusTest
@QuarkusTestResource(value = S3Minio.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = SourcePostgresqlDB.class, restrictToAnnotatedClass = true)
class IcebergTableOperatorTest extends BaseSparkTest {

  static String testTable = "inventory.test_table_operator";
  @ConfigProperty(name = "debezium.sink.iceberg.table-prefix", defaultValue = "")
  String tablePrefix;
  @ConfigProperty(name = "debezium.sink.iceberg.table-namespace", defaultValue = "default")
  String namespace;
  @ConfigProperty(name = "debezium.sink.iceberg.upsert", defaultValue = "true")
  boolean upsert;
  @ConfigProperty(name = "debezium.sink.iceberg." + DEFAULT_FILE_FORMAT, defaultValue = DEFAULT_FILE_FORMAT_DEFAULT)
  String writeFormat;
  @Inject
  IcebergTableOperator icebergTableOperator;
  IcebergChangeEventBuilder eventBuilder = new IcebergChangeEventBuilder().destination(testTable);

  public Table createTable(RecordConverter sampleEvent) {
    HadoopCatalog icebergCatalog = getIcebergCatalog();
    final TableIdentifier tableId = TableIdentifier.of(Namespace.of(namespace), tablePrefix + sampleEvent.destination());
    return IcebergUtil.createIcebergTable(icebergCatalog, tableId, sampleEvent.icebergSchema(true), writeFormat);
  }

  @Test
  public void testIcebergTableOperator() {
    // setup
    List<RecordConverter> events = new ArrayList<>();
    Table icebergTable = this.createTable(
        new IcebergChangeEventBuilder()
            .destination(testTable)
            .addKeyField("id", 1)
            .addField("data", "record1")
            .addField("preferences", "feature1", true)
            .build()
    );

    events.add(new IcebergChangeEventBuilder()
        .destination(testTable)
        .addKeyField("id", 1)
        .addField("data", "record1")
        .build()
    );
    events.add(new IcebergChangeEventBuilder()
        .destination(testTable)
        .addKeyField("id", 2)
        .addField("data", "record2")
        .build()
    );
    events.add(new IcebergChangeEventBuilder()
        .destination(testTable)
        .addKeyField("id", 3)
        .addField("user_name", "Alice")
        .addField("data", "record3_adding_field")
        .build()
    );
    icebergTableOperator.addToTable(icebergTable, events);

    getTableData(testTable).show(false);
    Assertions.assertEquals(3, getTableData(testTable).count());
    events.clear();
    events.add(new IcebergChangeEventBuilder()
        .destination(testTable)
        .addKeyField("id", 3)
        .addField("user_name", "Alice-Updated")
        .addField("data", "record3_updated")
        .addField("preferences", "feature2", "feature2Val2")
        .addField("__op", "u")
        .build()
    );
    icebergTableOperator.addToTable(icebergTable, events);
    getTableData(testTable).show(false);
    Assertions.assertEquals(4, getTableData(testTable).count());
    Assertions.assertEquals(1, getTableData(testTable).where("user_name == 'Alice-Updated'").count());
    //Assertions.assertEquals(1, getTableData(testTable).where("preferences.feature2 == 'feature2Val2'").count());
  }

  @Test
  public void testDeduplicateBatch() throws Exception {
    RecordConverter e1 = new IcebergChangeEventBuilder()
        .destination("destination")
        .addKeyField("id", 1)
        .addKeyField("first_name", "row1")
        .addField("__source_ts_ms", 1L)
        .build();
    RecordConverter e2 = new IcebergChangeEventBuilder()
        .destination("destination")
        .addKeyField("id", 1)
        .addKeyField("first_name", "row1")
        .addField("__source_ts_ms", 3L)
        .build();

    List<RecordConverter> records = List.of(e1, e2);
    List<RecordConverter> dedups = icebergTableOperator.deduplicateBatch(records);
    Assertions.assertEquals(1, dedups.size());
    Assertions.assertEquals(3L, dedups.get(0).value().get("__source_ts_ms").asLong(0L));

    RecordConverter e21 = new IcebergChangeEventBuilder()
        .destination("destination")
        .addKeyField("id", 1)
        .addField("__op", "r")
        .addField("__source_ts_ms", 1L)
        .build();
    RecordConverter e22 = new IcebergChangeEventBuilder()
        .destination("destination")
        .addKeyField("id", 1)
        .addField("__op", "u")
        .addField("__source_ts_ms", 1L)
        .build();

    List<RecordConverter> records2 = List.of(e21, e22);
    List<RecordConverter> dedups2 = icebergTableOperator.deduplicateBatch(records2);
    Assertions.assertEquals(1, dedups2.size());
    Assertions.assertEquals("u", dedups2.get(0).value().get("__op").asText("x"));

    // deduplicating wth null key should fail!
    RecordConverter e31 = new IcebergChangeEventBuilder()
        .destination("destination")
        .addField("id", 3)
        .addField("__op", "r")
        .addField("__source_ts_ms", 1L)
        .build();
    RecordConverter e32 = new IcebergChangeEventBuilder()
        .destination("destination")
        .addField("id", 3)
        .addField("__op", "u")
        .addField("__source_ts_ms", 1L)
        .build();

    List<RecordConverter> records3 = List.of(e31, e32);
    DebeziumException thrown = assertThrows(DebeziumException.class,
        () -> {
          icebergTableOperator.deduplicateBatch(records3);
        });

    Assertions.assertTrue(thrown.getMessage().contains("Cannot deduplicate data with null key!"));
  }
}