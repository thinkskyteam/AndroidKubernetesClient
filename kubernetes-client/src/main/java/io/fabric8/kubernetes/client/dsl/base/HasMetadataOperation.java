/**
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fabric8.kubernetes.client.dsl.base;

import io.fabric8.kubernetes.api.builder.Function;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.Doneable;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.internal.readiness.Readiness;
import io.fabric8.kubernetes.client.internal.readiness.ReadinessWatcher;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.TimeUnit;

public class HasMetadataOperation<T extends HasMetadata, L extends KubernetesResourceList, D extends Doneable<T>, R extends Resource<T, D>>
  extends BaseOperation< T, L, D, R> {
  protected static final DeletionPropagation DEFAULT_PROPAGATION_POLICY = DeletionPropagation.BACKGROUND;

  public HasMetadataOperation(OperationContext ctx) {
    super(ctx);
  }

  @Override
  public D edit() {
    final Function<T, T> visitor = resource -> {
      try {
        return patch(resource);
      } catch (Exception e) {
        throw KubernetesClientException.launderThrowable(forOperationType("edit"), e);
      }
    };

    try {
      T item = getMandatory();
      return getDoneableType().getDeclaredConstructor(getType(), Function.class).newInstance(item, visitor);
    } catch (InvocationTargetException | NoSuchMethodException | IllegalAccessException | InstantiationException e) {
      throw KubernetesClientException.launderThrowable(forOperationType("edit"), e);
    }
  }

  @Override
  public T replace(T item) {
    String fixedResourceVersion = getResourceVersion();
    Exception caught = null;
    int maxTries = 10;
    for (int i = 0; i < maxTries; i++) {
      try {
        final String resourceVersion;
        if (fixedResourceVersion != null) {
          resourceVersion = fixedResourceVersion;
        } else {
          T got = fromServer().get();
          if (got == null) {
            return null;
          }
          if (got.getMetadata() != null) {
            resourceVersion = got.getMetadata().getResourceVersion();
          } else {
            resourceVersion = null;
          }
        }

        final Function<T, T> visitor = resource -> {
          try {
            resource.getMetadata().setResourceVersion(resourceVersion);
            return handleReplace(resource);
          } catch (Exception e) {
            throw KubernetesClientException.launderThrowable(forOperationType("replace"), e);
          }
        };
        D doneable = getDoneableType().getDeclaredConstructor(getType(), Function.class).newInstance(item, visitor);
        return doneable.done();
      } catch (KubernetesClientException e) {
        caught = e;
        // Only retry if there's a conflict and using dynamic resource version - this is normally to do with resource version & server updates.
        if (e.getCode() != 409 || fixedResourceVersion != null) {
          break;
        }
        if (i < maxTries - 1) {
          try {
            TimeUnit.SECONDS.sleep(1);
          } catch (InterruptedException e1) {
            // Ignore this... would only hide the proper exception
            // ...but make sure to preserve the interrupted status
            Thread.currentThread().interrupt();
          }
        }
      } catch (Exception e) {
        caught = e;
      }
    }
    throw KubernetesClientException.launderThrowable(forOperationType("replace"), caught);
  }

  public T patch(T item) {
    Exception caught = null;
    int maxTries = 10;
    for (int i = 0; i < maxTries; i++) {
      try {
        String resourceVersion;
        final T got = fromServer().get();
        if (got == null) {
          return null;
        }
        if (got.getMetadata() != null) {
          resourceVersion = got.getMetadata().getResourceVersion();
        } else {
          resourceVersion = null;
        }
        final Function<T, T> visitor = resource -> {
          try {
            resource.getMetadata().setResourceVersion(resourceVersion);
            return handlePatch(got, resource);
          } catch (Exception e) {
            throw KubernetesClientException.launderThrowable(forOperationType("patch"), e);
          }
        };
        D doneable = getDoneableType().getDeclaredConstructor(getType(), Function.class).newInstance(item, visitor);
        return doneable.done();
      } catch (KubernetesClientException e) {
        caught = e;
        // Only retry if there's a conflict - this is normally to do with resource version & server updates.
        if (e.getCode() != 409) {
          break;
        }
        if (i < maxTries - 1) {
          try {
            TimeUnit.SECONDS.sleep(1);
          } catch (InterruptedException e1) {
            // Ignore this... would only hide the proper exception
            // ...but make sure to preserve the interrupted status
            Thread.currentThread().interrupt();
          }
        }
      } catch (Exception e) {
        caught = e;
      }
    }
    throw KubernetesClientException.launderThrowable(forOperationType("patch"), caught);
  }

  /**
   * A wait method that combines watching and polling.
   * The need for that is that in some cases a pure watcher approach consistently fails.
   * @param i           The number of iterations to perform.
   * @param started     Time in milliseconds where the watch started.
   * @param interval    The amount of time in millis to wait on each iteration.
   * @param amount      The maximum amount in millis of time since started to wait.
   * @return            The {@link ReplicationController} if ready.
   */
  protected T periodicWatchUntilReady(int i, long started, long interval, long amount) {
    T item = fromServer().get();
    if (Readiness.isReady(item)) {
      return item;
    }

    ReadinessWatcher<T> watcher = new ReadinessWatcher<>(item);
    try (Watch watch = watch(item.getMetadata().getResourceVersion(), watcher)) {
      try {
        return watcher.await(interval, TimeUnit.NANOSECONDS);
      } catch (KubernetesClientTimeoutException e) {
        if (i <= 0) {
          throw e;
        }
      }

      long remaining =  (started + amount) - System.nanoTime();
      long next = Math.max(0, Math.min(remaining, interval));
      return periodicWatchUntilReady(i - 1, started, next, amount);
    }
  }

  @Override
  public T waitUntilReady(long amount, TimeUnit timeUnit) throws InterruptedException {
    if (Readiness.isReadinessApplicable(getType())) {
      long started = System.nanoTime();
      waitUntilExists(amount, timeUnit);
      long alreadySpent = System.nanoTime() - started;

      long remaining = timeUnit.toNanos(amount) - alreadySpent;
      if (remaining <= 0) {
        return periodicWatchUntilReady(0, System.nanoTime(), 0, 0);
      }

      return periodicWatchUntilReady(10, System.nanoTime(), Math.max(remaining / 10, 1000000000L), remaining);
    }

    return super.waitUntilReady(amount, timeUnit);
  }
}
