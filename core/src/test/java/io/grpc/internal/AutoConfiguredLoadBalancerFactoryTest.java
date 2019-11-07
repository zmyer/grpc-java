/*
 * Copyright 2018 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.LoadBalancer.ATTR_LOAD_BALANCING_CONFIG;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.google.common.base.Preconditions;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.grpclb.GrpclbLoadBalancerProvider;
import io.grpc.internal.AutoConfiguredLoadBalancerFactory.AutoConfiguredLoadBalancer;
import io.grpc.internal.AutoConfiguredLoadBalancerFactory.PolicyException;
import io.grpc.internal.AutoConfiguredLoadBalancerFactory.PolicySelection;
import io.grpc.util.ForwardingLoadBalancerHelper;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link AutoConfiguredLoadBalancerFactory}.
 */
@RunWith(JUnit4.class)
public class AutoConfiguredLoadBalancerFactoryTest {
  private static final LoadBalancerRegistry defaultRegistry =
      LoadBalancerRegistry.getDefaultRegistry();
  private final AutoConfiguredLoadBalancerFactory lbf =
      new AutoConfiguredLoadBalancerFactory(GrpcUtil.DEFAULT_LB_POLICY);

  private final ChannelLogger channelLogger = mock(ChannelLogger.class);
  private final LoadBalancer testLbBalancer = mock(LoadBalancer.class);
  private final LoadBalancer testLbBalancer2 = mock(LoadBalancer.class);
  private final LoadBalancerProvider testLbBalancerProvider =
      mock(LoadBalancerProvider.class,
          delegatesTo(new FakeLoadBalancerProvider("test_lb", testLbBalancer)));
  private final LoadBalancerProvider testLbBalancerProvider2 =
      mock(LoadBalancerProvider.class,
          delegatesTo(new FakeLoadBalancerProvider("test_lb2", testLbBalancer2)));

  @Before
  public void setUp() {
    when(testLbBalancer.canHandleEmptyAddressListFromNameResolution()).thenCallRealMethod();
    assertThat(testLbBalancer.canHandleEmptyAddressListFromNameResolution()).isFalse();
    when(testLbBalancer2.canHandleEmptyAddressListFromNameResolution()).thenReturn(true);
    defaultRegistry.register(testLbBalancerProvider);
    defaultRegistry.register(testLbBalancerProvider2);
  }

  @After
  public void tearDown() {
    defaultRegistry.deregister(testLbBalancerProvider);
    defaultRegistry.deregister(testLbBalancerProvider2);
  }

  @Test
  public void newLoadBalancer_isAuto() {
    AutoConfiguredLoadBalancer lb = lbf.newLoadBalancer(new TestHelper());

    assertThat(lb).isInstanceOf(AutoConfiguredLoadBalancer.class);
  }

  @Test
  public void defaultIsPickFirst() {
    AutoConfiguredLoadBalancer lb = lbf.newLoadBalancer(new TestHelper());

    assertThat(lb.getDelegateProvider()).isInstanceOf(PickFirstLoadBalancerProvider.class);
    assertThat(lb.getDelegate().getClass().getName()).contains("PickFirst");
  }

  @Test
  public void defaultIsConfigurable() {
    AutoConfiguredLoadBalancer lb = new AutoConfiguredLoadBalancerFactory("test_lb")
        .newLoadBalancer(new TestHelper());

    assertThat(lb.getDelegateProvider()).isSameInstanceAs(testLbBalancerProvider);
    assertThat(lb.getDelegate()).isSameInstanceAs(testLbBalancer);
  }

  @SuppressWarnings("deprecation")
  @Test
  public void forwardsCalls() {
    AutoConfiguredLoadBalancer lb = lbf.newLoadBalancer(new TestHelper());

    final AtomicInteger calls = new AtomicInteger();
    TestLoadBalancer testlb = new TestLoadBalancer() {

      @Override
      public void handleNameResolutionError(Status error) {
        calls.getAndSet(1);
      }

      @Override
      public void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
        calls.getAndSet(2);
      }

      @Override
      public void shutdown() {
        calls.getAndSet(3);
      }
    };

    lb.setDelegate(testlb);

    lb.handleNameResolutionError(Status.RESOURCE_EXHAUSTED);
    assertThat(calls.getAndSet(0)).isEqualTo(1);

    lb.handleSubchannelState(null, null);
    assertThat(calls.getAndSet(0)).isEqualTo(2);

    lb.shutdown();
    assertThat(calls.getAndSet(0)).isEqualTo(3);
  }

  @Test
  public void handleResolvedAddressGroups_keepOldBalancer() {
    final List<EquivalentAddressGroup> servers =
        Collections.singletonList(new EquivalentAddressGroup(new SocketAddress(){}));
    Helper helper = new TestHelper() {
      @Override
      public Subchannel createSubchannel(CreateSubchannelArgs args) {
        assertThat(args.getAddresses()).isEqualTo(servers);
        return new TestSubchannel(args);
      }
    };
    AutoConfiguredLoadBalancer lb = lbf.newLoadBalancer(helper);
    LoadBalancer oldDelegate = lb.getDelegate();

    Status handleResult = lb.tryHandleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers)
            .setAttributes(Attributes.EMPTY)
            .build());

    assertThat(handleResult.getCode()).isEqualTo(Status.Code.OK);
    assertThat(lb.getDelegate()).isSameInstanceAs(oldDelegate);
  }

  @Test
  public void handleResolvedAddressGroups_shutsDownOldBalancer() {
    Map<String, String> serviceConfig = new HashMap<>();
    serviceConfig.put("loadBalancingPolicy", "round_robin");
    Attributes serviceConfigAttrs =
        Attributes.newBuilder()
            .set(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG, serviceConfig)
            .build();
    final List<EquivalentAddressGroup> servers =
        Collections.singletonList(new EquivalentAddressGroup(new SocketAddress(){}));
    Helper helper = new TestHelper() {
      @Override
      public Subchannel createSubchannel(CreateSubchannelArgs args) {
        assertThat(args.getAddresses()).isEqualTo(servers);
        return new TestSubchannel(args);
      }
    };
    AutoConfiguredLoadBalancer lb = lbf.newLoadBalancer(helper);
    final AtomicBoolean shutdown = new AtomicBoolean();
    TestLoadBalancer testlb = new TestLoadBalancer() {

      @Override
      public void handleNameResolutionError(Status error) {
        // noop
      }

      @Override
      public void shutdown() {
        shutdown.set(true);
      }
    };
    lb.setDelegate(testlb);

    Status handleResult = lb.tryHandleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers)
            .setAttributes(serviceConfigAttrs)
            .build());

    assertThat(handleResult.getCode()).isEqualTo(Status.Code.OK);
    assertThat(lb.getDelegateProvider().getClass().getName()).isEqualTo(
        "io.grpc.util.SecretRoundRobinLoadBalancerProvider$Provider");
    assertTrue(shutdown.get());
  }

  @Test
  public void handleResolvedAddressGroups_propagateLbConfigToDelegate() throws Exception {
    Map<String, ?> serviceConfig =
        parseConfig("{\"loadBalancingConfig\": [ {\"test_lb\": { \"setting1\": \"high\" } } ] }");
    Attributes serviceConfigAttrs =
        Attributes.newBuilder()
            .set(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG, serviceConfig)
            .build();
    final List<EquivalentAddressGroup> servers =
        Collections.singletonList(new EquivalentAddressGroup(new SocketAddress(){}));
    Helper helper = new TestHelper();
    AutoConfiguredLoadBalancer lb = lbf.newLoadBalancer(helper);

    Status handleResult = lb.tryHandleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers)
            .setAttributes(serviceConfigAttrs)
            .build());

    verify(testLbBalancerProvider).newLoadBalancer(same(helper));
    assertThat(handleResult.getCode()).isEqualTo(Status.Code.OK);
    assertThat(lb.getDelegate()).isSameInstanceAs(testLbBalancer);
    ArgumentCaptor<ResolvedAddresses> resultCaptor =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(testLbBalancer).handleResolvedAddresses(resultCaptor.capture());
    assertThat(resultCaptor.getValue().getAddresses()).containsExactlyElementsIn(servers).inOrder();
    Attributes actualAttributes = resultCaptor.getValue().getAttributes();
    assertThat(actualAttributes.get(ATTR_LOAD_BALANCING_CONFIG))
        .isEqualTo(Collections.singletonMap("setting1", "high"));
    verify(testLbBalancer, atLeast(0)).canHandleEmptyAddressListFromNameResolution();
    verifyNoMoreInteractions(testLbBalancer);

    serviceConfig =
        parseConfig("{\"loadBalancingConfig\": [ {\"test_lb\": { \"setting1\": \"low\" } } ] }");
    serviceConfigAttrs =
        Attributes.newBuilder()
            .set(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG, serviceConfig)
            .build();
    handleResult = lb.tryHandleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers)
            .setAttributes(serviceConfigAttrs)
            .build());

    resultCaptor =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(testLbBalancer, times(2)).handleResolvedAddresses(resultCaptor.capture());
    assertThat(handleResult.getCode()).isEqualTo(Status.Code.OK);
    assertThat(resultCaptor.getValue().getAddresses()).containsExactlyElementsIn(servers).inOrder();
    actualAttributes = resultCaptor.getValue().getAttributes();
    // But the balancer config is changed.
    assertThat(actualAttributes.get(ATTR_LOAD_BALANCING_CONFIG))
        .isEqualTo(Collections.singletonMap("setting1", "low"));
    // Service config didn't change policy, thus the delegateLb is not swapped
    verifyNoMoreInteractions(testLbBalancer);
    verify(testLbBalancerProvider).newLoadBalancer(any(Helper.class));
  }

  @Test
  public void handleResolvedAddressGroups_propagateOnlyBackendAddrsToDelegate() throws Exception {
    // This case only happens when grpclb is missing.  We will use a local registry
    LoadBalancerRegistry registry = new LoadBalancerRegistry();
    registry.register(new PickFirstLoadBalancerProvider());
    registry.register(new FakeLoadBalancerProvider("round_robin", testLbBalancer));

    final List<EquivalentAddressGroup> servers =
        Arrays.asList(
            new EquivalentAddressGroup(new SocketAddress(){}),
            new EquivalentAddressGroup(
                new SocketAddress(){},
                Attributes.newBuilder().set(GrpcAttributes.ATTR_LB_ADDR_AUTHORITY, "ok").build()));
    Helper helper = new TestHelper();
    AutoConfiguredLoadBalancer lb = new AutoConfiguredLoadBalancerFactory(
            registry, GrpcUtil.DEFAULT_LB_POLICY).newLoadBalancer(helper);

    Status handleResult = lb.tryHandleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers)
            .setAttributes(Attributes.EMPTY)
            .build());

    assertThat(handleResult.getCode()).isEqualTo(Status.Code.OK);
    assertThat(lb.getDelegate()).isSameInstanceAs(testLbBalancer);
    verify(testLbBalancer).handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.singletonList(servers.get(0)))
            .setAttributes(Attributes.EMPTY)
            .build());
  }

  @Test
  public void handleResolvedAddressGroups_delegateDoNotAcceptEmptyAddressList_nothing()
      throws Exception {
    Helper helper = new TestHelper();
    AutoConfiguredLoadBalancer lb = lbf.newLoadBalancer(helper);

    Map<String, ?> serviceConfig =
        parseConfig("{\"loadBalancingConfig\": [ {\"test_lb\": { \"setting1\": \"high\" } } ] }");
    Status handleResult = lb.tryHandleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(Attributes.newBuilder()
                .set(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG, serviceConfig).build())
            .build());

    assertThat(testLbBalancer.canHandleEmptyAddressListFromNameResolution()).isFalse();
    assertThat(handleResult.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(handleResult.getDescription()).startsWith("NameResolver returned no usable address");
    assertThat(lb.getDelegate()).isSameInstanceAs(testLbBalancer);
  }

  @Test
  public void handleResolvedAddressGroups_delegateAcceptsEmptyAddressList()
      throws Exception {
    Helper helper = new TestHelper();
    AutoConfiguredLoadBalancer lb = lbf.newLoadBalancer(helper);

    Map<String, ?> serviceConfig =
        parseConfig("{\"loadBalancingConfig\": [ {\"test_lb2\": { \"setting1\": \"high\" } } ] }");
    Status handleResult = lb.tryHandleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(Attributes.newBuilder()
                .set(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG, serviceConfig).build())
            .build());

    assertThat(handleResult.getCode()).isEqualTo(Status.Code.OK);
    assertThat(lb.getDelegate()).isSameInstanceAs(testLbBalancer2);
    assertThat(testLbBalancer2.canHandleEmptyAddressListFromNameResolution()).isTrue();
    ArgumentCaptor<ResolvedAddresses> resultCaptor =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(testLbBalancer2).handleResolvedAddresses(resultCaptor.capture());
    assertThat(resultCaptor.getValue().getAddresses()).isEmpty();
    Attributes actualAttributes = resultCaptor.getValue().getAttributes();

    Map<String, ?> lbConfig = actualAttributes.get(LoadBalancer.ATTR_LOAD_BALANCING_CONFIG);
    assertThat(lbConfig).isEqualTo(Collections.<String, Object>singletonMap("setting1", "high"));
    assertThat(actualAttributes.get(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG))
        .isSameInstanceAs(serviceConfig);
  }

  @Test
  public void decideLoadBalancerProvider_noBalancerAddresses_noServiceConfig_pickFirst()
      throws Exception {
    AutoConfiguredLoadBalancer lb = lbf.newLoadBalancer(new TestHelper());
    Map<String, ?> serviceConfig = null;
    List<EquivalentAddressGroup> servers =
        Collections.singletonList(new EquivalentAddressGroup(new SocketAddress(){}));
    PolicySelection selection = lb.decideLoadBalancerProvider(servers, serviceConfig);

    assertThat(selection.provider).isInstanceOf(PickFirstLoadBalancerProvider.class);
    assertThat(selection.serverList).isEqualTo(servers);
    assertThat(selection.config).isNull();
    verifyZeroInteractions(channelLogger);
  }

  @Test
  public void decideLoadBalancerProvider_noBalancerAddresses_noServiceConfig_customDefault()
      throws Exception {
    AutoConfiguredLoadBalancer lb = new AutoConfiguredLoadBalancerFactory("test_lb")
        .newLoadBalancer(new TestHelper());
    Map<String, ?> serviceConfig = null;
    List<EquivalentAddressGroup> servers =
        Collections.singletonList(new EquivalentAddressGroup(new SocketAddress(){}));
    PolicySelection selection = lb.decideLoadBalancerProvider(servers, serviceConfig);

    assertThat(selection.provider).isSameInstanceAs(testLbBalancerProvider);
    assertThat(selection.serverList).isEqualTo(servers);
    assertThat(selection.config).isNull();
    verifyZeroInteractions(channelLogger);
  }

  @Test
  public void decideLoadBalancerProvider_oneBalancer_noServiceConfig_grpclb() throws Exception {
    AutoConfiguredLoadBalancer lb = lbf.newLoadBalancer(new TestHelper());
    Map<String, ?> serviceConfig = null;
    List<EquivalentAddressGroup> servers =
        Collections.singletonList(
            new EquivalentAddressGroup(
                new SocketAddress(){},
                Attributes.newBuilder().set(GrpcAttributes.ATTR_LB_ADDR_AUTHORITY, "ok").build()));
    PolicySelection selection = lb.decideLoadBalancerProvider(servers, serviceConfig);

    assertThat(selection.provider).isInstanceOf(GrpclbLoadBalancerProvider.class);
    assertThat(selection.serverList).isEqualTo(servers);
    assertThat(selection.config).isNull();
    verifyZeroInteractions(channelLogger);
  }

  @Test
  public void decideLoadBalancerProvider_serviceConfigLbPolicy() throws Exception {
    AutoConfiguredLoadBalancer lb = lbf.newLoadBalancer(new TestHelper());
    Map<String, String> serviceConfig = new HashMap<>();
    serviceConfig.put("loadBalancingPolicy", "round_robin");
    List<EquivalentAddressGroup> servers =
        Arrays.asList(
            new EquivalentAddressGroup(
                new SocketAddress(){},
                Attributes.newBuilder().set(GrpcAttributes.ATTR_LB_ADDR_AUTHORITY, "ok").build()),
            new EquivalentAddressGroup(
                new SocketAddress(){}));
    List<EquivalentAddressGroup> backends = Arrays.asList(servers.get(1));
    PolicySelection selection = lb.decideLoadBalancerProvider(servers, serviceConfig);

    assertThat(selection.provider.getClass().getName()).isEqualTo(
        "io.grpc.util.SecretRoundRobinLoadBalancerProvider$Provider");
    assertThat(selection.serverList).isEqualTo(backends);
    assertThat(selection.config).isEqualTo(Collections.<String, Object>emptyMap());
    verifyZeroInteractions(channelLogger);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void decideLoadBalancerProvider_serviceConfigLbConfig() throws Exception {
    AutoConfiguredLoadBalancer lb = lbf.newLoadBalancer(new TestHelper());
    Map<String, ?> serviceConfig =
        parseConfig("{\"loadBalancingConfig\": [ {\"round_robin\": {} } ] }");
    List<EquivalentAddressGroup> servers =
        Arrays.asList(
            new EquivalentAddressGroup(
                new SocketAddress(){},
                Attributes.newBuilder().set(GrpcAttributes.ATTR_LB_ADDR_AUTHORITY, "ok").build()),
            new EquivalentAddressGroup(
                new SocketAddress(){}));
    List<EquivalentAddressGroup> backends = Arrays.asList(servers.get(1));
    PolicySelection selection = lb.decideLoadBalancerProvider(servers, serviceConfig);

    assertThat(selection.provider.getClass().getName()).isEqualTo(
        "io.grpc.util.SecretRoundRobinLoadBalancerProvider$Provider");
    assertThat(selection.serverList).isEqualTo(backends);
    assertThat(selection.config).isEqualTo(Collections.<String, Object>emptyMap());
    verifyZeroInteractions(channelLogger);
  }

  @Test
  public void decideLoadBalancerProvider_grpclbConfigPropagated() throws Exception {
    AutoConfiguredLoadBalancer lb = lbf.newLoadBalancer(new TestHelper());
    Map<String, ?> serviceConfig =
        parseConfig(
            "{\"loadBalancingConfig\": ["
            + "{\"grpclb\": {\"childPolicy\": [ {\"pick_first\": {} } ] } }"
            + "] }");
    List<EquivalentAddressGroup> servers =
        Collections.singletonList(
            new EquivalentAddressGroup(
                new SocketAddress(){},
                Attributes.newBuilder().set(GrpcAttributes.ATTR_LB_ADDR_AUTHORITY, "ok").build()));
    PolicySelection selection = lb.decideLoadBalancerProvider(servers, serviceConfig);

    assertThat(selection.provider).isInstanceOf(GrpclbLoadBalancerProvider.class);
    assertThat(selection.serverList).isEqualTo(servers);
    assertThat(selection.config).isEqualTo(
        parseConfig("{\"childPolicy\": [ {\"pick_first\": {} } ] }"));
    verifyZeroInteractions(channelLogger);
  }

  @Test
  public void decideLoadBalancerProvider_policyUnavailButGrpclbAddressPresent() throws Exception {
    AutoConfiguredLoadBalancer lb = lbf.newLoadBalancer(new TestHelper());
    Map<String, ?> serviceConfig =
        parseConfig(
            "{\"loadBalancingConfig\": ["
            + "{\"unavail\": {} }"
            + "] }");
    List<EquivalentAddressGroup> servers =
        Collections.singletonList(
            new EquivalentAddressGroup(
                new SocketAddress(){},
                Attributes.newBuilder().set(GrpcAttributes.ATTR_LB_ADDR_AUTHORITY, "ok").build()));
    PolicySelection selection = lb.decideLoadBalancerProvider(servers, serviceConfig);

    assertThat(selection.provider).isInstanceOf(GrpclbLoadBalancerProvider.class);
    assertThat(selection.serverList).isEqualTo(servers);
    assertThat(selection.config).isNull();
    verifyZeroInteractions(channelLogger);
  }

  @Test
  public void decideLoadBalancerProvider_grpclbProviderNotFound_fallbackToRoundRobin()
      throws Exception {
    LoadBalancerRegistry registry = new LoadBalancerRegistry();
    registry.register(new PickFirstLoadBalancerProvider());
    LoadBalancerProvider fakeRoundRobinProvider =
        new FakeLoadBalancerProvider("round_robin", testLbBalancer);
    registry.register(fakeRoundRobinProvider);
    AutoConfiguredLoadBalancer lb = new AutoConfiguredLoadBalancerFactory(
        registry, GrpcUtil.DEFAULT_LB_POLICY).newLoadBalancer(new TestHelper());
    Map<String, ?> serviceConfig =
        parseConfig("{\"loadBalancingConfig\": [ {\"grpclb\": {} } ] }");
    List<EquivalentAddressGroup> servers =
        Arrays.asList(
            new EquivalentAddressGroup(
                new SocketAddress(){},
                Attributes.newBuilder().set(GrpcAttributes.ATTR_LB_ADDR_AUTHORITY, "ok").build()),
            new EquivalentAddressGroup(new SocketAddress(){}));
    PolicySelection selection = lb.decideLoadBalancerProvider(servers, serviceConfig);

    assertThat(selection.provider).isSameInstanceAs(fakeRoundRobinProvider);
    assertThat(selection.config).isNull();
    verify(channelLogger).log(
        eq(ChannelLogLevel.ERROR),
        startsWith("Found balancer addresses but grpclb runtime is missing"));

    // Called for the second time, the warning is only logged once
    selection = lb.decideLoadBalancerProvider(servers, serviceConfig);

    assertThat(selection.provider).isSameInstanceAs(fakeRoundRobinProvider);
    // Balancer addresses are filtered out in the server list passed to round_robin
    assertThat(selection.serverList).containsExactly(servers.get(1));
    assertThat(selection.config).isNull();
    verifyNoMoreInteractions(channelLogger);
  }

  @Test
  public void decideLoadBalancerProvider_grpclbProviderNotFound_noBackendAddress()
      throws Exception {
    LoadBalancerRegistry registry = new LoadBalancerRegistry();
    registry.register(new PickFirstLoadBalancerProvider());
    registry.register(new FakeLoadBalancerProvider("round_robin", testLbBalancer));
    AutoConfiguredLoadBalancer lb = new AutoConfiguredLoadBalancerFactory(
        registry, GrpcUtil.DEFAULT_LB_POLICY).newLoadBalancer(new TestHelper());
    Map<String, ?> serviceConfig =
        parseConfig("{\"loadBalancingConfig\": [ {\"grpclb\": {} } ] }");
    List<EquivalentAddressGroup> servers =
        Collections.singletonList(
            new EquivalentAddressGroup(
                new SocketAddress(){},
                Attributes.newBuilder().set(GrpcAttributes.ATTR_LB_ADDR_AUTHORITY, "ok").build()));
    try {
      lb.decideLoadBalancerProvider(servers, serviceConfig);
      fail("Should throw");
    } catch (PolicyException e) {
      assertThat(e)
          .hasMessageThat()
          .isEqualTo("Received ONLY balancer addresses but grpclb runtime is missing");
    }
  }

  @Test
  public void decideLoadBalancerProvider_serviceConfigLbPolicyOverridesDefault() throws Exception {
    AutoConfiguredLoadBalancer lb = lbf.newLoadBalancer(new TestHelper());
    Map<String, String> serviceConfig = new HashMap<>();
    serviceConfig.put("loadBalancingPolicy", "round_robin");
    List<EquivalentAddressGroup> servers =
        Collections.singletonList(new EquivalentAddressGroup(new SocketAddress(){}));
    PolicySelection selection = lb.decideLoadBalancerProvider(servers, serviceConfig);

    assertThat(selection.provider.getClass().getName()).isEqualTo(
        "io.grpc.util.SecretRoundRobinLoadBalancerProvider$Provider");
    assertThat(selection.config).isEqualTo(Collections.emptyMap());
    verifyZeroInteractions(channelLogger);
  }

  @Test
  public void decideLoadBalancerProvider_serviceConfigLbConfigOverridesDefault() throws Exception {
    AutoConfiguredLoadBalancer lb = lbf.newLoadBalancer(new TestHelper());
    Map<String, ?> serviceConfig =
        parseConfig("{\"loadBalancingConfig\": [ {\"round_robin\": {\"setting1\": \"high\"} } ] }");
    List<EquivalentAddressGroup> servers =
        Collections.singletonList(new EquivalentAddressGroup(new SocketAddress(){}));
    PolicySelection selection = lb.decideLoadBalancerProvider(servers, serviceConfig);

    assertThat(selection.provider.getClass().getName()).isEqualTo(
        "io.grpc.util.SecretRoundRobinLoadBalancerProvider$Provider");
    assertThat(selection.serverList).isEqualTo(servers);
    assertThat(selection.config).isEqualTo(Collections.singletonMap("setting1", "high"));
    verifyZeroInteractions(channelLogger);
  }

  @Test
  public void decideLoadBalancerProvider_serviceConfigLbPolicyFailsOnUnknown() {
    AutoConfiguredLoadBalancer lb = lbf.newLoadBalancer(new TestHelper());
    Map<String, String> serviceConfig = new HashMap<>();
    serviceConfig.put("loadBalancingPolicy", "MAGIC_BALANCER");
    List<EquivalentAddressGroup> servers =
        Collections.singletonList(new EquivalentAddressGroup(new SocketAddress(){}));
    try {
      lb.decideLoadBalancerProvider(servers, serviceConfig);
      fail();
    } catch (PolicyException e) {
      assertThat(e).hasMessageThat().isEqualTo(
          "None of [magic_balancer] specified by Service Config are available.");
    }
  }

  @Test
  public void decideLoadBalancerProvider_serviceConfigLbConfigFailsOnUnknown() throws Exception {
    AutoConfiguredLoadBalancer lb = lbf.newLoadBalancer(new TestHelper());
    Map<String, ?> serviceConfig =
        parseConfig("{\"loadBalancingConfig\": [ {\"magic_balancer\": {} } ] }");
    List<EquivalentAddressGroup> servers =
        Collections.singletonList(new EquivalentAddressGroup(new SocketAddress(){}));
    try {
      lb.decideLoadBalancerProvider(servers, serviceConfig);
      fail();
    } catch (PolicyException e) {
      assertThat(e).hasMessageThat().isEqualTo(
          "None of [magic_balancer] specified by Service Config are available.");
    }
  }

  @Test
  public void decideLoadBalancerProvider_serviceConfigLbConfigSkipUnknown() throws Exception {
    AutoConfiguredLoadBalancer lb = lbf.newLoadBalancer(new TestHelper());
    Map<String, ?> serviceConfig =
        parseConfig(
            "{\"loadBalancingConfig\": [ {\"magic_balancer\": {} }, {\"round_robin\": {} } ] }");
    List<EquivalentAddressGroup> servers =
        Collections.singletonList(new EquivalentAddressGroup(new SocketAddress(){}));
    PolicySelection selection = lb.decideLoadBalancerProvider(servers, serviceConfig);

    assertThat(selection.provider.getClass().getName()).isEqualTo(
        "io.grpc.util.SecretRoundRobinLoadBalancerProvider$Provider");
    assertThat(selection.serverList).isEqualTo(servers);
    assertThat(selection.config).isEqualTo(Collections.emptyMap());
    verify(channelLogger).log(
        eq(ChannelLogLevel.DEBUG),
        eq("{0} specified by Service Config are not available"),
        eq(new LinkedHashSet<>(Arrays.asList("magic_balancer"))));
  }

  @Test
  public void decideLoadBalancerProvider_serviceConfigHasZeroLbConfig() throws Exception {
    AutoConfiguredLoadBalancer lb = lbf.newLoadBalancer(new TestHelper());
    List<EquivalentAddressGroup> servers =
        Collections.singletonList(new EquivalentAddressGroup(new SocketAddress(){}));
    PolicySelection selection = lb.decideLoadBalancerProvider(
        servers, Collections.<String, Object>emptyMap());

    assertThat(selection.provider).isInstanceOf(PickFirstLoadBalancerProvider.class);
    assertThat(selection.serverList).isEqualTo(servers);
    assertThat(selection.config).isNull();
    verifyZeroInteractions(channelLogger);
  }

  @Test
  public void channelTracing_lbPolicyChanged() {
    final FakeClock clock = new FakeClock();
    List<EquivalentAddressGroup> servers =
        Collections.singletonList(new EquivalentAddressGroup(new SocketAddress(){}));
    Helper helper = new TestHelper() {
      @Override
      @Deprecated
      public Subchannel createSubchannel(List<EquivalentAddressGroup> addrs, Attributes attrs) {
        return new TestSubchannel(CreateSubchannelArgs.newBuilder()
            .setAddresses(addrs)
            .setAttributes(attrs)
            .build());
      }

      @Override
      public Subchannel createSubchannel(CreateSubchannelArgs args) {
        return new TestSubchannel(args);
      }

      @Override
      public ManagedChannel createOobChannel(EquivalentAddressGroup eag, String authority) {
        return mock(ManagedChannel.class, RETURNS_DEEP_STUBS);
      }

      @Override
      public String getAuthority() {
        return "fake_authority";
      }

      @Override
      public SynchronizationContext getSynchronizationContext() {
        return new SynchronizationContext(
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread t, Throwable e) {
                throw new AssertionError(e);
              }
            });
      }

      @Override
      public ScheduledExecutorService getScheduledExecutorService() {
        return clock.getScheduledExecutorService();
      }
    };

    AutoConfiguredLoadBalancer lb =
        new AutoConfiguredLoadBalancerFactory(GrpcUtil.DEFAULT_LB_POLICY).newLoadBalancer(helper);
    Status handleResult = lb.tryHandleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers)
            .setAttributes(Attributes.EMPTY)
            .build());

    assertThat(handleResult.getCode()).isEqualTo(Status.Code.OK);
    verifyNoMoreInteractions(channelLogger);

    Map<String, String> serviceConfig = new HashMap<>();
    serviceConfig.put("loadBalancingPolicy", "round_robin");
    handleResult = lb.tryHandleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers)
            .setAttributes(Attributes.newBuilder()
                .set(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG, serviceConfig).build())
            .build());

    assertThat(handleResult.getCode()).isEqualTo(Status.Code.OK);
    verify(channelLogger).log(
        eq(ChannelLogLevel.INFO),
        eq("Load balancer changed from {0} to {1}"),
        eq("PickFirstLoadBalancer"), eq("RoundRobinLoadBalancer"));
    verify(channelLogger).log(
        eq(ChannelLogLevel.DEBUG),
        eq("Load-balancing config: {0}"),
        eq(Collections.emptyMap()));
    verifyNoMoreInteractions(channelLogger);

    serviceConfig.put("loadBalancingPolicy", "round_robin");
    handleResult = lb.tryHandleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers)
            .setAttributes(Attributes.newBuilder()
                .set(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG, serviceConfig).build())
            .build());
    assertThat(handleResult.getCode()).isEqualTo(Status.Code.OK);
    verify(channelLogger, times(2)).log(
        eq(ChannelLogLevel.DEBUG),
        eq("Load-balancing config: {0}"),
        eq(Collections.emptyMap()));
    verifyNoMoreInteractions(channelLogger);

    servers = Collections.singletonList(new EquivalentAddressGroup(
        new SocketAddress(){},
        Attributes.newBuilder().set(GrpcAttributes.ATTR_LB_ADDR_AUTHORITY, "ok").build()));
    handleResult = lb.tryHandleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers)
            .setAttributes(Attributes.EMPTY)
            .build());

    assertThat(handleResult.getCode()).isEqualTo(Status.Code.OK);
    verify(channelLogger).log(
        eq(ChannelLogLevel.INFO),
        eq("Load balancer changed from {0} to {1}"),
        eq("RoundRobinLoadBalancer"), eq("GrpclbLoadBalancer"));

    verifyNoMoreInteractions(channelLogger);
  }

  public static class ForwardingLoadBalancer extends LoadBalancer {
    private final LoadBalancer delegate;

    public ForwardingLoadBalancer(LoadBalancer delegate) {
      this.delegate = delegate;
    }

    protected LoadBalancer delegate() {
      return delegate;
    }

    @Override
    @Deprecated
    public void handleResolvedAddressGroups(
        List<EquivalentAddressGroup> servers, Attributes attributes) {
      delegate().handleResolvedAddressGroups(servers, attributes);
    }

    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      delegate().handleResolvedAddresses(resolvedAddresses);
    }

    @Override
    public void handleNameResolutionError(Status error) {
      delegate().handleNameResolutionError(error);
    }

    @Override
    public void shutdown() {
      delegate().shutdown();
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> parseConfig(String json) throws Exception {
    return (Map<String, ?>) JsonParser.parse(json);
  }

  private static class TestLoadBalancer extends ForwardingLoadBalancer {
    TestLoadBalancer() {
      super(null);
    }
  }

  private class TestHelper extends ForwardingLoadBalancerHelper {
    @Override
    protected Helper delegate() {
      return null;
    }

    @Override
    public ChannelLogger getChannelLogger() {
      return channelLogger;
    }

    @Override
    public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
      // noop
    }
  }

  private static class TestSubchannel extends Subchannel {
    TestSubchannel(CreateSubchannelArgs args) {
      this.addrs = args.getAddresses();
      this.attrs = args.getAttributes();
    }

    List<EquivalentAddressGroup> addrs;
    final Attributes attrs;

    @Override
    public void start(SubchannelStateListener listener) {
    }

    @Override
    public void shutdown() {
    }

    @Override
    public void requestConnection() {
    }

    @Override
    public List<EquivalentAddressGroup> getAllAddresses() {
      return addrs;
    }

    @Override
    public Attributes getAttributes() {
      return attrs;
    }

    @Override
    public void updateAddresses(List<EquivalentAddressGroup> addrs) {
      Preconditions.checkNotNull(addrs, "addrs");
      this.addrs = addrs;
    }
  }

  private static final class FakeLoadBalancerProvider extends LoadBalancerProvider {
    private final String policyName;
    private final LoadBalancer balancer;

    FakeLoadBalancerProvider(String policyName, LoadBalancer balancer) {
      this.policyName = policyName;
      this.balancer = balancer;
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int getPriority() {
      return 5;
    }

    @Override
    public String getPolicyName() {
      return policyName;
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      return balancer;
    }
  }
}
