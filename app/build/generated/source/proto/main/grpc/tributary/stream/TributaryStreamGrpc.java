package tributary.stream;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.63.0)",
    comments = "Source: tributary.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class TributaryStreamGrpc {

  private TributaryStreamGrpc() {}

  public static final java.lang.String SERVICE_NAME = "tributary.stream.TributaryStream";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<tributary.stream.ProduceRequest,
      tributary.stream.ProduceAck> getProduceMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Produce",
      requestType = tributary.stream.ProduceRequest.class,
      responseType = tributary.stream.ProduceAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<tributary.stream.ProduceRequest,
      tributary.stream.ProduceAck> getProduceMethod() {
    io.grpc.MethodDescriptor<tributary.stream.ProduceRequest, tributary.stream.ProduceAck> getProduceMethod;
    if ((getProduceMethod = TributaryStreamGrpc.getProduceMethod) == null) {
      synchronized (TributaryStreamGrpc.class) {
        if ((getProduceMethod = TributaryStreamGrpc.getProduceMethod) == null) {
          TributaryStreamGrpc.getProduceMethod = getProduceMethod =
              io.grpc.MethodDescriptor.<tributary.stream.ProduceRequest, tributary.stream.ProduceAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Produce"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  tributary.stream.ProduceRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  tributary.stream.ProduceAck.getDefaultInstance()))
              .setSchemaDescriptor(new TributaryStreamMethodDescriptorSupplier("Produce"))
              .build();
        }
      }
    }
    return getProduceMethod;
  }

  private static volatile io.grpc.MethodDescriptor<tributary.stream.SubscribeRequest,
      tributary.stream.Event> getSubscribeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Subscribe",
      requestType = tributary.stream.SubscribeRequest.class,
      responseType = tributary.stream.Event.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<tributary.stream.SubscribeRequest,
      tributary.stream.Event> getSubscribeMethod() {
    io.grpc.MethodDescriptor<tributary.stream.SubscribeRequest, tributary.stream.Event> getSubscribeMethod;
    if ((getSubscribeMethod = TributaryStreamGrpc.getSubscribeMethod) == null) {
      synchronized (TributaryStreamGrpc.class) {
        if ((getSubscribeMethod = TributaryStreamGrpc.getSubscribeMethod) == null) {
          TributaryStreamGrpc.getSubscribeMethod = getSubscribeMethod =
              io.grpc.MethodDescriptor.<tributary.stream.SubscribeRequest, tributary.stream.Event>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Subscribe"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  tributary.stream.SubscribeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  tributary.stream.Event.getDefaultInstance()))
              .setSchemaDescriptor(new TributaryStreamMethodDescriptorSupplier("Subscribe"))
              .build();
        }
      }
    }
    return getSubscribeMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static TributaryStreamStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<TributaryStreamStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<TributaryStreamStub>() {
        @java.lang.Override
        public TributaryStreamStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new TributaryStreamStub(channel, callOptions);
        }
      };
    return TributaryStreamStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static TributaryStreamBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<TributaryStreamBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<TributaryStreamBlockingStub>() {
        @java.lang.Override
        public TributaryStreamBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new TributaryStreamBlockingStub(channel, callOptions);
        }
      };
    return TributaryStreamBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static TributaryStreamFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<TributaryStreamFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<TributaryStreamFutureStub>() {
        @java.lang.Override
        public TributaryStreamFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new TributaryStreamFutureStub(channel, callOptions);
        }
      };
    return TributaryStreamFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     * <pre>
     * Bi-directional streaming: client sends ProduceRequests,
     * server acks each one as a ProduceAck
     * </pre>
     */
    default io.grpc.stub.StreamObserver<tributary.stream.ProduceRequest> produce(
        io.grpc.stub.StreamObserver<tributary.stream.ProduceAck> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getProduceMethod(), responseObserver);
    }

    /**
     * <pre>
     * Server streaming: client sends one SubscribeRequest,
     * then server pushes Events forever
     * </pre>
     */
    default void subscribe(tributary.stream.SubscribeRequest request,
        io.grpc.stub.StreamObserver<tributary.stream.Event> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSubscribeMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service TributaryStream.
   */
  public static abstract class TributaryStreamImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return TributaryStreamGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service TributaryStream.
   */
  public static final class TributaryStreamStub
      extends io.grpc.stub.AbstractAsyncStub<TributaryStreamStub> {
    private TributaryStreamStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected TributaryStreamStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new TributaryStreamStub(channel, callOptions);
    }

    /**
     * <pre>
     * Bi-directional streaming: client sends ProduceRequests,
     * server acks each one as a ProduceAck
     * </pre>
     */
    public io.grpc.stub.StreamObserver<tributary.stream.ProduceRequest> produce(
        io.grpc.stub.StreamObserver<tributary.stream.ProduceAck> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getProduceMethod(), getCallOptions()), responseObserver);
    }

    /**
     * <pre>
     * Server streaming: client sends one SubscribeRequest,
     * then server pushes Events forever
     * </pre>
     */
    public void subscribe(tributary.stream.SubscribeRequest request,
        io.grpc.stub.StreamObserver<tributary.stream.Event> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getSubscribeMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service TributaryStream.
   */
  public static final class TributaryStreamBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<TributaryStreamBlockingStub> {
    private TributaryStreamBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected TributaryStreamBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new TributaryStreamBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Server streaming: client sends one SubscribeRequest,
     * then server pushes Events forever
     * </pre>
     */
    public java.util.Iterator<tributary.stream.Event> subscribe(
        tributary.stream.SubscribeRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getSubscribeMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service TributaryStream.
   */
  public static final class TributaryStreamFutureStub
      extends io.grpc.stub.AbstractFutureStub<TributaryStreamFutureStub> {
    private TributaryStreamFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected TributaryStreamFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new TributaryStreamFutureStub(channel, callOptions);
    }
  }

  private static final int METHODID_SUBSCRIBE = 0;
  private static final int METHODID_PRODUCE = 1;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_SUBSCRIBE:
          serviceImpl.subscribe((tributary.stream.SubscribeRequest) request,
              (io.grpc.stub.StreamObserver<tributary.stream.Event>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_PRODUCE:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.produce(
              (io.grpc.stub.StreamObserver<tributary.stream.ProduceAck>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getProduceMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              tributary.stream.ProduceRequest,
              tributary.stream.ProduceAck>(
                service, METHODID_PRODUCE)))
        .addMethod(
          getSubscribeMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              tributary.stream.SubscribeRequest,
              tributary.stream.Event>(
                service, METHODID_SUBSCRIBE)))
        .build();
  }

  private static abstract class TributaryStreamBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    TributaryStreamBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return tributary.stream.TributaryModels.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("TributaryStream");
    }
  }

  private static final class TributaryStreamFileDescriptorSupplier
      extends TributaryStreamBaseDescriptorSupplier {
    TributaryStreamFileDescriptorSupplier() {}
  }

  private static final class TributaryStreamMethodDescriptorSupplier
      extends TributaryStreamBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    TributaryStreamMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (TributaryStreamGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new TributaryStreamFileDescriptorSupplier())
              .addMethod(getProduceMethod())
              .addMethod(getSubscribeMethod())
              .build();
        }
      }
    }
    return result;
  }
}
