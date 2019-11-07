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

package io.grpc.netty;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.internal.GrpcUtil.DEFAULT_KEEPALIVE_TIMEOUT_NANOS;
import static io.grpc.internal.GrpcUtil.KEEPALIVE_TIME_NANOS_DISABLED;

import com.google.common.annotations.VisibleForTesting;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ExperimentalApi;
import io.grpc.HttpConnectProxiedSocketAddress;
import io.grpc.Internal;
import io.grpc.internal.AbstractManagedChannelImplBuilder;
import io.grpc.internal.AtomicBackoff;
import io.grpc.internal.ClientTransportFactory;
import io.grpc.internal.ConnectionClientTransport;
import io.grpc.internal.FixedObjectPool;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.KeepAliveManager;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourcePool;
import io.grpc.internal.TransportTracer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;

/**
 * A builder to help simplify construction of channels using the Netty transport.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1784")
@CanIgnoreReturnValue
public final class NettyChannelBuilder
    extends AbstractManagedChannelImplBuilder<NettyChannelBuilder> {

  public static final int DEFAULT_FLOW_CONTROL_WINDOW = 1048576; // 1MiB

  private static final long AS_LARGE_AS_INFINITE = TimeUnit.DAYS.toNanos(1000L);

  private static final ChannelFactory<? extends Channel> DEFAULT_CHANNEL_FACTORY =
      new ReflectiveChannelFactory<>(Utils.DEFAULT_CLIENT_CHANNEL_TYPE);
  private static final ObjectPool<? extends EventLoopGroup> DEFAULT_EVENT_LOOP_GROUP_POOL =
      SharedResourcePool.forResource(Utils.DEFAULT_WORKER_EVENT_LOOP_GROUP);

  private final Map<ChannelOption<?>, Object> channelOptions =
      new HashMap<>();

  private NegotiationType negotiationType = NegotiationType.TLS;
  private OverrideAuthorityChecker authorityChecker;
  private ChannelFactory<? extends Channel> channelFactory = DEFAULT_CHANNEL_FACTORY;
  private ObjectPool<? extends EventLoopGroup> eventLoopGroupPool = DEFAULT_EVENT_LOOP_GROUP_POOL;
  private SslContext sslContext;
  private int flowControlWindow = DEFAULT_FLOW_CONTROL_WINDOW;
  private int maxHeaderListSize = GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE;
  private long keepAliveTimeNanos = KEEPALIVE_TIME_NANOS_DISABLED;
  private long keepAliveTimeoutNanos = DEFAULT_KEEPALIVE_TIMEOUT_NANOS;
  private boolean keepAliveWithoutCalls;
  private ProtocolNegotiatorFactory protocolNegotiatorFactory;
  private LocalSocketPicker localSocketPicker;

  /**
   * If true, indicates that the transport may use the GET method for RPCs, and may include the
   * request body in the query params.
   */
  private final boolean useGetForSafeMethods = false;

  /**
   * Creates a new builder with the given server address. This factory method is primarily intended
   * for using Netty Channel types other than SocketChannel. {@link #forAddress(String, int)} should
   * generally be preferred over this method, since that API permits delaying DNS lookups and
   * noticing changes to DNS.
   */
  @CheckReturnValue
  public static NettyChannelBuilder forAddress(SocketAddress serverAddress) {
    return new NettyChannelBuilder(serverAddress);
  }

  /**
   * Creates a new builder with the given host and port.
   */
  @CheckReturnValue
  public static NettyChannelBuilder forAddress(String host, int port) {
    return new NettyChannelBuilder(host, port);
  }

  /**
   * Creates a new builder with the given target string that will be resolved by
   * {@link io.grpc.NameResolver}.
   */
  @CheckReturnValue
  public static NettyChannelBuilder forTarget(String target) {
    return new NettyChannelBuilder(target);
  }

  @CheckReturnValue
  NettyChannelBuilder(String host, int port) {
    this(GrpcUtil.authorityFromHostAndPort(host, port));
  }

  @CheckReturnValue
  NettyChannelBuilder(String target) {
    super(target);
  }

  @CheckReturnValue
  NettyChannelBuilder(SocketAddress address) {
    super(address, getAuthorityFromAddress(address));
  }

  @CheckReturnValue
  private static String getAuthorityFromAddress(SocketAddress address) {
    if (address instanceof InetSocketAddress) {
      InetSocketAddress inetAddress = (InetSocketAddress) address;
      return GrpcUtil.authorityFromHostAndPort(inetAddress.getHostString(), inetAddress.getPort());
    } else {
      return address.toString();
    }
  }

  /**
   * Specifies the channel type to use, by default we use {@code EpollSocketChannel} if available,
   * otherwise using {@link NioSocketChannel}.
   *
   * <p>You either use this or {@link #channelFactory(io.netty.channel.ChannelFactory)} if your
   * {@link Channel} implementation has no no-args constructor.
   *
   * <p>It's an optional parameter. If the user has not provided an Channel type or ChannelFactory
   * when the channel is built, the builder will use the default one which is static.
   *
   * <p>You must also provide corresponding {@link #eventLoopGroup(EventLoopGroup)}. For example,
   * {@link NioSocketChannel} must use {@link io.netty.channel.nio.NioEventLoopGroup}, otherwise
   * your application won't start.
   */
  public NettyChannelBuilder channelType(Class<? extends Channel> channelType) {
    checkNotNull(channelType, "channelType");
    return channelFactory(new ReflectiveChannelFactory<>(channelType));
  }

  /**
   * Specifies the {@link ChannelFactory} to create {@link Channel} instances. This method is
   * usually only used if the specific {@code Channel} requires complex logic which requires
   * additional information to create the {@code Channel}. Otherwise, recommend to use {@link
   * #channelType(Class)}.
   *
   * <p>It's an optional parameter. If the user has not provided an Channel type or ChannelFactory
   * when the channel is built, the builder will use the default one which is static.
   *
   * <p>You must also provide corresponding {@link #eventLoopGroup(EventLoopGroup)}. For example,
   * {@link NioSocketChannel} based {@link ChannelFactory} must use {@link
   * io.netty.channel.nio.NioEventLoopGroup}, otherwise your application won't start.
   */
  public NettyChannelBuilder channelFactory(ChannelFactory<? extends Channel> channelFactory) {
    this.channelFactory = checkNotNull(channelFactory, "channelFactory");
    return this;
  }

  /**
   * Specifies a channel option. As the underlying channel as well as network implementation may
   * ignore this value applications should consider it a hint.
   */
  public <T> NettyChannelBuilder withOption(ChannelOption<T> option, T value) {
    channelOptions.put(option, value);
    return this;
  }

  /**
   * Sets the negotiation type for the HTTP/2 connection.
   *
   * <p>Default: <code>TLS</code>
   */
  public NettyChannelBuilder negotiationType(NegotiationType type) {
    negotiationType = type;
    return this;
  }

  /**
   * Provides an EventGroupLoop to be used by the netty transport.
   *
   * <p>It's an optional parameter. If the user has not provided an EventGroupLoop when the channel
   * is built, the builder will use the default one which is static.
   *
   * <p>You must also provide corresponding {@link #channelType(Class)} or {@link
   * #channelFactory(ChannelFactory)} corresponding to the given {@code EventLoopGroup}. For
   * example, {@link io.netty.channel.nio.NioEventLoopGroup} requires {@link NioSocketChannel}
   *
   * <p>The channel won't take ownership of the given EventLoopGroup. It's caller's responsibility
   * to shut it down when it's desired.
   */
  public NettyChannelBuilder eventLoopGroup(@Nullable EventLoopGroup eventLoopGroup) {
    if (eventLoopGroup != null) {
      return eventLoopGroupPool(new FixedObjectPool<>(eventLoopGroup));
    }
    return eventLoopGroupPool(DEFAULT_EVENT_LOOP_GROUP_POOL);
  }

  NettyChannelBuilder eventLoopGroupPool(ObjectPool<? extends EventLoopGroup> eventLoopGroupPool) {
    this.eventLoopGroupPool = checkNotNull(eventLoopGroupPool, "eventLoopGroupPool");
    return this;
  }

  /**
   * SSL/TLS context to use instead of the system default. It must have been configured with {@link
   * GrpcSslContexts}, but options could have been overridden.
   */
  public NettyChannelBuilder sslContext(SslContext sslContext) {
    if (sslContext != null) {
      checkArgument(sslContext.isClient(),
          "Server SSL context can not be used for client channel");
      GrpcSslContexts.ensureAlpnAndH2Enabled(sslContext.applicationProtocolNegotiator());
    }
    this.sslContext = sslContext;
    return this;
  }

  /**
   * Sets the flow control window in bytes. If not called, the default value
   * is {@link #DEFAULT_FLOW_CONTROL_WINDOW}).
   */
  public NettyChannelBuilder flowControlWindow(int flowControlWindow) {
    checkArgument(flowControlWindow > 0, "flowControlWindow must be positive");
    this.flowControlWindow = flowControlWindow;
    return this;
  }

  /**
   * Sets the maximum size of header list allowed to be received. This is cumulative size of the
   * headers with some overhead, as defined for
   * <a href="http://httpwg.org/specs/rfc7540.html#rfc.section.6.5.2">
   * HTTP/2's SETTINGS_MAX_HEADER_LIST_SIZE</a>. The default is 8 KiB.
   *
   * @deprecated Use {@link #maxInboundMetadataSize} instead
   */
  @Deprecated
  public NettyChannelBuilder maxHeaderListSize(int maxHeaderListSize) {
    return maxInboundMetadataSize(maxHeaderListSize);
  }

  /**
   * Sets the maximum size of metadata allowed to be received. This is cumulative size of the
   * entries with some overhead, as defined for
   * <a href="http://httpwg.org/specs/rfc7540.html#rfc.section.6.5.2">
   * HTTP/2's SETTINGS_MAX_HEADER_LIST_SIZE</a>. The default is 8 KiB.
   *
   * @param bytes the maximum size of received metadata
   * @return this
   * @throws IllegalArgumentException if bytes is non-positive
   * @since 1.17.0
   */
  @Override
  public NettyChannelBuilder maxInboundMetadataSize(int bytes) {
    checkArgument(bytes > 0, "maxInboundMetadataSize must be > 0");
    this.maxHeaderListSize = bytes;
    return this;
  }

  /**
   * Equivalent to using {@link #negotiationType(NegotiationType)} with {@code PLAINTEXT} or
   * {@code PLAINTEXT_UPGRADE}.
   *
   * @deprecated use {@link #usePlaintext()} instead.
   */
  @Override
  @Deprecated
  public NettyChannelBuilder usePlaintext(boolean skipNegotiation) {
    if (skipNegotiation) {
      negotiationType(NegotiationType.PLAINTEXT);
    } else {
      negotiationType(NegotiationType.PLAINTEXT_UPGRADE);
    }
    return this;
  }

  /**
   * Equivalent to using {@link #negotiationType(NegotiationType)} with {@code PLAINTEXT}.
   */
  @Override
  public NettyChannelBuilder usePlaintext() {
    negotiationType(NegotiationType.PLAINTEXT);
    return this;
  }

  /**
   * Equivalent to using {@link #negotiationType(NegotiationType)} with {@code TLS}.
   */
  @Override
  public NettyChannelBuilder useTransportSecurity() {
    negotiationType(NegotiationType.TLS);
    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @since 1.3.0
   */
  @Override
  public NettyChannelBuilder keepAliveTime(long keepAliveTime, TimeUnit timeUnit) {
    checkArgument(keepAliveTime > 0L, "keepalive time must be positive");
    keepAliveTimeNanos = timeUnit.toNanos(keepAliveTime);
    keepAliveTimeNanos = KeepAliveManager.clampKeepAliveTimeInNanos(keepAliveTimeNanos);
    if (keepAliveTimeNanos >= AS_LARGE_AS_INFINITE) {
      // Bump keepalive time to infinite. This disables keepalive.
      keepAliveTimeNanos = KEEPALIVE_TIME_NANOS_DISABLED;
    }
    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @since 1.3.0
   */
  @Override
  public NettyChannelBuilder keepAliveTimeout(long keepAliveTimeout, TimeUnit timeUnit) {
    checkArgument(keepAliveTimeout > 0L, "keepalive timeout must be positive");
    keepAliveTimeoutNanos = timeUnit.toNanos(keepAliveTimeout);
    keepAliveTimeoutNanos = KeepAliveManager.clampKeepAliveTimeoutInNanos(keepAliveTimeoutNanos);
    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @since 1.3.0
   */
  @Override
  public NettyChannelBuilder keepAliveWithoutCalls(boolean enable) {
    keepAliveWithoutCalls = enable;
    return this;
  }


  /**
   * If non-{@code null}, attempts to create connections bound to a local port.
   */
  public NettyChannelBuilder localSocketPicker(@Nullable LocalSocketPicker localSocketPicker) {
    this.localSocketPicker = localSocketPicker;
    return this;
  }

  /**
   * This class is meant to be overriden with a custom implementation of
   * {@link #createSocketAddress}.  The default implementation is a no-op.
   *
   * @since 1.16.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/4917")
  public static class LocalSocketPicker {

    /**
     * Called by gRPC to pick local socket to bind to.  This may be called multiple times.
     * Subclasses are expected to override this method.
     *
     * @param remoteAddress the remote address to connect to.
     * @param attrs the Attributes present on the {@link io.grpc.EquivalentAddressGroup} associated
     *        with the address.
     * @return a {@link SocketAddress} suitable for binding, or else {@code null}.
     * @since 1.16.0
     */
    @Nullable
    public SocketAddress createSocketAddress(
        SocketAddress remoteAddress, @EquivalentAddressGroup.Attr Attributes attrs) {
      return null;
    }
  }

  @Override
  @CheckReturnValue
  @Internal
  protected ClientTransportFactory buildTransportFactory() {
    assertEventLoopAndChannelType();

    ProtocolNegotiator negotiator;
    if (protocolNegotiatorFactory != null) {
      negotiator = protocolNegotiatorFactory.buildProtocolNegotiator();
    } else {
      SslContext localSslContext = sslContext;
      if (negotiationType == NegotiationType.TLS && localSslContext == null) {
        try {
          localSslContext = GrpcSslContexts.forClient().build();
        } catch (SSLException ex) {
          throw new RuntimeException(ex);
        }
      }
      negotiator = createProtocolNegotiatorByType(negotiationType, localSslContext);
    }

    return new NettyTransportFactory(
        negotiator, channelFactory, channelOptions,
        eventLoopGroupPool, flowControlWindow, maxInboundMessageSize(),
        maxHeaderListSize, keepAliveTimeNanos, keepAliveTimeoutNanos, keepAliveWithoutCalls,
        transportTracerFactory, localSocketPicker, useGetForSafeMethods);
  }

  @VisibleForTesting
  void assertEventLoopAndChannelType() {
    boolean bothProvided = channelFactory != DEFAULT_CHANNEL_FACTORY
        && eventLoopGroupPool != DEFAULT_EVENT_LOOP_GROUP_POOL;
    boolean nonProvided = channelFactory == DEFAULT_CHANNEL_FACTORY
        && eventLoopGroupPool == DEFAULT_EVENT_LOOP_GROUP_POOL;
    checkState(
        bothProvided || nonProvided,
        "Both EventLoopGroup and ChannelType should be provided or neither should be");
  }

  @Override
  @CheckReturnValue
  protected int getDefaultPort() {
    switch (negotiationType) {
      case PLAINTEXT:
      case PLAINTEXT_UPGRADE:
        return GrpcUtil.DEFAULT_PORT_PLAINTEXT;
      case TLS:
        return GrpcUtil.DEFAULT_PORT_SSL;
      default:
        throw new AssertionError(negotiationType + " not handled");
    }
  }

  void overrideAuthorityChecker(@Nullable OverrideAuthorityChecker authorityChecker) {
    this.authorityChecker = authorityChecker;
  }

  @VisibleForTesting
  @CheckReturnValue
  static ProtocolNegotiator createProtocolNegotiatorByType(
      NegotiationType negotiationType,
      SslContext sslContext) {
    switch (negotiationType) {
      case PLAINTEXT:
        return ProtocolNegotiators.plaintext();
      case PLAINTEXT_UPGRADE:
        return ProtocolNegotiators.plaintextUpgrade();
      case TLS:
        return ProtocolNegotiators.tls(sslContext);
      default:
        throw new IllegalArgumentException("Unsupported negotiationType: " + negotiationType);
    }
  }

  @CheckReturnValue
  interface OverrideAuthorityChecker {
    String checkAuthority(String authority);
  }

  @Override
  @CheckReturnValue
  @Internal
  protected String checkAuthority(String authority) {
    if (authorityChecker != null) {
      return authorityChecker.checkAuthority(authority);
    }
    return super.checkAuthority(authority);
  }

  void protocolNegotiatorFactory(ProtocolNegotiatorFactory protocolNegotiatorFactory) {
    this.protocolNegotiatorFactory
        = checkNotNull(protocolNegotiatorFactory, "protocolNegotiatorFactory");
  }

  @Override
  protected void setTracingEnabled(boolean value) {
    super.setTracingEnabled(value);
  }

  @Override
  protected void setStatsEnabled(boolean value) {
    super.setStatsEnabled(value);
  }

  @Override
  protected void setStatsRecordStartedRpcs(boolean value) {
    super.setStatsRecordStartedRpcs(value);
  }

  @Override
  protected void setStatsRecordRealTimeMetrics(boolean value) {
    super.setStatsRecordRealTimeMetrics(value);
  }

  @VisibleForTesting
  NettyChannelBuilder setTransportTracerFactory(TransportTracer.Factory transportTracerFactory) {
    this.transportTracerFactory = transportTracerFactory;
    return this;
  }

  interface ProtocolNegotiatorFactory {
    /**
     * Returns a ProtocolNegotatior instance configured for this Builder. This method is called
     * during {@code ManagedChannelBuilder#build()}.
     */
    ProtocolNegotiator buildProtocolNegotiator();
  }

  /**
   * Creates Netty transports. Exposed for internal use, as it should be private.
   */
  @CheckReturnValue
  private static final class NettyTransportFactory implements ClientTransportFactory {
    private final ProtocolNegotiator protocolNegotiator;
    private final ChannelFactory<? extends Channel> channelFactory;
    private final Map<ChannelOption<?>, ?> channelOptions;
    private final ObjectPool<? extends EventLoopGroup> groupPool;
    private final EventLoopGroup group;
    private final int flowControlWindow;
    private final int maxMessageSize;
    private final int maxHeaderListSize;
    private final AtomicBackoff keepAliveTimeNanos;
    private final long keepAliveTimeoutNanos;
    private final boolean keepAliveWithoutCalls;
    private final TransportTracer.Factory transportTracerFactory;
    private final LocalSocketPicker localSocketPicker;
    private final boolean useGetForSafeMethods;

    private boolean closed;

    NettyTransportFactory(ProtocolNegotiator protocolNegotiator,
        ChannelFactory<? extends Channel> channelFactory,
        Map<ChannelOption<?>, ?> channelOptions, ObjectPool<? extends EventLoopGroup> groupPool,
        int flowControlWindow, int maxMessageSize, int maxHeaderListSize,
        long keepAliveTimeNanos, long keepAliveTimeoutNanos, boolean keepAliveWithoutCalls,
        TransportTracer.Factory transportTracerFactory, LocalSocketPicker localSocketPicker,
        boolean useGetForSafeMethods) {
      this.protocolNegotiator = checkNotNull(protocolNegotiator, "protocolNegotiator");
      this.channelFactory = channelFactory;
      this.channelOptions = new HashMap<ChannelOption<?>, Object>(channelOptions);
      this.groupPool = groupPool;
      this.group = groupPool.getObject();
      this.flowControlWindow = flowControlWindow;
      this.maxMessageSize = maxMessageSize;
      this.maxHeaderListSize = maxHeaderListSize;
      this.keepAliveTimeNanos = new AtomicBackoff("keepalive time nanos", keepAliveTimeNanos);
      this.keepAliveTimeoutNanos = keepAliveTimeoutNanos;
      this.keepAliveWithoutCalls = keepAliveWithoutCalls;
      this.transportTracerFactory = transportTracerFactory;
      this.localSocketPicker =
          localSocketPicker != null ? localSocketPicker : new LocalSocketPicker();
      this.useGetForSafeMethods = useGetForSafeMethods;
    }

    @Override
    public ConnectionClientTransport newClientTransport(
        SocketAddress serverAddress, ClientTransportOptions options, ChannelLogger channelLogger) {
      checkState(!closed, "The transport factory is closed.");

      ProtocolNegotiator localNegotiator = protocolNegotiator;
      HttpConnectProxiedSocketAddress proxiedAddr = options.getHttpConnectProxiedSocketAddress();
      if (proxiedAddr != null) {
        serverAddress = proxiedAddr.getTargetAddress();
        localNegotiator = ProtocolNegotiators.httpProxy(
            proxiedAddr.getProxyAddress(),
            proxiedAddr.getUsername(),
            proxiedAddr.getPassword(),
            protocolNegotiator);
      }

      final AtomicBackoff.State keepAliveTimeNanosState = keepAliveTimeNanos.getState();
      Runnable tooManyPingsRunnable = new Runnable() {
        @Override
        public void run() {
          keepAliveTimeNanosState.backoff();
        }
      };

      // TODO(carl-mastrangelo): Pass channelLogger in.
      NettyClientTransport transport = new NettyClientTransport(
          serverAddress, channelFactory, channelOptions, group,
          localNegotiator, flowControlWindow,
          maxMessageSize, maxHeaderListSize, keepAliveTimeNanosState.get(), keepAliveTimeoutNanos,
          keepAliveWithoutCalls, options.getAuthority(), options.getUserAgent(),
          tooManyPingsRunnable, transportTracerFactory.create(), options.getEagAttributes(),
          localSocketPicker, channelLogger, useGetForSafeMethods);
      return transport;
    }

    @Override
    public ScheduledExecutorService getScheduledExecutorService() {
      return group;
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;

      protocolNegotiator.close();
      groupPool.returnObject(group);
    }
  }
}
