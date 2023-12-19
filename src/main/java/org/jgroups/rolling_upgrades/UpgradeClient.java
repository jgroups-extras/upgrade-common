package org.jgroups.rolling_upgrades;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.handler.ssl.SslContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jgroups.rolling_upgrades.UpgradeServiceGrpc.UpgradeServiceStub;

import javax.annotation.concurrent.GuardedBy;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.jgroups.rolling_upgrades.ConnectionStatus.State.*;


/**
 * Class which interacts with a gRPC server, e.g. sending and receiving messages, retry logic etc. The state transitions
 * are start - connect - disconnect (possibly multiple times) - stop
 * @author Bela Ban
 * @since  1.1.1
 */
public class UpgradeClient implements StreamObserver<Response> {
    protected String                       server_address="localhost";
    protected int                          server_port=50051;
    protected String                       server_cert;
    protected ManagedChannel               channel;
    protected UpgradeServiceStub           asyncStub;
    // protected UpgradeServiceBlockingStub   syncStub; // we can have both a sync/async stub; they use the same channel
    protected StreamObserver<Request>      send_stream;
    protected final Set<Consumer<View>>    view_handlers=new HashSet<>();
    protected final Set<Consumer<Message>> message_handlers=new HashSet<>();
    protected Consumer<GetViewResponse>    view_rsp_handler;
    protected final ConnectionStatus       state=new ConnectionStatus();
    protected long                         reconnect_interval=3000; // in ms
    protected Runner                       reconnector;
    protected Runnable                     reconnect_function;
    protected Logger                       log;

    public String           serverAddress()                                  {return server_address;}
    public UpgradeClient    serverAddress(String a)                          {server_address=a; return this;}
    public int              serverPort()                                     {return server_port;}
    public UpgradeClient    serverPort(int p)                                {server_port=p; return this;}
    public String           serverCert()                                     {return server_cert;}
    public UpgradeClient    serverCert(String c)                             {server_cert=c; return this;}
    public boolean          connected()                                       {return state.isState(connected);}
    public ConnectionStatus state()                                          {return state;}
    public UpgradeClient    reconnectionFunction(Runnable f)                 {reconnect_function=f; return this;}
    public long             reconnectInterval()                              {return reconnect_interval;}
    public UpgradeClient    reconnectInterval(long i)                        {reconnect_interval=i; return this;}
    public UpgradeClient    addViewHandler(Consumer<View> h)                 {view_handlers.add(h); return this;}
    public UpgradeClient    removeViewHandler(Consumer<View> h)              {view_handlers.remove(h); return this;}
    public UpgradeClient    addMessageHandler(Consumer<Message> h)           {message_handlers.add(h); return this;}
    public UpgradeClient    removeMessageHandler(Consumer<Message> h)        {message_handlers.remove(h); return this;}
    public UpgradeClient    viewResponseHandler(Consumer<GetViewResponse> h) {this.view_rsp_handler=h; return this;}
    public Consumer<GetViewResponse> viewResponseHandler()                   {return view_rsp_handler;}
    public boolean          reconnectorRunning()                             {return reconnector.isRunning();}
    public Logger           log()                                            {return log;}
    public UpgradeClient    log(Logger l)                                    {log=l; return this;}



    public UpgradeClient start() throws Exception {
        InputStream server_cert_stream=null;
        SslContext  ctx=null;

        if(log == null)
            log=LogManager.getFormatterLogger(getClass());

        if(server_cert != null && !server_cert.trim().isEmpty()) {
            if((server_cert_stream=Utils.getFile(server_cert)) == null)
                throw new FileNotFoundException(String.format("server certificate %s not found", server_cert));
            ctx=GrpcSslContexts.forClient().trustManager(server_cert_stream).build();
        }
        NettyChannelBuilder cb=NettyChannelBuilder.forAddress(server_address, server_port);
        if(server_cert_stream == null)
            channel=cb.usePlaintext().build();
        else
            channel=cb.sslContext(ctx).build();
        // syncStub=UpgradeServiceGrpc.newBlockingStub(channel);
        asyncStub=UpgradeServiceGrpc.newStub(channel); // .withWaitForReady();
        if(reconnect_function != null)
            reconnector=createReconnector();
        return this;
    }

    public UpgradeClient stop() {
        channel.shutdown();
        state.setState(disconnecting);
        try {
            channel.awaitTermination(30, TimeUnit.SECONDS);
        }
        catch(InterruptedException e) {
        }
        finally {
            state.setState(disconnected);
        }
        return this;
    }

    public synchronized UpgradeClient getViewFromServer(String cluster) {
        GetViewRequest gv=GetViewRequest.newBuilder().setClusterName(cluster).build();
        Request req=Request.newBuilder().setGetViewReq(gv).build();
        send(req);
        return this;
    }

    public UpgradeClient registerView(String cluster, View local_view, Address local_addr) {
        RegisterView register_req=RegisterView.newBuilder().setClusterName(cluster).setView(local_view)
          .setLocalAddr(local_addr).build();
        Request req=Request.newBuilder().setRegisterReq(register_req).build();
        return _connect(req);
    }

    public UpgradeClient connect(String cluster, Address local_addr) {
        JoinRequest join_req=JoinRequest.newBuilder().setAddress(local_addr).setClusterName(cluster).build();
        Request req=Request.newBuilder().setJoinReq(join_req).build();
        return _connect(req);
    }

    public UpgradeClient disconnect(String cluster, Address local_addr) {
        if(local_addr == null || cluster == null)
            return this;
        LeaveRequest leave_req=LeaveRequest.newBuilder().setClusterName(cluster).setLeaver(local_addr).build();
        Request request=Request.newBuilder().setLeaveReq(leave_req).build();
        synchronized(this) {
            send(request);
            state.setState(disconnected);
            if(send_stream != null)
                send_stream.onCompleted();
        }
        return this;
    }

    public synchronized UpgradeClient send(Request req) {
        if(checkSendStream())
            send_stream.onNext(req);
        return this;
    }

    public void onNext(Response rsp) {
        if(rsp.hasMessage()) {
            handleMessage(rsp.getMessage());
            return;
        }
        if(rsp.hasView()) {
            handleView(rsp.getView());
            return;
        }
        if(rsp.hasRegViewOk()) {
            state.setState(connected);
            return;
        }
        if(rsp.hasGetViewRsp()) {
            if(view_rsp_handler != null)
                view_rsp_handler.accept(rsp.getGetViewRsp());
            return;
        }
        throw new IllegalStateException(String.format("response is illegal: %s", rsp));
    }

    public synchronized void onError(Throwable t) {
        if(state.isState(connected))
            log.warn(String.format("exception from server: %s (%s)", t, t.getCause()));
        send_stream=null;
        state.setState(reconnecting);
        startReconnector();
    }

    public synchronized void onCompleted() {
        send_stream=null;
        state.setState(disconnected);
    }

    protected synchronized UpgradeClient _connect(Request req) {
        if(send_stream == null) {
            state.setState(connecting);
            send_stream=asyncStub.connect(this);
        }
        send_stream.onNext(req);
        return this;
    }

    @GuardedBy("this")
    protected boolean checkSendStream() {
        if(send_stream == null) {
            log.error("send stream is null, cannot send message");
            return false;
        }
        return true;
    }

    protected void handleMessage(Message msg) {
        for(Consumer<Message> c: message_handlers)
            c.accept(msg);
    }

    protected void handleView(View view) {
        state.setState(connected);
        stopReconnector();
        for(Consumer<View> c: view_handlers)
            c.accept(view);
    }

    protected synchronized Runner createReconnector() {
        return new Runner("client-reconnector", () -> {
            Utils.sleep(reconnect_interval);
            reconnect_function.run();
        }, null);
    }

    protected synchronized UpgradeClient startReconnector() {
        if(reconnector != null && !reconnector.isRunning()) {
            log.debug("starting reconnector");
            reconnector.start();
        }
        return this;
    }

    protected synchronized UpgradeClient stopReconnector() {
        if(reconnector != null && reconnector.isRunning()) {
            log.debug("stopping reconnector");
            reconnector.stop();
        }
        return this;
    }

    public static void main(String[] args) throws Exception {
        UpgradeClient client=new UpgradeClient()
          .addMessageHandler(m -> System.out.printf("-- msg from %s: %s\n",
                                                    m.getSender().getName(), new String(m.getPayload().toByteArray())))
          .addViewHandler(v -> System.out.printf("-- view: %s\n", v))
          .start();


        UUID uuid=UUID.newBuilder().setLeastSig(1).setMostSig(2).build();
        Address a=Address.newBuilder().setUuid(uuid).setName("A").build();
        client.connect("rpcs", a);

        byte[] buf="hello world".getBytes();
        Message msg=Message.newBuilder()
          .setClusterName("rpcs")
          .setSender(Address.newBuilder().setName("A").setUuid(uuid).build())
          .setPayload(ByteString.copyFrom(buf)).build();
        client.send(Request.newBuilder().setMessage(msg).build());
        client.disconnect("rpcs", a);

    }
}
