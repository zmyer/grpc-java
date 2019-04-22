/*
 * Copyright 2014 The gRPC Authors
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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.BinaryLog;
import io.grpc.BindableService;
import io.grpc.CompressorRegistry;
import io.grpc.Context;
import io.grpc.DecompressorRegistry;
import io.grpc.HandlerRegistry;
import io.grpc.InternalChannelz;
import io.grpc.InternalNotifyOnServerBuild;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.ServerTransportFilter;
import io.opencensus.trace.Tracing;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * The base class for server builders.
 *
 * @param <T> The concrete type for this builder.
 */
public abstract class AbstractServerImplBuilder<T extends AbstractServerImplBuilder<T>>
        extends ServerBuilder<T> {

  public static ServerBuilder<?> forPort(int port) {
    throw new UnsupportedOperationException("Subclass failed to hide static factory");
  }

  // defaults
  private static final ObjectPool<? extends Executor> DEFAULT_EXECUTOR_POOL =
      SharedResourcePool.forResource(GrpcUtil.SHARED_CHANNEL_EXECUTOR);
  private static final HandlerRegistry DEFAULT_FALLBACK_REGISTRY = new DefaultFallbackRegistry();
  private static final DecompressorRegistry DEFAULT_DECOMPRESSOR_REGISTRY =
      DecompressorRegistry.getDefaultInstance();
  private static final CompressorRegistry DEFAULT_COMPRESSOR_REGISTRY =
      CompressorRegistry.getDefaultInstance();
  private static final long DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(120);

  // mutable state
  final InternalHandlerRegistry.Builder registryBuilder =
      new InternalHandlerRegistry.Builder();
  final List<ServerTransportFilter> transportFilters = new ArrayList<>();
  final List<ServerInterceptor> interceptors = new ArrayList<>();
  private final List<InternalNotifyOnServerBuild> notifyOnBuildList = new ArrayList<>();
  private final List<ServerStreamTracer.Factory> streamTracerFactories = new ArrayList<>();
  HandlerRegistry fallbackRegistry = DEFAULT_FALLBACK_REGISTRY;
  ObjectPool<? extends Executor> executorPool = DEFAULT_EXECUTOR_POOL;
  DecompressorRegistry decompressorRegistry = DEFAULT_DECOMPRESSOR_REGISTRY;
  CompressorRegistry compressorRegistry = DEFAULT_COMPRESSOR_REGISTRY;
  long handshakeTimeoutMillis = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS;
  @Nullable private CensusStatsModule censusStatsOverride;
  private boolean statsEnabled = true;
  private boolean recordStartedRpcs = true;
  private boolean recordFinishedRpcs = true;
  private boolean recordRealTimeMetrics = false;
  private boolean tracingEnabled = true;
  @Nullable BinaryLog binlog;
  TransportTracer.Factory transportTracerFactory = TransportTracer.getDefaultFactory();
  InternalChannelz channelz = InternalChannelz.instance();
  CallTracer.Factory callTracerFactory = CallTracer.getDefaultFactory();

  @Override
  public final T directExecutor() {
    return executor(MoreExecutors.directExecutor());
  }

  @Override
  public final T executor(@Nullable Executor executor) {
    this.executorPool = executor != null ? new FixedObjectPool<>(executor) : DEFAULT_EXECUTOR_POOL;
    return thisT();
  }

  @Override
  public final T addService(ServerServiceDefinition service) {
    registryBuilder.addService(checkNotNull(service, "service"));
    return thisT();
  }

  @Override
  public final T addService(BindableService bindableService) {
    if (bindableService instanceof InternalNotifyOnServerBuild) {
      notifyOnBuildList.add((InternalNotifyOnServerBuild) bindableService);
    }
    return addService(checkNotNull(bindableService, "bindableService").bindService());
  }

  @Override
  public final T addTransportFilter(ServerTransportFilter filter) {
    transportFilters.add(checkNotNull(filter, "filter"));
    return thisT();
  }

  @Override
  public final T intercept(ServerInterceptor interceptor) {
    interceptors.add(checkNotNull(interceptor, "interceptor"));
    return thisT();
  }

  @Override
  public final T addStreamTracerFactory(ServerStreamTracer.Factory factory) {
    streamTracerFactories.add(checkNotNull(factory, "factory"));
    return thisT();
  }

  @Override
  public final T fallbackHandlerRegistry(@Nullable HandlerRegistry registry) {
    this.fallbackRegistry = registry != null ? registry : DEFAULT_FALLBACK_REGISTRY;
    return thisT();
  }

  @Override
  public final T decompressorRegistry(@Nullable DecompressorRegistry registry) {
    this.decompressorRegistry = registry != null ? registry : DEFAULT_DECOMPRESSOR_REGISTRY;
    return thisT();
  }

  @Override
  public final T compressorRegistry(@Nullable CompressorRegistry registry) {
    this.compressorRegistry = registry != null ? registry : DEFAULT_COMPRESSOR_REGISTRY;
    return thisT();
  }

  @Override
  public final T handshakeTimeout(long timeout, TimeUnit unit) {
    checkArgument(timeout > 0, "handshake timeout is %s, but must be positive", timeout);
    this.handshakeTimeoutMillis = checkNotNull(unit, "unit").toMillis(timeout);
    return thisT();
  }

  @Override
  public final T setBinaryLog(@Nullable BinaryLog binaryLog) {
    this.binlog = binaryLog;
    return thisT();
  }

  /**
   * Override the default stats implementation.
   */
  @VisibleForTesting
  protected final T overrideCensusStatsModule(@Nullable CensusStatsModule censusStats) {
    this.censusStatsOverride = censusStats;
    return thisT();
  }

  @VisibleForTesting
  public final T setTransportTracerFactory(TransportTracer.Factory transportTracerFactory) {
    this.transportTracerFactory = transportTracerFactory;
    return thisT();
  }

  /**
   * Disable or enable stats features.  Enabled by default.
   */
  protected void setStatsEnabled(boolean value) {
    this.statsEnabled = value;
  }

  /**
   * Disable or enable stats recording for RPC upstarts.  Effective only if {@link
   * #setStatsEnabled} is set to true.  Enabled by default.
   */
  protected void setStatsRecordStartedRpcs(boolean value) {
    recordStartedRpcs = value;
  }

  /**
   * Disable or enable stats recording for RPC completions.  Effective only if {@link
   * #setStatsEnabled} is set to true.  Enabled by default.
   */
  protected void setStatsRecordFinishedRpcs(boolean value) {
    recordFinishedRpcs = value;
  }

  /**
   * Disable or enable real-time metrics recording.  Effective only if {@link #setStatsEnabled} is
   * set to true.  Disabled by default.
   */
  protected void setStatsRecordRealTimeMetrics(boolean value) {
    recordRealTimeMetrics = value;
  }

  /**
   * Disable or enable tracing features.  Enabled by default.
   */
  protected void setTracingEnabled(boolean value) {
    tracingEnabled = value;
  }

  @Override
  public final Server build() {
    ServerImpl server = new ServerImpl(
        this,
        buildTransportServers(getTracerFactories()),
        Context.ROOT);
    for (InternalNotifyOnServerBuild notifyTarget : notifyOnBuildList) {
      notifyTarget.notifyOnBuild(server);
    }
    return server;
  }

  @VisibleForTesting
  final List<? extends ServerStreamTracer.Factory> getTracerFactories() {
    ArrayList<ServerStreamTracer.Factory> tracerFactories = new ArrayList<>();
    if (statsEnabled) {
      CensusStatsModule censusStats = censusStatsOverride;
      if (censusStats == null) {
        censusStats = new CensusStatsModule(
            GrpcUtil.STOPWATCH_SUPPLIER, true, recordStartedRpcs, recordFinishedRpcs,
            recordRealTimeMetrics);
      }
      tracerFactories.add(censusStats.getServerTracerFactory());
    }
    if (tracingEnabled) {
      CensusTracingModule censusTracing =
          new CensusTracingModule(Tracing.getTracer(),
              Tracing.getPropagationComponent().getBinaryFormat());
      tracerFactories.add(censusTracing.getServerTracerFactory());
    }
    tracerFactories.addAll(streamTracerFactories);
    tracerFactories.trimToSize();
    return Collections.unmodifiableList(tracerFactories);
  }

  protected final InternalChannelz getChannelz() {
    return channelz;
  }

  protected final TransportTracer.Factory getTransportTracerFactory() {
    return transportTracerFactory;
  }

  /**
   * Children of AbstractServerBuilder should override this method to provide transport specific
   * information for the server.  This method is mean for Transport implementors and should not be
   * used by normal users.
   *
   * @param streamTracerFactories an immutable list of stream tracer factories
   */
  protected abstract List<? extends io.grpc.internal.InternalServer> buildTransportServers(
      List<? extends ServerStreamTracer.Factory> streamTracerFactories);

  private T thisT() {
    @SuppressWarnings("unchecked")
    T thisT = (T) this;
    return thisT;
  }

  private static final class DefaultFallbackRegistry extends HandlerRegistry {
    @Override
    public List<ServerServiceDefinition> getServices() {
      return Collections.emptyList();
    }

    @Nullable
    @Override
    public ServerMethodDefinition<?, ?> lookupMethod(
        String methodName, @Nullable String authority) {
      return null;
    }
  }
}
