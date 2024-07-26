package io.polaris.service.task;

import io.polaris.core.context.CallContext;
import io.polaris.core.entity.PolarisBaseEntity;
import io.polaris.core.entity.PolarisEntity;
import io.polaris.core.entity.PolarisEntityType;
import io.polaris.core.entity.TaskEntity;
import io.polaris.core.persistence.MetaStoreManagerFactory;
import io.polaris.core.persistence.PolarisMetaStoreManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Given a list of registered {@link TaskHandler}s, execute tasks asynchronously with the provided
 * {@link CallContext}.
 */
public class TaskExecutorImpl implements TaskExecutor {
  private static final Logger LOGGER = LoggerFactory.getLogger(TaskExecutorImpl.class);
  public static final long TASK_RETRY_DELAY = 1000;
  private final ExecutorService executorService;
  private final MetaStoreManagerFactory metaStoreManagerFactory;
  private final List<TaskHandler> taskHandlers = new ArrayList<>();

  public TaskExecutorImpl(
      ExecutorService executorService, MetaStoreManagerFactory metaStoreManagerFactory) {
    this.executorService = executorService;
    this.metaStoreManagerFactory = metaStoreManagerFactory;
  }

  /**
   * Add a {@link TaskHandler}. {@link TaskEntity}s will be tested against the {@link
   * TaskHandler#canHandleTask(TaskEntity)} method and will be handled by the first handler that
   * responds true.
   *
   * @param taskHandler
   */
  public void addTaskHandler(TaskHandler taskHandler) {
    taskHandlers.add(taskHandler);
  }

  /**
   * Register a {@link CallContext} for a specific task id. That task will be loaded and executed
   * asynchronously with a clone of the provided {@link CallContext}.
   *
   * @param taskEntityId
   * @param callContext
   */
  @Override
  public void addTaskHandlerContext(long taskEntityId, CallContext callContext) {
    CallContext clone = CallContext.copyOf(callContext);
    tryHandleTask(taskEntityId, clone, null, 1);
  }

  private @NotNull CompletableFuture<Void> tryHandleTask(
      long taskEntityId, CallContext clone, Throwable e, int attempt) {
    if (attempt > 3) {
      return CompletableFuture.failedFuture(e);
    }
    return CompletableFuture.runAsync(
            () -> {
              // set the call context INSIDE the async task
              try (CallContext ctx = CallContext.setCurrentContext(CallContext.copyOf(clone))) {
                PolarisMetaStoreManager metaStoreManager =
                    metaStoreManagerFactory.getOrCreateMetaStoreManager(ctx.getRealmContext());
                PolarisBaseEntity taskEntity =
                    metaStoreManager
                        .loadEntity(ctx.getPolarisCallContext(), 0L, taskEntityId)
                        .getEntity();
                if (!PolarisEntityType.TASK.equals(taskEntity.getType())) {
                  throw new IllegalArgumentException("Provided taskId must be a task entity type");
                }
                TaskEntity task = TaskEntity.of(taskEntity);
                Optional<TaskHandler> handlerOpt =
                    taskHandlers.stream().filter(th -> th.canHandleTask(task)).findFirst();
                if (handlerOpt.isEmpty()) {
                  LOGGER
                      .atWarn()
                      .addKeyValue("taskEntityId", taskEntityId)
                      .addKeyValue("taskType", task.getTaskType())
                      .log("Unable to find handler for task type");
                  return;
                }
                TaskHandler handler = handlerOpt.get();
                boolean success = handler.handleTask(task);
                if (success) {
                  LOGGER
                      .atInfo()
                      .addKeyValue("taskEntityId", taskEntityId)
                      .addKeyValue("handlerClass", handler.getClass())
                      .log("Task successfully handled");
                  metaStoreManager.dropEntityIfExists(
                      ctx.getPolarisCallContext(),
                      null,
                      PolarisEntity.toCore(taskEntity),
                      Map.of(),
                      false);
                } else {
                  LOGGER
                      .atWarn()
                      .addKeyValue("taskEntityId", taskEntityId)
                      .addKeyValue("taskEntityName", taskEntity.getName())
                      .log("Unable to execute async task");
                }
              }
            },
            executorService)
        .exceptionallyComposeAsync(
            (t) -> {
              LOGGER.warn("Failed to handle task entity id {}", taskEntityId, t);
              return tryHandleTask(taskEntityId, clone, t, attempt + 1);
            },
            CompletableFuture.delayedExecutor(
                TASK_RETRY_DELAY * (long) attempt, TimeUnit.MILLISECONDS, executorService));
  }
}
