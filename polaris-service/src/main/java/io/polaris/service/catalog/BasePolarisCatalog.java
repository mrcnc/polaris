package io.polaris.service.catalog;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import io.polaris.core.PolarisCallContext;
import io.polaris.core.catalog.PolarisCatalogHelpers;
import io.polaris.core.context.CallContext;
import io.polaris.core.entity.CatalogEntity;
import io.polaris.core.entity.NamespaceEntity;
import io.polaris.core.entity.PolarisEntity;
import io.polaris.core.entity.PolarisEntityConstants;
import io.polaris.core.entity.PolarisEntitySubType;
import io.polaris.core.entity.PolarisEntityType;
import io.polaris.core.entity.PolarisTaskConstants;
import io.polaris.core.entity.TableLikeEntity;
import io.polaris.core.persistence.PolarisEntityManager;
import io.polaris.core.persistence.PolarisMetaStoreManager;
import io.polaris.core.persistence.PolarisResolvedPathWrapper;
import io.polaris.core.persistence.resolver.PolarisResolutionManifestCatalogView;
import io.polaris.core.storage.InMemoryStorageIntegration;
import io.polaris.core.storage.PolarisStorageActions;
import io.polaris.core.storage.PolarisStorageConfigurationInfo;
import io.polaris.core.storage.PolarisStorageIntegration;
import io.polaris.core.storage.aws.PolarisS3FileIOClientFactory;
import io.polaris.service.task.TaskExecutor;
import io.polaris.service.types.NotificationRequest;
import io.polaris.service.types.NotificationType;
import jakarta.ws.rs.BadRequestException;
import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.BaseMetastoreTableOperations;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.aws.s3.S3FileIOProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.NoSuchViewException;
import org.apache.iceberg.exceptions.NotFoundException;
import org.apache.iceberg.exceptions.UnprocessableEntityException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.io.CloseableGroup;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.view.BaseMetastoreViewCatalog;
import org.apache.iceberg.view.BaseViewOperations;
import org.apache.iceberg.view.ViewBuilder;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewMetadataParser;
import org.apache.iceberg.view.ViewUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;

/** Defines the relationship between PolarisEntities and Iceberg's business logic. */
public class BasePolarisCatalog extends BaseMetastoreViewCatalog
    implements SupportsNamespaces, SupportsNotifications, Closeable, SupportsCredentialDelegation {
  private static final Logger LOG = LoggerFactory.getLogger(BasePolarisCatalog.class);

  private static final Joiner SLASH = Joiner.on("/");
  private static final Joiner DOT = Joiner.on(".");

  // Config key for whether to allow setting the FILE_IO_IMPL using catalog properties. Should
  // only be allowed in dev/test environments.
  static final String ALLOW_SPECIFYING_FILE_IO_IMPL = "ALLOW_SPECIFYING_FILE_IO_IMPL";
  private static final int MAX_RETRIES = 12;

  static final Predicate<Exception> SHOULD_RETRY_REFRESH_PREDICATE =
      new Predicate<Exception>() {
        @Override
        public boolean test(Exception ex) {
          // Default arguments from BaseMetastoreTableOperation only stop retries on
          // NotFoundException. We should more carefully identify the set of retriable
          // and non-retriable exceptions here.
          return !(ex instanceof NotFoundException)
              && !(ex instanceof IllegalArgumentException)
              && !(ex instanceof AlreadyExistsException)
              && !(ex instanceof ForbiddenException)
              && !(ex instanceof UnprocessableEntityException)
              && isStorageProviderRetryableException(ex);
        }
      };
  public static final String CLEANUP_ON_NAMESPACE_DROP = "CLEANUP_ON_NAMESPACE_DROP";

  private final PolarisEntityManager entityManager;
  private final CallContext callContext;
  private final PolarisResolutionManifestCatalogView resolvedEntityView;
  private final CatalogEntity catalogEntity;
  private final TaskExecutor taskExecutor;
  private String ioImplClassName;
  private FileIO io;
  private String catalogName;
  private long catalogId = -1;
  private String defaultBaseLocation;
  private CloseableGroup closeableGroup;
  private Map<String, String> catalogProperties;

  /**
   * @param entityManager provides handle to underlying PolarisMetaStoreManager with which to
   *     perform mutations on entities.
   * @param callContext the current CallContext
   * @param resolvedEntityView accessor to resolved entity paths that have been pre-vetted to ensure
   *     this catalog instance only interacts with authorized resolved paths.
   * @param taskExecutor Executor we use to register cleanup task handlers
   */
  public BasePolarisCatalog(
      PolarisEntityManager entityManager,
      CallContext callContext,
      PolarisResolutionManifestCatalogView resolvedEntityView,
      TaskExecutor taskExecutor) {
    this.entityManager = entityManager;
    this.callContext = callContext;
    this.resolvedEntityView = resolvedEntityView;
    this.catalogEntity =
        CatalogEntity.of(resolvedEntityView.getResolvedReferenceCatalogEntity().getRawLeafEntity());

    this.taskExecutor = taskExecutor;
    this.catalogId = catalogEntity.getId();
    this.catalogName = catalogEntity.getName();
  }

  @Override
  public String name() {
    return catalogName;
  }

  @TestOnly
  FileIO getIo() {
    return io;
  }

  @Override
  public void initialize(String name, Map<String, String> properties) {
    Preconditions.checkState(
        this.catalogName.equals(name),
        "Tried to initialize catalog as name %s but already constructed with name %s",
        name,
        this.catalogName);

    // Base location from catalogEntity is primary source of truth, otherwise fall through
    // to the same key from the properties map, annd finally fall through to WAREHOUSE_LOCATION.
    String baseLocation =
        Optional.ofNullable(catalogEntity.getDefaultBaseLocation())
            .orElse(
                properties.getOrDefault(
                    CatalogEntity.DEFAULT_BASE_LOCATION_KEY,
                    properties.getOrDefault(CatalogProperties.WAREHOUSE_LOCATION, "")));
    this.defaultBaseLocation = baseLocation.replaceAll("/*$", "");

    Boolean allowSpecifyingFileIoImpl =
        callContext
            .getPolarisCallContext()
            .getConfigurationStore()
            .getConfiguration(
                callContext.getPolarisCallContext(), ALLOW_SPECIFYING_FILE_IO_IMPL, false);

    if (properties.containsKey(CatalogProperties.FILE_IO_IMPL)) {
      ioImplClassName = properties.get(CatalogProperties.FILE_IO_IMPL);

      if (!Boolean.TRUE.equals(allowSpecifyingFileIoImpl)) {
        throw new ValidationException(
            "Cannot set property '%s' to '%s' for this catalog.",
            CatalogProperties.FILE_IO_IMPL, ioImplClassName);
      }
      LOG.debug(
          "Allowing overriding ioImplClassName to {} for storageConfiguration {}",
          ioImplClassName,
          catalogEntity.getStorageConfigurationInfo());
    } else {
      ioImplClassName = catalogEntity.getStorageConfigurationInfo().getFileIoImplClassName();
      LOG.debug(
          "Resolved ioImplClassName {} for storageConfiguration {}",
          ioImplClassName,
          catalogEntity.getStorageConfigurationInfo());
    }
    this.io = loadFileIO(ioImplClassName, properties);

    this.closeableGroup = CallContext.getCurrentContext().closeables();
    closeableGroup.addCloseable(metricsReporter());
    // TODO: FileIO initialization should should happen later depending on the operation so
    // we'd also add it to the closeableGroup later.
    closeableGroup.addCloseable(this.io);
    closeableGroup.setSuppressCloseFailure(true);
    catalogProperties = properties;
  }

  @Override
  protected Map<String, String> properties() {
    return catalogProperties == null ? ImmutableMap.of() : catalogProperties;
  }

  @Override
  public TableBuilder buildTable(TableIdentifier identifier, Schema schema) {
    return new BasePolarisCatalogTableBuilder(identifier, schema);
  }

  @Override
  public ViewBuilder buildView(TableIdentifier identifier) {
    return new BasePolarisCatalogViewBuilder(identifier);
  }

  @Override
  protected TableOperations newTableOps(TableIdentifier tableIdentifier) {
    return new BasePolarisTableOperations(io, tableIdentifier);
  }

  @Override
  protected String defaultWarehouseLocation(TableIdentifier tableIdentifier) {
    return SLASH.join(
        defaultNamespaceLocation(tableIdentifier.namespace()), tableIdentifier.name());
  }

  private String defaultNamespaceLocation(Namespace namespace) {
    if (namespace.isEmpty()) {
      return defaultBaseLocation;
    } else {
      return SLASH.join(defaultBaseLocation, SLASH.join(namespace.levels()));
    }
  }

  @Override
  public boolean dropTable(TableIdentifier tableIdentifier, boolean purge) {
    TableOperations ops = newTableOps(tableIdentifier);
    TableMetadata lastMetadata;
    if (purge && ops.current() != null) {
      lastMetadata = ops.current();
    } else {
      lastMetadata = null;
    }

    Optional<PolarisEntity> storageInfoEntity = findStorageInfo(tableIdentifier);
    if (purge && lastMetadata != null) {
      Map<String, String> credentialsMap =
          storageInfoEntity
              .map(
                  entity ->
                      refreshCredentials(
                          tableIdentifier,
                          Set.of(PolarisStorageActions.READ, PolarisStorageActions.WRITE),
                          lastMetadata.location(),
                          entity))
              .orElse(Map.of());
      Map<String, String> tableProperties = new HashMap<>(lastMetadata.properties());
      tableProperties.putAll(credentialsMap);
      if (!tableProperties.isEmpty()) {
        io = loadFileIO(ioImplClassName, tableProperties);
        // ensure the new fileIO is closed when the catalog is closed
        closeableGroup.addCloseable(io);
      }
    }
    Map<String, String> storageProperties =
        storageInfoEntity
            .map(PolarisEntity::getInternalPropertiesAsMap)
            .map(
                properties -> {
                  if (lastMetadata == null) {
                    return Map.<String, String>of();
                  }
                  Map<String, String> clone = new HashMap<>(properties);
                  clone.put(CatalogProperties.FILE_IO_IMPL, ioImplClassName);
                  try {
                    clone.putAll(io.properties());
                  } catch (UnsupportedOperationException e) {
                    LOG.warn("FileIO doesn't implement properties()");
                  }
                  clone.put(PolarisTaskConstants.STORAGE_LOCATION, lastMetadata.location());
                  return clone;
                })
            .orElse(Map.of());
    PolarisMetaStoreManager.DropEntityResult dropEntityResult =
        dropTableLike(
            catalogId, PolarisEntitySubType.TABLE, tableIdentifier, storageProperties, purge);
    if (!dropEntityResult.isSuccess()) {
      return false;
    }

    if (purge && lastMetadata != null && dropEntityResult.getCleanupTaskId() != null) {
      LOG.info(
          "Scheduled cleanup task {} for table {}",
          dropEntityResult.getCleanupTaskId(),
          tableIdentifier);
      taskExecutor.addTaskHandlerContext(
          dropEntityResult.getCleanupTaskId(), CallContext.getCurrentContext());
    }

    return true;
  }

  @Override
  public List<TableIdentifier> listTables(Namespace namespace) {
    if (!namespaceExists(namespace) && !namespace.isEmpty()) {
      throw new NoSuchNamespaceException(
          "Cannot list tables for namespace. Namespace does not exist: %s", namespace);
    }

    return listTableLike(catalogId, PolarisEntitySubType.TABLE, namespace);
  }

  @Override
  public void renameTable(TableIdentifier from, TableIdentifier to) {
    if (from.equals(to)) {
      return;
    }

    renameTableLike(catalogId, PolarisEntitySubType.TABLE, from, to);
  }

  @Override
  public void createNamespace(Namespace namespace) {
    createNamespace(namespace, Collections.emptyMap());
  }

  @Override
  public void createNamespace(Namespace namespace, Map<String, String> metadata) {
    LOG.debug("Creating namespace {} with metadata {}", namespace, metadata);
    if (namespace.isEmpty()) {
      throw new AlreadyExistsException(
          "Cannot create root namespace, as it already exists implicitly.");
    }

    // TODO: These should really be helpers in core Iceberg Namespace.
    Namespace parentNamespace = PolarisCatalogHelpers.getParentNamespace(namespace);

    PolarisResolvedPathWrapper resolvedParent = resolvedEntityView.getResolvedPath(parentNamespace);
    if (resolvedParent == null) {
      throw new NoSuchNamespaceException(
          "Cannot create namespace %s. Parent namespace does not exist.", namespace);
    }
    createNamespaceInternal(namespace, metadata, resolvedParent);
  }

  private void createNamespaceInternal(
      Namespace namespace,
      Map<String, String> metadata,
      PolarisResolvedPathWrapper resolvedParent) {
    NamespaceEntity entity =
        new NamespaceEntity.Builder(namespace)
            .setCatalogId(getCatalogId())
            .setId(
                entityManager
                    .getMetaStoreManager()
                    .generateNewEntityId(getCurrentPolarisContext())
                    .getId())
            .setParentId(resolvedParent.getRawLeafEntity().getId())
            .setProperties(metadata)
            .setCreateTimestamp(System.currentTimeMillis())
            .build();
    PolarisEntity returnedEntity =
        PolarisEntity.of(
            entityManager
                .getMetaStoreManager()
                .createEntityIfNotExists(
                    getCurrentPolarisContext(),
                    PolarisEntity.toCoreList(resolvedParent.getRawFullPath()),
                    entity));
    if (returnedEntity == null) {
      throw new AlreadyExistsException(
          "Cannot create namespace %s. Namespace already exists", namespace);
    }
  }

  @Override
  public boolean namespaceExists(Namespace namespace) {
    PolarisResolvedPathWrapper resolvedEntities = resolvedEntityView.getResolvedPath(namespace);
    if (resolvedEntities == null) {
      return false;
    }
    return true;
  }

  @Override
  public boolean dropNamespace(Namespace namespace) throws NamespaceNotEmptyException {
    PolarisResolvedPathWrapper resolvedEntities = resolvedEntityView.getResolvedPath(namespace);
    if (resolvedEntities == null) {
      return false;
    }

    List<PolarisEntity> catalogPath = resolvedEntities.getRawParentPath();
    PolarisEntity leafEntity = resolvedEntities.getRawLeafEntity();

    // drop if exists and is empty
    PolarisCallContext polarisCallContext = callContext.getPolarisCallContext();
    PolarisMetaStoreManager.DropEntityResult dropEntityResult =
        entityManager
            .getMetaStoreManager()
            .dropEntityIfExists(
                getCurrentPolarisContext(),
                PolarisEntity.toCoreList(catalogPath),
                leafEntity,
                Map.of(),
                polarisCallContext
                    .getConfigurationStore()
                    .getConfiguration(polarisCallContext, CLEANUP_ON_NAMESPACE_DROP, false));

    if (!dropEntityResult.isSuccess() && dropEntityResult.failedBecauseNotEmpty()) {
      throw new NamespaceNotEmptyException("Namespace %s is not empty", namespace);
    }

    // return status of drop operation
    return dropEntityResult.isSuccess();
  }

  @Override
  public boolean setProperties(Namespace namespace, Map<String, String> properties)
      throws NoSuchNamespaceException {
    PolarisResolvedPathWrapper resolvedEntities = resolvedEntityView.getResolvedPath(namespace);
    if (resolvedEntities == null) {
      throw new NoSuchNamespaceException("Namespace does not exist: %s", namespace);
    }
    PolarisEntity entity = resolvedEntities.getRawLeafEntity();
    Map<String, String> newProperties = new HashMap<>(entity.getPropertiesAsMap());

    // Merge new properties into existing map.
    newProperties.putAll(properties);
    PolarisEntity updatedEntity =
        new PolarisEntity.Builder(entity).setProperties(newProperties).build();

    List<PolarisEntity> parentPath = resolvedEntities.getRawFullPath();
    PolarisEntity returnedEntity =
        Optional.ofNullable(
                entityManager
                    .getMetaStoreManager()
                    .updateEntityPropertiesIfNotChanged(
                        getCurrentPolarisContext(),
                        PolarisEntity.toCoreList(parentPath),
                        updatedEntity)
                    .getEntity())
            .map(PolarisEntity::new)
            .orElse(null);
    if (returnedEntity == null) {
      throw new RuntimeException("Concurrent modification of namespace: " + namespace);
    }
    return true;
  }

  @Override
  public boolean removeProperties(Namespace namespace, Set<String> properties)
      throws NoSuchNamespaceException {
    PolarisResolvedPathWrapper resolvedEntities = resolvedEntityView.getResolvedPath(namespace);
    if (resolvedEntities == null) {
      throw new NoSuchNamespaceException("Namespace does not exist: %s", namespace);
    }
    PolarisEntity entity = resolvedEntities.getRawLeafEntity();

    Map<String, String> updatedProperties = new HashMap<>(entity.getPropertiesAsMap());
    properties.forEach(updatedProperties::remove);

    PolarisEntity updatedEntity =
        new PolarisEntity.Builder(entity).setProperties(updatedProperties).build();

    List<PolarisEntity> parentPath = resolvedEntities.getRawFullPath();
    PolarisEntity returnedEntity =
        Optional.ofNullable(
                entityManager
                    .getMetaStoreManager()
                    .updateEntityPropertiesIfNotChanged(
                        getCurrentPolarisContext(),
                        PolarisEntity.toCoreList(parentPath),
                        updatedEntity)
                    .getEntity())
            .map(PolarisEntity::new)
            .orElse(null);
    if (returnedEntity == null) {
      throw new RuntimeException("Concurrent modification of namespace: " + namespace);
    }
    return true;
  }

  @Override
  public Map<String, String> loadNamespaceMetadata(Namespace namespace)
      throws NoSuchNamespaceException {
    PolarisResolvedPathWrapper resolvedEntities = resolvedEntityView.getResolvedPath(namespace);
    if (resolvedEntities == null) {
      throw new NoSuchNamespaceException("Namespace does not exist: %s", namespace);
    }
    NamespaceEntity entity = NamespaceEntity.of(resolvedEntities.getRawLeafEntity());
    Preconditions.checkState(
        entity.getParentNamespace().equals(PolarisCatalogHelpers.getParentNamespace(namespace)),
        "Mismatched stored parentNamespace '%s' vs looked up parentNamespace '%s",
        entity.getParentNamespace(),
        PolarisCatalogHelpers.getParentNamespace(namespace));

    return entity.getPropertiesAsMap();
  }

  @Override
  public List<Namespace> listNamespaces() {
    return listNamespaces(Namespace.empty());
  }

  @Override
  public List<Namespace> listNamespaces(Namespace namespace) throws NoSuchNamespaceException {
    PolarisResolvedPathWrapper resolvedEntities = resolvedEntityView.getResolvedPath(namespace);
    if (resolvedEntities == null) {
      throw new NoSuchNamespaceException("Namespace does not exist: %s", namespace);
    }

    List<PolarisEntity> catalogPath = resolvedEntities.getRawFullPath();
    List<PolarisEntity.NameAndId> entities =
        PolarisEntity.toNameAndIdList(
            entityManager
                .getMetaStoreManager()
                .listEntities(
                    getCurrentPolarisContext(),
                    PolarisEntity.toCoreList(catalogPath),
                    PolarisEntityType.NAMESPACE,
                    PolarisEntitySubType.NULL_SUBTYPE)
                .getEntities());
    return PolarisCatalogHelpers.nameAndIdToNamespaces(catalogPath, entities);
  }

  @Override
  public void close() throws IOException {}

  @Override
  public List<TableIdentifier> listViews(Namespace namespace) {
    if (!namespaceExists(namespace) && !namespace.isEmpty()) {
      throw new NoSuchNamespaceException(
          "Cannot list views for namespace. Namespace does not exist: %s", namespace);
    }

    return listTableLike(catalogId, PolarisEntitySubType.VIEW, namespace);
  }

  @Override
  protected BasePolarisViewOperations newViewOps(TableIdentifier identifier) {
    return new BasePolarisViewOperations(io, identifier);
  }

  @Override
  public boolean dropView(TableIdentifier identifier) {
    return dropTableLike(catalogId, PolarisEntitySubType.VIEW, identifier, Map.of(), true)
        .isSuccess();
  }

  @Override
  public void renameView(TableIdentifier from, TableIdentifier to) {
    if (from.equals(to)) {
      return;
    }

    renameTableLike(catalogId, PolarisEntitySubType.VIEW, from, to);
  }

  @Override
  public boolean sendNotification(
      TableIdentifier identifier, NotificationRequest notificationRequest) {
    return sendNotificationForTableLike(
        catalogId, PolarisEntitySubType.TABLE, identifier, notificationRequest);
  }

  @Override
  public Map<String, String> getCredentialConfig(
      TableIdentifier tableIdentifier,
      TableMetadata tableMetadata,
      Set<PolarisStorageActions> storageActions) {
    Optional<PolarisEntity> storageInfo = findStorageInfo(tableIdentifier);
    if (storageInfo.isEmpty()) {
      LOG.atWarn()
          .addKeyValue("tableIdentifier", tableIdentifier)
          .log("Table entity has no storage configuration in its hierarchy");
      return Map.of();
    }
    return refreshCredentials(
        tableIdentifier, storageActions, tableMetadata.location(), storageInfo.get());
  }

  /**
   * Based on configuration settings, for callsites that need to handle potentially setting a new
   * base location for a TableLike entity, produces the transformed location if applicable, or else
   * the unaltered specified location.
   */
  public String transformTableLikeLocation(String specifiedTableLikeLocation) {
    String replaceNewLocationPrefix = catalogEntity.getReplaceNewLocationPrefixWithCatalogDefault();
    if (specifiedTableLikeLocation != null
        && replaceNewLocationPrefix != null
        && specifiedTableLikeLocation.startsWith(replaceNewLocationPrefix)) {
      String modifiedLocation =
          defaultBaseLocation
              + specifiedTableLikeLocation.substring(replaceNewLocationPrefix.length());
      LOG.atDebug()
          .addKeyValue("specifiedTableLikeLocation", specifiedTableLikeLocation)
          .addKeyValue("modifiedLocation", modifiedLocation)
          .log("Translating specifiedTableLikeLocation based on config");
      return modifiedLocation;
    }
    return specifiedTableLikeLocation;
  }

  private @NotNull Optional<PolarisEntity> findStorageInfo(TableIdentifier tableIdentifier) {
    PolarisResolvedPathWrapper resolvedTableEntities =
        resolvedEntityView.getResolvedPath(tableIdentifier, PolarisEntitySubType.TABLE);

    PolarisResolvedPathWrapper resolvedStorageEntity =
        resolvedTableEntities == null
            ? resolvedEntityView.getResolvedPath(tableIdentifier.namespace())
            : resolvedTableEntities;

    return findStorageInfoFromHierarchy(resolvedStorageEntity);
  }

  private Map<String, String> refreshCredentials(
      TableIdentifier tableIdentifier,
      Set<PolarisStorageActions> storageActions,
      String tableLocation,
      PolarisEntity entity) {
    // Important: Any locations added to the set of requested locations need to be validated
    // prior to requested subscoped credentials.
    validateLocationForTableLike(tableIdentifier, tableLocation);

    boolean allowList =
        storageActions.contains(PolarisStorageActions.LIST)
            || storageActions.contains(PolarisStorageActions.ALL);
    Set<String> writeLocations =
        storageActions.contains(PolarisStorageActions.WRITE)
                || storageActions.contains(PolarisStorageActions.DELETE)
                || storageActions.contains(PolarisStorageActions.ALL)
            ? Set.of(tableLocation)
            : Set.of();
    Map<String, String> credentialsMap =
        entityManager
            .getCredentialCache()
            .getOrGenerateSubScopeCreds(
                entityManager.getMetaStoreManager(),
                callContext.getPolarisCallContext(),
                entity,
                allowList,
                Set.of(tableLocation),
                writeLocations);
    LOG.atDebug()
        .addKeyValue("tableIdentifier", tableIdentifier)
        .addKeyValue("credentialKeys", credentialsMap.keySet())
        .log("Loaded scoped credentials for table");
    if (credentialsMap.isEmpty()) {
      LOG.debug("No credentials found for table");
    }
    return credentialsMap;
  }

  /**
   * Validates that the specified {@code location} is valid for whatever storage config is found for
   * this TableLike's parent hierarchy.
   */
  private void validateLocationForTableLike(TableIdentifier identifier, String location) {
    PolarisResolvedPathWrapper resolvedStorageEntity =
        resolvedEntityView.getResolvedPath(identifier, PolarisEntitySubType.ANY_SUBTYPE);
    if (resolvedStorageEntity == null) {
      resolvedStorageEntity = resolvedEntityView.getResolvedPath(identifier.namespace());
    }

    validateLocationForTableLike(identifier, location, resolvedStorageEntity);
  }

  /**
   * Validates that the specified {@code location} is valid for whatever storage config is found for
   * this TableLike's parent hierarchy.
   */
  private void validateLocationForTableLike(
      TableIdentifier identifier,
      String location,
      PolarisResolvedPathWrapper resolvedStorageEntity) {
    Optional<PolarisEntity> storageInfoHolder = findStorageInfoFromHierarchy(resolvedStorageEntity);
    storageInfoHolder.ifPresentOrElse(
        storageInfoHolderEntity -> {
          // Though the storage entity may not actually be a CatalogEntity, we just use the
          // CatalogEntity wrapper here for a convenient deserializer helper method.
          PolarisStorageConfigurationInfo storageConfigInfo =
              new CatalogEntity(storageInfoHolderEntity).getStorageConfigurationInfo();
          Map<String, Map<PolarisStorageActions, PolarisStorageIntegration.ValidationResult>>
              validationResults =
                  InMemoryStorageIntegration.validateSubpathsOfAllowedLocations(
                      storageConfigInfo, Set.of(PolarisStorageActions.ALL), Set.of(location));
          validationResults.values().stream()
              .forEach(
                  actionResult ->
                      actionResult.values().stream()
                          .forEach(
                              result -> {
                                if (!result.isSuccess()) {
                                  throw new ForbiddenException(
                                      "Invalid location '%s' for identifier '%s': %s",
                                      location, identifier, result.getMessage());
                                }
                              }));

          // TODO: Consider exposing a property to control whether to use the explicit default
          // in-memory PolarisStorageIntegration implementation to perform validation or
          // whether to delegate to PolarisMetaStoreManager::validateAccessToLocations.
          // Usually the validation is better to perform with local business logic, but if
          // there are additional rules to be evaluated by a custom PolarisMetaStoreManager
          // implementation, then the validation should go through that API instead as follows:
          //
          // PolarisMetaStoreManager.ValidateAccessResult validateResult =
          //     entityManager.getMetaStoreManager().validateAccessToLocations(
          //         getCurrentPolarisContext(),
          //         storageInfoHolderEntity.getCatalogId(),
          //         storageInfoHolderEntity.getId(),
          //         Set.of(PolarisStorageActions.ALL),
          //         Set.of(location));
          // if (!validateResult.isSuccess()) {
          //   throw new ForbiddenException("Invalid location '%s' for identifier '%s': %s",
          //       location, identifier, validateResult.getExtraInformation());
          // }
        },
        () -> {
          if (location.startsWith("file:") || location.startsWith("http")) {
            throw new ForbiddenException(
                "Invalid location '%s' for identifier '%s': File locations are not allowed",
                location, identifier);
          }
        });
  }

  private class BasePolarisCatalogTableBuilder
      extends BaseMetastoreViewCatalog.BaseMetastoreViewCatalogTableBuilder {
    private final TableIdentifier identifier;

    public BasePolarisCatalogTableBuilder(TableIdentifier identifier, Schema schema) {
      super(identifier, schema);
      this.identifier = identifier;
    }

    @Override
    public TableBuilder withLocation(String newLocation) {
      return super.withLocation(transformTableLikeLocation(newLocation));
    }
  }

  private class BasePolarisCatalogViewBuilder extends BaseMetastoreViewCatalog.BaseViewBuilder {
    private final TableIdentifier identifier;

    public BasePolarisCatalogViewBuilder(TableIdentifier identifier) {
      super(identifier);
      this.identifier = identifier;
    }

    @Override
    public ViewBuilder withLocation(String newLocation) {
      return super.withLocation(transformTableLikeLocation(newLocation));
    }
  }

  private class BasePolarisTableOperations extends BaseMetastoreTableOperations {
    private final TableIdentifier tableIdentifier;
    private final String fullTableName;
    private FileIO fileIO;

    BasePolarisTableOperations(FileIO defaultFileIO, TableIdentifier tableIdentifier) {
      LOG.debug("new BasePolarisTableOperations for {}", tableIdentifier);
      this.tableIdentifier = tableIdentifier;
      this.fullTableName = fullTableName(catalogName, tableIdentifier);
      this.fileIO = defaultFileIO;
    }

    @Override
    public void doRefresh() {
      LOG.debug("doRefresh for tableIdentifier {}", tableIdentifier);
      // While doing refresh/commit protocols, we must fetch the fresh "passthrough" resolved
      // table entity instead of the statically-resolved authz resolution set.
      PolarisResolvedPathWrapper resolvedEntities =
          resolvedEntityView.getPassthroughResolvedPath(
              tableIdentifier, PolarisEntitySubType.TABLE);
      TableLikeEntity entity = null;

      if (resolvedEntities != null) {
        entity = TableLikeEntity.of(resolvedEntities.getRawLeafEntity());
        if (!tableIdentifier.equals(entity.getTableIdentifier())) {
          LOG.atError()
              .addKeyValue("entity.getTableIdentifier()", entity.getTableIdentifier())
              .addKeyValue("tableIdentifier", tableIdentifier)
              .log("Stored entity identifier mismatches requested identifier");
        }
      }

      String latestLocation = entity != null ? entity.getMetadataLocation() : null;
      LOG.debug("Refreshing latestLocation: {}", latestLocation);
      if (latestLocation == null) {
        disableRefresh();
      } else {
        refreshFromMetadataLocation(
            latestLocation,
            SHOULD_RETRY_REFRESH_PREDICATE,
            MAX_RETRIES,
            metadataLocation -> {
              FileIO fileIO = this.fileIO;
              boolean closeFileIO = false;
              PolarisResolvedPathWrapper resolvedStorageEntity =
                  resolvedEntities == null
                      ? resolvedEntityView.getResolvedPath(tableIdentifier.namespace())
                      : resolvedEntities;
              String latestLocationDir =
                  latestLocation.substring(0, latestLocation.lastIndexOf('/'));
              Optional<PolarisEntity> storageInfoEntity =
                  findStorageInfoFromHierarchy(resolvedStorageEntity);
              Map<String, String> credentialsMap =
                  storageInfoEntity
                      .map(
                          storageInfo ->
                              refreshCredentials(
                                  tableIdentifier,
                                  Set.of(PolarisStorageActions.READ),
                                  latestLocationDir,
                                  storageInfo))
                      .orElse(Map.of());
              if (!credentialsMap.isEmpty()) {
                String ioImpl = fileIO.getClass().getName();
                fileIO = loadFileIO(ioImpl, credentialsMap);
                closeFileIO = true;
              }
              try {
                return TableMetadataParser.read(fileIO, metadataLocation);
              } finally {
                if (closeFileIO) {
                  fileIO.close();
                }
              }
            });
      }
    }

    @Override
    public void doCommit(TableMetadata base, TableMetadata metadata) {
      LOG.debug("doCommit for {} with base {}, metadata {}", tableIdentifier, base, metadata);
      // TODO: Maybe avoid writing metadata if there's definitely a transaction conflict
      if (null == base && !namespaceExists(tableIdentifier.namespace())) {
        throw new NoSuchNamespaceException(
            "Cannot create table %s. Namespace does not exist: %s",
            tableIdentifier, tableIdentifier.namespace());
      }

      PolarisResolvedPathWrapper resolvedTableEntities =
          resolvedEntityView.getPassthroughResolvedPath(
              tableIdentifier, PolarisEntitySubType.TABLE);

      // Fetch credentials for the resolved entity. The entity could be the table itself (if it has
      // already been stored and credentials have been configured directly) or it could be the
      // table's namespace or catalog.
      PolarisResolvedPathWrapper resolvedStorageEntity =
          resolvedTableEntities == null
              ? resolvedEntityView.getResolvedPath(tableIdentifier.namespace())
              : resolvedTableEntities;

      if (base == null || !metadata.location().equals(base.location())) {
        // If location is changing then we must validate that the requested location is valid
        // for the storage configuration inherited under this entity's path.
        validateLocationForTableLike(tableIdentifier, metadata.location(), resolvedStorageEntity);
      }

      Optional<PolarisEntity> storageInfoEntity =
          findStorageInfoFromHierarchy(resolvedStorageEntity);
      Map<String, String> credentialsMap =
          storageInfoEntity
              .map(
                  storageInfo ->
                      refreshCredentials(
                          tableIdentifier,
                          Set.of(PolarisStorageActions.READ, PolarisStorageActions.WRITE),
                          metadata.location(),
                          storageInfo))
              .orElse(Map.of());

      // Update the FileIO before we write the new metadata file
      // update with table properties in case there are table-level overrides
      // the credentials should always override table-level properties, since
      // storage configuration will be found at whatever entity defines it
      Map<String, String> tableProperties = new HashMap<>(metadata.properties());
      tableProperties.putAll(credentialsMap);
      if (!tableProperties.isEmpty()) {
        String ioImpl = fileIO.getClass().getName();
        fileIO = loadFileIO(ioImpl, tableProperties);
        // ensure the new fileIO is closed when the catalog is closed
        closeableGroup.addCloseable(fileIO);
      }

      String newLocation = writeNewMetadataIfRequired(base == null, metadata);
      String oldLocation = base == null ? null : base.metadataFileLocation();

      PolarisResolvedPathWrapper resolvedView =
          resolvedEntityView.getPassthroughResolvedPath(tableIdentifier, PolarisEntitySubType.VIEW);
      if (resolvedView != null) {
        throw new AlreadyExistsException("View with same name already exists: %s", tableIdentifier);
      }

      // TODO: Consider using the entity from doRefresh() directly to do the conflict detection
      // instead of a two-layer CAS (checking metadataLocation to detect concurrent modification
      // between doRefresh() and doCommit(), and then updateEntityPropertiesIfNotChanged to detect
      // concurrent
      // modification between our checking of unchanged metadataLocation here and actual
      // persistence-layer commit).
      PolarisResolvedPathWrapper resolvedEntities =
          resolvedEntityView.getPassthroughResolvedPath(
              tableIdentifier, PolarisEntitySubType.TABLE);
      TableLikeEntity entity =
          TableLikeEntity.of(resolvedEntities == null ? null : resolvedEntities.getRawLeafEntity());
      String existingLocation;
      if (null == entity) {
        existingLocation = null;
        entity =
            new TableLikeEntity.Builder(tableIdentifier, newLocation)
                .setCatalogId(getCatalogId())
                .setSubType(PolarisEntitySubType.TABLE)
                .setId(
                    entityManager
                        .getMetaStoreManager()
                        .generateNewEntityId(getCurrentPolarisContext())
                        .getId())
                .build();
      } else {
        existingLocation = entity.getMetadataLocation();
        entity = new TableLikeEntity.Builder(entity).setMetadataLocation(newLocation).build();
      }
      if (!Objects.equal(existingLocation, oldLocation)) {
        if (null == base) {
          throw new AlreadyExistsException("Table already exists: %s", tableName());
        }

        if (null == existingLocation) {
          throw new NoSuchTableException("Table does not exist: %s", tableName());
        }

        throw new CommitFailedException(
            "Cannot commit to table %s metadata location from %s to %s "
                + "because it has been concurrently modified to %s",
            tableIdentifier, oldLocation, newLocation, existingLocation);
      }
      if (null == existingLocation) {
        createTableLike(catalogId, tableIdentifier, entity);
      } else {
        updateTableLike(catalogId, tableIdentifier, entity);
      }
    }

    @Override
    public FileIO io() {
      return fileIO;
    }

    @Override
    protected String tableName() {
      return fullTableName;
    }
  }

  private static @NotNull Optional<PolarisEntity> findStorageInfoFromHierarchy(
      PolarisResolvedPathWrapper resolvedStorageEntity) {
    Optional<PolarisEntity> storageInfoEntity =
        resolvedStorageEntity.getRawFullPath().reversed().stream()
            .filter(
                e ->
                    e.getInternalPropertiesAsMap()
                        .containsKey(PolarisEntityConstants.getStorageConfigInfoPropertyName()))
            .findFirst();
    return storageInfoEntity;
  }

  private class BasePolarisViewOperations extends BaseViewOperations {
    private final TableIdentifier identifier;
    private final String fullViewName;
    private FileIO io;

    BasePolarisViewOperations(FileIO io, TableIdentifier identifier) {
      this.io = io;
      this.identifier = identifier;
      this.fullViewName = ViewUtil.fullViewName(catalogName, identifier);
    }

    @Override
    public void doRefresh() {
      PolarisResolvedPathWrapper resolvedEntities =
          resolvedEntityView.getPassthroughResolvedPath(identifier, PolarisEntitySubType.VIEW);
      TableLikeEntity entity = null;

      if (resolvedEntities != null) {
        entity = TableLikeEntity.of(resolvedEntities.getRawLeafEntity());
        if (!identifier.equals(entity.getTableIdentifier())) {
          LOG.atError()
              .addKeyValue("entity.getTableIdentifier()", entity.getTableIdentifier())
              .addKeyValue("identifier", identifier)
              .log("Stored entity identifier mismatches requested identifier");
        }
      }

      String latestLocation = entity != null ? entity.getMetadataLocation() : null;
      LOG.debug("Refreshing view latestLocation: {}", latestLocation);
      if (latestLocation == null) {
        disableRefresh();
      } else {
        refreshFromMetadataLocation(
            latestLocation,
            SHOULD_RETRY_REFRESH_PREDICATE,
            MAX_RETRIES,
            metadataLocation -> {
              FileIO fileIO = this.io;
              boolean closeFileIO = false;
              PolarisResolvedPathWrapper resolvedStorageEntity =
                  resolvedEntities == null
                      ? resolvedEntityView.getResolvedPath(identifier.namespace())
                      : resolvedEntities;
              String latestLocationDir =
                  latestLocation.substring(0, latestLocation.lastIndexOf('/'));
              Optional<PolarisEntity> storageInfoEntity =
                  findStorageInfoFromHierarchy(resolvedStorageEntity);
              Map<String, String> credentialsMap =
                  storageInfoEntity
                      .map(
                          storageInfo ->
                              refreshCredentials(
                                  identifier,
                                  Set.of(PolarisStorageActions.READ),
                                  latestLocationDir,
                                  storageInfo))
                      .orElse(Map.of());
              if (!credentialsMap.isEmpty()) {
                String ioImpl = fileIO.getClass().getName();
                fileIO = loadFileIO(ioImpl, credentialsMap);
                closeFileIO = true;
              }
              try {
                return ViewMetadataParser.read(fileIO.newInputFile(metadataLocation));
              } finally {
                if (closeFileIO) {
                  fileIO.close();
                }
              }
            });
      }
    }

    @Override
    public void doCommit(ViewMetadata base, ViewMetadata metadata) {
      // TODO: Maybe avoid writing metadata if there's definitely a transaction conflict
      LOG.debug("doCommit for {} with base {}, metadata {}", identifier, base, metadata);
      if (null == base && !namespaceExists(identifier.namespace())) {
        throw new NoSuchNamespaceException(
            "Cannot create view %s. Namespace does not exist: %s",
            identifier, identifier.namespace());
      }

      PolarisResolvedPathWrapper resolvedTable =
          resolvedEntityView.getPassthroughResolvedPath(identifier, PolarisEntitySubType.TABLE);
      if (resolvedTable != null) {
        throw new AlreadyExistsException("Table with same name already exists: %s", identifier);
      }

      PolarisResolvedPathWrapper resolvedEntities =
          resolvedEntityView.getPassthroughResolvedPath(identifier, PolarisEntitySubType.VIEW);

      // Fetch credentials for the resolved entity. The entity could be the view itself (if it has
      // already been stored and credentials have been configured directly) or it could be the
      // table's namespace or catalog.
      PolarisResolvedPathWrapper resolvedStorageEntity =
          resolvedEntities == null
              ? resolvedEntityView.getResolvedPath(identifier.namespace())
              : resolvedEntities;

      if (base == null || !metadata.location().equals(base.location())) {
        // If location is changing then we must validate that the requested location is valid
        // for the storage configuration inherited under this entity's path.
        validateLocationForTableLike(identifier, metadata.location(), resolvedStorageEntity);
      }

      Optional<PolarisEntity> storageInfoEntity =
          findStorageInfoFromHierarchy(resolvedStorageEntity);
      Map<String, String> credentialsMap =
          storageInfoEntity
              .map(
                  storageInfo ->
                      refreshCredentials(
                          identifier,
                          Set.of(PolarisStorageActions.READ, PolarisStorageActions.WRITE),
                          metadata.location(),
                          storageInfo))
              .orElse(Map.of());

      // Update the FileIO before we write the new metadata file
      // update with table properties in case there are table-level overrides
      // the credentials should always override table-level properties, since
      // storage configuration will be found at whatever entity defines it
      Map<String, String> tableProperties = new HashMap<>(metadata.properties());
      tableProperties.putAll(credentialsMap);
      if (!tableProperties.isEmpty()) {
        String ioImpl = io.getClass().getName();
        io = loadFileIO(ioImpl, tableProperties);
        // ensure the new fileIO is closed when the catalog is closed
        closeableGroup.addCloseable(io);
      }
      String newLocation = writeNewMetadataIfRequired(metadata);
      String oldLocation = base == null ? null : currentMetadataLocation();

      if (null == base && !namespaceExists(identifier.namespace())) {
        throw new NoSuchNamespaceException(
            "Cannot create view %s. Namespace does not exist: %s",
            identifier, identifier.namespace());
      }

      TableLikeEntity entity =
          TableLikeEntity.of(resolvedEntities == null ? null : resolvedEntities.getRawLeafEntity());
      String existingLocation;
      if (null == entity) {
        existingLocation = null;
        entity =
            new TableLikeEntity.Builder(identifier, newLocation)
                .setCatalogId(getCatalogId())
                .setSubType(PolarisEntitySubType.VIEW)
                .setId(
                    entityManager
                        .getMetaStoreManager()
                        .generateNewEntityId(getCurrentPolarisContext())
                        .getId())
                .build();
      } else {
        existingLocation = entity.getMetadataLocation();
        entity = new TableLikeEntity.Builder(entity).setMetadataLocation(newLocation).build();
      }
      if (!Objects.equal(existingLocation, oldLocation)) {
        if (null == base) {
          throw new AlreadyExistsException("View already exists: %s", identifier);
        }

        if (null == existingLocation) {
          throw new NoSuchViewException("View does not exist: %s", identifier);
        }

        throw new CommitFailedException(
            "Cannot commit to view %s metadata location from %s to %s "
                + "because it has been concurrently modified to %s",
            identifier, oldLocation, newLocation, existingLocation);
      }
      if (null == existingLocation) {
        createTableLike(catalogId, identifier, entity);
      } else {
        updateTableLike(catalogId, identifier, entity);
      }
    }

    @Override
    public FileIO io() {
      return io;
    }

    @Override
    protected String viewName() {
      return fullViewName;
    }
  }

  private PolarisCallContext getCurrentPolarisContext() {
    return callContext.getPolarisCallContext();
  }

  @VisibleForTesting
  long getCatalogId() {
    // TODO: Properly handle initialization
    if (catalogId <= 0) {
      throw new RuntimeException(
          "Failed to initialize catalogId before using catalog with name: " + catalogName);
    }
    return catalogId;
  }

  private void renameTableLike(
      long catalogId, PolarisEntitySubType subType, TableIdentifier from, TableIdentifier to) {
    LOG.debug("Renaming tableLike from {} to {}", from, to);
    PolarisResolvedPathWrapper resolvedEntities = resolvedEntityView.getResolvedPath(from, subType);
    if (resolvedEntities == null) {
      if (subType == PolarisEntitySubType.VIEW) {
        throw new NoSuchViewException("Cannot rename %s to %s. View does not exist", from, to);
      } else {
        throw new NoSuchTableException("Cannot rename %s to %s. Table does not exist", from, to);
      }
    }
    List<PolarisEntity> catalogPath = resolvedEntities.getRawParentPath();
    PolarisEntity leafEntity = resolvedEntities.getRawLeafEntity();
    final TableLikeEntity toEntity;
    List<PolarisEntity> newCatalogPath = null;
    if (!from.namespace().equals(to.namespace())) {
      PolarisResolvedPathWrapper resolvedNewParentEntities =
          resolvedEntityView.getResolvedPath(to.namespace());
      if (resolvedNewParentEntities == null) {
        throw new NoSuchNamespaceException(
            "Cannot rename %s to %s. Namespace does not exist: %s", from, to, to.namespace());
      }
      newCatalogPath = resolvedNewParentEntities.getRawFullPath();

      // the "to" table has a new parent and a new name / namespace path
      toEntity =
          new TableLikeEntity.Builder(TableLikeEntity.of(leafEntity))
              .setTableIdentifier(to)
              .setParentId(resolvedNewParentEntities.getResolvedLeafEntity().getEntity().getId())
              .build();
    } else {
      // only the name of the entity is changed
      toEntity =
          new TableLikeEntity.Builder(TableLikeEntity.of(leafEntity))
              .setTableIdentifier(to)
              .build();
    }

    // rename the entity now
    PolarisMetaStoreManager.EntityResult returnedEntityResult =
        entityManager
            .getMetaStoreManager()
            .renameEntity(
                getCurrentPolarisContext(),
                PolarisEntity.toCoreList(catalogPath),
                leafEntity,
                PolarisEntity.toCoreList(newCatalogPath),
                toEntity);

    // handle error
    if (!returnedEntityResult.isSuccess()) {
      LOG.debug(
          "Rename error {} trying to rename {} to {}. Checking existing object.",
          returnedEntityResult.getReturnStatus(),
          from,
          to);
      switch (returnedEntityResult.getReturnStatus()) {
        case PolarisMetaStoreManager.ReturnStatus.ENTITY_ALREADY_EXISTS:
          {
            PolarisEntitySubType existingEntitySubType =
                returnedEntityResult.getAlreadyExistsEntitySubType();
            if (existingEntitySubType == null) {
              // this code path is unexpected
              throw new AlreadyExistsException(
                  "Cannot rename %s to %s. Object %s already exists", from, to);
            } else if (existingEntitySubType == PolarisEntitySubType.TABLE) {
              throw new AlreadyExistsException(
                  "Cannot rename %s to %s. Table already exists", from, to);
            } else if (existingEntitySubType == PolarisEntitySubType.VIEW) {
              throw new AlreadyExistsException(
                  "Cannot rename %s to %s. View already exists", from, to);
            }
          }

        case PolarisMetaStoreManager.ReturnStatus.ENTITY_NOT_FOUND:
          throw new NotFoundException("Cannot rename %s to %s. %s does not exist", from, to, from);

          // this is temporary. Should throw a special error that will be caught and retried
        case PolarisMetaStoreManager.ReturnStatus.TARGET_ENTITY_CONCURRENTLY_MODIFIED:
        case PolarisMetaStoreManager.ReturnStatus.ENTITY_CANNOT_BE_RESOLVED:
          throw new RuntimeException("concurrent update detected, please retry");

          // some entities cannot be renamed
        case PolarisMetaStoreManager.ReturnStatus.ENTITY_CANNOT_BE_RENAMED:
          throw new BadRequestException("Cannot rename built-in object " + leafEntity.getName());

          // some entities cannot be renamed
        default:
          throw new IllegalStateException(
              "Unknown error status " + returnedEntityResult.getReturnStatus());
      }
    } else {
      TableLikeEntity returnedEntity = TableLikeEntity.of(returnedEntityResult.getEntity());
      if (!toEntity.getTableIdentifier().equals(returnedEntity.getTableIdentifier())) {
        // As long as there are older deployments which don't support the atomic update of the
        // internalProperties during rename, we can log and then patch it up explicitly
        // in a best-effort way.
        LOG.atError()
            .addKeyValue("toEntity.getTableIdentifier()", toEntity.getTableIdentifier())
            .addKeyValue("returnedEntity.getTableIdentifier()", returnedEntity.getTableIdentifier())
            .log("Returned entity identifier doesn't match toEntity identifier");
        entityManager
            .getMetaStoreManager()
            .updateEntityPropertiesIfNotChanged(
                getCurrentPolarisContext(),
                PolarisEntity.toCoreList(newCatalogPath),
                new TableLikeEntity.Builder(returnedEntity).setTableIdentifier(to).build());
      }
    }
  }

  /**
   * Caller must fill in all entity fields except parentId, since the caller may not want to
   * duplicate the logic to try to reolve parentIds before constructing the proposed entity. This
   * method will fill in the parentId if needed upon resolution.
   */
  private void createTableLike(long catalogId, TableIdentifier identifier, PolarisEntity entity) {
    PolarisResolvedPathWrapper resolvedParent =
        resolvedEntityView.getResolvedPath(identifier.namespace());
    if (resolvedParent == null) {
      // Illegal state because the namespace should've already been in the static resolution set.
      throw new IllegalStateException(
          String.format("Failed to fetch resolved parent for TableIdentifier '%s'", identifier));
    }

    createTableLike(catalogId, identifier, entity, resolvedParent);
  }

  private void createTableLike(
      long catalogId,
      TableIdentifier identifier,
      PolarisEntity entity,
      PolarisResolvedPathWrapper resolvedParent) {
    // Make sure the metadata file is valid for our allowed locations.
    String metadataLocation = TableLikeEntity.of(entity).getMetadataLocation();
    validateLocationForTableLike(identifier, metadataLocation, resolvedParent);

    List<PolarisEntity> catalogPath = resolvedParent.getRawFullPath();

    if (entity.getParentId() <= 0) {
      // TODO: Validate catalogPath size is at least 1 for catalog entity?
      entity =
          new PolarisEntity.Builder(entity)
              .setParentId(resolvedParent.getRawLeafEntity().getId())
              .build();
    }
    entity =
        new PolarisEntity.Builder(entity).setCreateTimestamp(System.currentTimeMillis()).build();

    PolarisEntity returnedEntity =
        PolarisEntity.of(
            entityManager
                .getMetaStoreManager()
                .createEntityIfNotExists(
                    getCurrentPolarisContext(), PolarisEntity.toCoreList(catalogPath), entity));
    LOG.debug("Created TableLike entity {} with TableIdentifier {}", entity, identifier);
    if (returnedEntity == null) {
      // TODO: Error or retry?
    }
  }

  private void updateTableLike(long catalogId, TableIdentifier identifier, PolarisEntity entity) {
    PolarisResolvedPathWrapper resolvedEntities =
        resolvedEntityView.getResolvedPath(identifier, entity.getSubType());
    if (resolvedEntities == null) {
      // Illegal state because the identifier should've already been in the static resolution set.
      throw new IllegalStateException(
          String.format("Failed to fetch resolved TableIdentifier '%s'", identifier));
    }

    // Make sure the metadata file is valid for our allowed locations.
    String metadataLocation = TableLikeEntity.of(entity).getMetadataLocation();
    validateLocationForTableLike(identifier, metadataLocation, resolvedEntities);

    List<PolarisEntity> catalogPath = resolvedEntities.getRawParentPath();
    PolarisEntity returnedEntity =
        Optional.ofNullable(
                entityManager
                    .getMetaStoreManager()
                    .updateEntityPropertiesIfNotChanged(
                        getCurrentPolarisContext(), PolarisEntity.toCoreList(catalogPath), entity)
                    .getEntity())
            .map(PolarisEntity::new)
            .orElse(null);
    if (returnedEntity == null) {
      // TODO: Error or retry?
    }
  }

  private @NotNull PolarisMetaStoreManager.DropEntityResult dropTableLike(
      long catalogId,
      PolarisEntitySubType subType,
      TableIdentifier identifier,
      Map<String, String> storageProperties,
      boolean purge) {
    PolarisResolvedPathWrapper resolvedEntities =
        resolvedEntityView.getResolvedPath(identifier, subType);
    if (resolvedEntities == null) {
      // TODO: Error?
      return new PolarisMetaStoreManager.DropEntityResult(
          PolarisMetaStoreManager.ReturnStatus.ENTITY_NOT_FOUND, null);
    }

    List<PolarisEntity> catalogPath = resolvedEntities.getRawParentPath();
    PolarisEntity leafEntity = resolvedEntities.getRawLeafEntity();
    return entityManager
        .getMetaStoreManager()
        .dropEntityIfExists(
            getCurrentPolarisContext(),
            PolarisEntity.toCoreList(catalogPath),
            leafEntity,
            storageProperties,
            purge);
  }

  private boolean sendNotificationForTableLike(
      long catalogId,
      PolarisEntitySubType subType,
      TableIdentifier tableIdentifier,
      NotificationRequest request) {
    LOG.debug("Handling notification request {} for tableIdentifier {}", request, tableIdentifier);
    PolarisResolvedPathWrapper resolvedEntities =
        resolvedEntityView.getPassthroughResolvedPath(tableIdentifier, subType);

    NotificationType notificationType = request.getNotificationType();

    Preconditions.checkNotNull(notificationType, "Expected a valid notification type.");

    if (notificationType == NotificationType.DROP) {
      return dropTableLike(
              catalogId, PolarisEntitySubType.TABLE, tableIdentifier, Map.of(), false /* purge */)
          .isSuccess();
    } else if (notificationType == NotificationType.CREATE
        || notificationType == NotificationType.UPDATE) {

      Namespace ns = tableIdentifier.namespace();
      createNonExistingNamespaces(ns);

      PolarisResolvedPathWrapper resolvedParent = resolvedEntityView.getPassthroughResolvedPath(ns);

      TableLikeEntity entity =
          TableLikeEntity.of(resolvedEntities == null ? null : resolvedEntities.getRawLeafEntity());

      String existingLocation;
      String newLocation = transformTableLikeLocation(request.getPayload().getMetadataLocation());
      if (null == entity) {
        existingLocation = null;
        entity =
            new TableLikeEntity.Builder(tableIdentifier, newLocation)
                .setCatalogId(getCatalogId())
                .setSubType(PolarisEntitySubType.TABLE)
                .setId(
                    entityManager
                        .getMetaStoreManager()
                        .generateNewEntityId(getCurrentPolarisContext())
                        .getId())
                .build();
      } else {
        existingLocation = entity.getMetadataLocation();
        entity = new TableLikeEntity.Builder(entity).setMetadataLocation(newLocation).build();
      }
      // TODO: These might fail due to concurrent update; we need to do a retry in those cases.
      if (null == existingLocation) {
        LOG.debug(
            "Creating table {} for notification with metadataLocation {}",
            tableIdentifier,
            newLocation);
        createTableLike(catalogId, tableIdentifier, entity, resolvedParent);
      } else {
        LOG.debug(
            "Updating table {} for notification with metadataLocation {}",
            tableIdentifier,
            newLocation);
        updateTableLike(catalogId, tableIdentifier, entity);
      }
    }
    return true;
  }

  private void createNonExistingNamespaces(Namespace namespace) {
    // Pre-create namespaces if they don't exist
    for (int i = 1; i <= namespace.length(); i++) {
      Namespace nsLevel =
          Namespace.of(
              Arrays.stream(namespace.levels())
                  .limit(i)
                  .collect(Collectors.toList())
                  .toArray(String[]::new));
      if (resolvedEntityView.getPassthroughResolvedPath(nsLevel) == null) {
        Namespace parentNamespace = PolarisCatalogHelpers.getParentNamespace(nsLevel);
        PolarisResolvedPathWrapper resolvedParent =
            resolvedEntityView.getPassthroughResolvedPath(parentNamespace);
        createNamespaceInternal(nsLevel, Collections.emptyMap(), resolvedParent);
      }
    }
  }

  private List<TableIdentifier> listTableLike(
      long catalogId, PolarisEntitySubType subType, Namespace namespace) {
    PolarisResolvedPathWrapper resolvedEntities = resolvedEntityView.getResolvedPath(namespace);
    if (resolvedEntities == null) {
      // Illegal state because the namespace should've already been in the static resolution set.
      throw new IllegalStateException(
          String.format("Failed to fetch resolved namespace '%s'", namespace));
    }

    List<PolarisEntity> catalogPath = resolvedEntities.getRawFullPath();
    List<PolarisEntity.NameAndId> entities =
        PolarisEntity.toNameAndIdList(
            entityManager
                .getMetaStoreManager()
                .listEntities(
                    getCurrentPolarisContext(),
                    PolarisEntity.toCoreList(catalogPath),
                    PolarisEntityType.TABLE_LIKE,
                    subType)
                .getEntities());
    return PolarisCatalogHelpers.nameAndIdToTableIdentifiers(catalogPath, entities);
  }

  /**
   * Load FileIO with provided impl and properties
   *
   * @param ioImpl full class name of a custom FileIO implementation
   * @param properties used to initialize the FileIO implementation
   * @return FileIO object
   */
  private FileIO loadFileIO(String ioImpl, Map<String, String> properties) {
    Map<String, String> propertiesWithS3CustomizedClientFactory = new HashMap<>(properties);
    propertiesWithS3CustomizedClientFactory.put(
        S3FileIOProperties.CLIENT_FACTORY, PolarisS3FileIOClientFactory.class.getName());
    return CatalogUtil.loadFileIO(
        ioImpl, propertiesWithS3CustomizedClientFactory, new Configuration());
  }

  /**
   * Check if the exception is retryable for the storage provider
   *
   * @param ex exception
   * @return true if the exception is retryable
   */
  private static boolean isStorageProviderRetryableException(Exception ex) {
    // For S3/Azure, the exception is not wrapped, while for GCP the exception is wrapped as a
    // RuntimeException
    Throwable rootCause = ExceptionUtils.getRootCause(ex);
    if (rootCause == null) {
      // no root cause, let it retry
      return true;
    }
    // only S3 SdkException has this retryable property
    if (rootCause instanceof SdkException && ((SdkException) rootCause).retryable()) {
      return true;
    }
    // add more cases here if needed
    // AccessDenied is not retryable
    return !isAccessDenied(rootCause.getMessage());
  }

  private static boolean isAccessDenied(String errorMsg) {
    // corresponding error messages for storage providers Aws/Azure/Gcp
    boolean isAccessDenied =
        errorMsg != null
            && (errorMsg.contains("Access Denied")
                || errorMsg.contains("This request is not authorized to perform this operation")
                || errorMsg.contains("Forbidden"));
    if (isAccessDenied) {
      LOG.debug("Access Denied or Forbidden error: {}", errorMsg);
      return true;
    }
    return false;
  }
}
