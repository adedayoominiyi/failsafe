/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package net.jodah.failsafe;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * A CompletableFuture implementation that proposages cancellations.
 *
 * @param <T> result type
 * @author Jonathan Halterman
 */
public class FailsafeFuture<T> extends CompletableFuture<T> {
  private final FailsafeExecutor<T> executor;
  private ExecutionContext execution;

  // Mutable state
  private volatile Future<T> delegate;

  FailsafeFuture(FailsafeExecutor<T> executor) {
    this.executor = executor;
  }

  /**
   * Cancels this and the internal delegate.
   */
  @Override
  public synchronized boolean cancel(boolean mayInterruptIfRunning) {
    if (isDone())
      return false;

    super.cancel(mayInterruptIfRunning);
    boolean cancelResult = delegate.cancel(mayInterruptIfRunning);
    Throwable failure = new CancellationException();
    executor.handleComplete(ExecutionResult.failure(failure), execution);
    complete(null, failure);
    return cancelResult;
  }

  //  // TODO delete?
  //
  //  /**
  //   * Waits if necessary for the execution to complete, and then returns its result.
  //   *
  //   * @return the execution result
  //   * @throws CancellationException if the execution was cancelled
  //   * @throws ExecutionException if the execution threw an exception
  //   * @throws InterruptedException if the current thread was interrupted while waiting
  //   */
  //  @Override
  //  public T get() throws InterruptedException, ExecutionException {
  //    circuit.await();
  //    if (failure != null) {
  //      if (failure instanceof CancellationException)
  //        throw (CancellationException) failure;
  //      throw new ExecutionException(failure);
  //    }
  //    return result;
  //  }
  //
  //  // TODO delete?
  //  /**
  //   * Waits if necessary for at most the given time for the execution to complete, and then returns its result, if
  //   * available.
  //   *
  //   * @param timeout the maximum time to wait
  //   * @param unit the time unit of the timeout argument
  //   * @return the execution result
  //   * @throws CancellationException if the execution was cancelled
  //   * @throws ExecutionException if the execution threw an exception
  //   * @throws InterruptedException if the current thread was interrupted while waiting
  //   * @throws TimeoutException if the wait timed out
  //   * @throws NullPointerException if {@code unit} is null
  //   * @throws IllegalArgumentException if {@code timeout} is < 0
  //   */
  //  @Override
  //  public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
  //    Assert.isTrue(timeout >= 0, "timeout cannot be negative");
  //    if (!circuit.await(timeout, Assert.notNull(unit, "unit")))
  //      throw new TimeoutException();
  //    if (failure != null)
  //      throw new ExecutionException(failure);
  //    return result;
  //  }

  synchronized void complete(T result, Throwable failure) {
    if (isDone())
      return;

    if (failure != null)
      super.completeExceptionally(failure);
    else
      super.complete(result);
  }

  public void inject(Future<T> delegate) {
    this.delegate = delegate;
  }

  void inject(ExecutionContext execution) {
    this.execution = execution;
  }
}
