package io.scalecube.cluster.transport.api;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class SenderAwareTransport implements Transport {

  private final Transport transport;
  private final Address sender;

  public SenderAwareTransport(Transport transport) {
    this(transport, transport.address());
  }

  public SenderAwareTransport(Transport transport, Address sender) {
    this.transport = transport;
    this.sender = sender;
  }

  @Override
  public Address address() {
    return transport.address();
  }

  @Override
  public Mono<Void> stop() {
    return transport.stop();
  }

  @Override
  public boolean isStopped() {
    return transport.isStopped();
  }

  @Override
  public Mono<Void> send(Address address, Message message) {
    return Mono.defer(() -> transport.send(address, Message.with(message).sender(sender).build()));
  }

  @Override
  public Mono<Message> requestResponse(Address address, Message request) {
    return Mono.defer(
        () -> transport.requestResponse(address, Message.with(request).sender(sender).build()));
  }

  @Override
  public Flux<Message> listen() {
    return transport.listen();
  }
}
