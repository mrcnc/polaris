package io.polaris.service.catalog;

import io.polaris.core.auth.AuthenticatedPolarisPrincipal;
import io.polaris.core.catalog.PolarisCatalogHelpers;
import io.polaris.core.context.CallContext;
import io.polaris.core.entity.PolarisEntitySubType;
import io.polaris.core.entity.PolarisEntityType;
import io.polaris.core.persistence.PolarisEntityManager;
import io.polaris.core.persistence.PolarisResolvedPathWrapper;
import io.polaris.core.persistence.resolver.PolarisResolutionManifest;
import io.polaris.core.persistence.resolver.PolarisResolutionManifestCatalogView;
import io.polaris.core.persistence.resolver.ResolverPath;
import java.util.Arrays;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;

/**
 * For test purposes or for elevated-privilege scenarios where entity resolution is allowed to
 * directly access a PolarisEntityManager/PolarisMetaStoreManager without being part of an
 * authorization-gated PolarisResolutionManifest, this class delegates entity resolution directly to
 * new single-use PolarisResolutionManifests for each desired resolved path without defining a fixed
 * set of resolved entities that need to be checked against authorizable operations.
 */
public class PolarisPassthroughResolutionView implements PolarisResolutionManifestCatalogView {
  private final PolarisEntityManager entityManager;
  private final CallContext callContext;
  private final AuthenticatedPolarisPrincipal authenticatedPrincipal;
  private final String catalogName;

  public PolarisPassthroughResolutionView(
      CallContext callContext,
      PolarisEntityManager entityManager,
      AuthenticatedPolarisPrincipal authenticatedPrincipal,
      String catalogName) {
    this.entityManager = entityManager;
    this.callContext = callContext;
    this.authenticatedPrincipal = authenticatedPrincipal;
    this.catalogName = catalogName;
  }

  @Override
  public PolarisResolvedPathWrapper getResolvedReferenceCatalogEntity() {
    PolarisResolutionManifest manifest =
        entityManager.prepareResolutionManifest(callContext, authenticatedPrincipal, catalogName);
    manifest.resolveAll();
    return manifest.getResolvedReferenceCatalogEntity();
  }

  @Override
  public PolarisResolvedPathWrapper getResolvedPath(Object key) {
    PolarisResolutionManifest manifest =
        entityManager.prepareResolutionManifest(callContext, authenticatedPrincipal, catalogName);

    if (key instanceof Namespace) {
      Namespace namespace = (Namespace) key;
      manifest.addPath(
          new ResolverPath(Arrays.asList(namespace.levels()), PolarisEntityType.NAMESPACE),
          namespace);
      manifest.resolveAll();
      return manifest.getResolvedPath(namespace);
    } else {
      throw new IllegalStateException(
          String.format(
              "Trying to getResolvedPath(key) for %s with class %s", key, key.getClass()));
    }
  }

  @Override
  public PolarisResolvedPathWrapper getResolvedPath(Object key, PolarisEntitySubType subType) {
    PolarisResolutionManifest manifest =
        entityManager.prepareResolutionManifest(callContext, authenticatedPrincipal, catalogName);

    if (key instanceof TableIdentifier) {
      TableIdentifier identifier = (TableIdentifier) key;
      manifest.addPath(
          new ResolverPath(
              PolarisCatalogHelpers.tableIdentifierToList(identifier),
              PolarisEntityType.TABLE_LIKE),
          identifier);
      manifest.resolveAll();
      return manifest.getResolvedPath(identifier, subType);
    } else {
      throw new IllegalStateException(
          String.format(
              "Trying to getResolvedPath(key, subType) for %s with class %s and subType %s",
              key, key.getClass(), subType));
    }
  }

  @Override
  public PolarisResolvedPathWrapper getPassthroughResolvedPath(Object key) {
    PolarisResolutionManifest manifest =
        entityManager.prepareResolutionManifest(callContext, authenticatedPrincipal, catalogName);

    if (key instanceof Namespace) {
      Namespace namespace = (Namespace) key;
      manifest.addPassthroughPath(
          new ResolverPath(Arrays.asList(namespace.levels()), PolarisEntityType.NAMESPACE),
          namespace);
      return manifest.getPassthroughResolvedPath(namespace);
    } else {
      throw new IllegalStateException(
          String.format(
              "Trying to getResolvedPath(key) for %s with class %s", key, key.getClass()));
    }
  }

  @Override
  public PolarisResolvedPathWrapper getPassthroughResolvedPath(
      Object key, PolarisEntitySubType subType) {
    PolarisResolutionManifest manifest =
        entityManager.prepareResolutionManifest(callContext, authenticatedPrincipal, catalogName);

    if (key instanceof TableIdentifier) {
      TableIdentifier identifier = (TableIdentifier) key;
      manifest.addPassthroughPath(
          new ResolverPath(
              PolarisCatalogHelpers.tableIdentifierToList(identifier),
              PolarisEntityType.TABLE_LIKE),
          identifier);
      return manifest.getPassthroughResolvedPath(identifier, subType);
    } else {
      throw new IllegalStateException(
          String.format(
              "Trying to getResolvedPath(key, subType) for %s with class %s and subType %s",
              key, key.getClass(), subType));
    }
  }
}
