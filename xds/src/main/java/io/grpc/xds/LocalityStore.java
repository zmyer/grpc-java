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

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.xds.ClientLoadCounter.LoadRecordingSubchannelPicker;
import io.grpc.xds.ClientLoadCounter.MetricsObservingSubchannelPicker;
import io.grpc.xds.ClientLoadCounter.MetricsRecordingListener;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.LbEndpoint;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.InterLocalityPicker.WeightedChildPicker;
import io.grpc.xds.OrcaOobUtil.OrcaReportingConfig;
import io.grpc.xds.OrcaOobUtil.OrcaReportingHelperWrapper;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Manages EAG and locality info for a collection of subchannels, not including subchannels
 * created by the fallback balancer.
 */
// Must be accessed/run in SynchronizedContext.
interface LocalityStore {

  void reset();

  void updateLocalityStore(ImmutableMap<Locality, LocalityLbEndpoints> localityInfoMap);

  void updateDropPercentage(ImmutableList<DropOverload> dropOverloads);

  void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState);

  void updateOobMetricsReportInterval(long reportIntervalNano);

  LoadStatsStore getLoadStatsStore();

  final class LocalityStoreImpl implements LocalityStore {
    private static final String ROUND_ROBIN = "round_robin";
    private static final long DELAYED_DELETION_TIMEOUT_MINUTES = 15L;

    private final Helper helper;
    private final PickerFactory pickerFactory;
    private final LoadBalancerProvider loadBalancerProvider;
    private final ThreadSafeRandom random;
    private final LoadStatsStore loadStatsStore;
    private final OrcaPerRequestUtil orcaPerRequestUtil;
    private final OrcaOobUtil orcaOobUtil;
    private final PriorityManager priorityManager = new PriorityManager();
    private final Map<Locality, LocalityLbInfo> localityMap = new HashMap<>();
    // Most current set of localities instructed by traffic director
    private Set<Locality> localities = ImmutableSet.of();
    private ImmutableList<DropOverload> dropOverloads = ImmutableList.of();
    private long metricsReportIntervalNano = -1;

    LocalityStoreImpl(Helper helper, LoadBalancerRegistry lbRegistry) {
      this(helper, pickerFactoryImpl, lbRegistry, ThreadSafeRandom.ThreadSafeRandomImpl.instance,
          new LoadStatsStoreImpl(), OrcaPerRequestUtil.getInstance(), OrcaOobUtil.getInstance());
    }

    @VisibleForTesting
    LocalityStoreImpl(
        Helper helper,
        PickerFactory pickerFactory,
        LoadBalancerRegistry lbRegistry,
        ThreadSafeRandom random,
        LoadStatsStore loadStatsStore,
        OrcaPerRequestUtil orcaPerRequestUtil,
        OrcaOobUtil orcaOobUtil) {
      this.helper = checkNotNull(helper, "helper");
      this.pickerFactory = checkNotNull(pickerFactory, "pickerFactory");
      loadBalancerProvider = checkNotNull(
          lbRegistry.getProvider(ROUND_ROBIN),
          "Unable to find '%s' LoadBalancer", ROUND_ROBIN);
      this.random = checkNotNull(random, "random");
      this.loadStatsStore = checkNotNull(loadStatsStore, "loadStatsStore");
      this.orcaPerRequestUtil = checkNotNull(orcaPerRequestUtil, "orcaPerRequestUtil");
      this.orcaOobUtil = checkNotNull(orcaOobUtil, "orcaOobUtil");
    }

    @VisibleForTesting // Introduced for testing only.
    interface PickerFactory {
      SubchannelPicker picker(List<WeightedChildPicker> childPickers);
    }

    private static final class DroppablePicker extends SubchannelPicker {

      final ImmutableList<DropOverload> dropOverloads;
      final SubchannelPicker delegate;
      final ThreadSafeRandom random;
      final LoadStatsStore loadStatsStore;

      DroppablePicker(
          ImmutableList<DropOverload> dropOverloads, SubchannelPicker delegate,
          ThreadSafeRandom random, LoadStatsStore loadStatsStore) {
        this.dropOverloads = dropOverloads;
        this.delegate = delegate;
        this.random = random;
        this.loadStatsStore = loadStatsStore;
      }

      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        for (DropOverload dropOverload : dropOverloads) {
          int rand = random.nextInt(1000_000);
          if (rand < dropOverload.getDropsPerMillion()) {
            loadStatsStore.recordDroppedRequest(dropOverload.getCategory());
            return PickResult.withDrop(Status.UNAVAILABLE.withDescription(
                "dropped by loadbalancer: " + dropOverload.toString()));
          }
        }
        return delegate.pickSubchannel(args);
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("dropOverloads", dropOverloads)
            .add("delegate", delegate)
            .toString();
      }
    }

    private static final PickerFactory pickerFactoryImpl =
        new PickerFactory() {
          @Override
          public SubchannelPicker picker(List<WeightedChildPicker> childPickers) {
            return new InterLocalityPicker(childPickers);
          }
        };

    // This is triggered by xdsLoadbalancer.handleSubchannelState
    @Override
    public void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
      // delegate to the childBalancer who manages this subchannel
      for (LocalityLbInfo localityLbInfo : localityMap.values()) {
        // This will probably trigger childHelper.updateBalancingState
        localityLbInfo.childBalancer.handleSubchannelState(subchannel, newState);
      }
    }

    @Override
    public void reset() {
      for (Locality locality : localityMap.keySet()) {
        localityMap.get(locality).shutdown();
      }
      localityMap.clear();

      for (Locality locality : localities) {
        loadStatsStore.removeLocality(locality);
      }
      localities = ImmutableSet.of();

      priorityManager.reset();
    }

    // This is triggered by EDS response.
    @Override
    public void updateLocalityStore(
        final ImmutableMap<Locality, LocalityLbEndpoints> localityInfoMap) {

      Set<Locality> newLocalities = localityInfoMap.keySet();
      // TODO: put endPointWeights into attributes for WRR.
      for (Locality locality : newLocalities) {
        if (localityMap.containsKey(locality)) {
          final LocalityLbInfo localityLbInfo = localityMap.get(locality);
          LocalityLbEndpoints localityInfo = localityInfoMap.get(locality);
          final List<EquivalentAddressGroup> eags = new ArrayList<>();
          for (LbEndpoint endpoint: localityInfo.getEndpoints()) {
            eags.add(endpoint.getAddress());
          }
          // In extreme case handleResolvedAddresses() may trigger updateBalancingState()
          // immediately, so execute handleResolvedAddresses() after all the setup in this method is
          // complete.
          helper.getSynchronizationContext().execute(new Runnable() {
            @Override
            public void run() {
              localityLbInfo.childBalancer.handleResolvedAddresses(
                  ResolvedAddresses.newBuilder().setAddresses(eags).build());
            }
          });
        }
      }

      for (Locality newLocality : newLocalities) {
        if (!localities.contains(newLocality)) {
          loadStatsStore.addLocality(newLocality);
        }
      }
      final Set<Locality> toBeRemovedFromStatsStore = new HashSet<>();
      // There is a race between picking a subchannel and updating localities, which leads to
      // the possibility that RPCs will be sent to a removed locality. As a result, those RPC
      // loads will not be recorded. We consider this to be natural. By removing locality counters
      // after updating subchannel pickers, we eliminate the race and conservatively record loads
      // happening in that period.
      for (Locality oldLocality : localities) {
        if (!localityInfoMap.containsKey(oldLocality)) {
          toBeRemovedFromStatsStore.add(oldLocality);
        }
      }
      helper.getSynchronizationContext().execute(new Runnable() {
        @Override
        public void run() {
          for (Locality locality : toBeRemovedFromStatsStore) {
            loadStatsStore.removeLocality(locality);
          }
        }
      });
      localities = newLocalities;

      priorityManager.updateLocalities(localityInfoMap);

      for (Locality oldLocality : localityMap.keySet()) {
        if (!newLocalities.contains(oldLocality)) {
          deactivate(oldLocality);
        }
      }
    }

    @Override
    public void updateDropPercentage(ImmutableList<DropOverload> dropOverloads) {
      this.dropOverloads = checkNotNull(dropOverloads, "dropOverloads");
    }

    private void deactivate(final Locality locality) {
      if (!localityMap.containsKey(locality) || localityMap.get(locality).isDeactivated()) {
        return;
      }

      final LocalityLbInfo localityLbInfo = localityMap.get(locality);
      class DeletionTask implements Runnable {

        @Override
        public void run() {
          localityLbInfo.shutdown();
          localityMap.remove(locality);
        }

        @Override
        public String toString() {
          return "DeletionTask: locality=" + locality;
        }
      }

      localityLbInfo.delayedDeletionTimer = helper.getSynchronizationContext().schedule(
          new DeletionTask(), DELAYED_DELETION_TIMEOUT_MINUTES,
          TimeUnit.MINUTES, helper.getScheduledExecutorService());
    }

    @Override
    public LoadStatsStore getLoadStatsStore() {
      return loadStatsStore;
    }

    @Override
    public void updateOobMetricsReportInterval(long reportIntervalNano) {
      metricsReportIntervalNano = reportIntervalNano;
      for (LocalityLbInfo lbInfo : localityMap.values()) {
        lbInfo.childHelper.updateMetricsReportInterval(reportIntervalNano);
      }
    }

    @Nullable
    private static ConnectivityState aggregateState(
        @Nullable ConnectivityState overallState, ConnectivityState childState) {
      if (overallState == null) {
        return childState;
      }
      if (overallState == READY || childState == READY) {
        return READY;
      }
      if (overallState == CONNECTING || childState == CONNECTING) {
        return CONNECTING;
      }
      if (overallState == IDLE || childState == IDLE) {
        return IDLE;
      }
      return overallState;
    }

    private void updatePicker(
        @Nullable ConnectivityState state,  List<WeightedChildPicker> childPickers) {
      childPickers = Collections.unmodifiableList(childPickers);
      SubchannelPicker picker;
      if (childPickers.isEmpty()) {
        if (state == TRANSIENT_FAILURE) {
          picker = new ErrorPicker(Status.UNAVAILABLE); // TODO: more details in status
        } else {
          picker = XdsSubchannelPickers.BUFFER_PICKER;
        }
      } else {
        picker = pickerFactory.picker(childPickers);
      }

      if (!dropOverloads.isEmpty()) {
        picker = new DroppablePicker(dropOverloads, picker, random, loadStatsStore);
      }

      if (state != null) {
        helper.updateBalancingState(state, picker);
      }
    }

    /**
     * State of a single Locality.
     */
    // TODO(zdapeng): rename it to LocalityLbState
    static final class LocalityLbInfo {

      final LoadBalancer childBalancer;
      final ChildHelper childHelper;

      @Nullable
      private ScheduledHandle delayedDeletionTimer;

      LocalityLbInfo(
          LoadBalancer childBalancer, ChildHelper childHelper) {
        this.childBalancer = checkNotNull(childBalancer, "childBalancer");
        this.childHelper = checkNotNull(childHelper, "childHelper");
      }

      void shutdown() {
        if (delayedDeletionTimer != null) {
          delayedDeletionTimer.cancel();
          delayedDeletionTimer = null;
        }
        childBalancer.shutdown();
      }

      void reactivate() {
        if (delayedDeletionTimer != null) {
          delayedDeletionTimer.cancel();
          delayedDeletionTimer = null;
        }
      }

      boolean isDeactivated() {
        return delayedDeletionTimer != null;
      }
    }

    class ChildHelper extends ForwardingLoadBalancerHelper {

      private final OrcaReportingHelperWrapper orcaReportingHelperWrapper;

      private SubchannelPicker currentChildPicker = XdsSubchannelPickers.BUFFER_PICKER;
      private ConnectivityState currentChildState = CONNECTING;

      ChildHelper(final Locality locality, final ClientLoadCounter counter,
          OrcaOobUtil orcaOobUtil) {
        checkNotNull(locality, "locality");
        checkNotNull(counter, "counter");
        checkNotNull(orcaOobUtil, "orcaOobUtil");
        Helper delegate = new ForwardingLoadBalancerHelper() {
          @Override
          protected Helper delegate() {
            return helper;
          }

          @Override
          public void updateBalancingState(ConnectivityState newState,
              final SubchannelPicker newPicker) {
            checkNotNull(newState, "newState");
            checkNotNull(newPicker, "newPicker");

            currentChildState = newState;
            currentChildPicker =
                new LoadRecordingSubchannelPicker(counter,
                    new MetricsObservingSubchannelPicker(new MetricsRecordingListener(counter),
                        newPicker, orcaPerRequestUtil));

            // delegate to parent helper
            priorityManager.updatePriorityState(priorityManager.getPriority(locality));
          }

          @Override
          public String toString() {
            return MoreObjects.toStringHelper(this).add("locality", locality).toString();
          }

          @Override
          public String getAuthority() {
            //FIXME: This should be a new proposed field of Locality, locality_name
            return locality.getSubzone();
          }
        };
        orcaReportingHelperWrapper =
            checkNotNull(orcaOobUtil, "orcaOobUtil")
                .newOrcaReportingHelperWrapper(delegate, new MetricsRecordingListener(counter));

        if (metricsReportIntervalNano > 0) {
          updateMetricsReportInterval(metricsReportIntervalNano);
        }
      }

      void updateMetricsReportInterval(long intervalNanos) {
        orcaReportingHelperWrapper
            .setReportingConfig(OrcaReportingConfig.newBuilder()
                .setReportInterval(intervalNanos, TimeUnit.NANOSECONDS).build());
      }

      @Override
      protected Helper delegate() {
        return orcaReportingHelperWrapper.asHelper();
      }
    }

    private final class PriorityManager {

      private final List<List<Locality>> priorityTable = new ArrayList<>();
      private Map<Locality, LocalityLbEndpoints> localityInfoMap = ImmutableMap.of();
      private int currentPriority = -1;
      private ScheduledHandle failOverTimer;

      /**
       * Updates the priority ordering of localities with the given collection of localities.
       * Recomputes the current ready localities to be used.
       */
      void updateLocalities(Map<Locality, LocalityLbEndpoints> localityInfoMap) {
        this.localityInfoMap = localityInfoMap;
        priorityTable.clear();
        for (Locality newLocality : localityInfoMap.keySet()) {
          int priority = localityInfoMap.get(newLocality).getPriority();
          while (priorityTable.size() <= priority) {
            priorityTable.add(new ArrayList<Locality>());
          }
          priorityTable.get(priority).add(newLocality);
        }

        currentPriority = -1;
        failOver();
      }

      /**
       * Refreshes the group of localities with the given priority. Recomputes the current ready
       * localities to be used.
       */
      void updatePriorityState(int priority) {
        if (priority == -1 || priority > currentPriority) {
          return;
        }
        List<WeightedChildPicker> childPickers = new ArrayList<>();

        ConnectivityState overallState = null;
        for (Locality l : priorityTable.get(priority)) {
          if (!localityMap.containsKey(l)) {
            initLocality(l);
          }
          LocalityLbInfo localityLbInfo = localityMap.get(l);
          localityLbInfo.reactivate();
          ConnectivityState childState = localityLbInfo.childHelper.currentChildState;
          SubchannelPicker childPicker = localityLbInfo.childHelper.currentChildPicker;

          overallState = aggregateState(overallState, childState);

          if (READY == childState) {
            childPickers.add(
                new WeightedChildPicker(localityInfoMap.get(l).getLocalityWeight(), childPicker));
          }
        }

        if (priority == currentPriority) {
          updatePicker(overallState, childPickers);
          if (overallState == READY) {
            cancelFailOverTimer();
          } else if (overallState == TRANSIENT_FAILURE) {
            cancelFailOverTimer();
            failOver();
          } else if (failOverTimer == null) {
            failOver();
          } // else, still connecting and failOverTimer not expired yet, noop
        } else if (overallState == READY) {
          updatePicker(overallState, childPickers);
          cancelFailOverTimer();
          currentPriority = priority;
        }

        if (overallState == READY) {
          for (int p = priority + 1; p < priorityTable.size(); p++) {
            for (Locality xdsLocality : priorityTable.get(p)) {
              deactivate(xdsLocality);
            }
          }
        }
      }

      int getPriority(Locality locality) {
        if (localityInfoMap.containsKey(locality)) {
          return localityInfoMap.get(locality).getPriority();
        }
        return -1;
      }

      void reset() {
        cancelFailOverTimer();
        priorityTable.clear();
        localityInfoMap = ImmutableMap.of();
        currentPriority = -1;
      }

      private void cancelFailOverTimer() {
        if (failOverTimer != null) {
          failOverTimer.cancel();
          failOverTimer = null;
        }
      }

      private void failOver() {
        if (currentPriority == priorityTable.size() - 1) {
          return;
        }

        currentPriority++;

        List<Locality> localities = priorityTable.get(currentPriority);
        boolean initializedBefore = false;
        for (Locality locality : localities) {
          if (localityMap.containsKey(locality)) {
            initializedBefore = true;
            localityMap.get(locality).reactivate();
          } else {
            initLocality(locality);
          }
        }

        if (!initializedBefore) {
          class FailOverTask implements Runnable {
            @Override
            public void run() {
              failOverTimer = null;
              failOver();
            }
          }

          failOverTimer = helper.getSynchronizationContext().schedule(
              new FailOverTask(), 10, TimeUnit.SECONDS, helper.getScheduledExecutorService());
        }

        updatePriorityState(currentPriority);
      }

      private void initLocality(final Locality locality) {
        ChildHelper childHelper =
            new ChildHelper(locality, loadStatsStore.getLocalityCounter(locality),
                orcaOobUtil);
        final LocalityLbInfo localityLbInfo =
            new LocalityLbInfo(
                loadBalancerProvider.newLoadBalancer(childHelper),
                childHelper);
        localityMap.put(locality, localityLbInfo);

        LocalityLbEndpoints localityInfo = localityInfoMap.get(locality);
        final List<EquivalentAddressGroup> eags = new ArrayList<>();
        for (LbEndpoint endpoint: localityInfo.getEndpoints()) {
          eags.add(endpoint.getAddress());
        }
        // In extreme case handleResolvedAddresses() may trigger updateBalancingState() immediately,
        // so execute handleResolvedAddresses() after all the setup in the caller is complete.
        helper.getSynchronizationContext().execute(new Runnable() {
          @Override
          public void run() {
            // TODO: put endPointWeights into attributes for WRR.
            localityLbInfo.childBalancer
                .handleResolvedAddresses(ResolvedAddresses.newBuilder()
                    .setAddresses(eags).build());
          }
        });
      }
    }
  }
}
