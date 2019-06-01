package io.scalecube.cluster.membership;

import io.scalecube.cluster.transport.api.Address;
import java.util.List;

public interface MembershipConfig {

  List<Address> getSeedMembers();

  <T> T getMetadata();

  int getSyncInterval();

  int getSyncTimeout();

  String getSyncGroup();

  int getPingInterval();

  int getSuspicionMult();

  String getMemberHost();

  Integer getMemberPort();
}
