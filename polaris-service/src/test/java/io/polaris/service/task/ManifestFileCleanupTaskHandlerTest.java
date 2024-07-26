package io.polaris.service.task;

import static org.assertj.core.api.Assertions.assertThatPredicate;

import io.polaris.core.PolarisCallContext;
import io.polaris.core.PolarisDefaultDiagServiceImpl;
import io.polaris.core.context.CallContext;
import io.polaris.core.context.RealmContext;
import io.polaris.core.entity.AsyncTaskType;
import io.polaris.core.entity.TaskEntity;
import io.polaris.service.persistence.InMemoryPolarisMetaStoreManagerFactory;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.codec.binary.Base64;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.inmemory.InMemoryFileIO;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.io.PositionOutputStream;
import org.junit.jupiter.api.Test;

class ManifestFileCleanupTaskHandlerTest {

  @Test
  public void testCleanupFileNotExists() throws IOException {
    InMemoryPolarisMetaStoreManagerFactory metaStoreManagerFactory =
        new InMemoryPolarisMetaStoreManagerFactory();
    RealmContext realmContext = () -> "realmName";
    PolarisCallContext polarisCallContext =
        new PolarisCallContext(
            metaStoreManagerFactory.getOrCreateSessionSupplier(realmContext).get(),
            new PolarisDefaultDiagServiceImpl());
    try (CallContext callCtx = CallContext.of(realmContext, polarisCallContext)) {
      CallContext.setCurrentContext(callCtx);
      FileIO fileIO = new InMemoryFileIO();
      TableIdentifier tableIdentifier =
          TableIdentifier.of(Namespace.of("db1", "schema1"), "table1");
      ManifestFileCleanupTaskHandler handler =
          new ManifestFileCleanupTaskHandler((task) -> fileIO, Executors.newSingleThreadExecutor());
      ManifestFile manifestFile =
          TaskTestUtils.manifestFile(
              fileIO, "manifest1.avro", 1L, "dataFile1.parquet", "dataFile2.parquet");
      fileIO.deleteFile(manifestFile.path());
      TaskEntity task =
          new TaskEntity.Builder()
              .withTaskType(AsyncTaskType.FILE_CLEANUP)
              .withData(
                  new ManifestFileCleanupTaskHandler.ManifestCleanupTask(
                      tableIdentifier,
                      Base64.encodeBase64String(ManifestFiles.encode(manifestFile))))
              .setName(UUID.randomUUID().toString())
              .build();
      assertThatPredicate(handler::canHandleTask).accepts(task);
      assertThatPredicate(handler::handleTask).accepts(task);
    }
  }

  @Test
  public void testCleanupFileManifestExistsDataFilesDontExist() throws IOException {
    InMemoryPolarisMetaStoreManagerFactory metaStoreManagerFactory =
        new InMemoryPolarisMetaStoreManagerFactory();
    RealmContext realmContext = () -> "realmName";
    PolarisCallContext polarisCallContext =
        new PolarisCallContext(
            metaStoreManagerFactory.getOrCreateSessionSupplier(realmContext).get(),
            new PolarisDefaultDiagServiceImpl());
    try (CallContext callCtx = CallContext.of(realmContext, polarisCallContext)) {
      CallContext.setCurrentContext(callCtx);
      FileIO fileIO = new InMemoryFileIO();
      TableIdentifier tableIdentifier =
          TableIdentifier.of(Namespace.of("db1", "schema1"), "table1");
      ManifestFileCleanupTaskHandler handler =
          new ManifestFileCleanupTaskHandler((task) -> fileIO, Executors.newSingleThreadExecutor());
      ManifestFile manifestFile =
          TaskTestUtils.manifestFile(
              fileIO, "manifest1.avro", 100L, "dataFile1.parquet", "dataFile2.parquet");
      TaskEntity task =
          new TaskEntity.Builder()
              .withTaskType(AsyncTaskType.FILE_CLEANUP)
              .withData(
                  new ManifestFileCleanupTaskHandler.ManifestCleanupTask(
                      tableIdentifier,
                      Base64.encodeBase64String(ManifestFiles.encode(manifestFile))))
              .setName(UUID.randomUUID().toString())
              .build();
      assertThatPredicate(handler::canHandleTask).accepts(task);
      assertThatPredicate(handler::handleTask).accepts(task);
    }
  }

  @Test
  public void testCleanupFiles() throws IOException {
    InMemoryPolarisMetaStoreManagerFactory metaStoreManagerFactory =
        new InMemoryPolarisMetaStoreManagerFactory();
    RealmContext realmContext = () -> "realmName";
    PolarisCallContext polarisCallContext =
        new PolarisCallContext(
            metaStoreManagerFactory.getOrCreateSessionSupplier(realmContext).get(),
            new PolarisDefaultDiagServiceImpl());
    try (CallContext callCtx = CallContext.of(realmContext, polarisCallContext)) {
      CallContext.setCurrentContext(callCtx);
      FileIO fileIO =
          new InMemoryFileIO() {
            @Override
            public void close() {
              // no-op
            }
          };
      TableIdentifier tableIdentifier =
          TableIdentifier.of(Namespace.of("db1", "schema1"), "table1");
      ManifestFileCleanupTaskHandler handler =
          new ManifestFileCleanupTaskHandler((task) -> fileIO, Executors.newSingleThreadExecutor());
      String dataFile1Path = "dataFile1.parquet";
      OutputFile dataFile1 = fileIO.newOutputFile(dataFile1Path);
      PositionOutputStream out1 = dataFile1.createOrOverwrite();
      out1.write("the data".getBytes());
      out1.close();
      String dataFile2Path = "dataFile2.parquet";
      OutputFile dataFile2 = fileIO.newOutputFile(dataFile2Path);
      PositionOutputStream out2 = dataFile2.createOrOverwrite();
      out2.write("the data".getBytes());
      out2.close();
      ManifestFile manifestFile =
          TaskTestUtils.manifestFile(fileIO, "manifest1.avro", 100L, dataFile1Path, dataFile2Path);
      TaskEntity task =
          new TaskEntity.Builder()
              .withTaskType(AsyncTaskType.FILE_CLEANUP)
              .withData(
                  new ManifestFileCleanupTaskHandler.ManifestCleanupTask(
                      tableIdentifier,
                      Base64.encodeBase64String(ManifestFiles.encode(manifestFile))))
              .setName(UUID.randomUUID().toString())
              .build();
      assertThatPredicate(handler::canHandleTask).accepts(task);
      assertThatPredicate(handler::handleTask).accepts(task);
      assertThatPredicate((String f) -> TaskUtils.exists(f, fileIO)).rejects(dataFile1Path);
      assertThatPredicate((String f) -> TaskUtils.exists(f, fileIO)).rejects(dataFile2Path);
    }
  }

  @Test
  public void testCleanupFilesWithRetries() throws IOException {
    InMemoryPolarisMetaStoreManagerFactory metaStoreManagerFactory =
        new InMemoryPolarisMetaStoreManagerFactory();
    RealmContext realmContext = () -> "realmName";
    PolarisCallContext polarisCallContext =
        new PolarisCallContext(
            metaStoreManagerFactory.getOrCreateSessionSupplier(realmContext).get(),
            new PolarisDefaultDiagServiceImpl());
    try (CallContext callCtx = CallContext.of(realmContext, polarisCallContext)) {
      CallContext.setCurrentContext(callCtx);
      Map<String, AtomicInteger> retryCounter = new HashMap<>();
      FileIO fileIO =
          new InMemoryFileIO() {
            @Override
            public void close() {
              // no-op
            }

            @Override
            public void deleteFile(String location) {
              int attempts =
                  retryCounter
                      .computeIfAbsent(location, k -> new AtomicInteger(0))
                      .incrementAndGet();
              if (attempts < 3) {
                throw new RuntimeException("I'm failing to test retries");
              } else {
                // succeed on the third attempt
                super.deleteFile(location);
              }
            }
          };

      TableIdentifier tableIdentifier =
          TableIdentifier.of(Namespace.of("db1", "schema1"), "table1");
      ManifestFileCleanupTaskHandler handler =
          new ManifestFileCleanupTaskHandler((task) -> fileIO, Executors.newSingleThreadExecutor());
      String dataFile1Path = "dataFile1.parquet";
      OutputFile dataFile1 = fileIO.newOutputFile(dataFile1Path);
      PositionOutputStream out1 = dataFile1.createOrOverwrite();
      out1.write("the data".getBytes());
      out1.close();
      String dataFile2Path = "dataFile2.parquet";
      OutputFile dataFile2 = fileIO.newOutputFile(dataFile2Path);
      PositionOutputStream out2 = dataFile2.createOrOverwrite();
      out2.write("the data".getBytes());
      out2.close();
      ManifestFile manifestFile =
          TaskTestUtils.manifestFile(fileIO, "manifest1.avro", 100L, dataFile1Path, dataFile2Path);
      TaskEntity task =
          new TaskEntity.Builder()
              .withTaskType(AsyncTaskType.FILE_CLEANUP)
              .withData(
                  new ManifestFileCleanupTaskHandler.ManifestCleanupTask(
                      tableIdentifier,
                      Base64.encodeBase64String(ManifestFiles.encode(manifestFile))))
              .setName(UUID.randomUUID().toString())
              .build();
      assertThatPredicate(handler::canHandleTask).accepts(task);
      assertThatPredicate(handler::handleTask).accepts(task);
      assertThatPredicate((String f) -> TaskUtils.exists(f, fileIO)).rejects(dataFile1Path);
      assertThatPredicate((String f) -> TaskUtils.exists(f, fileIO)).rejects(dataFile2Path);
    }
  }
}
