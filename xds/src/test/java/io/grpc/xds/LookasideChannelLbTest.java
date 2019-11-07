/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.UInt32Value;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.core.Address;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.api.v2.core.SocketAddress;
import io.envoyproxy.envoy.api.v2.endpoint.Endpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.envoyproxy.envoy.type.FractionalPercent;
import io.envoyproxy.envoy.type.FractionalPercent.DenominatorType;
import io.grpc.ChannelLogger;
import io.grpc.LoadBalancer.Helper;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.testing.StreamRecorder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.LoadReportClient.LoadReportCallback;
import io.grpc.xds.LookasideChannelLb.LookasideChannelCallback;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link LookasideChannelLb}.
 */
@RunWith(JUnit4.class)
public class LookasideChannelLbTest {

  private static final String SERVICE_AUTHORITY = "test authority";

  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();
  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final StreamRecorder<DiscoveryRequest> streamRecorder = StreamRecorder.create();

  private final DiscoveryResponse edsResponse =
      DiscoveryResponse.newBuilder()
          .addResources(Any.pack(ClusterLoadAssignment.getDefaultInstance()))
          .setTypeUrl("type.googleapis.com/envoy.api.v2.ClusterLoadAssignment")
          .build();

  @Mock
  private Helper helper;
  @Mock
  private LookasideChannelCallback lookasideChannelCallback;
  @Mock
  private LoadReportClient loadReportClient;
  @Mock
  private LocalityStore localityStore;
  @Mock
  private LoadStatsStore loadStatsStore;

  private ManagedChannel channel;
  private StreamObserver<DiscoveryResponse> serverResponseWriter;

  @Captor
  private ArgumentCaptor<ImmutableMap<Locality, LocalityLbEndpoints>>
      localityEndpointsMappingCaptor;

  private LookasideChannelLb lookasideChannelLb;

  @Before
  public void setUp() throws Exception {
    AggregatedDiscoveryServiceImplBase serviceImpl = new AggregatedDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamAggregatedResources(
          final StreamObserver<DiscoveryResponse> responseObserver) {
        serverResponseWriter = responseObserver;

        return new StreamObserver<DiscoveryRequest>() {

          @Override
          public void onNext(DiscoveryRequest value) {
            streamRecorder.onNext(value);
          }

          @Override
          public void onError(Throwable t) {
            streamRecorder.onError(t);
          }

          @Override
          public void onCompleted() {
            streamRecorder.onCompleted();
            responseObserver.onCompleted();
          }
        };
      }
    };

    String serverName = InProcessServerBuilder.generateName();
    cleanupRule.register(
        InProcessServerBuilder
            .forName(serverName)
            .directExecutor()
            .addService(serviceImpl)
            .build()
            .start());
    channel = cleanupRule.register(
        InProcessChannelBuilder
            .forName(serverName)
            .directExecutor()
            .build());

    doReturn(SERVICE_AUTHORITY).when(helper).getAuthority();
    doReturn(syncContext).when(helper).getSynchronizationContext();
    doReturn(mock(ChannelLogger.class)).when(helper).getChannelLogger();
    doReturn(loadStatsStore).when(localityStore).getLoadStatsStore();

    lookasideChannelLb = new LookasideChannelLb(
        helper, lookasideChannelCallback, channel, loadReportClient, localityStore,
        Node.getDefaultInstance());
  }

  @Test
  public void firstAndSecondEdsResponseReceived() {
    verify(lookasideChannelCallback, never()).onWorking();
    verify(loadReportClient, never()).startLoadReporting(any(LoadReportCallback.class));

    // first EDS response
    serverResponseWriter.onNext(edsResponse);
    verify(lookasideChannelCallback).onWorking();
    ArgumentCaptor<LoadReportCallback> loadReportCallbackCaptor =
        ArgumentCaptor.forClass(LoadReportCallback.class);
    verify(loadReportClient).startLoadReporting(loadReportCallbackCaptor.capture());
    LoadReportCallback loadReportCallback = loadReportCallbackCaptor.getValue();

    // second EDS response
    serverResponseWriter.onNext(edsResponse);
    verify(lookasideChannelCallback, times(1)).onWorking();
    verify(loadReportClient, times(1)).startLoadReporting(any(LoadReportCallback.class));

    verify(localityStore, never()).updateOobMetricsReportInterval(anyLong());
    loadReportCallback.onReportResponse(1234);
    verify(localityStore).updateOobMetricsReportInterval(1234);

    verify(lookasideChannelCallback, never()).onError();

    lookasideChannelLb.shutdown();
  }

  @Test
  public void handleDropUpdates() {
    verify(localityStore, never()).updateDropPercentage(
        ArgumentMatchers.<ImmutableList<DropOverload>>any());

    serverResponseWriter.onNext(edsResponse);
    verify(localityStore).updateDropPercentage(eq(ImmutableList.<DropOverload>of()));

    ClusterLoadAssignment clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
        .setPolicy(Policy.newBuilder()
            .addDropOverloads(Policy.DropOverload.newBuilder()
                .setCategory("cat_1").setDropPercentage(FractionalPercent.newBuilder()
                    .setDenominator(DenominatorType.HUNDRED)
                    .setNumerator(3)
                    .build())
                .build())

            .addDropOverloads(Policy.DropOverload.newBuilder()
                .setCategory("cat_2").setDropPercentage(FractionalPercent.newBuilder()
                    .setDenominator(DenominatorType.TEN_THOUSAND)
                    .setNumerator(45)
                    .build())
                .build())
            .addDropOverloads(Policy.DropOverload.newBuilder()
                .setCategory("cat_3").setDropPercentage(FractionalPercent.newBuilder()
                    .setDenominator(DenominatorType.MILLION)
                    .setNumerator(6789)
                    .build())
                .build())
            .build())
        .build();
    serverResponseWriter.onNext(
        DiscoveryResponse.newBuilder()
            .addResources(Any.pack(clusterLoadAssignment))
            .setTypeUrl("type.googleapis.com/envoy.api.v2.ClusterLoadAssignment")
            .build());

    verify(lookasideChannelCallback, never()).onAllDrop();
    verify(localityStore).updateDropPercentage(ImmutableList.of(
        new DropOverload("cat_1", 300_00),
        new DropOverload("cat_2", 45_00),
        new DropOverload("cat_3", 6789)));


    clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
        .setPolicy(Policy.newBuilder()
            .addDropOverloads(Policy.DropOverload.newBuilder()
                .setCategory("cat_1").setDropPercentage(FractionalPercent.newBuilder()
                    .setDenominator(DenominatorType.HUNDRED)
                    .setNumerator(3)
                    .build())
                .build())
            .addDropOverloads(Policy.DropOverload.newBuilder()
                .setCategory("cat_2").setDropPercentage(FractionalPercent.newBuilder()
                    .setDenominator(DenominatorType.HUNDRED)
                    .setNumerator(101)
                    .build())
                .build())
            .addDropOverloads(Policy.DropOverload.newBuilder()
                .setCategory("cat_3").setDropPercentage(FractionalPercent.newBuilder()
                    .setDenominator(DenominatorType.HUNDRED)
                    .setNumerator(23)
                    .build())
                .build())
            .build())
        .build();
    serverResponseWriter.onNext(
        DiscoveryResponse.newBuilder()
            .addResources(Any.pack(clusterLoadAssignment))
            .setTypeUrl("type.googleapis.com/envoy.api.v2.ClusterLoadAssignment")
            .build());

    verify(lookasideChannelCallback).onAllDrop();
    verify(localityStore).updateDropPercentage(ImmutableList.of(
        new DropOverload("cat_1", 300_00),
        new DropOverload("cat_2", 100_00_00)));

    verify(lookasideChannelCallback, never()).onError();

    lookasideChannelLb.shutdown();
  }

  @Test
  public void handleLocalityAssignmentUpdates() {
    io.envoyproxy.envoy.api.v2.core.Locality localityProto1 =
        io.envoyproxy.envoy.api.v2.core.Locality
            .newBuilder()
            .setRegion("region1")
            .setZone("zone1")
            .setSubZone("subzone1")
            .build();
    LbEndpoint endpoint11 = LbEndpoint.newBuilder()
        .setEndpoint(Endpoint.newBuilder()
            .setAddress(Address.newBuilder()
                .setSocketAddress(SocketAddress.newBuilder()
                    .setAddress("addr11").setPortValue(11))))
        .setLoadBalancingWeight(UInt32Value.of(11))
        .build();
    LbEndpoint endpoint12 = LbEndpoint.newBuilder()
        .setEndpoint(Endpoint.newBuilder()
            .setAddress(Address.newBuilder()
                .setSocketAddress(SocketAddress.newBuilder()
                    .setAddress("addr12").setPortValue(12))))
        .setLoadBalancingWeight(UInt32Value.of(12))
        .build();
    io.envoyproxy.envoy.api.v2.core.Locality localityProto2 =
        io.envoyproxy.envoy.api.v2.core.Locality
            .newBuilder()
            .setRegion("region2")
            .setZone("zone2")
            .setSubZone("subzone2")
            .build();
    LbEndpoint endpoint21 = LbEndpoint.newBuilder()
        .setEndpoint(Endpoint.newBuilder()
            .setAddress(Address.newBuilder()
                .setSocketAddress(SocketAddress.newBuilder()
                    .setAddress("addr21").setPortValue(21))))
        .setLoadBalancingWeight(UInt32Value.of(21))
        .build();
    LbEndpoint endpoint22 = LbEndpoint.newBuilder()
        .setEndpoint(Endpoint.newBuilder()
            .setAddress(Address.newBuilder()
                .setSocketAddress(SocketAddress.newBuilder()
                    .setAddress("addr22").setPortValue(22))))
        .setLoadBalancingWeight(UInt32Value.of(22))
        .build();
    io.envoyproxy.envoy.api.v2.core.Locality localityProto3 =
        io.envoyproxy.envoy.api.v2.core.Locality
            .newBuilder()
            .setRegion("region3")
            .setZone("zone3")
            .setSubZone("subzone3")
            .build();
    LbEndpoint endpoint3 = LbEndpoint.newBuilder()
        .setEndpoint(Endpoint.newBuilder()
            .setAddress(Address.newBuilder()
                .setSocketAddress(SocketAddress.newBuilder()
                    .setAddress("addr31").setPortValue(31))))
        .setLoadBalancingWeight(UInt32Value.of(31))
        .build();
    ClusterLoadAssignment clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
            .addEndpoints(io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints.newBuilder()
                .setLocality(localityProto1)
                .addLbEndpoints(endpoint11)
                .addLbEndpoints(endpoint12)
                .setLoadBalancingWeight(UInt32Value.of(1)))
            .addEndpoints(io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints.newBuilder()
                .setLocality(localityProto2)
                .addLbEndpoints(endpoint21)
                .addLbEndpoints(endpoint22)
                .setLoadBalancingWeight(UInt32Value.of(2)))
            .addEndpoints(io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints.newBuilder()
                .setLocality(localityProto3)
                .addLbEndpoints(endpoint3)
                .setLoadBalancingWeight(UInt32Value.of(0)))
            .build();
    serverResponseWriter.onNext(
        DiscoveryResponse.newBuilder()
            .addResources(Any.pack(clusterLoadAssignment))
            .setTypeUrl("type.googleapis.com/envoy.api.v2.ClusterLoadAssignment")
            .build());

    Locality locality1 = Locality.fromEnvoyProtoLocality(localityProto1);
    LocalityLbEndpoints localityInfo1 = new LocalityLbEndpoints(
        ImmutableList.of(
            EnvoyProtoData.LbEndpoint.fromEnvoyProtoLbEndpoint(endpoint11),
            EnvoyProtoData.LbEndpoint.fromEnvoyProtoLbEndpoint(endpoint12)),
        1, 0);
    LocalityLbEndpoints localityInfo2 = new LocalityLbEndpoints(
        ImmutableList.of(
            EnvoyProtoData.LbEndpoint.fromEnvoyProtoLbEndpoint(endpoint21),
            EnvoyProtoData.LbEndpoint.fromEnvoyProtoLbEndpoint(endpoint22)),
        2, 0);
    Locality locality2 = Locality.fromEnvoyProtoLocality(localityProto2);

    InOrder inOrder = inOrder(localityStore);
    inOrder.verify(localityStore).updateDropPercentage(ImmutableList.<DropOverload>of());
    inOrder.verify(localityStore).updateLocalityStore(localityEndpointsMappingCaptor.capture());
    assertThat(localityEndpointsMappingCaptor.getValue()).containsExactly(
        locality1, localityInfo1, locality2, localityInfo2).inOrder();

    verify(lookasideChannelCallback, never()).onError();

    lookasideChannelLb.shutdown();
  }

  @Test
  public void verifyRpcErrorPropagation() {
    verify(lookasideChannelCallback, never()).onError();
    serverResponseWriter.onError(new RuntimeException());
    verify(lookasideChannelCallback).onError();
  }

  @Test
  public void shutdown() {
    verify(loadReportClient, never()).stopLoadReporting();
    assertThat(channel.isShutdown()).isFalse();

    lookasideChannelLb.shutdown();

    verify(loadReportClient).stopLoadReporting();
    assertThat(channel.isShutdown()).isTrue();
  }

  /**
   * Tests load reporting is initiated after receiving the first valid EDS response from the traffic
   * director, then its operation is independent of load balancing until xDS load balancer is
   * shutdown.
   */
  @Test
  public void reportLoadAfterReceivingFirstEdsResponseUntilShutdown() {
    // Simulates a syntactically incorrect EDS response.
    serverResponseWriter.onNext(DiscoveryResponse.getDefaultInstance());
    verify(loadReportClient, never()).startLoadReporting(any(LoadReportCallback.class));
    verify(lookasideChannelCallback, never()).onWorking();
    verify(lookasideChannelCallback, never()).onError();

    // Simulate a syntactically correct EDS response.
    DiscoveryResponse edsResponse =
        DiscoveryResponse.newBuilder()
            .addResources(Any.pack(ClusterLoadAssignment.getDefaultInstance()))
            .setTypeUrl("type.googleapis.com/envoy.api.v2.ClusterLoadAssignment")
            .build();
    serverResponseWriter.onNext(edsResponse);

    verify(lookasideChannelCallback).onWorking();

    ArgumentCaptor<LoadReportCallback> lrsCallbackCaptor = ArgumentCaptor.forClass(null);
    verify(loadReportClient).startLoadReporting(lrsCallbackCaptor.capture());
    lrsCallbackCaptor.getValue().onReportResponse(19543);
    verify(localityStore).updateOobMetricsReportInterval(19543);

    // Simulate another EDS response from the same remote balancer.
    serverResponseWriter.onNext(edsResponse);
    verifyNoMoreInteractions(lookasideChannelCallback, loadReportClient);

    // Simulate an EDS error response.
    serverResponseWriter.onError(Status.ABORTED.asException());
    verify(lookasideChannelCallback).onError();

    verifyNoMoreInteractions(lookasideChannelCallback, loadReportClient);
    verify(localityStore, times(1)).updateOobMetricsReportInterval(anyLong()); // only once

    lookasideChannelLb.shutdown();
  }
}
