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

import net.jodah.failsafe.internal.util.Assert;
import net.jodah.failsafe.util.concurrent.Scheduler;

import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Tracks asynchronous executions and allows retries to be scheduled according to a {@link RetryPolicy}. May be
 * explicitly completed or made to retry.
 *
 * @author Jonathan Halterman
 */
@SuppressWarnings("WeakerAccess")
public final class AsyncExecution extends AbstractExecution {
  /** Used to complete a promise for a scheduled execution */
  private static final Exception SCHEDULED = new Exception("The execution has been scheduled");

  private Supplier<CompletableFuture<ExecutionResult>> executionSupplier;
  final FailsafeFuture<Object> future;
  final Scheduler scheduler;
  private volatile boolean completeCalled;
  private volatile boolean retryCalled;

  <T> AsyncExecution(Scheduler scheduler, FailsafeFuture<T> future, FailsafeExecutor<?> executor) {
    super((FailsafeExecutor<Object>) executor);
    this.scheduler = scheduler;
    this.future = (FailsafeFuture<Object>) future;
  }

  /**
   * Completes the execution and the associated {@code FutureResult}.
   *
   * @throws IllegalStateException if the execution is already complete
   */
  public void complete() {
    postExecute(ExecutionResult.NONE);
  }

  /**
   * Attempts to complete the execution and the associated {@code FutureResult} with the {@code result}. Returns true on
   * success, else false if completion failed and the execution should be retried via {@link #retry()}.
   *
   * @throws IllegalStateException if the execution is already complete
   */
  public boolean complete(Object result) {
    return postExecute(new ExecutionResult(result, null));
  }

  /**
   * Attempts to complete the execution and the associated {@code FutureResult} with the {@code result} and {@code
   * failure}. Returns true on success, else false if completion failed and the execution should be retried via {@link
   * #retry()}.
   * <p>
   * Note: the execution may be completed even when the {@code failure} is not {@code null}, such as when the
   * RetryPolicy does not allow retries for the {@code failure}.
   *
   * @throws IllegalStateException if the execution is already complete
   */
  public boolean complete(Object result, Throwable failure) {
    return postExecute(new ExecutionResult(result, failure));
  }

  /**
   * Records an execution and returns true if a retry has been scheduled for else returns returns false and completes
   * the execution and associated {@code FutureResult}.
   *
   * @throws IllegalStateException if a retry method has already been called or the execution is already complete
   */
  public boolean retry() {
    return retryFor(lastResult, lastFailure);
  }

  /**
   * Records an execution and returns true if a retry has been scheduled for the {@code result}, else returns false and
   * marks the execution and associated {@code FutureResult} as complete.
   *
   * @throws IllegalStateException if a retry method has already been called or the execution is already complete
   */
  public boolean retryFor(Object result) {
    return retryFor(result, null);
  }

  /**
   * Records an execution and returns true if a retry has been scheduled for the {@code result} or {@code failure}, else
   * returns false and marks the execution and associated {@code FutureResult} as complete.
   *
   * @throws IllegalStateException if a retry method has already been called or the execution is already complete
   */
  public boolean retryFor(Object result, Throwable failure) {
    Assert.state(!retryCalled, "Retry has already been called");
    retryCalled = true;
    return !completeOrHandle(result, failure);
  }

  /**
   * Records an execution and returns true if a retry has been scheduled for the {@code failure}, else returns false and
   * marks the execution and associated {@code FutureResult} as complete.
   *
   * @throws NullPointerException if {@code failure} is null
   * @throws IllegalStateException if a retry method has already been called or the execution is already complete
   */
  public boolean retryOn(Throwable failure) {
    Assert.notNull(failure, "failure");
    return retryFor(null, failure);
  }

  /**
   * Prepares for an execution by resetting internal flags.
   */
  void preExecute() {
    completeCalled = false;
    retryCalled = false;
  }

  /**
   * Attempts to complete the parent execution, calls failure handlers, and completes the future if needed.
   *
   * @throws IllegalStateException if the execution is already complete
   */
  @Override
  boolean postExecute(ExecutionResult result) {
    synchronized (future) {
      if (!completeCalled) {
        if (super.postExecute(result))
          complete(result, null);
        completeCalled = true;
      }

      return completed;
    }
  }

  /**
   * Attempts to complete the execution else handle according to the configured policies. Returns {@code true} if the
   * execution was completed, else false which indicates the result was handled asynchronously and may have triggered a
   * retry.
   *
   * @throws IllegalStateException if the execution is already complete
   */
  boolean completeOrHandle(Object result, Throwable failure) {
    synchronized (future) {
      ExecutionResult er = new ExecutionResult(result, failure);
      if (!completeCalled)
        record(er);
      completeCalled = true;

      Supplier<CompletableFuture<ExecutionResult>> supplier = Functions.supplyOnce(
          CompletableFuture.completedFuture(er), executionSupplier);
      for (PolicyExecutor<Policy<Object>> policyExecutor : policyExecutors)
        supplier = policyExecutor.supplyAsync(supplier, scheduler, future);

      supplier.get().whenComplete(this::complete);
      return completed;
    }
  }

  void executeAsync(Supplier<CompletableFuture<ExecutionResult>> supplier, boolean makeAsync) {
    for (PolicyExecutor<Policy<Object>> policyExecutor : policyExecutors)
      supplier = policyExecutor.supplyAsync(supplier, scheduler, future);
    if (makeAsync)
      supplier = makeAsync(supplier);
    supplier.get().whenComplete(this::complete);
  }

  <T> void executeAsyncExecution(Supplier<T> supplier) {
    executionSupplier = makeAsyncExecution(supplier);
    executionSupplier.get();
  }

  private void complete(ExecutionResult result, Throwable error) {
    if (AsyncExecution.SCHEDULED.equals(error))
      return;

    completed = true;
    if (result != null) {
      executor.handleComplete(result, this);
      future.complete(result.result, result.failure);
    } else
      future.complete(null, error);
  }

  @SuppressWarnings("unchecked")
  private Supplier<CompletableFuture<ExecutionResult>> makeAsync(
      Supplier<CompletableFuture<ExecutionResult>> supplier) {
    return () -> {
      CompletableFuture<ExecutionResult> promise = new CompletableFuture<>();
      Callable<Object> callable = () -> supplier.get().handle((result, error) -> {
        // Propagate result
        if (result != null)
          promise.complete(result);
        else
          promise.completeExceptionally(error);
        return result;
      });

      try {
        future.inject((Future) scheduler.schedule(callable, 0, TimeUnit.NANOSECONDS));
      } catch (Exception e) {
        promise.completeExceptionally(e);
      }
      return promise;
    };
  }

  @SuppressWarnings("unchecked")
  private <T> Supplier<CompletableFuture<ExecutionResult>> makeAsyncExecution(Supplier<T> supplier) {
    return () -> {
      CompletableFuture<ExecutionResult> promise = new CompletableFuture<>();
      try {
        future.inject((Future) scheduler.schedule(supplier::get, 0, TimeUnit.NANOSECONDS));
        promise.completeExceptionally(AsyncExecution.SCHEDULED);
      } catch (Exception e) {
        promise.completeExceptionally(e);
      }
      return promise;
    };
  }
}
