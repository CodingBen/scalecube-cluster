package io.scalecube.cluster.membership;

import io.scalecube.cluster.Member;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Event which is emitted on cluster membership changes when new member added, updated in the
 * cluster or removed from the cluster.
 */
public final class MembershipEvent {

  public enum Type {
    ADDED,
    REMOVED,
    UPDATED
  }

  private final Type type;
  private final Member member;
  private final ByteBuffer oldMetadata;
  private final ByteBuffer newMetadata;

  private MembershipEvent(
      Type type, Member member, ByteBuffer oldMetadata, ByteBuffer newMetadata) {
    this.type = type;
    this.member = member;
    this.oldMetadata = oldMetadata;
    this.newMetadata = newMetadata;
  }

  /**
   * Creates REMOVED membership event with cluster member and its metadata (optional).
   *
   * @param member cluster member; not null
   * @param metadata member metadata; optional
   * @return membership event
   */
  public static MembershipEvent createRemoved(Member member, ByteBuffer metadata) {
    Objects.requireNonNull(member, "member must be not null");
    return new MembershipEvent(Type.REMOVED, member, metadata, null);
  }

  /**
   * Creates ADDED membership event with cluster member and its metadata.
   *
   * @param member cluster memeber; not null
   * @param metadata member metadata; not null
   * @return membership event
   */
  public static MembershipEvent createAdded(Member member, ByteBuffer metadata) {
    Objects.requireNonNull(member, "member must be not null");
    Objects.requireNonNull(metadata, "metadata must be not null for ADDED event");
    return new MembershipEvent(Type.ADDED, member, null, metadata);
  }

  /**
   * Creates UPDATED membership event.
   *
   * @param member cluster member; not null
   * @param oldMetadata previous metadata; not null
   * @param newMetadata new metadata; not null
   * @return membership event
   */
  public static MembershipEvent createUpdated(
      Member member, ByteBuffer oldMetadata, ByteBuffer newMetadata) {
    Objects.requireNonNull(member, "member must be not null");
    Objects.requireNonNull(newMetadata, "old metadata must be not null for UPDATED event");
    Objects.requireNonNull(newMetadata, "new metadata must be not null for UPDATED event");
    return new MembershipEvent(Type.UPDATED, member, oldMetadata, newMetadata);
  }

  public Type type() {
    return type;
  }

  public boolean isAdded() {
    return type == Type.ADDED;
  }

  public boolean isRemoved() {
    return type == Type.REMOVED;
  }

  public boolean isUpdated() {
    return type == Type.UPDATED;
  }

  public Member member() {
    return member;
  }

  public ByteBuffer oldMetadata() {
    return oldMetadata;
  }

  public ByteBuffer newMetadata() {
    return newMetadata;
  }

  @Override
  public String toString() {
    return "MembershipEvent{type="
        + type
        + ", member="
        + member
        + ", newMetadata="
        + metadataAsString(newMetadata)
        + ", oldMetadata="
        + metadataAsString(oldMetadata)
        + '}';
  }

  private String metadataAsString(ByteBuffer metadata) {
    if (metadata == null) {
      return null;
    }
    return Integer.toHexString(metadata.hashCode() & Integer.MAX_VALUE) + "-" + metadata.capacity();
  }
}
