package io.scalecube.cluster.membership;

import static io.scalecube.cluster.ClusterConfig.DEFAULT_SUSPICION_MULT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.cluster.BaseTest;
import io.scalecube.cluster.ClusterConfig;
import io.scalecube.cluster.ClusterMath;
import io.scalecube.cluster.CorrelationIdGenerator;
import io.scalecube.cluster.Member;
import io.scalecube.cluster.fdetector.FailureDetectorImpl;
import io.scalecube.cluster.gossip.GossipProtocolImpl;
import io.scalecube.cluster.metadata.MetadataStoreImpl;
import io.scalecube.transport.Address;
import io.scalecube.transport.Transport;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import reactor.core.Exceptions;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class MembershipProtocolTest extends BaseTest {

  public static final Duration TIMEOUT = Duration.ofSeconds(10);

  public static final int TEST_SYNC_INTERVAL = 500;
  public static final int PING_INTERVAL = 200;

  private Scheduler scheduler;

  @BeforeEach
  void setUp(TestInfo testInfo) {
    scheduler = Schedulers.newSingle(testInfo.getDisplayName().replaceAll(" ", "_"), true);
  }

  @AfterEach
  void tearDown() {
    if (scheduler != null) {
      scheduler.dispose();
    }
  }

  @Test
  public void testInitialPhaseOk() {
    Transport a = Transport.bindAwait(true);
    Transport b = Transport.bindAwait(true);
    Transport c = Transport.bindAwait(true);
    List<Address> members = Arrays.asList(a.address(), b.address(), c.address());

    MembershipProtocolImpl cmA = createMembership(a, members);
    MembershipProtocolImpl cmB = createMembership(b, members);
    MembershipProtocolImpl cmC = createMembership(c, members);

    try {
      awaitSeconds(1);

      assertTrusted(cmA, a.address(), b.address(), c.address());
      assertNoSuspected(cmA);
      assertTrusted(cmB, a.address(), b.address(), c.address());
      assertNoSuspected(cmB);
      assertTrusted(cmC, a.address(), b.address(), c.address());
      assertNoSuspected(cmC);
    } finally {
      stopAll(cmA, cmB, cmC);
    }
  }

  @Test
  public void testNetworkPartitionDueNoOutboundThenRecover() {
    Transport a = Transport.bindAwait(true);
    Transport b = Transport.bindAwait(true);
    Transport c = Transport.bindAwait(true);
    List<Address> members = Arrays.asList(a.address(), b.address(), c.address());

    MembershipProtocolImpl cmA = createMembership(a, members);
    MembershipProtocolImpl cmB = createMembership(b, members);
    MembershipProtocolImpl cmC = createMembership(c, members);

    awaitSeconds(3);

    // Block traffic
    a.networkEmulator().blockOutbound(members);
    b.networkEmulator().blockOutbound(members);
    c.networkEmulator().blockOutbound(members);

    try {

      awaitSuspicion(3);

      assertTrusted(cmA, a.address());
      assertNoSuspected(cmA);
      assertTrusted(cmB, b.address());
      assertNoSuspected(cmB);
      assertTrusted(cmC, c.address());
      assertNoSuspected(cmC);

      a.networkEmulator().unblockAllOutbound();
      b.networkEmulator().unblockAllOutbound();
      c.networkEmulator().unblockAllOutbound();

      awaitSeconds(TEST_SYNC_INTERVAL * 2 / 1000);

      assertTrusted(cmA, a.address(), b.address(), c.address());
      assertNoSuspected(cmA);
      assertTrusted(cmB, a.address(), b.address(), c.address());
      assertNoSuspected(cmB);
      assertTrusted(cmC, a.address(), b.address(), c.address());
      assertNoSuspected(cmC);
    } finally {
      stopAll(cmA, cmB, cmC);
    }
  }

  @Test
  public void testMemberLostNetworkDueNoOutboundThenRecover() {
    Transport a = Transport.bindAwait(true);
    Transport b = Transport.bindAwait(true);
    Transport c = Transport.bindAwait(true);
    List<Address> members = Arrays.asList(a.address(), b.address(), c.address());

    MembershipProtocolImpl cmA = createMembership(a, members);
    MembershipProtocolImpl cmB = createMembership(b, members);
    MembershipProtocolImpl cmC = createMembership(c, members);

    try {
      awaitSeconds(1);

      // Check all trusted
      assertTrusted(cmA, a.address(), b.address(), c.address());
      assertNoSuspected(cmA);
      assertTrusted(cmB, a.address(), b.address(), c.address());
      assertNoSuspected(cmB);
      assertTrusted(cmC, a.address(), b.address(), c.address());
      assertNoSuspected(cmC);

      // Node b lost network
      b.networkEmulator().blockOutbound(Arrays.asList(a.address(), c.address()));
      a.networkEmulator().blockOutbound(b.address());
      c.networkEmulator().blockOutbound(b.address());

      awaitSeconds(1);

      // Check partition: {b}, {a, c}
      assertTrusted(cmA, a.address(), c.address());
      assertSuspected(cmA, b.address());
      assertTrusted(cmB, b.address());
      assertSuspected(cmB, a.address(), c.address());
      assertTrusted(cmC, a.address(), c.address());
      assertSuspected(cmC, b.address());

      // Node b recover network
      a.networkEmulator().unblockAllOutbound();
      b.networkEmulator().unblockAllOutbound();
      c.networkEmulator().unblockAllOutbound();

      awaitSeconds(1);

      // Check all trusted again
      assertTrusted(cmA, a.address(), b.address(), c.address());
      assertNoSuspected(cmA);
      assertTrusted(cmB, a.address(), b.address(), c.address());
      assertNoSuspected(cmB);
      assertTrusted(cmC, a.address(), b.address(), c.address());
      assertNoSuspected(cmC);
    } finally {
      stopAll(cmA, cmB, cmC);
    }
  }

  @Test
  public void testNetworkPartitionTwiceDueNoOutboundThenRecover() {
    Transport a = Transport.bindAwait(true);
    Transport b = Transport.bindAwait(true);
    Transport c = Transport.bindAwait(true);
    List<Address> members = Arrays.asList(a.address(), b.address(), c.address());

    MembershipProtocolImpl cmA = createMembership(a, members);
    MembershipProtocolImpl cmB = createMembership(b, members);
    MembershipProtocolImpl cmC = createMembership(c, members);

    try {
      awaitSeconds(1);

      // Check all trusted
      assertTrusted(cmA, a.address(), b.address(), c.address());
      assertNoSuspected(cmA);
      assertTrusted(cmB, a.address(), b.address(), c.address());
      assertNoSuspected(cmB);
      assertTrusted(cmC, a.address(), b.address(), c.address());
      assertNoSuspected(cmC);

      // Node b lost network
      b.networkEmulator().blockOutbound(Arrays.asList(a.address(), c.address()));
      a.networkEmulator().blockOutbound(b.address());
      c.networkEmulator().blockOutbound(b.address());

      awaitSeconds(1);

      // Check partition: {b}, {a, c}
      assertTrusted(cmA, a.address(), c.address());
      assertSuspected(cmA, b.address());
      assertTrusted(cmB, b.address());
      assertSuspected(cmB, a.address(), c.address());
      assertTrusted(cmC, a.address(), c.address());
      assertSuspected(cmC, b.address());

      // Node a and c lost network
      a.networkEmulator().blockOutbound(c.address());
      c.networkEmulator().blockOutbound(a.address());

      awaitSeconds(1);

      // Check partition: {a}, {b}, {c}
      assertTrusted(cmA, a.address());
      assertSuspected(cmA, b.address(), c.address());
      assertTrusted(cmB, b.address());
      assertSuspected(cmB, a.address(), c.address());
      assertTrusted(cmC, c.address());
      assertSuspected(cmC, b.address(), a.address());

      // Recover network
      a.networkEmulator().unblockAllOutbound();
      b.networkEmulator().unblockAllOutbound();
      c.networkEmulator().unblockAllOutbound();

      awaitSeconds(1);

      // Check all trusted again
      assertTrusted(cmA, a.address(), b.address(), c.address());
      assertNoSuspected(cmA);
      assertTrusted(cmB, a.address(), b.address(), c.address());
      assertNoSuspected(cmB);
      assertTrusted(cmC, a.address(), b.address(), c.address());
      assertNoSuspected(cmC);
    } finally {
      stopAll(cmA, cmB, cmC);
    }
  }

  @Test
  public void testNetworkLostOnAllNodesDueNoOutboundThenRecover() {
    Transport a = Transport.bindAwait(true);
    Transport b = Transport.bindAwait(true);
    Transport c = Transport.bindAwait(true);
    List<Address> members = Arrays.asList(a.address(), b.address(), c.address());

    MembershipProtocolImpl cmA = createMembership(a, members);
    MembershipProtocolImpl cmB = createMembership(b, members);
    MembershipProtocolImpl cmC = createMembership(c, members);

    try {
      awaitSeconds(1);

      assertTrusted(cmA, a.address(), b.address(), c.address());
      assertNoSuspected(cmA);
      assertTrusted(cmB, a.address(), b.address(), c.address());
      assertNoSuspected(cmB);
      assertTrusted(cmC, a.address(), b.address(), c.address());
      assertNoSuspected(cmC);

      a.networkEmulator().blockOutbound(members);
      b.networkEmulator().blockOutbound(members);
      c.networkEmulator().blockOutbound(members);

      awaitSeconds(1);

      assertTrusted(cmA, a.address());
      assertSuspected(cmA, b.address(), c.address());

      assertTrusted(cmB, b.address());
      assertSuspected(cmB, a.address(), c.address());

      assertTrusted(cmC, c.address());
      assertSuspected(cmC, a.address(), b.address());

      a.networkEmulator().unblockAllOutbound();
      b.networkEmulator().unblockAllOutbound();
      c.networkEmulator().unblockAllOutbound();

      awaitSeconds(1);

      assertTrusted(cmA, a.address(), b.address(), c.address());
      assertNoSuspected(cmA);

      assertTrusted(cmB, a.address(), b.address(), c.address());
      assertNoSuspected(cmB);

      assertTrusted(cmC, a.address(), b.address(), c.address());
      assertNoSuspected(cmC);
    } finally {
      stopAll(cmA, cmB, cmC);
    }
  }

  @Test
  public void testLongNetworkPartitionDueNoOutboundThenRemoved() {
    Transport a = Transport.bindAwait(true);
    Transport b = Transport.bindAwait(true);
    Transport c = Transport.bindAwait(true);
    Transport d = Transport.bindAwait(true);
    List<Address> members = Arrays.asList(a.address(), b.address(), c.address(), d.address());

    MembershipProtocolImpl cmA = createMembership(a, members);
    MembershipProtocolImpl cmB = createMembership(b, members);
    MembershipProtocolImpl cmC = createMembership(c, members);
    MembershipProtocolImpl cmD = createMembership(d, members);

    try {
      awaitSeconds(1);

      assertTrusted(cmA, a.address(), b.address(), c.address(), d.address());
      assertTrusted(cmB, a.address(), b.address(), c.address(), d.address());
      assertTrusted(cmC, a.address(), b.address(), c.address(), d.address());
      assertTrusted(cmD, a.address(), b.address(), c.address(), d.address());

      a.networkEmulator().blockOutbound(Arrays.asList(c.address(), d.address()));
      b.networkEmulator().blockOutbound(Arrays.asList(c.address(), d.address()));

      c.networkEmulator().blockOutbound(Arrays.asList(a.address(), b.address()));
      d.networkEmulator().blockOutbound(Arrays.asList(a.address(), b.address()));

      awaitSeconds(2);

      assertTrusted(cmA, a.address(), b.address());
      assertSuspected(cmA, c.address(), d.address());
      assertTrusted(cmB, a.address(), b.address());
      assertSuspected(cmB, c.address(), d.address());
      assertTrusted(cmC, c.address(), d.address());
      assertSuspected(cmC, a.address(), b.address());
      assertTrusted(cmD, c.address(), d.address());
      assertSuspected(cmD, a.address(), b.address());

      awaitSuspicion(4);

      assertTrusted(cmA, a.address(), b.address());
      assertNoSuspected(cmA);
      assertTrusted(cmB, a.address(), b.address());
      assertNoSuspected(cmB);
      assertTrusted(cmC, c.address(), d.address());
      assertNoSuspected(cmC);
      assertTrusted(cmD, c.address(), d.address());
      assertNoSuspected(cmD);
    } finally {
      stopAll(cmA, cmB, cmC, cmD);
    }
  }

  @Test
  public void testRestartFailedMembers() {
    Transport a = Transport.bindAwait(true);
    Transport b = Transport.bindAwait(true);
    Transport c = Transport.bindAwait(true);
    Transport d = Transport.bindAwait(true);
    List<Address> members = Arrays.asList(a.address(), b.address(), c.address(), d.address());

    MembershipProtocolImpl cmA = createMembership(a, members);
    MembershipProtocolImpl cmB = createMembership(b, members);
    MembershipProtocolImpl cmC = createMembership(c, members);
    MembershipProtocolImpl cmD = createMembership(d, members);

    MembershipProtocolImpl cmRestartedC = null;
    MembershipProtocolImpl cmRestartedD = null;

    try {
      awaitSeconds(1);

      assertTrusted(cmA, a.address(), b.address(), c.address(), d.address());
      assertTrusted(cmB, a.address(), b.address(), c.address(), d.address());
      assertTrusted(cmC, a.address(), b.address(), c.address(), d.address());
      assertTrusted(cmD, a.address(), b.address(), c.address(), d.address());

      stop(cmC);
      stop(cmD);

      awaitSeconds(1);

      assertTrusted(cmA, a.address(), b.address());
      assertSuspected(cmA, c.address(), d.address());
      assertTrusted(cmB, a.address(), b.address());
      assertSuspected(cmB, c.address(), d.address());

      awaitSuspicion(4);

      assertTrusted(cmA, a.address(), b.address());
      assertNoSuspected(cmA);
      assertTrusted(cmB, a.address(), b.address());
      assertNoSuspected(cmB);

      c = Transport.bindAwait(true);
      d = Transport.bindAwait(true);
      cmRestartedC = createMembership(c, Arrays.asList(a.address(), b.address()));
      cmRestartedD = createMembership(d, Arrays.asList(a.address(), b.address()));

      awaitSeconds(1);

      assertTrusted(cmRestartedC, a.address(), b.address(), c.address(), d.address());
      assertNoSuspected(cmRestartedC);
      assertTrusted(cmRestartedD, a.address(), b.address(), c.address(), d.address());
      assertNoSuspected(cmRestartedD);
      assertTrusted(cmA, a.address(), b.address(), c.address(), d.address());
      assertNoSuspected(cmA);
      assertTrusted(cmB, a.address(), b.address(), c.address(), d.address());
      assertNoSuspected(cmB);
    } finally {
      stopAll(cmA, cmB, cmRestartedC, cmRestartedD);
    }
  }

  @Test
  public void testLimitedSeedMembers() {
    Transport a = Transport.bindAwait(true);
    Transport b = Transport.bindAwait(true);
    Transport c = Transport.bindAwait(true);
    Transport d = Transport.bindAwait(true);
    Transport e = Transport.bindAwait(true);

    MembershipProtocolImpl cmA = createMembership(a, Collections.emptyList());
    MembershipProtocolImpl cmB = createMembership(b, Collections.singletonList(a.address()));
    MembershipProtocolImpl cmC = createMembership(c, Collections.singletonList(a.address()));
    MembershipProtocolImpl cmD = createMembership(d, Collections.singletonList(b.address()));
    MembershipProtocolImpl cmE = createMembership(e, Collections.singletonList(b.address()));

    try {
      awaitSeconds(3);

      assertTrusted(cmA, a.address(), b.address(), c.address(), d.address(), e.address());
      assertNoSuspected(cmA);
      assertTrusted(cmB, a.address(), b.address(), c.address(), d.address(), e.address());
      assertNoSuspected(cmB);
      assertTrusted(cmC, a.address(), b.address(), c.address(), d.address(), e.address());
      assertNoSuspected(cmC);
      assertTrusted(cmD, a.address(), b.address(), c.address(), d.address(), e.address());
      assertNoSuspected(cmD);
      assertTrusted(cmE, a.address(), b.address(), c.address(), d.address(), e.address());
      assertNoSuspected(cmE);
    } finally {
      stopAll(cmA, cmB, cmC, cmD, cmE);
    }
  }

  @Test
  public void testOverrideMemberAddress() throws UnknownHostException {
    String localAddress = InetAddress.getLocalHost().getHostName();

    Transport a = Transport.bindAwait(true);
    Transport b = Transport.bindAwait(true);
    Transport c = Transport.bindAwait(true);
    Transport d = Transport.bindAwait(true);
    Transport e = Transport.bindAwait(true);

    MembershipProtocolImpl cmA =
        createMembership(a, testConfig(Collections.emptyList()).memberHost(localAddress).build());
    MembershipProtocolImpl cmB =
        createMembership(
            b, testConfig(Collections.singletonList(a.address())).memberHost(localAddress).build());
    MembershipProtocolImpl cmC =
        createMembership(
            c, testConfig(Collections.singletonList(a.address())).memberHost(localAddress).build());
    MembershipProtocolImpl cmD =
        createMembership(
            d, testConfig(Collections.singletonList(b.address())).memberHost(localAddress).build());
    MembershipProtocolImpl cmE =
        createMembership(
            e, testConfig(Collections.singletonList(b.address())).memberHost(localAddress).build());

    try {
      awaitSeconds(3);

      assertTrusted(
          cmA,
          cmA.member().address(),
          cmB.member().address(),
          cmC.member().address(),
          cmD.member().address(),
          cmE.member().address());
      assertNoSuspected(cmA);
      assertTrusted(
          cmB,
          cmA.member().address(),
          cmB.member().address(),
          cmC.member().address(),
          cmD.member().address(),
          cmE.member().address());
      assertNoSuspected(cmB);
      assertTrusted(
          cmC,
          cmA.member().address(),
          cmB.member().address(),
          cmC.member().address(),
          cmD.member().address(),
          cmE.member().address());
      assertNoSuspected(cmC);
      assertTrusted(
          cmD,
          cmA.member().address(),
          cmB.member().address(),
          cmC.member().address(),
          cmD.member().address(),
          cmE.member().address());
      assertNoSuspected(cmD);
      assertTrusted(
          cmE,
          cmA.member().address(),
          cmB.member().address(),
          cmC.member().address(),
          cmD.member().address(),
          cmE.member().address());
      assertNoSuspected(cmE);
    } finally {
      stopAll(cmA, cmB, cmC, cmD, cmE);
    }
  }

  @Test
  public void testNodeJoinClusterWithNoInbound() {
    Transport a = Transport.bindAwait(true);
    Transport b = Transport.bindAwait(true);
    Transport c_noInbound = Transport.bindAwait(true);

    // Block traffic
    c_noInbound.networkEmulator().blockAllInbound();

    MembershipProtocolImpl cmA = createMembership(a, Collections.emptyList());
    MembershipProtocolImpl cmB = createMembership(b, Collections.singletonList(a.address()));
    MembershipProtocolImpl cm_noInbound =
        createMembership(c_noInbound, Collections.singletonList(a.address()));

    awaitSeconds(3);

    try {
      assertTrusted(cmA, cmA.member().address(), cmB.member().address());
      assertTrusted(cmB, cmB.member().address(), cmA.member().address());

      //noinspection RedundantArrayCreation
      assertSuspected(cm_noInbound, new Address[0]);
      assertTrusted(cm_noInbound, cm_noInbound.member().address());
    } finally {
      stopAll(cmA, cmB, cm_noInbound);
    }
  }

  @Test
  public void testNodeJoinClusterWithNoInboundThenInboundRecover() {
    Transport a = Transport.bindAwait(true);
    Transport b = Transport.bindAwait(true);
    Transport c_noInboundThenInboundOk = Transport.bindAwait(true);

    // Block traffic
    c_noInboundThenInboundOk.networkEmulator().blockAllInbound();

    MembershipProtocolImpl cmA = createMembership(a, Collections.emptyList());
    MembershipProtocolImpl cmB = createMembership(b, Collections.singletonList(a.address()));
    MembershipProtocolImpl cm_noInboundThenInboundOk =
        createMembership(c_noInboundThenInboundOk, Collections.singletonList(a.address()));

    awaitSeconds(3);

    try {
      assertTrusted(cmA, cmA.member().address(), cmB.member().address());
      assertTrusted(cmB, cmB.member().address(), cmA.member().address());

      //noinspection RedundantArrayCreation
      assertSuspected(cm_noInboundThenInboundOk, new Address[0]);
      assertTrusted(cm_noInboundThenInboundOk, cm_noInboundThenInboundOk.member().address());

      // Unblock traffic
      c_noInboundThenInboundOk.networkEmulator().unblockAllInbound();

      awaitSeconds(1);

      // Verify cluster
      assertTrusted(
          cmA,
          cmA.member().address(),
          cmB.member().address(),
          cm_noInboundThenInboundOk.member().address());
      assertTrusted(
          cmB,
          cmA.member().address(),
          cmB.member().address(),
          cm_noInboundThenInboundOk.member().address());
      assertTrusted(
          cm_noInboundThenInboundOk,
          cmA.member().address(),
          cmB.member().address(),
          cm_noInboundThenInboundOk.member().address());
    } finally {
      stopAll(cmA, cmB, cm_noInboundThenInboundOk);
    }
  }

  @Test
  public void testNetworkPartitionDueNoInboundThenRemoved() {
    Transport a = Transport.bindAwait(true);
    Transport b = Transport.bindAwait(true);
    Transport c = Transport.bindAwait(true);

    MembershipProtocolImpl cmA = createMembership(a, Collections.emptyList());
    MembershipProtocolImpl cmB = createMembership(b, Collections.singletonList(a.address()));
    MembershipProtocolImpl cmC = createMembership(c, Collections.singletonList(a.address()));

    try {
      awaitSeconds(3);
      // prerequisites
      assertTrusted(cmA, cmA.member().address(), cmB.member().address(), cmC.member().address());
      assertTrusted(cmB, cmB.member().address(), cmA.member().address(), cmC.member().address());
      assertTrusted(cmC, cmB.member().address(), cmA.member().address(), cmC.member().address());

      // block inbound msgs from all
      c.networkEmulator().blockAllInbound();

      awaitSuspicion(3);

      assertTrusted(cmA, cmA.member().address(), cmB.member().address());
      assertNoSuspected(cmA);
      assertTrusted(cmB, cmB.member().address(), cmA.member().address());
      assertNoSuspected(cmB);
      assertTrusted(cmC, cmC.member().address());
      assertNoSuspected(cmC);
    } finally {
      stopAll(cmA, cmB, cmC);
    }
  }

  @Test
  public void testNetworkPartitionDueNoInboundUntilRemovedThenInboundRecover() {
    Transport a = Transport.bindAwait(true);
    Transport b = Transport.bindAwait(true);
    Transport c = Transport.bindAwait(true);

    MembershipProtocolImpl cmA = createMembership(a, Collections.emptyList());
    MembershipProtocolImpl cmB = createMembership(b, Collections.singletonList(a.address()));
    MembershipProtocolImpl cmC = createMembership(c, Collections.singletonList(a.address()));

    try {
      awaitSeconds(3);
      // prerequisites
      assertTrusted(cmA, cmA.member().address(), cmB.member().address(), cmC.member().address());
      assertTrusted(cmB, cmB.member().address(), cmA.member().address(), cmC.member().address());
      assertTrusted(cmC, cmB.member().address(), cmA.member().address(), cmC.member().address());

      // block inbound msgs from all
      c.networkEmulator().blockAllInbound();

      awaitSuspicion(3);

      assertTrusted(cmA, cmA.member().address(), cmB.member().address());
      assertNoSuspected(cmA);
      assertTrusted(cmB, cmB.member().address(), cmA.member().address());
      assertNoSuspected(cmB);
      assertTrusted(cmC, cmC.member().address());
      assertNoSuspected(cmC);

      // unblock inbound msgs for all
      c.networkEmulator().unblockAllInbound();

      awaitSeconds(3);

      assertTrusted(cmA, cmA.member().address(), cmB.member().address(), cmC.member().address());
      assertTrusted(cmB, cmB.member().address(), cmA.member().address(), cmC.member().address());
      assertTrusted(cmC, cmB.member().address(), cmA.member().address(), cmC.member().address());
    } finally {
      stopAll(cmA, cmB, cmC);
    }
  }

  @Test
  public void testNetworkPartitionBetweenTwoMembersDueNoInbound() {
    Transport a = Transport.bindAwait(true);
    Transport b = Transport.bindAwait(true);
    Transport c = Transport.bindAwait(true);

    MembershipProtocolImpl cmA = createMembership(a, Collections.emptyList());
    MembershipProtocolImpl cmB = createMembership(b, Collections.singletonList(a.address()));
    MembershipProtocolImpl cmC = createMembership(c, Collections.singletonList(a.address()));

    try {
      awaitSeconds(3);
      // prerequisites
      assertTrusted(cmA, cmA.member().address(), cmB.member().address(), cmC.member().address());
      assertTrusted(cmB, cmB.member().address(), cmA.member().address(), cmC.member().address());
      assertTrusted(cmC, cmB.member().address(), cmA.member().address(), cmC.member().address());

      // block inbound msgs from b
      c.networkEmulator().inboundSettings(b.address(), false);

      awaitSuspicion(3);

      assertTrusted(cmA, cmA.member().address(), cmB.member().address(), cmC.member().address());
      assertTrusted(cmB, cmB.member().address(), cmA.member().address(), cmC.member().address());
      assertTrusted(cmC, cmB.member().address(), cmA.member().address(), cmC.member().address());
    } finally {
      stopAll(cmA, cmB, cmC);
    }
  }

  @Test
  public void testNetworkPartitionBetweenTwoMembersDueNoOutbound() {
    Transport a = Transport.bindAwait(true);
    Transport b = Transport.bindAwait(true);
    Transport c = Transport.bindAwait(true);

    MembershipProtocolImpl cmA = createMembership(a, Collections.emptyList());
    MembershipProtocolImpl cmB = createMembership(b, Collections.singletonList(a.address()));
    MembershipProtocolImpl cmC = createMembership(c, Collections.singletonList(a.address()));

    try {
      awaitSeconds(3);
      // prerequisites
      assertTrusted(cmA, cmA.member().address(), cmB.member().address(), cmC.member().address());
      assertTrusted(cmB, cmB.member().address(), cmA.member().address(), cmC.member().address());
      assertTrusted(cmC, cmB.member().address(), cmA.member().address(), cmC.member().address());

      // block outbound msgs from b
      c.networkEmulator().outboundSettings(b.address(), 100, 0);

      awaitSuspicion(3);

      assertTrusted(cmA, cmA.member().address(), cmB.member().address(), cmC.member().address());
      assertTrusted(cmB, cmB.member().address(), cmA.member().address(), cmC.member().address());
      assertTrusted(cmC, cmB.member().address(), cmA.member().address(), cmC.member().address());
    } finally {
      stopAll(cmA, cmB, cmC);
    }
  }

  @Test
  public void testNetworkPartitionBetweenTwoMembersDueNoTrafficAtAll() {
    Transport a = Transport.bindAwait(true);
    Transport b = Transport.bindAwait(true);
    Transport c = Transport.bindAwait(true);

    MembershipProtocolImpl cmA = createMembership(a, Collections.emptyList());
    MembershipProtocolImpl cmB = createMembership(b, Collections.singletonList(a.address()));
    MembershipProtocolImpl cmC = createMembership(c, Collections.singletonList(a.address()));

    try {
      awaitSeconds(3);
      // prerequisites
      assertTrusted(cmA, cmA.member().address(), cmB.member().address(), cmC.member().address());
      assertTrusted(cmB, cmB.member().address(), cmA.member().address(), cmC.member().address());
      assertTrusted(cmC, cmB.member().address(), cmA.member().address(), cmC.member().address());

      // block all traffic msgs from b
      c.networkEmulator().outboundSettings(b.address(), 100, 0);
      c.networkEmulator().inboundSettings(b.address(), false);

      awaitSuspicion(3);

      assertTrusted(cmA, cmA.member().address(), cmB.member().address(), cmC.member().address());
      assertTrusted(cmB, cmB.member().address(), cmA.member().address(), cmC.member().address());
      assertTrusted(cmC, cmB.member().address(), cmA.member().address(), cmC.member().address());
    } finally {
      stopAll(cmA, cmB, cmC);
    }
  }

  private void awaitSeconds(long seconds) {
    try {
      TimeUnit.SECONDS.sleep(seconds);
    } catch (InterruptedException e) {
      throw Exceptions.propagate(e);
    }
  }

  private ClusterConfig.Builder testConfig(List<Address> seedAddresses) {
    // Create faster config for local testing
    return ClusterConfig.builder()
        .seedMembers(seedAddresses)
        .syncInterval(TEST_SYNC_INTERVAL)
        .syncTimeout(100)
        .pingInterval(PING_INTERVAL)
        .pingTimeout(100)
        .metadataTimeout(100);
  }

  private MembershipProtocolImpl createMembership(
      Transport transport, List<Address> seedAddresses) {
    return createMembership(transport, testConfig(seedAddresses).build());
  }

  private MembershipProtocolImpl createMembership(Transport transport, ClusterConfig config) {
    Member localMember = new Member(UUID.randomUUID().toString(), transport.address());

    DirectProcessor<MembershipEvent> membershipProcessor = DirectProcessor.create();
    FluxSink<MembershipEvent> membershipSink = membershipProcessor.sink();

    CorrelationIdGenerator cidGenerator = new CorrelationIdGenerator(localMember.id());

    FailureDetectorImpl failureDetector =
        new FailureDetectorImpl(
            localMember, transport, membershipProcessor, config, scheduler, cidGenerator);

    GossipProtocolImpl gossipProtocol =
        new GossipProtocolImpl(localMember, transport, membershipProcessor, config, scheduler);

    MetadataStoreImpl metadataStore =
        new MetadataStoreImpl(
            localMember, transport, Collections.emptyMap(), config, scheduler, cidGenerator);

    MembershipProtocolImpl membership =
        new MembershipProtocolImpl(
            localMember,
            transport,
            failureDetector,
            gossipProtocol,
            metadataStore,
            config,
            scheduler,
            cidGenerator);

    membership.listen().subscribe(membershipSink::next);

    try {
      failureDetector.start();
      gossipProtocol.start();
      metadataStore.start();
      membership.start().block(TIMEOUT);
    } catch (Exception ex) {
      throw Exceptions.propagate(ex);
    }

    return membership;
  }

  private void stopAll(MembershipProtocolImpl... memberships) {
    for (MembershipProtocolImpl membership : memberships) {
      if (membership != null) {
        stop(membership);
      }
    }
  }

  private void stop(MembershipProtocolImpl membership) {
    membership.stop();
    membership.getMetadataStore().stop();
    membership.getGossipProtocol().stop();
    membership.getFailureDetector().stop();
    try {
      membership.getTransport().stop().block(Duration.ofSeconds(1));
    } catch (Exception ignore) {
      // ignore
    }
  }

  private void assertTrusted(MembershipProtocolImpl membership, Address... expected) {
    List<Address> actual = getAddressesWithStatus(membership, MemberStatus.ALIVE);
    assertEquals(
        expected.length,
        actual.size(),
        "Expected "
            + expected.length
            + " trusted members "
            + Arrays.toString(expected)
            + ", but actual: "
            + actual);
    for (Address member : expected) {
      assertTrue(
          actual.contains(member), "Expected to trust " + member + ", but actual: " + actual);
    }
  }

  private void assertSuspected(MembershipProtocolImpl membership, Address... expected) {
    List<Address> actual = getAddressesWithStatus(membership, MemberStatus.SUSPECT);
    assertEquals(
        expected.length,
        actual.size(),
        "Expected "
            + expected.length
            + " suspect members "
            + Arrays.toString(expected)
            + ", but actual: "
            + actual);
    for (Address member : expected) {
      assertTrue(
          actual.contains(member), "Expected to suspect " + member + ", but actual: " + actual);
    }
  }

  private void assertNoSuspected(MembershipProtocolImpl membership) {
    List<Address> actual = getAddressesWithStatus(membership, MemberStatus.SUSPECT);
    assertEquals(0, actual.size(), "Expected no suspected, but actual: " + actual);
  }

  private List<Address> getAddressesWithStatus(
      MembershipProtocolImpl membership, MemberStatus status) {
    return membership.getMembershipRecords().stream()
        .filter(member -> member.status() == status)
        .map(MembershipRecord::address)
        .collect(Collectors.toList());
  }

  private void awaitSuspicion(int clusterSize) {
    int defaultSuspicionMult = DEFAULT_SUSPICION_MULT;
    int pingInterval = PING_INTERVAL;
    long suspicionTimeoutSec =
        ClusterMath.suspicionTimeout(defaultSuspicionMult, clusterSize, pingInterval) / 1000;
    awaitSeconds(suspicionTimeoutSec + 2);
  }
}
