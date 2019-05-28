package io.scalecube.cluster;

import io.scalecube.cluster.membership.MembershipEvent;
import io.scalecube.transport.Message;

public interface ClusterEventListener {

  default void onMessage(Message message) {}

  default void onGossip(Message gossip) {}

  default void onEvent(MembershipEvent event) {}
}
