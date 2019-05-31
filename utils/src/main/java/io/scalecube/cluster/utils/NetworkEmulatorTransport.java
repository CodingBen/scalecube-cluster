package io.scalecube.cluster.utils;

import io.scalecube.cluster.transport.api.Address;
import io.scalecube.cluster.transport.api.Message;
import io.scalecube.cluster.transport.api.Transport;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class NetworkEmulatorTransport implements Transport {

  private final Transport transport;
  private final NetworkEmulator networkEmulator;

  /**
   * Constructor.
   *
   * @param transport transport
   */
  public NetworkEmulatorTransport(Transport transport) {
    this.transport = transport;
    this.networkEmulator = new NetworkEmulator(transport.address());
  }

  public NetworkEmulator networkEmulator() {
    return networkEmulator;
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
    return Mono.defer(
        () ->
            Mono.just(message)
                .flatMap(msg -> networkEmulator.tryFailOutbound(msg, address))
                .flatMap(msg -> networkEmulator.tryDelayOutbound(msg, address))
                .flatMap(msg -> transport.send(address, msg)));
  }

  @Override
  public Mono<Message> requestResponse(Address address, Message request) {
    return Mono.defer(
        () ->
            Mono.just(request)
                .flatMap(msg -> networkEmulator.tryFailOutbound(msg, address))
                .flatMap(msg -> networkEmulator.tryDelayOutbound(msg, address))
                .flatMap(msg -> transport.requestResponse(address, msg)));
  }

  @Override
  public Flux<Message> listen() {
    return transport
        .listen()
        .filter(message -> networkEmulator.inboundSettings(message.sender()).shallPass())
        .onBackpressureBuffer();
  }
}
